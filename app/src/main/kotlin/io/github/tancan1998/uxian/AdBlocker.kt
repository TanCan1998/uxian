package io.github.tancan1998.uxian

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 广告拦截（开屏 + 摇一摇 + 素材下载）。
 *
 * 取代原来分散在 NewEntry.hookSplash / hookSplashAssets 和
 * ResponseFilter.filterAdItems / filterTopAds / filterSearchAds 里的一堆零散钩子。
 *
 * ## 为什么原来那套不生效（2026-10-30 实测）
 *
 * 原实现 hook 的是 `com.alimm.xadsdk.business.splashad.SplashAdController`
 * —— 那只是**阿里妈妈这一家**广告 SDK 的底层类。而闲鱼 7.28.80 实际走的是
 * 自己的链路：
 *
 * ```
 * com.taobao.fleamarket.advert.AdvertiseSplash.init()
 *   → com.taobao.fleamarket.splashad.SplashAdController
 *       ├─ requestAsyncSplashAd()  → mtop.taobao.idlecommerce.splash.async.ads 1.0
 *       ├─ loadSplashAd()          → 读本地预取缓存
 *       └─ requestAndShow()        → SplashAdShower.showSplashAd()
 * ```
 *
 * 而且广告数据**预取到了本地**（`files/mmkv/splash_ad_mmkv` 2MB，
 * 里面是 `mamaAd` 完整素材 + 追踪参数），所以清空接口响应没用 ——
 * SDK 从 MMKV 读出来照样展示。这就是"日志说已拦截、广告照样出现"的原因。
 *
 * ## 现在的策略：不请求
 *
 * 直接拦 `loadSplashAd` / `requestAsyncSplashAd` / `requestAndShow` 三个入口，
 * 广告既不发请求也不读缓存，自然没有展示。
 *
 * 明确**不碰**的东西（避免留下伪造痕迹）：
 *   - 不 hook `mtop.idle.ad.expose`（曝光上报）
 *   - 不 hook `TrackUtil` / `SplashAdLaunchFunnel`（广告埋点）
 *   - 不伪造 `show_duration`
 * 服务端看到的是「这次没拿到广告」，与真实环境的广告填充失败同形。
 *
 * ## 2026-10-31 新增：摇一摇禁用
 *
 * 挂 `AbsSensorTrigger.initTriggerAction()V`（protected final）。
 * **不要 hook `SensorManager.registerListener`** —— 那是全局的，会波及宿主里
 * 所有依赖传感器的功能（地图定位、摇一摇反馈、AR 等）。
 * 只在这个触发器自己的注册入口拦掉，影响面精确到开屏广告。
 *
 * 摇一摇和「转一转」（OrientationLimiter）都走 AbsSensorTrigger，
 * 用 `chain.thisObject` 的类名区分，只拦 ShakeNewTrigger。
 */
object AdBlocker {

    private const val TAG = "xysidecar"

    /** 闲鱼自有开屏调度 */
    private const val SPLASH_CTRL = "com.taobao.fleamarket.splashad.SplashAdController"
    /** 闲鱼自有开屏展示 */
    private const val SPLASH_SHOWER = "com.taobao.fleamarket.splashad.SplashAdShower"
    /** 传感器触发器基类（摇一摇 / 转一转都继承它） */
    private const val SENSOR_TRIGGER = "com.taobao.fleamarket.splashad.trigger.AbsSensorTrigger"
    /**
     * 摇一摇触发器的实现类名关键字。
     *
     * 闲鱼 7.28.80 里 AbsSensorTrigger 有两个分支（mt 实测）：
     * ```
     * AbsSensorTrigger
     *   ├─ ShakeNewTrigger        ← 摇一摇（基础）
     *   │    └─ ShakeMultiNewTrigger   ← 摇一摇（多方向），类名不含 "ShakeNewTrigger"
     *   └─ AbsOrientationTrigger  ← 转一转（OriMultiRoll / OriSinglePitch…）
     * ```
     * 所以不能匹配 "ShakeNewTrigger" 全名 —— `ShakeMultiNewTrigger` 里并没有这个
     * 连续子串，会漏。用 "Shake" 才同时覆盖两个摇一摇实现，
     * 又不会误伤 AbsOrientationTrigger 那一支。
     */
    private const val SHAKE_KEYWORD = "Shake"

    /** 功能名（HookState 分组 / Prefs 键共用） */
    private const val F_SPLASH = "splash"
    private const val F_SHAKE = "shake"

    /** 已挂上的钩子数，供 diag 查看（保留旧字段，面板还在读） */
    @Volatile
    var hooked = 0
        private set

    fun install(module: XposedModule, cl: ClassLoader) {
        var n = 0
        if (hookSplashController(module, cl)) n++
        if (hookSplashShower(module, cl)) n++
        if (hookShakeTrigger(module, cl)) n++
        hooked = n

        // 面板的「部分功能未适配」提示依赖 cap* 标志。
        // 判据改成「有挂载点装上」而不是「至少一个」，避免半残也报正常。
        Protocol.State.capSplash = !HookState.feature(F_SPLASH).installed.isEmpty()
        Log.i(TAG, "AdBlocker: 已装 $n 组钩子")
        Protocol.State.logLine(
            if (n > 0) "广告拦截已启用（$n 组钩子）" else "广告拦截不可用",
        )
    }

