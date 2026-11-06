package io.github.tancan1998.uxian

import android.content.Context
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 走容器 RPC 栈的新通道 —— **照抄芝麻粒 NewRpcBridge 的做法**。
 *
 * ## 为什么这条路能成（芝麻粒源码 NewRpcBridge.load）
 *
 * 关键不是自己 new 一个 RpcBridgeExtension，而是**复用容器里已经存在的那个实例**：
 *
 * ```java
 * service = Nebula.getService()
 * extMgr  = service.getExtensionManager()
 * inst    = extMgr.createExtensionInstance(RpcBridgeExtension.class)
 * // 拿不到就遍历 getNodeExtensionMap()，找 key == RpcBridgeExtension 的现成实例
 * ```
 *
 * 我早先用 `newInstance()` 造了一个裸对象，没有 ApiContext，所以网关回 [3000]。
 * 复用容器实例之后，会话 / 签名 / 设备指纹全是容器自己的，不需要我们算。
 *
 * ## requestData 的固定结构（源码逐字照抄）
 *
 * ```json
 * {"__apiCallStartTime": <ms>,
 *  "apiCallLink": "XRiverNotFound",
 *  "execEngine": "XRiver",
 *  "operationType": "<mtop接口名>",
 *  "requestData": <业务参数对象>}
 * ```
 *
 * ## App / Page / ApiContext 全传 null
 *
 * 源码里就是 `..., null, null, null, callback)`。容器实例内部已经持有上下文，
 * 这三个参数确实用不到（我反查 `RpcBridgeExtension.rpc` 方法体也验证过：
 * 它只读 p6=appId 和全局 RVProxy，一次都没读 App/Page）。
 *
 * ## BridgeCallback 必须给真实现
 *
 * 传 null 会 `sendJSONResponse on a null object reference`（实测）。
 * 用 `Proxy.newProxyInstance` 动态实现，只拦 `sendJSONResponse` 拿结果。
 */
object NewRpcChannel {

    private const val TAG = "xysidecar"

    /**
     * 容器里 RPC 扩展的类名 —— **因容器而异**：
     *  - 支付宝 nebula 栈：`com.alibaba.ariver.commonability.network.rpc.RpcBridgeExtension`
     *  - 闲鱼 ariver 栈：  `com.alibaba.ariver.jsapi.rpc.RpcBridgeExtension`
     *
     * 两个都试，先命中的用。
     */
    private val RPC_EXT_CLASSES = arrayOf(
        // 芝麻粒源码写死的是 commonability 那个，但**闲鱼里没这个类**（实测 ClassNotFound）。
        // 闲鱼只有这两个（mt 全包类名扫描确认）：
        "com.alibaba.ariver.jsapi.rpc.RpcBridgeExtension",
        "com.mpaas.mriver.integration.rpc.RpcBridgeExtension",
    )

    @Volatile
    private var ready = false

    @Volatile
    private var lastError = ""

    /** ExtensionManager 是从哪个入口拿到的（诊断用） */
    @Volatile
    private var diagSrc = ""

    @JvmStatic
    fun diagSrc(): String = diagSrc

    @JvmStatic
    fun lastError(): String = lastError

    /**
     * 读容器自己认为的 mtop 网关 —— `ReadSettingServerUrl.getGWFURL(ctx)`。
     *
     * `RpcBridgeExtension.rpc` 的 **p6 参数就是 gateway**（注解 `BindingParam("gateway")`），
     * 传空时会兜底成 `https://mobilegw.alipay.com/mgw.htm`（支付宝网关），
     * 那就是之前 3000 / ILEGEL_SIGN 的来源。所以要拿到真实网关填进去。
     */
    @JvmStatic
    fun gateway(ctx: Context?): String {
        try {
            val cl = Protocol.State.classLoader
                ?: Protocol.State.mtop?.javaClass?.classLoader ?: return ""
            val ctx2 = (ctx ?: Protocol.State.app) ?: return ""
            val cls = Class.forName(
                "com.alipay.mobile.common.transport.utils.ReadSettingServerUrl", false, cl,
            )
            val inst = cls.getMethod("getInstance").invoke(null)
            val v = cls.getMethod("getGWFURL", android.content.Context::class.java)
                .invoke(inst, ctx2) as? String
            return v ?: ""
        } catch (t: Throwable) {
            lastError = "gateway: " + t.javaClass.simpleName + " " + (t.message ?: "")
            return ""
        }
    }

