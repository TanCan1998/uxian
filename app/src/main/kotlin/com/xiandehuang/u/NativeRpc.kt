package com.xiandehuang.u

import android.content.Context

/**
 * 走原生 RPC 服务的通道 —— **芝麻粒「不用打开页面」的真正原因**。
 *
 * ## 链路（mt 反编译 `DefaultRpcProxyImpl.sendSimpleRpc` 逐行确认）
 *
 * ```
 * RpcBridgeExtension.rpc(...)                     App/Page 参数没被读
 *   └─ RVRpcCallHelper.rpcCall(...)               构造 RVRpcConfig
 *        └─ RVRpcProxy.sendSimpleRpc(Node, isPb, op, cfg, data, headers)
 *             └─ DefaultRpcProxyImpl.sendSimpleRpc
 *                  ├─ LauncherApplicationAgent.getInstance()
 *                  │    .getMicroApplicationContext()
 *                  │    .findServiceByInterface("...RpcService")  ← 全局服务，不要页面
 *                  ├─ rpcService.getRpcProxy(SimpleRpcService::class)
 *                  ├─ rpcService.getRpcInvokeContext(proxy)
 *                  └─ proxy.executeRPC(operationType, dataString, headers)  ← 真正调用
 * ```
 *
 * **三层包装里一次都没用 App / Page**，`DefaultRpcProxyImpl` 拿的是
 * `LauncherApplicationAgent` 的全局服务 —— 这就是芝麻粒能「不等用户打开页面」
 * 的原因（它调 `H5RpcUtil.rpcCall` 时 H5Page 传 null，支付宝侧同样落到全局 RpcService）。
 *
 * 所以在闲鱼这边**最短路径是直接打 `SimpleRpcService.executeRPC`**，
 * 跳过 ariver 的 config/extension 两层（它们还要实例化 + 绑 ApiContext，
 * 反而容易 NPE 或被网关拒）。
 *
 * ## 参数类型（反编译确认，不是猜）
 *
 * `sendSimpleRpc` 对 requestData 的分支：
 * ```smali
 * if-eqz p2, :cond_6f        # isPb == false → String 分支
 *   check-cast p5, String    # 非 pb 时 requestData 必须是 String
 *   executeRPC(operationType, dataString, headers)
 * ```
 * 传 JSONObject 会 `ClassCastException: JSONObject cannot be cast to String`（实测踩到）。
 *
 * ## 与 [MtopBridge] 的分工
 *
 * `MtopBridge` 走 mtop SDK，请求头靠 hook 回放 —— 宿主没发过的请求就缺 header
 * （`asac` 正是这种情况）。这条走 `SimpleRpcService`，**会话由 RpcService 自己补**，
 * 理论上不需要从抓包库捞令牌。
 */
object NativeRpc {

    @Volatile
    private var lastError = ""

    /** 诊断：卡在哪一步（调试通道用） */
    @Volatile
    private var stage = ""

    @JvmStatic
    fun lastError(): String = lastError

    @JvmStatic
    fun stage(): String = stage

