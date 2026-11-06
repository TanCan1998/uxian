package io.github.tancan1998.uxian

import android.app.Application
import android.util.Log
import java.lang.reflect.Method

/**
 * 实验二：在 :channel 进程里**主动**初始化 Mtop 并尝试发一次 RPC。
 *
 * 前置事实（上一轮探测拿到）：
 *   - channel 里 mtopsdk.* / SecurityGuard / MtopInitializer 的**类全部可达**（dex 共享）
 *   - 但 `Mtop.instance()` 无参版本不存在（NoSuchMethodException）
 *   - 存在的是 `Mtop.instance(Context)` / `instance(Context,String)` 等 6 个重载，
 *     且是 **public static**，内部走 `init(Context, String)`
 *
 * 所以理论上 channel 里能自己起一套 Mtop。风险点（决定成败的）：
 *   1. 签名需要 SecurityGuard + wua，这些依赖宿主在主进程里灌进去的 appkey/utdid
 *   2. ttid / appVersion 由 `MtopSetting` 提供，channel 里可能没设
 *   3. 请求要过风控，需要 XState 里的设备指纹（跨进程靠 XStateService 共享）
 *
 * 本类只做**只读探测**：拿 `mtop.common.getTimestamp` 试，这个接口无参数、不碰用户数据、
 * 不改任何状态，成功即证明通道可用，失败也能拿到具体错误码定位卡在哪一步。
 */
object ChannelMtop {

    private const val TAG = "xysidecar"
    private const val PROBE_API = "mtop.common.getTimestamp"

    /**
     * 第二阶段：验完时间戳后，再打一个**真实业务接口**，确认不是只有公共接口能通。
     * 选 `idlemessage.session.sync`：只读、无参数敏感项、返回会话列表，能证明登录态可用。
     */
    private const val BIZ_API = "mtop.idle.challenge.idleCoinSign.info"
    private const val BIZ_VERSION = "1.0"
    private const val BIZ_DATA = """{"fishAdCode":"","gps":""}"""

    @Volatile
    private var tried = false

    fun tryInitAndProbe(cl: ClassLoader) {
        if (tried) return
        tried = true

        val app = currentApp()
        if (app == null) {
            Log.w(TAG, "ChannelMtop: Application 未就绪，改用延迟重试")
            scheduleRetry(cl, 0)
            return
        }
        runProbe(cl, app)
    }

