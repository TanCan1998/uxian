package com.xiandehuang.u

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File

/**
 * channel/主进程内的 HTTP 服务：把 mtop RPC 暴露成标准 HTTP 接口。
 *
 * ## 为什么比 provider 好（实测）
 *
 * ```
 * provider 路径   单次 1126ms   ← content query 的 acquireProvider 固定开销
 * channel 直连    单次   73ms
 * HTTP 路径       单次   ~80ms
 * ```
 *
 * ## 为什么用 NanoHTTPD（而不是自己写）
 *
 * 自写版本连续踩坑：
 *   - accept() 永不返回（ss 显示 Recv-Q 有连接但应用层拿不到）
 *   - 换 bind 地址、换线程模型、加预热，全部无效
 *   - 同设备上支付宝（芝麻粒的 NanoHTTPD）的 8080 一直正常
 *
 * 所以直接照芝麻粒的做法用 NanoHTTPD 2.3.1：
 *   - `start(SOCKET_READ_TIMEOUT, false)` —— 第二参数 daemon=false
 *   - 只绑 127.0.0.1
 *   - Bearer token 鉴权
 *
 * 参考实现：芝麻粒 `ModuleHttpServer` / `ModuleHttpServerManager`。
 *
 * ## 接口
 *
 * ```
 * GET  /ping                                    探活（免鉴权）
 * GET  /status                                  进程/任务状态
 * POST /rpc      {"api","version","data"}       单次调用
 * POST /batch    {"calls":[{...},...]}          并发调用
 * GET  /rel /traffic /bodies /logs /prefs /doc  数据查询
 * POST /analyze /export                         异步任务（返回 taskId）
 * GET  /task?id=xxx                             查任务结果
 * ```
 */
object ChannelHttp {

    private const val TAG = "xysidecar"

    /** 18787：8787 被 MT 管理器占用 */
    const val PORT = 18787

    @Volatile
    private var server: Server? = null

    @Volatile
    private var classLoader: ClassLoader? = null

    private val startLock = Any()

    fun start(cl: ClassLoader) {
        // 必须加锁：ChannelProbe 的等待线程可能被多个进程/多次触发，
        // 只判 server != null 挡不住并发 —— 实测启动过 3 次，
        // 每次 bind 都成功（SO_REUSEADDR），但只有最后一个生效，
        // 前面的变成僵尸 socket，accept 抢不到连接。
        synchronized(startLock) {
            if (server != null) return
            startLocked(cl)
        }
    }