    /**
     * 调一个 mtop 接口。
     *
     * @return 响应 JSON 字符串；失败返回空串（调用方回退 [MtopBridge]）
     */
    @JvmStatic
    fun request(ctx: Context?, api: String, version: String, data: String): String {
        val cl = Protocol.State.classLoader ?: run {
            lastError = "classloader 未就绪"
            return ""
        }
        Protocol.State.enterOurs()
        try {
            // ① LauncherApplicationAgent → MicroApplicationContext
            stage = "agent"
            val agentCls = Class.forName(
                "com.alipay.mobile.framework.LauncherApplicationAgent", false, cl,
            )
            val agent = agentCls.getMethod("getInstance").invoke(null)
                ?: run {
                    lastError = "LauncherApplicationAgent 为空"
                    return ""
                }
            stage = "mac"
            val mac = agentCls.getMethod("getMicroApplicationContext").invoke(agent)
                ?: run {
                    lastError = "MicroApplicationContext 为空"
                    return ""
                }
            // ② 全局 RpcService（不需要页面/App）
            stage = "findService"
            val rpcSvcIface = Class.forName(
                "com.alipay.mobile.framework.service.common.RpcService", false, cl,
            )
            val findM = mac.javaClass.getMethod(
                "findServiceByInterface", String::class.java,
            )
            val rpcService = findM.invoke(mac, rpcSvcIface.name)
                ?: run {
                    lastError = "RpcService 未注册（宿主没启动完？）"
                    return ""
                }
            stage = "getRpcProxy"
            val simpleIface = Class.forName(
                "com.alipay.mobile.framework.service.ext.SimpleRpcService", false, cl,
            )
            val getProxy = rpcSvcIface.getMethod("getRpcProxy", Class::class.java)
            val simple = getProxy.invoke(rpcService, simpleIface)
                ?: run {
                    lastError = "SimpleRpcService 为空"
                    return ""
                }
            // ③ RpcInvokeContext：只设确认存在的方法。
            //
            // 关于版本号：实测（2026-10-31）**大多数接口不敏感** ——
            // `queryolivertasklist` 抓包里宿主用 2.0，我们传 1.0 也正常返回任务列表；
            // `forest.energy.issue` 用 1.0 和 2.0 报的是**同一个** BIZPARAM_MISSED
            // （那是缺 bubbleId，不是版本问题）。
            // 所以这里不强行塞版本，只在调用方明确给了非 1.0 时用 addProtocolArgs
            // 试一次（这是协议层扩展参数的正规入口，见 docs/native-rpc-chain.md §3.4）。
            stage = "invokeContext"
            try {
                val ctxM = rpcSvcIface.getMethod("getRpcInvokeContext", Class::class.java)
                val rpcCtx = ctxM.invoke(rpcService, simple)
                if (rpcCtx != null) {
                    try {
                        rpcCtx.javaClass.getMethod("setTimeout", java.lang.Long.TYPE)
                            .invoke(rpcCtx, 15000L)
                    } catch (ignored: Throwable) {
                    }
                }
                // 版本号走 addProtocolArgs（静态方法，写 ThreadLocal EXT_PARAM）
                if (version.isNotEmpty() && version != "1.0") {
                    try {
                        val invokerCls = Class.forName(
                            "com.alipay.mobile.common.rpc.RpcInvoker", false, cl,
                        )
                        invokerCls.getMethod(
                            "addProtocolArgs", String::class.java, String::class.java,
                        ).invoke(null, "v", version)
                    } catch (ignored: Throwable) {
                    }
                }
            } catch (ignored: Throwable) {
            }
            // ④ 真正调用
            //
            // headers 传 **null** —— 反查 DefaultRpcProxyImpl 确认：
            //   `const/4 p4, 0x0` 之后 executeRPC(op, data, p4)
            // 也就是容器自己传的就是 null。请求头不走这个参数，
            // 而是通过 RpcInvokeContext.setRequestHeaders / setRpcConfig 设进去的。
            // 传空 Map 反而可能覆盖掉容器已设的 header。
            stage = "executeRPC"
            val exec = findExecuteRPC(simpleIface)
                ?: run {
                    lastError = "找不到 executeRPC"
                    return ""
                }
            val ret = exec.invoke(simple, api, data, null)
            stage = "done"
            if (ret is String) return ret
            if (ret != null) return ret.toString()
            lastError = "executeRPC 返回 null"
            return ""
        } catch (t: Throwable) {
            val cause = if (t is java.lang.reflect.InvocationTargetException) t.cause ?: t else t
            lastError = "[" + stage + "] " + cause.javaClass.simpleName + ": " +
                (cause.message ?: "")
            return ""
        } finally {
            Protocol.State.exitOurs()
        }
    }

    /** 找 `executeRPC` 里 (String, String, Map) 那个重载；退回 (String, byte[], Map) */
    private fun findExecuteRPC(iface: Class<*>): java.lang.reflect.Method? {
        var fallback: java.lang.reflect.Method? = null
        for (m in iface.methods) {
            if (m.name != "executeRPC") continue
            val p = m.parameterTypes
            if (p.size != 3 || p[0] != String::class.java) continue
            if (p[1] == String::class.java) return m
            if (fallback == null) fallback = m
        }
        return fallback
    }
}
