package com.xiandehuang.u

import android.content.Context
import android.util.Log
import java.lang.reflect.Method

/**
 * 反射调用宿主进程内的 mtopsdk —— 纯 java.lang.reflect，两套 API 通用。
 * 签名（x-sign）、t、utdid、设备指纹全部由宿主自己的 SecurityGuard 现算。
 */
object MtopBridge {

    const val TAG = "xysidecar"

    @JvmStatic
    fun tag(): String = TAG

    @JvmStatic
    @Throws(Exception::class)
    fun request(ctx: Context, api: String, version: String, data: String): String {
        // 整个调用期间打上"我方发起"标记：hook 会据此把它记成 origin=ours，
        // 不会和宿主自己的请求混在一起（否则抓包库里的 ver/method 基准会被污染）。
        Protocol.State.enterOurs()
        try {
            return doRequest(ctx, api, version, data)
        } finally {
            Protocol.State.exitOurs()
        }
    }

    @Throws(Exception::class)
    private fun doRequest(ctx: Context, api: String, version: String, data: String): String {
        val cl = Protocol.State.classLoader
            ?: throw IllegalStateException("classloader not ready")

        val mtopCls = Reflect.findClass(cl, "mtopsdk.mtop.intf.Mtop")
            ?: throw IllegalStateException("mtopsdk not found")

        // 必须用宿主自己初始化好的实例，否则 mtop 报 MTOPCONTEXT_INIT_ERROR
        var instance = Protocol.State.mtop
        if (instance == null) {
            Log.w(TAG, "no captured Mtop instance, falling back to Mtop.instance()")
            Protocol.State.enterOurs()
            instance = try {
                resolveInstance(mtopCls, ctx)
            } finally {
                Protocol.State.exitOurs()
            }
        }

        val reqCls = Reflect.findClass(cl, "mtopsdk.mtop.domain.MtopRequest")
            ?: throw IllegalStateException("MtopRequest not found")
        val req = reqCls.getDeclaredConstructor().newInstance()
        Reflect.call(req, "setApiName", api)
        Reflect.call(req, "setVersion", version)
        Reflect.call(req, "setData", data)
        // needSession / needEcode 必须按宿主真实值回放：
        // 写死 needSession=true 会让服务端按"已登录业务请求"解析参数，
        // 埋点类接口（ut.log.report / instant.log / highway.upload）于是报
        // FAIL_SYS_INVALID_HTTP_METHOD 或参数非法。宿主原值是 false。
        val realSession = Protocol.State.needSessionOf(api)
        Reflect.call(
            req, "setNeedSession",
            java.lang.Boolean.valueOf(realSession ?: true),
        )
        try {
            val realEcode = Protocol.State.needEcodeOf(api)
            Reflect.call(
                req, "setNeedEcode",
                java.lang.Boolean.valueOf(realEcode ?: false),
            )
        } catch (ignored: Throwable) {
        }

        val seqNo = "xysidecar-" + System.nanoTime()
        // 必须精确命中 build(MtopRequest, String)：否则 request 不会被放进 MtopContext
        val buildM: Method = try {
            Reflect.methodExact(instance.javaClass, "build", reqCls, String::class.java)
        } catch (t: Throwable) {
            val mtopIntf = Reflect.findClass(cl, "mtopsdk.mtop.intf.Mtop")
                ?: throw IllegalStateException("Mtop interface not found")
            Reflect.methodExact(mtopIntf, "build", reqCls, String::class.java)
        }
        val builder = buildM.invoke(instance, req, seqNo)
        // 回放宿主为同一 api 设置过的请求头。
        // 实测搜索接口只带 data 会返回空 data（服务端按 header 里的设备/用户标识校验），
        // 连 header 一起带才能拿到真实结果。
        applyHeaders(builder, api)
        // 回放 HTTP 方法。mtop 的 MtopNetworkProp 默认是 GET，不回放就会把 data
        // 塞进 URL：10KB 以上的参数会被服务端 414 拒掉，客户端再改写成
        // ANDROID_SYS_PARAM_TOO_LONG（掩盖真实原因）；而要求 POST 的接口则直接
        // 返回 FAIL_SYS_INVALID_HTTP_METHOD。这是 27 个「不可重放」接口的主因。
        applyMethod(builder, api, data)
        val resp = Reflect.call(builder, "syncRequest")
            ?: throw IllegalStateException("null response")

        val retCode = asString(tryCall(resp, "getRetCode"))
        val json = extractBody(resp)
        // 关键：{} 不等于成功。服务端拒绝时 data 也是 {}，只有 ret 能区分。
        // 之前这里直接返回 "{}"，把真实原因吞掉了，导致一路在猜。
        if (isEmptyJson(json)) {
            val api2 = asString(tryCall(resp, "getApi"))
            val msg = asString(tryCall(resp, "getRetMsg"))
            val retType = tryCall(resp, "getRetType").toString()
            val v = asString(tryCall(resp, "getV"))
            val full = "ret=$retCode retType=$retType msg=$msg respApi=$api2 v=$v"
            Log.w(TAG, "empty data: $full | api=$api ver=$version")
            if (retCode != null && retCode.startsWith("SUCCESS")) {
                return "{\"ret\":\"" + retCode + "\",\"_diag\":\"" +
                    full.replace('"', '\'') + "\"}"
            }
            throw IllegalStateException(full)
        }
        return json
    }

