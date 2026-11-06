package com.xiandehuang.u

import android.content.Context
import android.content.pm.PackageManager

/**
 * 目标应用版本门禁。
 *
 * 只声明「适配版本」这一件事，**不阻断 hook**（fail-open）：
 * 版本不认识时照常装钩子，由面板横幅和 diag 提示用户。
 * 理由 —— 闲鱼升级后大部分挂载点其实还在（我们 hook 的多是
 * `com.taobao.fleamarket.*` 这类非混淆业务类），一上来就禁用反而更糟。
 *
 * 参考静途（QuietMap）的 VersionGate 设计：版本只是一条提示信息，
 * 真正的健康度判断交给 [HookState] 的 reg/hit 计数。
 */
object VersionGate {

    /** 本模块实测适配的闲鱼版本 */
    const val SUPPORTED_VERSION = "7.28.80"
    const val SUPPORTED_VERSION_CODE = 525

    @Volatile @JvmField var currentVersion: String = ""
    @Volatile @JvmField var currentCode: Int = 0
    @Volatile @JvmField var supported: Boolean = true
    @Volatile @JvmField var checked: Boolean = false

    /** 读当前宿主版本。拿到 context 才真正查，查到了才置 checked。 */
    @JvmStatic
    fun check(c: Context?) {
        if (checked || c == null) return
        try {
            val pi = c.packageManager.getPackageInfo(c.packageName, 0)
            currentVersion = pi.versionName ?: ""
            currentCode = pi.versionCode
            supported = currentVersion == SUPPORTED_VERSION
            checked = true
            if (!supported) {
                Protocol.State.logLine(
                    "闲鱼版本 " + currentVersion + " 未适配（实测版本 " + SUPPORTED_VERSION + "）",
                )
            }
        } catch (t: Throwable) {
            // 查不到版本不算失败，按「未知」处理，不阻断
        }
    }

    /**
     * 用当前宿主 context 补一次检查。
     *
     * 为什么需要：`installAllHooks` 跑在 `onPackageLoaded` 阶段，那时
     * `Protocol.State.app` 还是 null（Application 还没 attach），
     * check(null) 会直接返回，于是 diag 里 targetVersion 一直是空的。
     * 面板和 diag 都在 app 就绪之后才执行，所以在这些地方补一次。
     */
    @JvmStatic
    fun ensureChecked() {
        if (checked) return
        try {
            val at = Class.forName("android.app.ActivityThread")
            val app = at.getMethod("currentApplication").invoke(null)
            if (app is Context) check(app)
        } catch (ignored: Throwable) {
        }
    }

}
