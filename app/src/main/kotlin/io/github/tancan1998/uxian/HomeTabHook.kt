package io.github.tancan1998.uxian

import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/** 首页顶部 tab 排序：在协议数据层改 headList，页面拿到的就是新顺序。 */
object HomeTabHook {
    fun install(module: XposedModule, cl: ClassLoader) {
        try {
            val cls = Class.forName("com.taobao.idlefish.protocol.net.ResponseParameter", false, cl)
            val method = cls.getMethod("getData")
            HookKit.install(module, "hometab", "getData", method, object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val value = chain.proceed() ?: return null
                    val api = try {
                        (Reflect.call(chain.getThisObject()!!, "getApi") as? String) ?: return value
                    } catch (_: Throwable) { return value }
                    if (api != HomeTabOrder.API) return value
                    HookState.entry("hometab")
                    val ctx: Context? = Protocol.State.app
                    try {
                        if (ctx != null) HomeTabOrder.apply(ctx, value)
                        HookState.hit("hometab")
                    } catch (t: Throwable) {
                        Protocol.State.logLine("首页 tab 排序跳过：" + (t.message ?: t.javaClass.simpleName))
                    }
                    return value
                }
            })
        } catch (t: Throwable) {
            HookState.failed("hometab", "getData", t.message ?: "协议数据不可用")
        }
    }
}