    /**
     * 把捕获到的宿主请求头写到本次 builder 上。
     * MtopBuilder 的真实入口是 headers(Map)（实测方法表：headers(Map):MtopBuilder），
     * 没有 addHeader(String,String)。拿不到就退回直接写 mtopProp.requestHeaders。
     */
    /**
     * 写接口清单 —— **固化值，不依赖抓包库**。
     *
     * 这些接口都是实测跑通的写操作，宿主自己发的时候一律 POST。
     * 固化下来的理由：抓包库可能被用户清空，而 method 猜错（默认 GET）
     * 会让服务端回 `FAIL_SYS_INVALID_HTTP_METHOD` 或
     * `ANDROID_SYS_PARAM_TOO_LONG`，两个错误都不指向真因。
     *
     * 只收「确定是写操作」的；读接口不列进来 —— 读用 GET/POST 都行，
     * 猜错也不致命，而写错一份清单会让 GET 的读接口被强行发成 POST。
     */
    private val WRITE_APIS = HashSet<String>()

    init {
        WRITE_APIS.add("mtop.taobao.idle.play.sign.in")            // 签到
        WRITE_APIS.add("mtop.idle.play.signin.cash.open")          // 拆红包
        WRITE_APIS.add("mtop.taobao.idle.task.uploadOliverTaskTrigger")  // 任务触发
        WRITE_APIS.add("mtop.taobao.idle.task.getolivertaskbenefit")     // 任务领奖
        WRITE_APIS.add("mtop.taobao.idle.joy.community.activity.task.award") // 挑战领奖
        WRITE_APIS.add("mtop.taobao.idle.cube.task.trigger")       // cube 触发
        WRITE_APIS.add("mtop.taobao.idle.play.prop.use")           // 用道具
        WRITE_APIS.add("mtop.taobao.idle.circle.point.sign.in")    // 圈子签到
        WRITE_APIS.add("mtop.taobao.idle.circle.benefit.exchange") // 圈子兑换
        WRITE_APIS.add("mtop.taobao.idle.forest.energy.issue")     // 森林领能量
        WRITE_APIS.add("mtop.taobao.idle.forest.animal.feed")      // 森林喂养
        WRITE_APIS.add("mtop.taobao.idle.treasure.hunt.map.roll")  // 寻宝掷骰
    }

    /**
     * 调试开关：跳过 header 回放。
     *
     * 用途：验证 mtop 的签名是否**真的依赖** hook 抓到的 header。
     * 如果跳过之后 `syncRequest` 依然成功 → 说明 header 回放不是必需的，
     * 新方案（自己算签名 + 直连网关）才有意义去追求。
     */
    @Volatile
    var skipHeaders = false