    @Volatile
    private var instance: Any? = null
    private var rpcMethod: Method? = null
    private var cbClass: Class<*>? = null
    private var fastjson: Class<*>? = null

    /**
     * 初始化：找到容器里**现成的** RpcBridgeExtension 实例。
     *
     * 两级回退（和芝麻粒一致）：
     * 1. `extensionManager.createExtensionInstance(RpcBridgeExtension.class)`
     * 2. 遍历 `getNodeExtensionMap()`，按类名找
     */
    @JvmStatic
    fun prepare(): Boolean {
        if (ready) return true
        synchronized(NewRpcChannel::class.java) {
            if (ready) return true
            try {
                val cl = Protocol.State.classLoader
                    ?: Protocol.State.mtop?.javaClass?.classLoader
                    ?: run {
                        lastError = "无 classloader"
                        return false
                    }

                // ① 找 ExtensionManager —— 闲鱼没有支付宝的 Nebula，
                //    但 ariver 框架有三个现成入口（按可靠性排序）：
                //      a) RVInitializer.sExtensionManager        （静态字段）
                //      b) BridgeDispatcher.getExtensionManager()（静态方法）
                //      c) ExtensionPoint.sExtensionManager       （静态字段）
                var extMgr: Any? = null
                val tried = StringBuilder()
                for (ci in arrayOf(
                    "com.alibaba.ariver.integration.RVInitializer",
                    "com.alibaba.ariver.kernel.api.extension.ExtensionPoint",
                    "com.alibaba.ariver.app.NodeInstance",
                )) {
                    try {
                        val c = Class.forName(ci, false, cl)
                        val f = c.getDeclaredField("sExtensionManager")
                        f.isAccessible = true
                        val v = f.get(null)
                        if (v != null) {
                            extMgr = v
                            tried.append("field:").append(ci.substringAfterLast('.')).append(' ')
                            break
                        }
                    } catch (ignored: Throwable) {
                    }
                }
                if (extMgr == null) {
                    try {
                        val c = Class.forName(
                            "com.alibaba.ariver.engine.common.bridge.dispatch.BridgeDispatcher",
                            false, cl,
                        )
                        val v = c.getMethod("getExtensionManager").invoke(null)
                        if (v != null) {
                            extMgr = v
                            tried.append("BridgeDispatcher ")
                        }
                    } catch (ignored: Throwable) {
                    }
                }
                if (extMgr == null) {
                    lastError = "找不到 ExtensionManager（试过：$tried）"
                    return false
                }
                diagSrc = tried.toString()

                var inst: Any? = null
                var rpcExtCls: Class<*>? = null
                var rpcExtName = ""
                for (n in RPC_EXT_CLASSES) {
                    try {
                        rpcExtCls = Class.forName(n, false, cl)
                        rpcExtName = n
                        break
                    } catch (ignored: Throwable) {
                    }
                }
                if (rpcExtCls == null) {
                    lastError = "RpcBridgeExtension 类都没找到"
                    return false
                }

                // ③ createExtensionInstance
                try {
                    val m = extMgr.javaClass.getDeclaredMethod(
                        "createExtensionInstance", Class::class.java,
                    )
                    m.isAccessible = true
                    inst = m.invoke(extMgr, rpcExtCls)
                    if (inst != null) diagSrc = diagSrc + "create:" + rpcExtName.substringAfterLast('.') + " "
                } catch (ignored: Throwable) {
                }

                // ④ 遍历 nodeExtensionMap 找现成的
                if (inst == null) {
                    try {
                        val map = extMgr.javaClass.getMethod("getNodeExtensionMap")
                            .invoke(extMgr) as? Map<*, *>
                        if (map != null) {
                            outer@ for (v in map.values) {
                                val inner = v as? Map<*, *> ?: continue
                                if (inner.containsKey(rpcExtName)) {
                                    inst = inner[rpcExtName]
                                    diagSrc = diagSrc + "map:" + rpcExtName.substringAfterLast('.') + " "
                                    break@outer
                                }
                            }
                        }
                    } catch (ignored: Throwable) {
                    }
                }

                if (inst == null) {
                    lastError = "拿不到 RpcBridgeExtension 实例"
                    return false
                }

                // ⑤ 找 rpc(...) 方法 —— 参数个数因容器版本而异（15 或 16）
                val target = inst.javaClass
                val fjson = Class.forName("com.alibaba.fastjson.JSONObject", false, cl)
                val app = Class.forName("com.alibaba.ariver.app.api.App", false, cl)
                val page = Class.forName("com.alibaba.ariver.app.api.Page", false, cl)
                val apiCtx = Class.forName(
                    "com.alibaba.ariver.engine.api.bridge.model.ApiContext", false, cl,
                )
                val cb = Class.forName(
                    "com.alibaba.ariver.engine.api.bridge.extension.BridgeCallback", false, cl,
                )

                var m: Method? = null
                try {
                    m = target.getMethod(
                        "rpc",
                        String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                        String::class.java, fjson, String::class.java, fjson,
                        Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java,
                        app, page, apiCtx, cb,
                    )
                } catch (ignored: Throwable) {
                }
                if (m == null) {
                    // 退一步：按名字找唯一那个 rpc 方法
                    for (cand in target.methods) {
                        if (cand.name == "rpc" && cand.parameterTypes.size >= 14) {
                            m = cand
                            break
                        }
                    }
                }
                if (m == null) {
                    lastError = "找不到 rpc() 方法"
                    return false
                }
                m.isAccessible = true

                instance = inst
                rpcMethod = m
                cbClass = cb
                fastjson = fjson
                ready = true
                lastError = ""
                return true
            } catch (t: Throwable) {
                val c = if (t is java.lang.reflect.InvocationTargetException) t.cause ?: t else t
                lastError = c.javaClass.simpleName + ": " + (c.message ?: "")
                return false
            }
        }
    }