    /** 开屏调度：不请求、不读缓存 */
    private fun hookSplashController(module: XposedModule, cl: ClassLoader): Boolean {
        val cls = HookKit.loadClass(SPLASH_CTRL, cl)
        if (cls == null) {
            HookState.failed(F_SPLASH, "SplashAdController", "类不存在")
            return false
        }
        var any = false
        // 这三个是拉广告的入口，全部拦掉
        val names = arrayOf("loadSplashAd", "loadSplashAdHotStart", "requestAsyncSplashAd", "requestAndShow")
        for (name in names) {
            val m = HookKit.findByName(cls, name)
            val ok = HookKit.install(
                module, F_SPLASH, name, m,
                HookKit.skipVoid(F_SPLASH, name) { enabled() },
            )
            if (ok) any = true
        }
        return any
    }

    /** 展示层兜底：即使调度漏了，也不让广告真正显示 */
    private fun hookSplashShower(module: XposedModule, cl: ClassLoader): Boolean {
        val cls = HookKit.loadClass(SPLASH_SHOWER, cl)
        if (cls == null) {
            HookState.failed(F_SPLASH, "SplashAdShower", "类不存在")
            return false
        }
        var any = false
        val names = arrayOf("showSplashAd", "showCover")
        for (name in names) {
            val list = HookKit.findAllByName(cls, name)
            for (m in list) {
                val ok = HookKit.install(
                    module, F_SPLASH, name + "/" + m.parameterCount, m,
                    HookKit.skipVoid(F_SPLASH, name) { enabled() },
                )
                if (ok) any = true
            }
        }
        return any
    }

    /**
     * 素材下载：**故意不拦**（2026-10-31 撤除）。
     *
     * 原来按方法名含 `download` 全拦，结果把「逛逛商城 / 浏览福利好物 /
     * 看视频领奖」这类 `fleamarket://showAds` 激励视频全废了 ——
     * `SplashAdAssetManager` 虽然叫 Splash，实际是**开屏与激励视频共用**的
     * 素材下载器，`downloadAsset` 被吞掉后 `SplashAdCacheManager` 的
     * `SplashAdWriteListener` 永远等不到回调，上层就一直卡在「打不开」。
     *
     * 而且拦它本来就多余：开屏已经在 `loadSplashAd` / `requestAsyncSplashAd` /
     * `requestAndShow` 三个入口拦死，素材根本不会被请求。
     *
     * ⚠️ 另一个坑：`downloadAsset(String)` 返回 **boolean**，
     * `HookKit.skipVoid` 返回 null，调用方拆箱直接 NPE。要拦也必须先判返回类型。
     */
    @Suppress("unused")
    private fun hookAssetDownloadRemoved() {
        // 保留说明，不留实现
    }

    /**
     * 摇一摇：在传感器触发器的注册入口拦掉。
     *
     * `AbsSensorTrigger.initTriggerAction()V` 是 protected final，
     * 内部 `getSystemService(SENSOR_SERVICE)` + `registerListener`。
     * 不 proceed = 传感器根本不注册 = 摇不动。
     *
     * 用 `thisObject` 的类名区分摇一摇和转一转：只拦 ShakeNewTrigger。
     */
    private fun hookShakeTrigger(module: XposedModule, cl: ClassLoader): Boolean {
        val cls = HookKit.loadClass(SENSOR_TRIGGER, cl)
        if (cls == null) {
            HookState.failed(F_SHAKE, "AbsSensorTrigger", "类不存在")
            return false
        }
        val m = HookKit.findByName(cls, "initTriggerAction")
        val hooker = object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                try {
                    HookState.entry(F_SHAKE)
                    if (!shakeEnabled()) return chain.proceed()
                    val self = chain.thisObject
                    val cn = if (self == null) "" else self.javaClass.name
                    // 只拦摇一摇；转一转（AbsOrientationTrigger 那一支）保留 ——
                    // 转一转没有跳转副作用，用户不反感。
                    if (cn.indexOf(SHAKE_KEYWORD) < 0) return chain.proceed()
                    val n = HookState.hit(F_SHAKE)
                    if (n <= 3 || n % 100 == 0L) {
                        Log.i(TAG, "摇一摇已禁用（第 $n 次）：$cn")
                    }
                    return null
                } catch (t: Throwable) {
                    Log.w(TAG, "摇一摇钩子异常，放行: $t")
                    return chain.proceed()
                }
            }
        }
        return HookKit.install(module, F_SHAKE, "initTriggerAction", m, hooker)
    }

    private fun enabled(): Boolean = try {
        val app = Protocol.State.app ?: return true
        Prefs.get(app, Prefs.SKIP_SPLASH, true)
    } catch (t: Throwable) {
        true
    }

    private fun shakeEnabled(): Boolean = try {
        val app = Protocol.State.app ?: return true
        Prefs.get(app, Prefs.SKIP_SHAKE, true)
    } catch (t: Throwable) {
        true
    }
}
