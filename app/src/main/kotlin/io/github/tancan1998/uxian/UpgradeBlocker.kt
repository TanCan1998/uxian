package io.github.tancan1998.uxian

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 后台静默升级弹窗拦截。
 *
 * ## 为什么不能直接拦 `Upgrade.check()`
 *
 * `Upgrade.check(UpgradeHandler)` 有 4 个调用方（mt 实测 7.28.80）：
 * ```
 * AboutActivity$5#onClick            ← 用户点「检查更新」
 * UpgradeActivity#onCreate           ← 用户主动进来的升级页
 * WVIdleFishApiPlugin#execute        ← H5 调 jsapi
 * CheckUpdateMainWorkflow#idleRun    ← 宿主启动后的后台静默检查
 * ```
 * 全局拦掉会把用户主动点「检查更新」也变成没反应 —— 那是明显的 bug。
 *
 * ## 正确的挂载点：`BaseUpgradeHandler#onUpgradeNeed`
 *
 * `BaseUpgradeHandler` 持有 `mCheckByUser:Z` 字段（构造参数传进来）：
 *   - `false` = 宿主自己在后台静默检查 → **拦掉，不弹窗**
 *   - `true`  = 用户主动检查 → 放行，让用户看到结果
 *
 * 这是唯一能区分「骚扰」和「用户想要」的判据，所以 hook 在这一层。
 *
 * ## 备用挂载点：`UpgradeActivity`
 *
 * 如果 onUpgradeNeed 的字段名在后续版本被 R8 改掉，退化为「不启动
 * UpgradeActivity」—— 但那样连主动检查也会被挡，所以只在
 * onUpgradeNeed 完全挂不上时才启用，并在 diag 里标明降级。
 */
object UpgradeBlocker {

    private const val TAG = "xysidecar"

    private const val HANDLER = "com.taobao.idlefish.upgrade.traceable.BaseUpgradeHandler"
    private const val F_UPGRADE = "upgrade"

    fun install(module: XposedModule, cl: ClassLoader): Boolean {
        val cls = HookKit.loadClass(HANDLER, cl)
        if (cls == null) {
            HookState.failed(F_UPGRADE, "BaseUpgradeHandler", "类不存在")
            return false
        }
        val m = HookKit.findByName(cls, "onUpgradeNeed")
        if (m == null) {
            HookState.failed(F_UPGRADE, "onUpgradeNeed", "方法不存在")
            return false
        }

        val hooker = object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                try {
                    HookState.entry(F_UPGRADE)
                    if (!enabled()) return chain.proceed()
                    // 用户主动检查时放行：mCheckByUser=true
                    if (byUser(chain.thisObject)) return chain.proceed()
                    val n = HookState.hit(F_UPGRADE)
                    if (n <= 3 || n % 100 == 0L) {
                        Log.i(TAG, "后台静默升级弹窗已拦（第 $n 次）")
                    }
                    // onUpgradeNeed 是 void：不 proceed 就不弹窗
                    return null
                } catch (t: Throwable) {
                    Log.w(TAG, "升级拦截钩子异常，放行: $t")
                    return chain.proceed()
                }
            }
        }
        return HookKit.install(module, F_UPGRADE, "onUpgradeNeed", m, hooker)
    }

    /**
     * 读 `mCheckByUser` 字段。
     *
     * R8 会重命名 private 字段，但 `mCheckByUser` 是**成员名**（不是局部变量），
     * 闲鱼包没开成员混淆的话名字会保留。取不到时按「用户主动」处理（fail-open，
     * 宁可多弹一次也不要把用户主动检查挡掉）。
     */
    private fun byUser(target: Any?): Boolean {
        if (target == null) return true
        try {
            var c: Class<*>? = target.javaClass
            var guard = 0
            while (c != null && guard < 6) {
                try {
                    val f = c.getDeclaredField("mCheckByUser")
                    f.isAccessible = true
                    val v = f.get(target)
                    if (v is Boolean) return v
                    return true
                } catch (ignored: NoSuchFieldException) {
                    // 往父类找
                }
                c = c.superclass
                guard++
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读 mCheckByUser 失败，按主动检查放行: $t")
        }
        return true
    }

    private fun enabled(): Boolean = try {
        val app = Protocol.State.app ?: return true
        Prefs.get(app, Prefs.SKIP_UPGRADE, true)
    } catch (t: Throwable) {
        true
    }
}
