package com.xiandehuang.u

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 主进程压制：在 :channel 进程里 hook 掉宿主「拉起主进程」的两条路径。
 *
 * 静态分析确认的链路（闲鱼 7.28.80）：
 * ```
 * TaoBaoApplication.bindService(Intent, ServiceConnection, int)
 *   ├─ AliveApplication.isMainProcessRunning()                       ← 检查
 *   ├─ ChannelAliveComponentLimiter.preventStartMainService(ctx, i)  ← 宿主自带限流器
 *   └─ AliveApplication.activeMainProcess()                          ← 真·拉起
 * ```
 * 另外还有一条不经过 bindService 的：
 * ```
 * AliveApplication.inst().activeMainProcess()
 * ```
 * 被别的组件（PushAliveAccountService / ChannelAlarmBroadcastReceiver）直接调用。
 *
 * 策略：
 *  1. `activeMainProcess()` 直接吞掉（返回 void 空实现）—— 这是最终动作，挡这里最彻底
 *  2. `preventStartMainService` 强制返回 true —— 借宿主自己的限流器语义
 *  3. `isMainProcessRunning` 保持真实返回值 —— **不能谎报为 true**，
 *     否则宿主会以为主进程活着，去 bind 不存在的服务，反而报错刷日志
 *
 * 只装在 :channel 里；主进程里装这个毫无意义（它自己就是主进程）。
 */
object ProcessSuppressor {

    private const val TAG = "xysidecar"

    private const val ALIVE_APP = "com.taobao.fleamarket.business.user_growth.alive.AliveApplication"
    private const val LIMITER =
        "com.taobao.fleamarket.business.user_growth.alive.channel.ChannelAliveComponentLimiter"

    /** 压制开关：默认关，用户显式开启才生效 */
    @Volatile
    var enabled: Boolean = false

    fun install(module: XposedModule, cl: ClassLoader, enable: Boolean) {
        enabled = enable
        if (!enable) {
            Log.i(TAG, "ProcessSuppressor: 未启用（开关关闭）")
            return
        }
        hookActiveMainProcess(module, cl)
        hookPreventStartMainService(module, cl)
    }

    /** 最关键的一刀：拉起动作本身 */
    private fun hookActiveMainProcess(module: XposedModule, cl: ClassLoader) {
        try {
            val cls = Class.forName(ALIVE_APP, false, cl)
            val m = cls.getDeclaredMethod("activeMainProcess")
            module.hook(m).setId("xysidecar-suppress-active")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        Log.i(TAG, "ProcessSuppressor: 拦截 activeMainProcess()，主进程不拉起")
                        return null // void 方法
                    }
                })
            Log.i(TAG, "ProcessSuppressor: hook activeMainProcess 成功")
        } catch (t: Throwable) {
            Log.w(TAG, "ProcessSuppressor: hook activeMainProcess 失败: $t")
        }
    }

    /** 借宿主自己的限流器：返回 true 表示「已阻止」 */
    private fun hookPreventStartMainService(module: XposedModule, cl: ClassLoader) {
        try {
            val cls = Class.forName(LIMITER, false, cl)
            val m = cls.getDeclaredMethod(
                "preventStartMainService",
                android.content.Context::class.java,
                android.content.Intent::class.java,
            )
            module.hook(m).setId("xysidecar-suppress-limiter")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val intent = chain.args.getOrNull(1) as? android.content.Intent
                        val target = intent?.component?.className ?: "?"
                        Log.i(TAG, "ProcessSuppressor: 阻止 bindService 拉起主进程 -> $target")
                        return true
                    }
                })
            Log.i(TAG, "ProcessSuppressor: hook preventStartMainService 成功")
        } catch (t: Throwable) {
            Log.w(TAG, "ProcessSuppressor: hook preventStartMainService 失败: $t")
        }
    }
}