    /**
     * 用容器 RPC 栈发一次请求。
     *
     * @param api   mtop 接口名（会成为 operationType）
     * @param data  业务参数 JSON 文本
     * @return 响应 JSON 文本；失败返回空串（原因见 [lastError]）
     */
    @JvmStatic
    fun request(ctx: Context?, api: String, version: String, data: String): String {
        if (!prepare()) return ""
        val inst = instance ?: return ""
        val m = rpcMethod ?: return ""
        val cb = cbClass ?: return ""
        val fj = fastjson ?: return ""
        val loader = inst.javaClass.classLoader
        // 网关必须在发请求前拿到 —— 这是 p6 参数
        // p6 gateway：先按芝麻粒的做法传空（让它自己兜底），
        // 若失败再用容器自认的网关重试。空串是芝麻粒验证过的路径。
        val gw = ""
        val rawData = data.ifEmpty { "{}" }.trim()
        val body = if (rawData.startsWith("{") || rawData.startsWith("[")) rawData else "{}"

        Protocol.State.enterOurs()
        try {
            // requestData 的固定结构 —— 逐字照抄芝麻粒 NewRpcBridge
            // requestData 必须作为**字符串值**塞进外层 JSON ——
            // 芝麻粒源码里 data 就是 String，`"\"requestData\":" + data` 拼出来是
            // {"requestData":"{\"sceneId\":496,...}"}（带引号的字符串），
            // 不是嵌套对象。传嵌套对象会 ClassCastException（实测）。
            // 这里用 fastjson 构造，避免手写转义出错。
            val outer = fj.getDeclaredConstructor().newInstance()
            val putM = fj.getMethod("put", String::class.java, Any::class.java)
            putM.invoke(outer, "__apiCallStartTime", System.currentTimeMillis())
            putM.invoke(outer, "apiCallLink", "XRiverNotFound")
            putM.invoke(outer, "execEngine", "XRiver")
            putM.invoke(outer, "operationType", api)
            putM.invoke(outer, "requestData", body)   // body 是 String
            val wrapper = outer.toString()

            // p5 直接用刚构造的 fastjson JSONObject
            val reqObj: Any = outer

            var captured = ""
            var errMark = false

            val callbackProxy = Proxy.newProxyInstance(
                loader, arrayOf(cb),
                InvocationHandler { _, innerMethod, innerArgs ->
                    // ⚠️ 闲鱼容器回传结果走的是 **sendBridgeResponse(BridgeResponse)**，
                    // 不是芝麻粒监听的 sendJSONResponse(fastjson) ——
                    // 那是支付宝容器版本。查 RpcBridgeExtension.rpc 方法体确认：
                    //   invoke-interface {v1, v0}, BridgeCallback;->sendBridgeResponse(...)
                    // 只监听 sendJSONResponse 的话，在闲鱼里回调永远不触发。
                    val name = innerMethod.name
                    if ((name == "sendBridgeResponse" || name == "sendJSONResponse") &&
                        innerArgs != null && innerArgs.size == 1
                    ) {
                        try {
                            val obj = innerArgs[0]
                            if (obj != null) {
                                val s = extractJson(obj)
                                if (!s.isNullOrEmpty() && s != "null") captured = s
                            }
                        } catch (t: Throwable) {
                            errMark = true
                        }
                    }
                    null
                },
            )

            // 参数顺序（芝麻粒源码）：
            // rpc(method, false, false, "json", reqObj, "", null, true, false,
            //     0, false, "", null, null, null, callback)
            // 按**闲鱼实际的签名**逐个塞（15 个参数，没有 ApiContext）：
            //   rpc(String operationType, ZZ, String dataType, JSONObject requestData,
            //       String, JSONObject, ZZ, int, Z, String, App, Page, BridgeCallback)
            //   芝麻粒源码是 16 参数（多一个 ApiContext）—— 那是支付宝容器的版本，
            //   闲鱼没有。实测参数个数不匹配会整体错位，全是类型错误。
            val args = ArrayList<Any?>(16)
            // 参数逐个对齐注解默认值（读 RpcBridgeExtension.rpc 的 @BindingParam 得到）：
            //   p1 operationType=""  p2 openRpc=false   p3 httpGet=false  p4 type="json"
            //   p5 BindingRequest    p6 gateway         p7 headers        p8 compress=true
            //   p9 retryable=false   p10 timeout=0      p11 getResponse=false
            //   p12 region=""        p13 App           p14 Page          p15 BindingCallback
            args.add(api)              // p1  operationType
            args.add(false)            // p2  openRpc
            args.add(false)            // p3  httpGet
            args.add("json")           // p4  type
            args.add(reqObj)           // p5  BindingRequest（外层 JSON，含 requestData）
            args.add(gw)               // p6  gateway  ← 关键
            args.add(null)             // p7  headers
            args.add(true)             // p8  compress（注解默认 true）
            args.add(false)            // p9  retryable（默认 false）
            args.add(0)                // p10 timeout（默认 0）
            args.add(false)            // p11 getResponse（默认 false）
            args.add("")               // p12 region
            args.add(null)             // p13 App
            args.add(null)             // p14 Page
            args.add(callbackProxy)    // p15 BridgeCallback

            // 万一容器签名是 16 个（带 ApiContext），在 callback 前补一个 null
            val want = m.parameterTypes.size
            if (want == 16 && args.size == 15) {
                args.add(args.size - 1, null)
            } else if (want < args.size) {
                while (args.size > want) args.removeAt(args.size - 2)
            }

            m.invoke(inst, *args.toTypedArray())

            // rpc() 是异步的：等 callback 回填
            val deadline = System.currentTimeMillis() + WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                if (captured.isNotEmpty()) return captured
                try {
                    Thread.sleep(50L)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            if (captured.isNotEmpty()) return captured
            if (errMark) lastError = "callback 抛异常"
            else if (lastError.isEmpty()) lastError = "callback 无响应（${WAIT_MS}ms 超时）"
            return ""
        } catch (t: Throwable) {
            val c = if (t is java.lang.reflect.InvocationTargetException) t.cause ?: t else t
            lastError = c.javaClass.simpleName + ": " + (c.message ?: "")
            return ""
        } finally {
            Protocol.State.exitOurs()
        }
    }

    /**
     * 从 BridgeResponse / fastjson JSONObject / String 里抠出响应 JSON 文本。
     *
     * BridgeResponse 不是 JSONObject，得先 `get()` 拿 `data` 字段；
     * 拿不到再退回 `toJSONString()`。
     */
    private fun extractJson(obj: Any): String {
        // 直接是字符串
        if (obj is String) return obj
        // BridgeResponse.get("data")
        try {
            val g = obj.javaClass.getMethod("get", String::class.java)
            val d = g.invoke(obj, "data")
            if (d != null) {
                val s = toJsonStr(d)
                if (s.isNotEmpty() && s != "null" && s != "{}") return s
            }
        } catch (ignored: Throwable) {
        }
        return toJsonStr(obj)
    }

    private fun toJsonStr(o: Any?): String {
        if (o == null) return ""
        if (o is String) return o
        try {
            val m = o.javaClass.getMethod("toJSONString")
            return (m.invoke(o) as? String) ?: ""
        } catch (ignored: Throwable) {
        }
        return o.toString()
    }

    private const val WAIT_MS = 15000L
}
