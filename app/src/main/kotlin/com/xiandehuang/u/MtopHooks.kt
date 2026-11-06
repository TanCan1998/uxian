package com.xiandehuang.u

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.json.JSONObject
import java.lang.reflect.Modifier

/**
 * mtop 相关的 hook：记录请求、捕获实例、初始化回调。
 *
 * 从 NewEntry 拆出来（原 1100 行）。这块是"摸清宿主到底怎么发请求"的核心，
 * 单独放一起便于对照真实抓包排查，不必在入口类里翻。
 *
 * 关键事实（真机实测）：
 *   - Mtop 必须用宿主自己初始化好的实例，否则报 MTOPCONTEXT_INIT_ERROR
 *   - 版本号不对会静默返回 {}（ret 仍是 SUCCESS），所以版本要和 api 一起记
 *   - 请求头也要回放：搜索接口只带 data 会返回空
 */
object MtopHooks {

    private const val TAG = "xysidecar"
    private const val LAUNCHER = "com.taobao.android.remoteobject.easy.MtopLauncher"

    /**
     * 宿主源码链：parseParams -> BaseApiProtocol.param -> ApiBusiness.parameter ->
     * EasyContext.parameterIs -> RemoteContext.parameter -> preProcess.internalRequest ->
     * MtopBusiness.build。只传递模块侧 identity，不往 data/headers 放标记。
     */
    private fun hookHostRpc(module: XposedModule, cl: ClassLoader) {
        Protocol.State.hostRpcReady = false
        try {
            val bridge = Class.forName("com.taobao.idlefish.card.weexcard.module.WeexMtopUtil", false, cl)
            val json = Class.forName("com.alibaba.fastjson.JSONObject", false, cl)
            val protocol = Class.forName("com.taobao.idlefish.protocol.net.api.BaseApiProtocol", false, cl)
            val callback = Class.forName("com.taobao.idlefish.protocol.net.ApiCallBack", false, cl)
            val remote = Class.forName("com.taobao.android.remoteobject.core.RemoteContext", false, cl)
            val sdk = Class.forName("com.taobao.android.remoteobject.mtopsdk.MtopSDKHandler", false, cl)
            val launcher = Class.forName(LAUNCHER, false, cl)
            val business = Class.forName("com.taobao.tao.remotebusiness.MtopBusiness", false, cl)
            val request = Class.forName("mtopsdk.mtop.domain.MtopRequest", false, cl)
            val mtop = Class.forName("mtopsdk.mtop.intf.Mtop", false, cl)
            val getParam = protocol.getMethod("getParam")
            val getParameter = remote.getMethod("getParameter")
            val getRequest = remote.getMethod("getInternalRequest")
            val sendInternal = launcher.getMethod("sendInternal", protocol, callback)
            val noLogin = business.getMethod("showLoginUI", Boolean::class.javaPrimitiveType)
            val loginField = business.getDeclaredField("showLoginUI").apply { isAccessible = true }
            val requestField = Class.forName("mtopsdk.mtop.intf.MtopBuilder", false, cl)
                .getDeclaredField("request").apply { isAccessible = true }
            var ready = true
            fun install(label: String, method: java.lang.reflect.Method, hook: XposedInterface.Hooker) {
                if (!HookKit.install(module, "host-rpc", label, method, hook)) ready = false
            }
            install("parse", bridge.getDeclaredMethod("parseParams", json, Boolean::class.javaPrimitiveType),
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val call = Protocol.State.hostCall(chain.getArg(0))
                        if (call != null && !call.active()) return null
                        val value = chain.proceed()
                        if (call != null && value != null) {
                            try {
                                val param = getParam.invoke(value) ?: throw IllegalStateException()
                                Protocol.State.markHostCall(value, call)
                                Protocol.State.markHostCall(param, call)
                            } catch (_: Throwable) {
                                call.fail("HOST_RPC_IDENTITY_FAILED")
                                return null
                            }
                        }
                        return value
                    }
                })
            install("send", launcher.getDeclaredMethod("send", protocol, callback),
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val call = Protocol.State.hostCall(chain.getArg(0)) ?: return chain.proceed()
                        if (!call.active()) return null
                        // 不 release 全局队列：仅此协议走宿主已有 sendInternal 分支。
                        try { sendInternal.invoke(chain.getThisObject(), chain.getArg(0), chain.getArg(1)) }
                        catch (_: Throwable) { call.fail("HOST_RPC_DISPATCH_FAILED") }
                        return null
                    }
                })
            val send = Class.forName("com.taobao.android.remoteobject.easy.MtopSend", false, cl)
            val apiBusiness = Class.forName("com.taobao.android.remoteobject.easy.IMtopBusiness", false, cl)
            val sourceRequest = apiBusiness.getMethod("getRequest")
            val businessParam = apiBusiness.getMethod("getParam")
            install("execute", send.getDeclaredMethod("execute", apiBusiness),
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val owner = chain.getArg(0)
                        val source = try { sourceRequest.invoke(owner) } catch (_: Throwable) { null }
                        val call = Protocol.State.hostCall(source) ?: return chain.proceed()
                        if (!call.active()) return null
                        try {
                            // ApiBusiness.parseAnno 可转换 param；在 execute 读取最终参数 identity。
                            val param = businessParam.invoke(owner) ?: throw IllegalStateException()
                            Protocol.State.markHostCall(param, call)
                        } catch (_: Throwable) {
                            call.fail("HOST_RPC_IDENTITY_FAILED")
                            return null
                        }
                        return chain.proceed()
                    }
                })
            install("prepare", sdk.getDeclaredMethod("preProcess", remote),
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val context = chain.getArg(0)
                        val param = try { getParameter.invoke(context) } catch (_: Throwable) { null }
                        val call = Protocol.State.hostCall(param) ?: return chain.proceed()
                        if (!call.active()) return false
                        return try {
                            val result = chain.proceed()
                            if (result == true) {
                                val req = getRequest.invoke(context) ?: throw IllegalStateException()
                                Protocol.State.markHostCall(req, call)
                            }
                            result
                        } catch (_: Throwable) {
                            call.fail("HOST_RPC_PREPARE_FAILED")
                            false
                        }
                    }
                })
            install("build", business.getDeclaredMethod("build", mtop, request, String::class.java),
                object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val value = chain.proceed()
                        val call = Protocol.State.hostCall(chain.getArg(1))
                        if (call != null && value != null) {
                            Protocol.State.markHostCall(value, call)
                            try { noLogin.invoke(value, false) }
                            catch (_: Throwable) { call.fail("HOST_RPC_UI_GUARD_FAILED") }
                        }
                        return value
                    }
                })
            val headers = business.declaredMethods.first {
                it.name == "headers" && it.returnType == business &&
                    it.parameterTypes.contentEquals(arrayOf(Map::class.java))
            }
            install("headers", headers, object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val self = chain.getThisObject()
                    val call = Protocol.State.hostCall(self)
                    if (call == null) {
                        // 宿主自己的请求：顺手把 data.asac 抄进参数库。
                        // 抓包库是「可清理的研究数据」，业务链不能靠它活着 ——
                        // 用户清一次库就断链，这种依赖必须断开。
                        try {
                            val req = requestField.get(self)
                            val raw = fieldSafe(req, "data")?.toString() ?: ""
                            if (raw.indexOf("asac") >= 0) {
                                BusinessParams.rememberHostAsac(
                                    Protocol.State.app, JSONObject(raw).optString("asac", ""),
                                )
                            }
                        } catch (_: Throwable) {
                        }
                        return chain.proceed()
                    }
                    if (!call.active()) return self
                    val args = try {
                        val req = requestField.get(self)
                        val raw = fieldSafe(req, "data")?.toString() ?: "{}"
                        val scoped = JSONObject(raw).optString("asac", "")
                        if (scoped.isEmpty()) return chain.proceed()
                        // PClientInfo 的 headers 可能是全局共享 Map，必须先复制。
                        val copy = LinkedHashMap<Any?, Any?>()
                        val original = chain.getArg(0) as? Map<*, *>
                        if (original != null) copy.putAll(original)
                        copy["asac"] = scoped
                        arrayOf<Any?>(copy)
                    } catch (_: Throwable) {
                        call.fail("HOST_RPC_HEADERS_FAILED")
                        return self
                    }
                    return chain.proceed(args)
                }
            })
            // 覆盖所有 startRequest 重载及 SDK 重试；只命中精确标记的 builder/request。
            val starts = HookKit.findAllByName(business, "startRequest")
            if (starts.isEmpty()) ready = false
            for (method in starts) {
                install("start-" + method.parameterCount, method, object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val self = chain.getThisObject()
                        val call = Protocol.State.hostCall(self)
                            ?: Protocol.State.hostCall(requestField.get(self)) ?: return chain.proceed()
                        if (!call.active()) return null
                        try {
                            noLogin.invoke(self, false)
                            if (loginField.getBoolean(self)) throw IllegalStateException()
                        } catch (_: Throwable) {
                            call.fail("HOST_RPC_UI_GUARD_FAILED")
                            return null
                        }
                        return chain.proceed()
                    }
                })
            }
            Protocol.State.hostRpcReady = ready
            Protocol.State.logLine(if (ready) "宿主 RPC 精确来源/无界面边界已启用" else "宿主 RPC 边界不完整，入口禁用")
        } catch (_: Throwable) {
            Protocol.State.logLine("宿主 RPC 边界安装失败，入口禁用")
        }
    }

    // ---------------------------------------------------------------- request tap

    /** 把宿主请求头按 api 存起来，供 MtopBridge 回放 */
    fun rememberHeaders(api: Any?, prop: Any?) {
        try {
            val h = fieldSafe(prop, "requestHeaders")
            if (h is Map<*, *>) {
                if (h.isNotEmpty()) {
                    val dst = LinkedHashMap<String, String>()
                    for ((k, v) in h) {
                        if (k == null || v == null) continue
                        dst[k.toString()] = v.toString()
                    }
                    Protocol.State.headers(api.toString(), dst)
                }
            }
            // 请求方法同样要记 —— 但这只是**初步值**。
            // prop.method 在 asyncRequest 那一刻可能还是默认的 GET，真正的动词由
            // AbstractNetworkConverter.convert 决定；hookFinalRequest 抓到的最终值
            // 会覆盖这里的猜测（见 Protocol.State.finalMethod）。
            val m = fieldSafe(prop, "method")
            if (m != null) {
                val ms = (Reflect.call(m, "getMethod") as? String) ?: m.toString()
                Protocol.State.methodGuess(api.toString(), ms)
                Log.i(TAG, "captured method $ms for $api")
            }
            // allowSwitchToPOST：为 true 时长参数 GET 会自动改走 POST。
            // 不回放它，重放就会把 10KB+ 参数塞进 URL → 服务端 414 →
            // 客户端 NetworkErrorAfterFilter 改写成 ANDROID_SYS_PARAM_TOO_LONG。
            val sw = fieldSafe(prop, "allowSwitchToPOST")
            if (sw is Boolean) {
                Protocol.State.switchPost(api.toString(), sw)
            }
            // queryParameterMap：有些接口 data 是 "{}"，参数全在 URL query 里，
            // 不回放就报 FAIL_BIZ_INVALID_QUERY_PARAM（edgecomputer.query 就是）。
            val q = fieldSafe(prop, "queryParameterMap")
            if (q is Map<*, *> && q.isNotEmpty()) {
                Protocol.State.queryParams(api.toString(), q)
            }
        } catch (ignored: Throwable) {
        }
    }

    /** 展开 MtopNetworkProp：只挑可能与"返回空"相关的字段 */
    fun describeProp(prop: Any?): String {
        val sb = StringBuilder("MtopNetworkProp{")
        val names = arrayOf(
            "protocol", "method", "envMode", "retryTimes",
            "autoRedirect", "requestHeaders", "queryParams", "supportStreamJson",
            "reqContext", "reqBizId", "bizId",
        )
        for (name in names) {
            try {
                val v = fieldSafe(prop, name) ?: continue
                var sv = v.toString()
                if (sv.length > 300) sv = sv.substring(0, 300) + "…"
                sb.append(name).append('=').append(sv).append(' ')
            } catch (ignored: Throwable) {
            }
        }
        return sb.append('}').toString()
    }

    /** 反射读字段，读不到返回 null 而不是抛异常 */
    fun fieldSafe(target: Any?, name: String): Any? =
        try {
            if (target == null) null else Reflect.field(target, name)
        } catch (t: Throwable) {
            null
        }

    // ---------------------------------------------------------------- final request tap

    /**
     * 抓**最终发出的** HTTP 请求（`MtopContext.networkRequest`）。
     *
     * 为什么必须挂这一层：`MtopNetworkProp.method` 在 asyncRequest 那一刻还是默认的
     * GET，真正改动词发生在 `AbstractNetworkConverter.convert` 里
     * （allowSwitchToPOST / isEnableLongParamOptimize 会把长参数 GET 改走 POST）。
     * 所以从 prop 上抓到的 method 经常是错的 —— 实测 ut.log.report 的宿主请求
     * prop.method=GET，实际发出的是 POST。按 prop 回放就会被服务端判
     * FAIL_SYS_INVALID_HTTP_METHOD。
     *
     * 接入点：`NetworkConvertBeforeFilter.doBefore(MtopContext)`，它拿到 convert 的
     * 结果并塞进 `MtopContext.networkRequest`，之后 100% 不再变。
     */
    fun hookFinalRequest(module: XposedModule, cl: ClassLoader) {
        try {
            val cls = Class.forName(
                "mtopsdk.framework.filter.before.NetworkConvertBeforeFilter", false, cl,
            )
            val m = cls.getDeclaredMethod("doBefore", Class.forName("mtopsdk.framework.domain.MtopContext", false, cl))
            module.hook(m).setId("xysidecar-finalreq")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val r = chain.proceed()
                        try {
                            val ctx = chain.getArg(0)
                            val req = fieldSafe(ctx, "networkRequest") ?: return r
                            val mtopReq = fieldSafe(ctx, "mtopRequest")
                            val ours = Protocol.State.hostCall(mtopReq) != null || Protocol.State.isOurs()
                            val api = fieldSafe(mtopReq, "apiName")?.toString()
                                ?: fieldSafe(req, "api")?.toString() ?: return r
                            // needSession / needEcode 影响服务端怎么解析参数：
                            // 写死 needSession=true 会让埋点类接口（ut.log.report /
                            // instant.log / highway.upload）报
                            // FAIL_SYS_INVALID_HTTP_METHOD。宿主原值是 false。
                            val ns = fieldSafe(mtopReq, "needSession")
                            if (ns is Boolean && !ours) Protocol.State.needSession(api, ns)
                            val ne = fieldSafe(mtopReq, "needEcode")
                            if (ne is Boolean && !ours) Protocol.State.needEcode(api, ne)
                            // method：Request.method 是 final String，就是线上动词。
                            //
                            // 但**只采纳宿主自己的请求**：hook 对我方重放同样生效，
                            // 我方发出去的值会被记进"真实值"表，反过来污染下一次回放
                            // （实测：宿主用 GET，我们探测时发 POST，之后一律按 POST 发）。
                            val meth = fieldSafe(req, "method")?.toString()
                            if (meth != null) {
                                if (ours) {
                                    Protocol.State.methodOurs(api, meth)
                                } else {
                                    Protocol.State.method(api, meth)
                                }
                            }
                            // headers：这一份是**签过名**的最终 header，含 x-sgext /
                            // x-sign / x-umt 等。这些签名里有时间戳，几分钟后就失效，
                            // 回放它们会让 mtop 报 FAIL_SYS_ILEGEL_SIGN —— 实测就是这么炸的。
                            // 所以只取「业务/设备类」头，签名类一律丢掉，让 mtop 自己重算。
                            val h = fieldSafe(req, "headers")
                            if (h is Map<*, *> && h.isNotEmpty()) {
                                val dst = LinkedHashMap<String, String>()
                                for ((k, v) in h) {
                                    if (k == null || v == null) continue
                                    val key = k.toString()
                                    if (isSignatureHeader(key)) continue
                                    dst[key] = v.toString()
                                }
                                // 只采纳宿主自己的 header：我方重放的 header 本来就是
                                // 从库/缓存里取出来的，再写回去等于自我循环。
                                if (dst.isNotEmpty() && !ours) {
                                    Protocol.State.headers(api, dst)
                                }
                            }
                            // url 里带 query 参数：解析出来回放，否则
                            // edgecomputer.query 这类「参数全在 URL」的接口永远参数非法
                            val url = fieldSafe(req, "url")?.toString()
                            if (url != null) {
                                val q = url.substringAfter('?', "")
                                if (q.isNotEmpty()) {
                                    val map = LinkedHashMap<String, String>()
                                    for (kv in q.split('&')) {
                                        if (kv.isEmpty()) continue
                                        val i = kv.indexOf('=')
                                        if (i <= 0) continue
                                        try {
                                            map[urlDecode(kv.substring(0, i))] =
                                                urlDecode(kv.substring(i + 1))
                                        } catch (ignored: Throwable) {
                                        }
                                    }
                                    if (map.isNotEmpty() && !ours) {
                                        Protocol.State.queryParams(api, map)
                                    }
                                }
                            }
                            Protocol.State.finalTapCount++
                        } catch (ignored: Throwable) {
                        }
                        return r
                    }
                })
            Protocol.State.logLine("最终请求抓取已启用")
            Log.i(TAG, "hooked final request (NetworkConvertBeforeFilter)")
        } catch (t: Throwable) {
            Log.w(TAG, "hookFinalRequest failed: $t")
            Protocol.State.logLine("最终请求抓取失败: $t")
        }
    }

    private fun urlDecode(s: String): String =
        try {
            java.net.URLDecoder.decode(s, "UTF-8")
        } catch (e: Throwable) {
            s
        }

    /**
     * 签名类请求头：每次请求由 mtop 现算（含时间戳/签名摘要），回放旧值必失效。
     * 实测回放 x-sgext 会让服务端直接返回 FAIL_SYS_ILEGEL_SIGN。
     *
     * 注意别误伤 x-umt / x-utdid / x-appkey：那些是设备与 appkey 标识，
     * 宿主自己的 mtop 也会算，但我们回放同样的值不会过期，反而有助于对齐环境。
     */
    private fun isSignatureHeader(k: String): Boolean = when (k.lowercase()) {
        "x-sgext", "x-sign", "x-simplex", "x-requestsign" -> true
        else -> false
    }

    /** 记录 App 自己发的 mtop 请求（api/version），用于对齐真实接口名 */
    fun hookRequestTap(module: XposedModule, cl: ClassLoader) {
        hookHostRpc(module, cl)
        try {
            val builder = Class.forName("mtopsdk.mtop.intf.MtopBuilder", false, cl)
            var n = 0
            for (m in builder.declaredMethods) {
                if ("asyncRequest" != m.name) continue
                try {
                    module.hook(m).setId("xysidecar-tap-" + m.parameterCount)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                try {
                                    val self = chain.getThisObject()!!
                                    val req = Reflect.field(self, "request")
                                    if (Protocol.State.hostCall(req) != null) {
                                        // 只给 record 设置 origin；不把 proceed 内的宿主其他调用染成 ours。
                                        Protocol.State.withOrigin(true) {
                                            TrafficStore.record(
                                                "REQ", fieldSafe(req, "apiName")?.toString() ?: "",
                                                fieldSafe(req, "version")?.toString(),
                                                fieldSafe(fieldSafe(self, "mtopProp"), "method")?.toString(),
                                                null, fieldSafe(req, "data")?.toString(), null,
                                            )
                                        }
                                        return chain.proceed()
                                    }
                                    if (req == null) {
                                        Protocol.State.tapLog("nullreq:" + self.javaClass.name)
                                    } else {
                                        val api = Reflect.field(req, "apiName")
                                        val ver = Reflect.field(req, "version")
                                        Protocol.State.tap(api.toString(), ver.toString())
                                        Protocol.State.tapLog("req:$api/$ver")
                                        // 额外 dump builder 上的其他字段：
                                        // 若某些接口"参数照搬却返回空"，差异常在这里
                                        // （instanceId / ttid / 自定义 header / retry 策略等）
                                        try {
                                            val mb = StringBuilder()
                                            val seen = HashSet<String>()
                                            var cc: Class<*>? = self.javaClass
                                            while (cc != null && !cc.name.startsWith("java.")) {
                                                for (f in cc.declaredFields) {
                                                    if (!seen.add(f.name)) continue
                                                    if (Modifier.isStatic(f.modifiers)) continue
                                                    try {
                                                        f.isAccessible = true
                                                        val v = f.get(self) ?: continue
                                                        var sv = v.toString()
                                                        if (sv.length > 120) sv = sv.substring(0, 120) + "…"
                                                        // mtopProp 里的 requestHeaders / retry / envMode 是
                                                        // "参数照搬却返回空" 的常见差异点，单独展开
                                                        if ("mtopProp" == f.name) {
                                                            sv = describeProp(v)
                                                            rememberHeaders(api, v)
                                                        }
                                                        mb.append(f.name).append('=').append(sv)
                                                            .append(" | ")
                                                    } catch (ignored: Throwable) {
                                                    }
                                                }
                                                cc = cc.superclass
                                            }
                                            Protocol.State.tapLog("builder:$api $mb")
                                        } catch (ignored: Throwable) {
                                        }
                                        var data: Any? = null
                                        try {
                                            data = Reflect.field(req, "data")
                                        } catch (ignored: Throwable) {
                                        }
                                        val ds = data?.toString() ?: "null"
                                        Protocol.State.body(api.toString(), ver.toString(), ds)
                                        // 完整抓包：版本 + 方法 + headers 一起入库，不靠猜
                                        try {
                                            val meth = Protocol.State.methodOf(api.toString())
                                            val hh = Protocol.State.headersOf(api.toString())
                                            val hjson = if (!hh.isNullOrEmpty()) {
                                                JSONObject.wrap(hh).toString()
                                            } else {
                                                null
                                            }
                                            // 调用层判定：必须在这个钩子里抓栈 ——
                                            // 栈只在发起请求的线程上有意义，放到写库线程就没了。
                                            val caller = try {
                                                CallerTrace.capture()?.encode()
                                            } catch (t: Throwable) {
                                                null
                                            }
                                            TrafficStore.record(
                                                "REQ", api.toString(), ver.toString(), meth,
                                                hjson, ds, caller,
                                            )
                                        } catch (ignored: Throwable) {
                                        }
                                        val shown = if (ds.length > 700) ds.substring(0, 700) + "..." else ds
                                        Protocol.State.tapLog("body:$api $shown")
                                    }
                                } catch (t: Throwable) {
                                    Protocol.State.tapLog("tapErr:$t")
                                }
                                return chain.proceed()
                            }
                        })
                    n++
                } catch (t: Throwable) {
                    Log.w(TAG, "tap hook failed: $t")
                }
            }
            // 另一条路：直接盯 MtopRequest 的 setter（builder 内部字段有时还没填）
            val reqCls = Class.forName("mtopsdk.mtop.domain.MtopRequest", false, cl)
            val pending = ThreadLocal<String>()
            module.hook(reqCls.getDeclaredMethod("setApiName", String::class.java))
                .setId("xysidecar-api")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        pending.set(chain.getArg(0).toString())
                        return chain.proceed()
                    }
                })
            module.hook(reqCls.getDeclaredMethod("setData", String::class.java))
                .setId("xysidecar-data")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val api = pending.get()
                        val d = chain.getArg(0)
                        if (api != null && d != null) {
                            val full = d.toString()
                            Protocol.State.body(api, full)
                            val ds = if (full.length > 600) full.substring(0, 600) + "..." else full
                            Protocol.State.tapLog("body:$api $ds")
                        }
                        return chain.proceed()
                    }
                })
            module.hook(reqCls.getDeclaredMethod("setVersion", String::class.java))
                .setId("xysidecar-ver")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val api = pending.get()
                        if (api != null) {
                            Protocol.State.tap(api, chain.getArg(0).toString())
                            pending.remove()
                        }
                        return chain.proceed()
                    }
                })
            n += 2

            Protocol.State.tapHooks = n
            Protocol.State.capTap = n > 0
            Protocol.State.logLine(if (n > 0) "请求记录已启用" else "请求记录不可用")
            Log.i(TAG, "tapped $n asyncRequest methods")
        } catch (t: Throwable) {
            Log.e(TAG, "hookRequestTap failed: $t")
        }
    }

    /** 捕获宿主自己拿到的 Mtop 实例（我们在 init 之前装钩子，能截到） */
    fun hookMtopInstance(module: XposedModule, cl: ClassLoader) {
        try {
            val mtopCls = Class.forName("mtopsdk.mtop.intf.Mtop", false, cl)
            var n = 0
            for (m in mtopCls.declaredMethods) {
                if ("instance" != m.name || !Modifier.isStatic(m.modifiers)) continue
                try {
                    module.hook(m).setId("xysidecar-instance-" + m.parameterCount)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                val r = chain.proceed()
                                if (Protocol.State.isOurs()) return r
                                if (r != null && Protocol.State.mtop == null) {
                                    Protocol.State.mtop = r
                                    Protocol.State.captures++
                                    Log.i(TAG, "captured Mtop via " + chain.getExecutable())
                                }
                                return r
                            }
                        })
                    n++
                } catch (t: Throwable) {
                    Log.w(TAG, "hook instance/${m.parameterCount} failed: $t")
                }
            }
            Protocol.State.instanceHookCount = n
            if (n <= 0) Protocol.State.capMtop = false
            Log.i(TAG, "hooked $n Mtop.instance methods")
        } catch (t: Throwable) {
            Log.e(TAG, "hookMtopInstance failed: $t")
            Protocol.State.logLine("mtopInstance 失败: $t")
        }
    }

    fun hookMtopInit(module: XposedModule, cl: ClassLoader) {
        for (name in arrayOf("initMtopSdk", "firstMtopRequest")) {
            try {
                val cls = Class.forName(LAUNCHER, false, cl)
                val m = cls.getDeclaredMethod(name, Application::class.java)
                module.hook(m).setId("xysidecar-init-$name").intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val r = chain.proceed()
                        val a = chain.getArg(0)
                        val c = if (a is Context) a else null
                        Protocol.State.markReady(c)
                        Protocol.State.initFired = true
                        Log.i(TAG, "initMtopSdk done, capturing instance")
                        try {
                            val mtopCls = Class.forName(
                                "mtopsdk.mtop.intf.Mtop", false,
                                Protocol.State.classLoader,
                            )
                            val inst = Reflect.callStatic(
                                mtopCls, "instance",
                                c ?: Protocol.State.app,
                            )
                            if (inst != null) {
                                Protocol.State.mtop = inst
                                Log.i(TAG, "captured Mtop after init")
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "post-init capture failed: $t")
                        }
                        return r
                    }
                })
            } catch (t: Throwable) {
                Log.w(TAG, "init hook ($name) skipped: $t")
            }
        }
    }

    // ---------------------------------------------------------------- cursors

}