    private fun applyHeaders(builder: Any, api: String) {
        if (skipHeaders) return
        // 先取内存缓存（hookFinalRequest 现抓的，最准）。
        // 拿不到就从抓包库回填 —— 一个接口只要宿主这次进程里还没发过，
        // 内存缓存就是空的，而「宿主从不调用、只有我们想试」的接口
        // 恰恰最需要 header（trigger 缺 referer 会回 PARAM_ILLEGAL）。
        // 有了这条兜底，试参数不用再编译装机。
        val h = Protocol.State.headersOf(api) ?: backfillHeaders(api)
        if (h == null || h.isEmpty()) return
        // 签名头回放必失效（服务端返回 FAIL_SYS_ILEGEL_SIGN）：抓到的 header 可能是
        // 早先 hook 记下的签过名版本，这里统一剔掉，让 mtop 自己现算。
        val safe = HashMap<String, String>()
        for ((k, v) in h) {
            val n = k.lowercase()
            if (n == "x-sgext" || n == "x-sign" || n == "x-simplex" || n == "x-requestsign") continue
            safe[k] = v
        }
        if (safe.isEmpty()) return
        try {
            Reflect.call(builder, "headers", safe)
            Log.i(TAG, "replayed ${safe.size} headers for $api")
            return
        } catch (t: Throwable) {
            Log.w(TAG, "headers(Map) failed: $t")
        }
        try {
            val prop = Reflect.field(builder, "mtopProp")
            val m = Reflect.field(prop!!, "requestHeaders")
            if (m is MutableMap<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val dst = m as MutableMap<Any?, Any?>
                dst.putAll(safe)
                Log.i(TAG, "replayed ${safe.size} headers via mtopProp for $api")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "header replay failed: $t")
        }
    }

    /**
     * 从抓包库回填 header，并把结果塞进内存缓存（同一 api 只查一次库）。
     *
     * 缓存命中后 `headersOf` 直接返回，下次不再查库 —— 一次查询 ~5ms，
     * 但每轮业务链会调十几次，缓存掉更稳。
     *
     * 只填「缺失的 api」，不覆盖已有的：内存缓存是宿主刚发的真值，
     * 比库里的历史记录新。
     *
     * 库里也没有时，用 [ParamStore] 里的 asac 兜一层 —— 用户清空抓包库后
     * 这是唯一还能拿到令牌的地方，不兜的话所有写接口立刻瘫。
     */
    private fun backfillHeaders(api: String): MutableMap<String, String>? {
        try {
            val fromDb = TrafficStore.headersFromDb(api)
            if (fromDb.isNotEmpty()) {
                // headers(Map) 是合并语义，不会覆盖已有键
                Protocol.State.headers(api, fromDb)
                Log.i(TAG, "header backfill: ${fromDb.size} fields for $api")
                return Protocol.State.headersOf(api)
            }
            // 库被清空/没抓到这个 api —— 至少把 asac 补上
            val ctx = Protocol.State.app
            val asac = ParamStore.value(ctx, "asac")
            if (asac.isNotEmpty()) {
                val one = HashMap<String, String>()
                one["asac"] = asac
                Protocol.State.headers(api, one)
                Log.i(TAG, "header backfill: asac only (db empty) for $api")
                return Protocol.State.headersOf(api)
            }
            return null
        } catch (t: Throwable) {
            Log.w(TAG, "header backfill failed: $t")
            return null
        }
    }

