package com.xiandehuang.u

import android.content.Context
import android.content.SharedPreferences

/** 模块自己的开关，存在宿主进程的 SharedPreferences 里 */
object Prefs {

    private const val NAME = "xysidecar_prefs"

    @JvmField val SKIP_SPLASH = "skip_splash"

    /**
     * 抓包（网络请求 + 响应入库）。
     * 取代原来的「记录请求日志」—— 那个开关只影响内存里的 20 条
     * 摘要文本，真正的数据现在全量进 SQLite，开关就是数据源本身。
     */
    @JvmField val TRAFFIC = "traffic_log"
    @JvmField val FILTER_SERVICE = "filter_service"
    @JvmField val STATIC_SPLASH = "static_splash"

    /**
     * 搜索结果去广告（2026-11-01 重新实现）。
     *
     * 旧结论「Flutter 渲染无法处理」只针对「改 MtopResponse 的 org.json」：Flutter
     * 并不读那一层。新挂点是 `MtopPlugin.sendMtop` 交给 `MethodChannel.Result` 的
     * 搜索结果，在它序列化送往页面之前按 resultList 剔除广告（见 SearchAdHook）。
     * 默认关，由用户主动开启，避免误删普通商品。
     */
    @JvmField val FILTER_SEARCH_AD = "filter_search_ad"


    /** 整个金刚位区块隐藏（不动数据，只把控件设为不可见） */
    @JvmField val KINGKONG_HIDE = "kingkong_hide"

    /**
     * 模块日志：打开后 logLine() 同时输出到 logcat（tag=xysidecar）。
     * 默认关 —— 常开会刷屏、也拖慢主线程。
     */
    @JvmField val MODULE_LOG = "module_log"

    /**
     * 主进程压制：在 :channel 进程里 hook 掉 `activeMainProcess()` /
     * `preventStartMainService()`，让闲鱼自己不会把主进程拉起来。
     *
     * 默认关 —— 开启后主进程被杀就不再恢复，需要用户主动接受这个行为
     * （推送仍走 :channel，但打开 App 会重新走一遍冷启动）。
     */
    @JvmField val PROCESS_SUPPRESS = "process_suppress"

    /**
     * 调试用 HTTP 接口（127.0.0.1:18787），供外部脚本/模块 App 调用。
     *
     * 默认关：面板自身和宿主功能都不经过它（面板直接调函数）。
     * 打开后主进程会监听端口，可用于 curl / adb forward 调试。
     * 注意实测本机 ColorOS 上该通道只能稳定支撑前几次请求，之后 accept
     * 不再返回，所以只作调试用途，不要当生产通道。
     */
    @JvmField val HTTP_DEBUG = "http_debug"

    /**
     * 强制深色（反色渲染）。
     *
     * 闲鱼包里没有任何夜间资源（res/ 下 0 个 -night 目录、arsc 无 dark/night 命中、
     * dex 里也搜不到 AppCompatDelegate / UiModeManager / ForceDark），
     * 所以没法走「换配色」那条路，只能对根视图套反色 + 180° 色相滤镜。
     * 副作用：图片会变负片；SurfaceView（Flutter 页）不受影响。
     */
    @JvmField val DARK_FORCE = "dark_force"
    @JvmField val DEBUG = "debug_mode"

    /**
     * 开屏摇一摇禁用。
     *
     * 开屏广告的「摇一摇跳转」比点击更容易误触（走路/拿手机就跳了）。
     * 挂 `AbsSensorTrigger.initTriggerAction()`，不 proceed 即传感器不注册。
     * 只作用于开屏广告的 ShakeNewTrigger，不动宿主其他传感器功能。
     */
    @JvmField val SKIP_SHAKE = "skip_shake"

    /**
     * 后台静默升级弹窗拦截。
     *
     * 只拦 `mCheckByUser=false`（宿主自己在后台静默检查更新）那条路径；
     * 用户在「设置 → 关于」里主动点「检查更新」时仍然放行，否则用户点完
     * 没反应会以为坏了。
     */
    @JvmField val SKIP_UPGRADE = "skip_upgrade"

    /**
     * UT 埋点上报断源（`com.ut.mini.UTTracker#send`）。
     *
     * **默认关**，因为抓包库本身是研究工具：埋点请求是「闲鱼在偷偷传什么」
     * 的一手证据。默认放行，需要干净库时再打开。
     */
    @JvmField val BLOCK_TRACK = "block_track"

    /**
     * 设备指纹上传断源（`com.ta.audid.upload.UtdidUploadTask`）。
     *
     * 默认关，理由同上：抓包库里 utdid 相关的上报是有价值的分析素材。
     * 注意**不要**改 utdid 的生成/读取逻辑（可能触发风控），只断上传。
     */
    @JvmField val BLOCK_UTDID = "block_utdid"

    /**
     * 启动时自动执行业务链。
     *
     * 每次宿主冷启动跑一遍全部业务链（签到 / 森林 / 现金 / 任务 / 挑战 /
     * 圈子 / 寻宝）。**重复调用靠幂等兜底**：签到已签返回
     * FAIL_BIZ_PLAY_SIGNED_IN、领奖已领返回 CANNOT_AWARD，链里都按「跳过」
     * 处理，不会报错也不会重复发奖励。
     *
     * 默认开 —— 这就是这个功能的全部意义：用户不用每天手动点一次。
     * 纯网络调用，不弹任何界面，不抢前台。
     */
    @JvmField val AUTO_START = "auto_start"