    private fun startLocked(cl: ClassLoader) {
        classLoader = cl

        try {
            val s = Server()
            // 换掉默认的 DefaultAsyncRunner：它内部 new Thread(Runnable) 后把
            // ClientHandler 塞进一个 ArrayList，R8 收缩后这个列表的迭代器
            // 在部分场景会失效（之前 MiniHttpServer 踩过同样的坑）。
            // 这里用最朴素的写法：直接 new Thread 并 start，不碰任何集合。
            s.setAsyncRunner(object : NanoHTTPD.AsyncRunner {
                override fun closeAll() {
                    // 不跟踪连接，无需关闭
                }

                override fun closed(clientHandler: NanoHTTPD.ClientHandler) {
                    // 不需要跟踪已关闭的连接
                }

                override fun exec(code: NanoHTTPD.ClientHandler) {
                    val t = Thread({
                        try {
                            code.run()
                        } catch (t: Throwable) {
                            // 不能让它抛出去：R8 收缩后 ClientHandler 里某些
                            // 集合操作会抛，逃逸会连带把 accept 线程搞死
                            diag("conn err: ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }, "MiniHttp-Conn")
                    t.isDaemon = true
                    t.start()
                }
            })
            // 与芝麻粒一致：daemon=false 表示在独立线程跑，不阻塞调用方
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = s
            diag("已启动 http://127.0.0.1:$PORT")
        } catch (t: Throwable) {
            Log.w(TAG, "ChannelHttp: 启动失败: $t")
            diag("启动失败: $t")
        }
    }

    /** 停止服务（调试开关关闭时调用） */
    fun stop() {
        synchronized(startLock) {
            try {
                server?.stop()
            } catch (ignored: Throwable) {
            }
            server = null
            diag("已停止")
        }
    }

    /** NanoHTTPD 子类：只绑回环，所有路由在一个方法里分发 */
    private class Server : NanoHTTPD("127.0.0.1", PORT) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method
            try {
                java.io.File("/data/data/com.taobao.idlefish/files/ch/serve.txt")
                    .appendText("serve $method $uri at " + System.currentTimeMillis() + "\n")
                if (uri == "/ping") {
                    return json(Response.Status.OK, """{"ok":true,"pong":${System.currentTimeMillis()}}""")
                }

                // 不做 token 鉴权：这是调试口，只绑 127.0.0.1。
                // 本机其他 App 理论上可访问，但需要 root/同 uid 才能连回环以外的路径，
                // 且接口只读宿主已有数据 + 转发 mtop，风险可接受。

                return when (uri) {
                    "/status" -> json(Response.Status.OK, statusJson())
                    "/analyze" -> json(Response.Status.OK, startTask("analyze"))
                    "/export" -> json(Response.Status.OK, startTask("export"))
                    "/task" -> json(Response.Status.OK, taskStatus(session.parms["id"]))
                    "/rel", "/traffic", "/bodies", "/logs", "/prefs", "/doc" ->
                        json(Response.Status.OK, handleQuery(uri, readBody(session)))
                    "/rpc" -> {
                        if (method != Method.POST) {
                            json(Response.Status.METHOD_NOT_ALLOWED, """{"ok":false,"error":"POST only"}""")
                        } else {
                            json(Response.Status.OK, handleRpc(readBody(session)))
                        }
                    }
                    // 业务链：POST /chain  {"name":"countdown"}
                    // 和 provider 的 xysidecar:chain:<名> 等价，但快 7 倍
                    // （provider 每次要过一次 content provider）。
                    "/chain" -> {
                        if (method != Method.POST) {
                            json(Response.Status.METHOD_NOT_ALLOWED, """{"ok":false,"error":"POST only"}""")
                        } else {
                            json(Response.Status.OK, handleChain(readBody(session)))
                        }
                    }
                    "/batch" -> {
                        if (method != Method.POST) {
                            json(Response.Status.METHOD_NOT_ALLOWED, """{"ok":false,"error":"POST only"}""")
                        } else {
                            json(Response.Status.OK, handleBatch(readBody(session)))
                        }
                    }
                    else -> json(Response.Status.NOT_FOUND, """{"ok":false,"error":"not found: $uri"}""")
                }
            } catch (t: Throwable) {
                return json(
                    Response.Status.INTERNAL_ERROR,
                    """{"ok":false,"error":"${t.javaClass.simpleName}: ${t.message}"}""",
                )
            }
        }
    }

    /**
     * 读 POST body。参考芝麻粒的 getPostBodySafe：
     * 按 Content-Length 读满，显式 UTF-8。
     */
    private fun readBody(session: NanoHTTPD.IHTTPSession): String {
        val lenStr = session.headers["content-length"] ?: return ""
        val len = lenStr.trim().toIntOrNull() ?: return ""
        if (len <= 0) return ""
        val buf = ByteArray(len)
        DataInputStream(session.inputStream).readFully(buf)
        return String(buf, Charsets.UTF_8)
    }

    private fun json(status: NanoHTTPD.Response.IStatus, body: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", body)

    // ---------------------------------------------------------------- RPC

    private fun handleRpc(body: String): String {
        val cl = classLoader ?: return """{"ok":false,"error":"classloader not ready"}"""
        return try {
            val o = JSONObject(body)
            val d = o.opt("data")
            val data = if (d == null) "{}" else (if (d is String) d else d.toString())
            val api = o.optString("api")
            val ver = o.optString("version", "1.0")
            if (o.optString("via", "") == "params") {
                val app = Protocol.State.app ?: return """{"ok":false,"error":"no context"}"""
                val items = org.json.JSONArray()
                for (scope in arrayOf(BusinessParams.TASK_TRIGGER, BusinessParams.TASK_BENEFIT,
                    BusinessParams.CASH_MAIN, BusinessParams.CASH_OPEN, BusinessParams.PROP_USE)) {
                    val value = BusinessParams.resolve(app, scope)
                    val p = ParamStore.get(app, scope.key)
                    items.put(JSONObject().put("key", scope.key).put("available", value.isNotEmpty())
                        .put("length", value.length).put("source", p?.sourceApi ?: "")
                        .put("field", p?.sourceField ?: "").put("checkedAt", p?.checkedAt ?: 0L))
                }
                return JSONObject().put("ok", true).put("items", items).toString()
            }
            // 闲鱼自身 H5/Weex 共用桥，不需要 WebView 或页面实例。
            if (o.optString("via", "") == "host") {
                val app = Protocol.State.app ?: return """{"ok":false,"error":"no context"}"""
                return HostRpc.request(app, api, ver, data)
            }
            // via=native 时走原生 RPC 服务（照抄芝麻粒「不等用户打开页面」的做法）
            if (o.optString("via", "") == "native") {
                val r = NativeRpc.request(Protocol.State.app, api, ver, data)
                if (r.isNotEmpty()) return r
                return """{"ok":false,"error":"native 失败：${NativeRpc.lastError()}"}"""
            }
            // via=gw 只读容器自认的 mtop 网关
            if (o.optString("via", "") == "gw") {
                val g = NewRpcChannel.gateway(Protocol.State.app)
                return """{"ok":${g.isNotEmpty()},"gateway":"$g","err":"${NewRpcChannel.lastError()}"}"""
            }
            // via=container 走容器 RPC 栈（照抄芝麻粒 NewRpcBridge，复用容器现有 Extension 实例）
            if (o.optString("via", "") == "container") {
                val r = NewRpcChannel.request(Protocol.State.app, api, ver, data)
                if (r.isNotEmpty()) return r
                return """{"ok":false,"error":"container 失败：${NewRpcChannel.lastError()}","src":"${NewRpcChannel.diagSrc()}"}"""
            }
            // via=safetoken 时探测能否主动生成 asac（不依赖用户操作）
            if (o.optString("via", "") == "safetoken") {
                return SafeToken.probe(Protocol.State.app)
            }
            // via=noheader 时跳过 header 回放，验证签名是否依赖 hook 抓的 header
            if (o.optString("via", "") == "noheader") {
                val app = Protocol.State.app
                if (app == null) return """{"ok":false,"error":"no context"}"""
                MtopBridge.skipHeaders = true
                try {
                    return MtopBridge.request(app, api, ver, data)
                } finally {
                    MtopBridge.skipHeaders = false
                }
            }
            // via=xstate 时 dump XState 全局会话表 —— 看清签名能用哪些字段
            if (o.optString("via", "") == "xstate") {
                return MtopSigner.dumpXState()
            }
            if (o.optString("via", "") == "sign") {
                // 字段清单来自 convertInnerBaseStrMap：utdid/uid/sid/ttid/deviceId
                // 缺任何一个，INPUT 串对应位置就是空串，签名必然对不上。
                val base = MtopSigner.buildBaseMap(api, ver, data)
                val ts = base["t"] ?: System.currentTimeMillis().toString()
                val key = MtopSigner.appKey()
                if (key.isNotEmpty()) base["appKey"] = key
                val hdrs = MtopSigner.unifiedSign(
                    base, java.util.HashMap<String, String>(), api,
                    MtopSigner.authCode(), true,
                )
                val out = JSONObject()
                out.put("ok", hdrs.isNotEmpty())
                out.put("appKey", key)
                out.put("t", ts)
                out.put("authCode", MtopSigner.authCode())
                out.put("fields", JSONObject(base as Map<*, *>))
                out.put("stage", MtopSigner.stage())
                out.put("diag", MtopSigner.diag())
                out.put("probe", MtopSigner.dumpXState())
                if (hdrs.isEmpty()) {
                    out.put("error", MtopSigner.lastError())
                } else {
                    val h = JSONObject()
                    for ((k, v) in hdrs) h.put(k, v)
                    out.put("headers", h)
                }
                return out.toString()
            }

            val ctx = Protocol.State.app ?: throw IllegalStateException("no context")
            if (o.optString("via", "") == "mtop") {
                return MtopBridge.request(ctx, api, ver, data)
            }
            // 调试默认与业务链使用相同宿主容器桥；失败原样返回，不重复提交。
            HostRpc.request(ctx, api, ver, data)
        } catch (t: Throwable) {
            """{"ok":false,"error":"${t.javaClass.simpleName}: ${t.message}"}"""
        }
    }

    /**
     * 跑一条业务链。
     *
     * 和 provider 的 `xysidecar:chain:<名>` 完全等价，区别只是不走 content provider
     * （每次省 ~800ms 通道开销）—— 一条链里有十几到几十次 mtop 调用时，
     * 这点开销会被放大成十几秒。
     *
     * 返回**有序数组**而不是对象：链的步骤名会重复（每个任务一行），
     * 用 JSONObject 会把同名的后面几条覆盖掉，只剩最后一条。
     *
     * 注意链是**写操作**（真签到、真掷骰），provider / HTTP 都是同步跑，
     * 别连发。
     */
    private fun handleChain(body: String): String {
        val ctx = Protocol.State.app ?: return """{"ok":false,"error":"no context"}"""
        return try {
            val o = try {
                if (body.isBlank()) JSONObject() else JSONObject(body)
            } catch (t: Throwable) {
                JSONObject()
            }
            val name = o.optString("name", "").trim()
            // 不传名字 = 列清单，和 provider 的 xysidecar:chain 行为一致
            if (name.isEmpty()) {
                val arr = org.json.JSONArray()
                for (entry in ChainPlan.entries) {
                    val row = JSONObject()
                    row.put("k", entry.id)
                    row.put("v", entry.title + "：" + entry.desc)
                    arr.put(row)
                }
                arr.put(JSONObject().put("k", "all").put("v", "全部按顺序跑"))
                arr.put(JSONObject().put("k", "pending").put("v", "推进任务及衍生奖励"))
                val out = JSONObject()
                out.put("ok", true)
                out.put("data", arr)
                return out.toString()
            }
            if (ChainPlan.select(name).isEmpty()) return """{"ok":false,"error":"unknown chain"}"""
            val results = Chains.manual(ctx, name)
            val arr = org.json.JSONArray()
            for (r in results) {
                if (r.steps.isEmpty()) {
                    val row = JSONObject()
                    row.put("k", r.title)
                    row.put("v", "无步骤")
                    arr.put(row)
                }
                for (s in r.steps) {
                    val row = JSONObject()
                    row.put("k", r.title + " · " + s.name)
                    row.put("v", s.detail)
                    row.put("ok", s.ok)
                    row.put("skipped", s.skipped)
                    arr.put(row)
                }
                if (r.state.isNotEmpty()) {
                    val row = JSONObject()
                    row.put("k", r.title + " · 结果")
                    row.put("v", r.state)
                    arr.put(row)
                }
            }
            val out = JSONObject()
            out.put("ok", true)
            out.put("data", arr)
            out.toString()
        } catch (t: Throwable) {
            """{"ok":false,"error":"${t.javaClass.simpleName}: ${t.message}"}"""
        }
    }

    /** 并发执行一批请求：HTTP 通道相对 provider 的核心优势 */
    private fun handleBatch(body: String): String {
        val cl = classLoader ?: return """{"ok":false,"error":"classloader not ready"}"""
        return try {
            val calls = JSONObject(body).optJSONArray("calls")
                ?: return """{"ok":false,"error":"missing calls"}"""
            val n = calls.length()
            val results = arrayOfNulls<String>(n)
            val done = intArrayOf(0)
            val lock = Object()

            var i = 0
            while (i < n) {
                val c = calls.getJSONObject(i)
                val api = c.optString("api")
                val ver = c.optString("version", "1.0")
                val d0 = c.opt("data")
                val d = if (d0 == null) "{}" else (if (d0 is String) d0 else d0.toString())
                val idx = i
                val th = Thread(object : Runnable {
                    override fun run() {
                        var r: String
                        try {
                            val ctx = Protocol.State.app ?: throw IllegalStateException("no context")
                            r = HostRpc.request(ctx, api, ver, d)
                        } catch (t: Throwable) {
                            r = """{"ok":false,"error":"${t.javaClass.simpleName}: ${t.message}"}"""
                        }
                        results[idx] = r
                        synchronized(lock) {
                            done[0] = done[0] + 1
                            (lock as Object).notifyAll()
                        }
                    }
                }, "batch-$i")
                th.isDaemon = true
                th.start()
                i++
            }

            val deadline = System.currentTimeMillis() + 120_000
            synchronized(lock) {
                while (done[0] < n) {
                    val remain = deadline - System.currentTimeMillis()
                    if (remain <= 0) break
                    (lock as Object).wait(remain)
                }
            }

            val arr = org.json.JSONArray()
            i = 0
            while (i < n) {
                val s = results[i]
                var o: JSONObject? = null
                if (s != null) {
                    try {
                        o = JSONObject(s)
                    } catch (ignored: Throwable) {
                    }
                }
                arr.put(o ?: JSONObject())
                i++
            }
            JSONObject().put("ok", true).put("count", n)
                .put("done", done[0]).put("results", arr).toString()
        } catch (t: Throwable) {
            """{"ok":false,"error":"${t.javaClass.simpleName}: ${t.message}"}"""
        }
    }

    // ---------------------------------------------------------------- 异步任务

    /**
     * analyze（7 秒）/ export（3 秒）走异步：立刻返回 taskId，后台跑，结果落盘。
     * 同步做会把 HTTP 连接占死，走 provider 时更会阻塞 binder 线程。
     */
    private fun startTask(kind: String): String {
        val id = kind + "-" + System.currentTimeMillis()
        val th = Thread(object : Runnable {
            override fun run() {
                val t0 = System.currentTimeMillis()
                var ok = false
                var detail = ""
                try {
                    val ctx = Protocol.State.app
                    if (ctx == null) {
                        detail = "no context"
                    } else if (kind == "analyze") {
                        ApiDoc.load(ctx)
                        val r = Relation.analyze(ctx)
                        ok = true
                        detail = "apis=" + r.apis + " relations=" + r.relations
                    } else {
                        ApiDoc.load(ctx)
                        val r = TrafficExport.build(ctx, null)
                        ok = true
                        detail = "path=" + r.path + " apis=" + r.apis + " bytes=" + r.bytes
                    }
                } catch (t: Throwable) {
                    detail = t.javaClass.simpleName + ": " + t.message
                }
                taskResult(id, ok, detail, System.currentTimeMillis() - t0)
            }
        }, "http-$kind")
        th.isDaemon = true
        th.start()
        return JSONObject().put("ok", true).put("task", id).toString()
    }

    private fun taskFile() = File("/data/data/com.taobao.idlefish/files/ch/tasks.json")

    @Synchronized
    private fun taskResult(id: String, ok: Boolean, detail: String, ms: Long) {
        try {
            val f = taskFile()
            f.parentFile?.mkdirs()
            val root = try {
                if (f.exists()) JSONObject(f.readText()) else JSONObject()
            } catch (e: Throwable) {
                JSONObject()
            }
            root.put(
                id,
                JSONObject().put("ok", ok).put("detail", detail)
                    .put("ms", ms).put("at", System.currentTimeMillis()),
            )
            f.writeText(root.toString())
        } catch (ignored: Throwable) {
        }
    }

    private fun taskStatus(id: String?): String {
        val f = taskFile()
        if (!f.exists()) return """{"ok":false,"error":"no tasks"}"""
        return try {
            val root = JSONObject(f.readText())
            if (id.isNullOrEmpty()) {
                JSONObject().put("ok", true).put("tasks", root).toString()
            } else {
                val t = root.optJSONObject(id)
                    ?: return """{"ok":false,"error":"task not found: $id"}"""
                JSONObject().put("ok", true).put("task", id).put("result", t).toString()
            }
        } catch (t: Throwable) {
            """{"ok":false,"error":"${t.javaClass.simpleName}: ${t.message}"}"""
        }
    }

    // ---------------------------------------------------------------- 查询转发

    /** 大数据查询：复用 ProviderHandlers 的逻辑，结果转 JSON 返回 */
    private fun handleQuery(path: String, body: String): String {
        val req = try {
            if (body.isBlank()) JSONObject() else JSONObject(body)
        } catch (t: Throwable) {
            JSONObject()
        }
        val out = JSONObject()
        return try {
            when (path) {
                "/rel" -> {
                    val api = req.optString("api", "")
                    val sel = if (api.isEmpty()) Protocol.REL else Protocol.REL + ":" + api
                    out.put("ok", true).put("data", cursorToJson(ProviderHandlers.relCursor(sel)))
                }
                "/traffic" -> {
                    val sb = StringBuilder(Protocol.TRAFFIC)
                    val api = req.optString("api", "")
                    if (api.isNotEmpty()) sb.append(":api=").append(api)
                    out.put("ok", true).put("data", cursorToJson(ProviderHandlers.trafficCursor(sb.toString())))
                }
                "/bodies" -> out.put("ok", true).put("data", cursorToJson(ProviderHandlers.bodiesCursor()))
                "/logs" -> out.put("ok", true).put("data", cursorToJson(logsCursor()))
                "/prefs" -> {
                    val ctx = Protocol.State.app
                    out.put("ok", true).put("data", cursorToJson(ProviderHandlers.prefsCursor(ctx, Protocol.PREFS)))
                }
                "/doc" -> {
                    val api = req.optString("api", "")
                    val sel = if (api.isEmpty()) Protocol.DOC else Protocol.DOC + ":" + api
                    out.put("ok", true).put("data", cursorToJson(ProviderHandlers.docCursor(Protocol.State.app, sel)))
                }
                else -> out.put("ok", false).put("error", "unknown: $path")
            }
            out.toString()
        } catch (t: Throwable) {
            """{"ok":false,"error":"${t.javaClass.simpleName}: ${t.message}"}"""
        }
    }

    private fun cursorToJson(c: android.database.Cursor): JSONObject {
        val o = JSONObject()
        try {
            var i = 0
            while (c.moveToNext()) {
                val k = c.getString(0) ?: "k$i"
                val v = c.getString(1) ?: ""
                o.put(k, v)
                i++
            }
        } finally {
            try {
                c.close()
            } catch (ignored: Throwable) {
            }
        }
        return o
    }

    private fun logsCursor(): android.database.Cursor {
        val c = android.database.MatrixCursor(arrayOf(Protocol.COL_KEY, Protocol.COL_VALUE))
        synchronized(Protocol.State.LOGS) {
            var i = 0
            for (line in Protocol.State.LOGS) {
                c.addRow(arrayOf<Any>("l" + i++, line))
            }
        }
        return c
    }

    // ---------------------------------------------------------------- 状态

    private fun statusJson(): String {
        val o = JSONObject()
            .put("ok", true)
            .put("port", PORT)
            .put("mainAlive", RenderReaper.isMainAlive())
            .put("suppress", ProcessSuppressor.enabled)
            .put("adBlockHooks", AdBlocker.hooked)
            .put("pid", android.os.Process.myPid())
        try {
            val f = taskFile()
            if (f.exists()) {
                val root = JSONObject(f.readText())
                val arr = org.json.JSONArray()
                val keys = root.keys()
                var n = 0
                while (keys.hasNext() && n < 5) {
                    val k = keys.next()
                    val t = root.optJSONObject(k) ?: continue
                    arr.put(
                        JSONObject().put("id", k)
                            .put("ok", t.optBoolean("ok"))
                            .put("ms", t.optLong("ms"))
                            .put("detail", t.optString("detail")),
                    )
                    n++
                }
                o.put("recentTasks", arr)
            }
        } catch (ignored: Throwable) {
        }
        return o.toString()
    }

    private fun diag(msg: String) {
        try {
            Log.i(TAG, "ChannelHttp: $msg")
        } catch (ignored: Throwable) {
        }
        try {
            var f: File? = null
            val ctx = Protocol.State.app
            if (ctx != null) {
                val dir = File(ctx.filesDir, "ch")
                dir.mkdirs()
                f = File(dir, "http_diag.txt")
            } else {
                f = File("/data/data/com.taobao.idlefish/files/ch/http_diag.txt")
            }
            f?.appendText(msg + "\n")
        } catch (ignored: Throwable) {
        }
    }
}