    /**
     * 回放 HTTP 方法与 URL query 参数。
     *
     * 为什么必须做（mt 静态分析 + 实测数据）：
     *   - `MtopNetworkProp.<init>` 把 method 默认成 MethodEnum.GET、
     *     allowSwitchToPOST 默认 false。
     *   - `AbstractNetworkConverter.convert`：只有 allowSwitchToPOST 或
     *     isEnableLongParamOptimize 才可能把长参数改走 POST；否则 GET 的 data
     *     全塞进 URL。
     *   - `NetworkErrorAfterFilter.doAfter`：responseCode ∈ {400,414,431,500}
     *     且 requestTotalLength > 0x2000 时，把 retCode 改写成
     *     ANDROID_SYS_PARAM_TOO_LONG —— 服务端的 414 被这个错误码盖掉了。
     * 于是：要求 POST 的接口报 FAIL_SYS_INVALID_HTTP_METHOD；参数大的接口报
     * ANDROID_SYS_PARAM_TOO_LONG。27 个「不可重放」里这两类占 7 个。
     */
    private fun applyMethod(builder: Any, api: String, data: String) {
        val cl = Protocol.State.classLoader ?: return
        // 进程刚起时 hook 还没抓到真值：从抓包库回填一次（幂等，只填空位）。
        // 这决定了写接口会不会被发成 GET —— 抓包里写操作几乎都是 POST。
        // 条件必须是「真值缺失」而不是 anyMethod：prop 的猜测值（默认 GET）
        // 会让 anyMethod 返回 true 从而跳过回填，写接口照样发成 GET（实测踩过）。
        if (!Protocol.State.hasFinalMethod(api)) {
            try {
                val all = TrafficStore.methodsFromDb()
                Protocol.State.backfillMethods(all)
                Log.i(
                    TAG,
                    "method backfill: ${all.size} apis from db, $api -> ${all[api]}",
                )
            } catch (t: Throwable) {
                Log.w(TAG, "method backfill failed: $t")
            }
            // 抓包库被清空/没抓到时，用固化表兜底。写接口一律 POST ——
            // 这是实测结论（抓包里写操作几乎全是 POST），不是猜测。
            if (!Protocol.State.hasFinalMethod(api) && WRITE_APIS.contains(api)) {
                val one = HashMap<String, String>()
                one[api] = "POST"
                Protocol.State.backfillMethods(one)
                Log.i(TAG, "method backfill: $api -> POST (固化表)")
            }
        }
        val name = Protocol.State.methodOf(api)
        if (name != null) {
            try {
                val enumCls = Reflect.findClass(cl, "mtopsdk.mtop.domain.MethodEnum")
                val valueOf = enumCls?.getMethod("valueOf", String::class.java)
                val e = valueOf?.invoke(null, name.uppercase())
                if (e != null) {
                    Reflect.call(builder, "reqMethod", e)
                    // 回读确认真的改到了 mtopProp.method：mtop 的 reqMethod 只是写字段，
                    // 万一被上层覆盖，日志里能立刻看出来
                    val prop = try {
                        Reflect.field(builder, "mtopProp")
                    } catch (t: Throwable) {
                        null
                    }
                    val now = if (prop != null) {
                        try {
                            Reflect.field(prop, "method")?.toString()
                        } catch (t: Throwable) {
                            null
                        }
                    } else {
                        null
                    }
                    Log.i(TAG, "replayed method $name for $api (prop.method=$now)")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "reqMethod($name) failed: $t")
            }
        }
        // allowSwitchToPOST：宿主为长参数接口打开它，我们照做，
        // 否则同样的 data 会因为「走 URL」而被服务端拒。
        val sw = Protocol.State.switchPostOf(api)
        try {
            if (sw != null && sw) {
                // 宿主自己开了 switchToPost：跟着开，让 mtop 按参数长度决定
                // GET→POST。这类接口（instant.log / highway.upload）的 data 全在
                // URL query 里，长度一超阈值就必须 POST，否则服务端报
                // FAIL_SYS_INVALID_HTTP_METHOD。
                Reflect.call(builder, "allowSwitchToPOST", java.lang.Boolean.TRUE)
            } else if (sw == null && name == null) {
                // 完全没有任何 method 信息时的兜底：开 switchToPost 自适应
                Reflect.call(builder, "allowSwitchToPOST", java.lang.Boolean.TRUE)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "allowSwitchToPOST failed: $t")
        }
        // URL query 参数回放：**只在 data 为空时才做**。
        //
        // 坑：H5 页面发请求时 data 会被放到 URL 上（MtopContext.networkRequest.url
        // 里能看到 query=data），这时我们既把 data 交给 setData，又把它当 query
        // 回放，参数就重复了 —— 服务端算签名时对不上，直接回 FAIL_SYS_ILEGEL_SIGN。
        // 实测森林 energy.issue 就是这么被自己搞挂的。
        val q = if (data.isEmpty() || data == "{}") Protocol.State.queryParamsOf(api) else null
        if (!q.isNullOrEmpty()) {
            try {
                // 真正写入用 mtopProp.queryParameterMap，一次放进全部 key
                // （addHttpQueryParameter 要逐个调，且会顺手多塞空键值）。
                val prop = Reflect.field(builder, "mtopProp")
                val f = Reflect.field(prop!!, "queryParameterMap")
                if (f is MutableMap<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val dst = f as MutableMap<Any?, Any?>
                    dst.putAll(q)
                    Log.i(TAG, "replayed ${q.size} query params for $api")
                } else {
                    // 字段为 null（宿主从没设过）时，先初始化再放
                    val fld = prop.javaClass.getDeclaredField("queryParameterMap")
                    fld.isAccessible = true
                    fld.set(prop, HashMap<String, String>(q))
                    Log.i(TAG, "replayed ${q.size} query params (init) for $api")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "query param replay failed: $t")
            }
        }
    }