    /** 不依赖主进程的独立 RPC：自己建 Mtop，自己发请求 */
    private fun doRequest(cl: ClassLoader, api: String, version: String, data: String): String {
        return try {
            val obj = requestJson(cl, api, version, data)
            "OK|ret=${obj.optString("ret")}|${obj.optString("body")}"
        } catch (t: Throwable) {
            "ERR|${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /**
     * 统一入口：channel 进程内自建 Mtop 并发请求，返回 JSON。
     * 供 ChannelServer 使用（socket 协议）。
     *
     * 返回结构：{"ok":true,"ret":"SUCCESS","body":"<原始 JSON 字符串>"}
     */
    fun request(cl: ClassLoader, api: String, version: String, data: String): String {
        val out = org.json.JSONObject()
        // 打上"我方发起"标记：channel 里发的请求同样是调试/功能调用，
        // 不能和宿主自己的请求混进同一个抓包库。
        Protocol.State.enterOurs()
        try {
            return try {
                val obj = requestJson(cl, api, version, data)
                out.put("ok", true)
                out.put("ret", obj.optString("ret"))
                out.put("body", obj.optString("body"))
                out.toString()
            } catch (t: Throwable) {
                out.put("ok", false)
                out.put("error", "${t.javaClass.simpleName}: ${t.message}")
                out.toString()
            }
        } finally {
            Protocol.State.exitOurs()
        }
    }

    /** 真正的请求实现，失败抛异常 */
    private fun requestJson(
        cl: ClassLoader,
        api: String,
        version: String,
        data: String,
    ): org.json.JSONObject {
        val app = currentApp() ?: throw IllegalStateException("no application")
        val mtopCls = Class.forName("mtopsdk.mtop.intf.Mtop", false, cl)
        val inst = mtopCls.getMethod("instance", android.content.Context::class.java)
            .invoke(null, app) ?: throw IllegalStateException("Mtop.instance null")

        val reqCls = Class.forName("mtopsdk.mtop.domain.MtopRequest", false, cl)
        val req = reqCls.getDeclaredConstructor().newInstance()
        reqCls.getMethod("setApiName", String::class.java).invoke(req, api)
        reqCls.getMethod("setVersion", String::class.java).invoke(req, version)
        reqCls.getMethod("setData", String::class.java).invoke(req, data)
        // 与主进程 MtopBridge 保持一致：需要登录态（sid），且不强制 ecode
        // 少了 setNeedSession(true)，服务端会把请求当未登录处理
        runCatching {
            reqCls.getMethod("setNeedSession", java.lang.Boolean::class.java)
                .invoke(req, java.lang.Boolean.TRUE)
        }
        runCatching {
            reqCls.getMethod("setNeedEcode", java.lang.Boolean::class.java)
                .invoke(req, java.lang.Boolean.FALSE)
        }

        val buildM = findBuild(inst, reqCls) ?: throw IllegalStateException("build not found")
        val builder = buildM.invoke(inst, req, "xysidecar-ch-" + System.nanoTime())

        // 和主进程 MtopBridge 保持一致：回放 header / HTTP method / query 参数。
        // 少了 method 就会走 mtop 默认的 GET —— 要求 POST 的接口报
        // FAIL_SYS_INVALID_HTTP_METHOD，长参数接口被服务端 414 后再被客户端
        // 改写成 ANDROID_SYS_PARAM_TOO_LONG。
        reapply(builder, api)

        val resp = builder.javaClass.getMethod("syncRequest").invoke(builder)
            ?: throw IllegalStateException("null response")

        val ret = tryCall(resp, "getRetCode")?.toString() ?: ""
        val body = tryCall(resp, "getDataJsonObject")?.toString() ?: ""
        return org.json.JSONObject().put("ret", ret).put("body", body)
    }

    /**
     * onPackageLoaded 早于 Application.attach —— 那时 `ActivityThread.currentApplication()`
     * 还是 null。挂一个后台线程轮询，最多等 30 秒。
     */
    private fun scheduleRetry(cl: ClassLoader, attempt: Int) {
        if (attempt >= 30) {
            Log.w(TAG, "ChannelMtop: 等 Application 超时（30s），放弃")
            return
        }
        val t = Thread({
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return@Thread
            }
            val app = currentApp()
            if (app == null) {
                scheduleRetry(cl, attempt + 1)
            } else {
                Log.i(TAG, "ChannelMtop: Application 就绪（第 ${attempt + 1}s）")
                // 这里拿到的 app 是最可靠的：ActivityThread 已 attach 完成
                Protocol.State.app = app
                runProbe(cl, app)
                // Application 就绪后才能建 Mtop 与 socket 服务
                ChannelServer.start(cl)
                // 注册闹钟接收器 + 恢复已有任务（重启后接着跑）
                try {
                    ChannelScheduler.registerReceiver(app)
                    val tasks = ChannelScheduler.loadTasks(app)
                    if (tasks.isNotEmpty()) {
                        Log.i(TAG, "ChannelMtop: 恢复 ${tasks.size} 个定时任务")
                        ChannelScheduler.schedule(app, 60_000)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "ChannelMtop: 调度器启动失败: $t")
                }
            }
        }, "ChannelMtop-Probe")
        t.isDaemon = true
        t.start()
    }

    private fun runProbe(cl: ClassLoader, app: Application) {
        try {
            val mtopCls = Class.forName("mtopsdk.mtop.intf.Mtop", false, cl)

            // 1) 先看有没有现成实例（宿主可能已经建过）
            var inst = readInstance(mtopCls)
            Log.i(TAG, "ChannelMtop: existing instance = $inst")

            // 2) 没有就自己初始化：Mtop.instance(Context) 是 public static
            if (inst == null) {
                inst = try {
                    val m = mtopCls.getMethod("instance", android.content.Context::class.java)
                    m.invoke(null, app)
                } catch (t: Throwable) {
                    Log.w(TAG, "ChannelMtop: Mtop.instance(ctx) failed: ${t.cause ?: t}")
                    null
                }
                Log.i(TAG, "ChannelMtop: after instance(ctx) = $inst")
            }

            if (inst == null) {
                Log.w(TAG, "ChannelMtop: 初始化失败，channel 内无法用 Mtop")
                return
            }

            // 3) 看 isInited
            try {
                val inited = inst.javaClass.getMethod("isInited").invoke(inst)
                Log.i(TAG, "ChannelMtop: isInited = $inited")
            } catch (t: Throwable) {
                Log.w(TAG, "ChannelMtop: isInited failed: $t")
            }

            // 4) 发一个无害请求验证
            probeRequest(cl, inst)

        } catch (t: Throwable) {
            Log.w(TAG, "ChannelMtop: failed: $t")
        }
    }

    private fun readInstance(mtopCls: Class<*>): Any? = try {
        // 尝试所有 instance 重载里"只吃 Context"的那个，但先不动手，只读已存在的
        val f = mtopCls.getDeclaredField("mtopInstances")
        f.isAccessible = true
        val map = f.get(null) as? Map<*, *>
        map?.entries?.firstOrNull()?.value
    } catch (t: Throwable) {
        null
    }

    /** 用 mtop.common.getTimestamp 验证：无参数、只读、不改任何状态 */
    private fun probeRequest(cl: ClassLoader, inst: Any) {
        try {
            val reqCls = Class.forName("mtopsdk.mtop.domain.MtopRequest", false, cl)
            val req = reqCls.getDeclaredConstructor().newInstance()
            reqCls.getMethod("setApiName", String::class.java).invoke(req, PROBE_API)
            reqCls.getMethod("setVersion", String::class.java).invoke(req, "1.0")
            reqCls.getMethod("setData", String::class.java).invoke(req, "{}")

            val buildM = findBuild(inst, reqCls)
            if (buildM == null) {
                Log.w(TAG, "ChannelMtop: build() not found")
                return
            }
            val builder = buildM.invoke(inst, req, "xysidecar-channel-" + System.nanoTime())

            val sync = builder.javaClass.getMethod("syncRequest")
            val resp = sync.invoke(builder)
            if (resp == null) {
                Log.w(TAG, "ChannelMtop: null response")
                return
            }
            val ret = tryCall(resp, "getRetCode")
            val data = tryCall(resp, "getDataJsonObject")?.toString()
            val msg = tryCall(resp, "getRetMsg")
            Log.i(TAG, "ChannelMtop: PROBE ret=$ret msg=$msg data=$data")

            if (data != null && data != "{}" && !data.isNullOrEmpty()) {
                Log.i(TAG, "ChannelMtop: ✅ channel 内 RPC 可用")
                // 公共接口通了，再验一个真实业务接口
                probeBiz(cl, inst)
            } else {
                Log.w(TAG, "ChannelMtop: ❌ ret=$ret（通道通了但服务端拒绝或空数据）")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ChannelMtop: probeRequest failed: ${t.cause ?: t}")
        }
    }

    /** 真实业务接口：会话列表。只读，验证登录态与业务域是否在 channel 里也通 */
    private fun probeBiz(cl: ClassLoader, inst: Any) {
        try {
            val reqCls = Class.forName("mtopsdk.mtop.domain.MtopRequest", false, cl)
            val req = reqCls.getDeclaredConstructor().newInstance()
            reqCls.getMethod("setApiName", String::class.java).invoke(req, BIZ_API)
            reqCls.getMethod("setVersion", String::class.java).invoke(req, BIZ_VERSION)
            reqCls.getMethod("setData", String::class.java).invoke(req, BIZ_DATA)

            val buildM = findBuild(inst, reqCls) ?: return
            val builder = buildM.invoke(inst, req, "xysidecar-biz-" + System.nanoTime())
            val resp = builder.javaClass.getMethod("syncRequest").invoke(builder) ?: return

            val ret = tryCall(resp, "getRetCode")
            val msg = tryCall(resp, "getRetMsg")
            val raw = tryCall(resp, "getDataJsonObject")?.toString() ?: ""
            Log.i(TAG, "ChannelMtop: BIZ ret=$ret msg=$msg len=${raw.length}")
            Log.i(TAG, "ChannelMtop: BIZ head=${raw.take(200)}")
            if (raw.length > 20) {
                Log.i(TAG, "ChannelMtop: ✅✅ channel 内业务接口也可用（${raw.length} 字节）")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ChannelMtop: probeBiz failed: ${t.cause ?: t}")
        }
    }

    /**
     * 把 hook 捕获到的 header / HTTP method / query 参数写到本次 builder 上。
     * 这里的失败一律吞掉：通道本身可用比「回放不完整」更重要，
     * 缺什么由调用方看 ret 判断。
     */
    private fun reapply(builder: Any, api: String) {
        try {
            val h = Protocol.State.headersOf(api)
            if (!h.isNullOrEmpty()) {
                try {
                    builder.javaClass.getMethod("headers", Map::class.java)
                        .invoke(builder, HashMap(h))
                } catch (t: Throwable) {
                }
            }
        } catch (ignored: Throwable) {
        }
        try {
            val name = Protocol.State.methodOf(api)
            if (name != null) {
                val cl = Protocol.State.classLoader
                if (cl != null) {
                    val enumCls = Class.forName("mtopsdk.mtop.domain.MethodEnum", false, cl)
                    val e = enumCls.getMethod("valueOf", String::class.java)
                        .invoke(null, name.uppercase())
                    builder.javaClass.getMethod(
                        "reqMethod",
                        Class.forName("mtopsdk.mtop.domain.MethodEnum", false, cl),
                    ).invoke(builder, e)
                }
            }
        } catch (ignored: Throwable) {
        }
        try {
            val sw = Protocol.State.switchPostOf(api)
            if (sw != null) {
                builder.javaClass.getMethod("allowSwitchToPOST", java.lang.Boolean.TYPE)
                    .invoke(builder, java.lang.Boolean.valueOf(sw))
            }
        } catch (ignored: Throwable) {
        }
        try {
            val q = Protocol.State.queryParamsOf(api)
            if (!q.isNullOrEmpty()) {
                val f = builder.javaClass.getDeclaredField("mtopProp")
                f.isAccessible = true
                val prop = f.get(builder) ?: return
                val qf = prop.javaClass.getDeclaredField("queryParameterMap")
                qf.isAccessible = true
                qf.set(prop, HashMap<String, String>(q))
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun findBuild(inst: Any, reqCls: Class<*>): Method? {        for (m in inst.javaClass.methods) {
            if (m.name == "build" &&
                m.parameterTypes.size == 2 &&
                m.parameterTypes[0] == reqCls &&
                m.parameterTypes[1] == String::class.java
            ) {
                return m
            }
        }
        return null
    }

    private fun tryCall(o: Any, name: String): Any? = try {
        o.javaClass.getMethod(name).invoke(o)
    } catch (t: Throwable) {
        null
    }

    private fun currentApp(): Application? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Application
    } catch (t: Throwable) {
        null
    }
}