    /**
     * 自动执行的时机：
     *   `always` 每次启动都跑一遍（默认）
     *   `daily`  每天只跑第一次启动，当天再启动不重跑
     *
     * 用字符串而不是布尔：以后要加「只在充电时」这类模式不用再改存储结构。
     */
    @JvmField val AUTO_MODE = "auto_mode"

    const val AUTO_MODE_ALWAYS = "always"
    const val AUTO_MODE_DAILY = "daily"

    /** 每 4 小时：启动时检查「上次执行」距今是否超过 4 小时 */
    const val AUTO_MODE_H4 = "h4"


    /** 重启背景图的 Uri（模块写、中转页读） */
    @JvmField val RESTART_BG = "restart_bg_uri"

    /**
     * 已废弃：面板主题不再单独配置，改为跟随 DARK_FORCE（反色开关）。
     * 保留常量和读写分支只为兼容旧配置文件里的 panel_theme。
     */
    @JvmField val THEME = "panel_theme"

    /** 无头读写用的全部开关键（顺序即展示顺序） */
    @JvmField
    val ALL = arrayOf(
        SKIP_SPLASH, SKIP_SHAKE, FILTER_SERVICE, FILTER_SEARCH_AD, KINGKONG_HIDE,
        SKIP_UPGRADE, BLOCK_TRACK, BLOCK_UTDID, AUTO_START,
        DARK_FORCE, TRAFFIC, MODULE_LOG, DEBUG, PROCESS_SUPPRESS, HTTP_DEBUG,
    )

    /** 默认值，必须与面板 rowSwitch 的 def 参数保持一致 */
    /**
     * 开关默认值。
     *
     * 注意 TRAFFIC / MODULE_LOG 这里是「文件里没写过时的兜底」，
     * 实际生效值还要看调试态（见 effectiveTraffic / effectiveModuleLog）：
     *   普通模式 → 强制关（不抓包、不打日志）
     *   调试模式 → 默认开
     */
    @JvmStatic
    fun defOf(key: String?): Boolean = when (key) {
        SKIP_SPLASH -> true
        SKIP_SHAKE -> true
        FILTER_SERVICE -> true
        FILTER_SEARCH_AD -> false   // 由用户确认开启，避免误删普通商品
        KINGKONG_HIDE -> false
        SKIP_UPGRADE -> true        // 只拦静默检查，主动检查放行
        BLOCK_TRACK -> false        // 埋点是研究素材，默认放行
        BLOCK_UTDID -> false        // 同上
        AUTO_START -> true          // 装上就每天自动跑一遍，这是功能本身
        DARK_FORCE -> false
        TRAFFIC -> true
        MODULE_LOG -> true          // 调试模式下默认开
        DEBUG -> false
        PROCESS_SUPPRESS -> false   // 危险操作，默认关
        HTTP_DEBUG -> false         // 调试口，默认关
        else -> false
    }

    /**
     * 抓包实际是否生效：**常驻，恒为 true**。
     *
     * 2026-10-31 改：抓包不是排查工具，而是「自动」里业务链的数据来源
     * （asac 这类风控值只能从真实请求里捞），所以不再给开关、也不受调试模式限制。
     * 调试模式只多出日志查看、报表分析这类入口。
     *
     * 容量由 TrafficStore 自己管：10 分钟/256 条双触发裁剪 + 空闲页回收。
     */
    @JvmStatic
    fun effectiveTraffic(c: Context?): Boolean = true

    /** 模块日志实际是否生效：同样只在调试模式下开 */
    @JvmStatic
    fun effectiveModuleLog(c: Context?): Boolean {
        if (c == null) return false
        if (!Protocol.State.liveBool(c, DEBUG, false)) return false
        return Protocol.State.liveBool(c, MODULE_LOG, true)
    }

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 优先用 Application context，拿不到就退回原 context */
    private fun ctx(c: Context): Context = c.applicationContext ?: c

    @JvmStatic
    fun get(c: Context, key: String, def: Boolean): Boolean =
        try {
            sp(ctx(c)).getBoolean(key, def)
        } catch (t: Throwable) {
            def
        }

    @JvmStatic
    fun getInt(c: Context, key: String, def: Int): Int =
        try {
            sp(ctx(c)).getInt(key, def)
        } catch (t: Throwable) {
            def
        }

    @JvmStatic
    fun setInt(c: Context, key: String, v: Int) {
        try {
            sp(ctx(c)).edit().putInt(key, v).apply()
        } catch (ignored: Throwable) {
        }
    }

    @JvmStatic
    fun getLong(c: Context, key: String, def: Long): Long =
        try {
            sp(ctx(c)).getLong(key, def)
        } catch (t: Throwable) {
            def
        }

    @JvmStatic
    fun setLong(c: Context, key: String, v: Long) {
        try {
            sp(ctx(c)).edit().putLong(key, v).apply()
        } catch (ignored: Throwable) {
        }
    }

    @JvmStatic
    fun getString(c: Context, key: String, def: String?): String? =
        try {
            sp(ctx(c)).getString(key, def)
        } catch (t: Throwable) {
            def
        }

    @JvmStatic
    fun setString(c: Context, key: String, v: String?) {
        try {
            sp(ctx(c)).edit().putString(key, v).apply()
        } catch (ignored: Throwable) {
        }
    }

    @JvmStatic
    fun set(c: Context, key: String, v: Boolean) {
        try {
            sp(ctx(c)).edit().putBoolean(key, v).apply()
        } catch (ignored: Throwable) {
        }
    }
}