    /**
     * MtopResponse 没有 getDataJson()，真实载体是 dataJsonObject / data(bytes)。
     *
     * 注意：getDataJsonObject() 可能返回**空对象**，此时不能直接返回 "{}" ——
     * 真实数据可能只在 data(bytes) 里。实测搜索接口就是这种情况：
     * dataJsonObject 是 {}，而 data 字节里才是完整结果。
     */
    private fun extractBody(resp: Any): String {
        val v = tryCall(resp, "getDataJsonObject")
        val fromObj = v?.toString()
        if (!isEmptyJson(fromObj)) return fromObj!!

        var f: Any? = null
        try {
            f = Reflect.field(resp, "dataJsonObject")
        } catch (ignored: Throwable) {
        }
        if (f != null) {
            val s = f.toString()
            if (!isEmptyJson(s)) return s
        }

        // 最后看原始字节：这里往往才是真数据
        val d = tryCall(resp, "getData")
        if (d is ByteArray) {
            try {
                val s = String(d, Charsets.UTF_8)
                if (s.trim().isNotEmpty()) {
                    Log.i(TAG, "extractBody: fell back to data bytes, ${s.length} chars")
                    return s
                }
            } catch (ignored: Throwable) {
            }
        }
        return fromObj ?: ""
    }

    private fun isEmptyJson(s: String?): Boolean {
        if (s == null) return true
        val t = s.trim()
        return t.isEmpty() || t == "{}" || t == "null"
    }

    private fun tryCall(target: Any, name: String): Any? =
        try {
            Reflect.call(target, name)
        } catch (t: Throwable) {
            null
        }

    private fun asString(o: Any?): String? = o as? String

    /** 闲鱼可能用带 instanceId 的 Mtop 实例；冷启动时实例可能还没建好，重试几次 */
    @Throws(Exception::class)
    private fun resolveInstance(mtopCls: Class<*>, ctx: Context): Any {
        for (round in 0 until 6) {
            tryCallStatic(mtopCls, "instance", ctx)?.let { return it }

            val id = Reflect.staticField(mtopCls, "MTOP_ID_XIANYU")
            if (id is String) {
                tryCallStatic(mtopCls, "instance", ctx, id)?.let { return it }
            }

            if (round < 5) {
                try {
                    Thread.sleep(700)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        throw IllegalStateException("Mtop.instance returned null (app not initialized?)")
    }

    private fun tryCallStatic(cls: Class<*>, name: String, vararg args: Any?): Any? =
        try {
            Reflect.callStatic(cls, name, *args)
        } catch (t: Throwable) {
            Log.w(TAG, "instance() failed: $t")
            null
        }
}
