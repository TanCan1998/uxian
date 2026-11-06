package com.xiandehuang.u

import android.content.Context

/** 业务参数状态与持久化限流暂停。参数缺失和账号状态分开判断。 */
object ApiInit {
    const val UNKNOWN = 0
    const val NO_APP = 1
    const val NO_MTOP = 2
    const val NO_ACCOUNT = 3
    const val READY = 4
    const val DEGRADED = 5
    const val PAUSED = 6
    const val PARAMS_PENDING = 7

    @Volatile private var stateCode = UNKNOWN
    @Volatile private var lastReason = ""
    private const val KEY_PAUSE_UNTIL = "pause_until"
    private const val DEFAULT_PAUSE_MS = 30L * 60L * 1000L

    /** 人机验证后的暂停时长：比限流更久，且必须用户手动处理 */
    private const val VALIDATE_PAUSE_MS = 60L * 60L * 1000L

    @JvmStatic fun state(): Int = stateCode
    @JvmStatic fun paused(c: Context?): Boolean =
        c != null && pauseUntil(c) > System.currentTimeMillis()
    @JvmStatic fun pauseUntil(c: Context?): Long =
        if (c == null) 0L else Prefs.getLong(c, KEY_PAUSE_UNTIL, 0L)

    @JvmStatic fun stateText(): String = when (stateCode) {
        NO_APP -> "无上下文"
        NO_MTOP -> "mtop 未就绪"
        NO_ACCOUNT -> "未登录"
        READY -> "就绪"
        DEGRADED -> "业务参数待检查"
        PARAMS_PENDING -> "业务参数待获取"
        PAUSED -> "暂停中"
        else -> "未探测"
    }

    @JvmStatic fun actionHint(c: Context?): String = when (stateCode) {
        NO_APP -> "模块还没注入到闲鱼，重启一次闲鱼即可"
        NO_MTOP -> "闲鱼还在启动，稍等一会"
        NO_ACCOUNT -> "请先登录闲鱼"
        PARAMS_PENDING -> "该业务参数暂不可用，后台会按场景检查公开配置；本轮跳过"
        DEGRADED -> "公开配置暂不可用，保留该业务上次正确作用域的参数"
        PAUSED -> {
            val left = (pauseUntil(c) - System.currentTimeMillis()) / 60000L + 1
            "服务端在限流，已暂停 $left 分钟（自动恢复，不用管）"
        }
        else -> ""
    }

    private fun set(s: Int, why: String) {
        if (stateCode != s) {
            stateCode = s
            Protocol.State.logLine("参数状态：" + stateText() + if (why.isEmpty()) "" else "（$why）")
        }
        lastReason = why
    }

    class AsacResult(
        @JvmField val value: String,
        @JvmField val refreshed: Boolean,
        /** 仅明确账号失效时才可为 true；缺少业务配置不是未登录。 */
        @JvmField val noAccount: Boolean,
        @JvmField val paused: Boolean,
        @JvmField val paramsMissing: Boolean = false,
    )

    /** 幂等建库并启动后台公开配置检查；主线程不会等待 HTTP。 */
    @JvmStatic fun bootstrap(c: Context?) {
        if (c == null) { set(NO_APP, "没有 Context"); return }
        ParamStore.ensureDefaults(c.applicationContext ?: c)
        if (paused(c)) { set(PAUSED, "暂停中"); return }
        BusinessParams.warmup(c)
        val trigger = ParamStore.fresh(c, BusinessParams.TASK_TRIGGER.key)
        val benefit = ParamStore.fresh(c, BusinessParams.TASK_BENEFIT.key)
        if (trigger.isNotEmpty() && benefit.isNotEmpty()) set(READY, "任务场景 496")
        else set(PARAMS_PENDING, "正在检查业务配置")
    }

    /** 只读取指定 api/scene；不再接收会返回任意活动或全局 asac 的 refresher。 */
    @JvmStatic fun ensureAsac(c: Context?, scope: BusinessParams.Scope): AsacResult {
        if (c == null) {
            set(NO_APP, "")
            return AsacResult("", false, false, false, true)
        }
        if (paused(c)) {
            set(PAUSED, "暂停中")
            return AsacResult("", false, false, true)
        }
        val before = ParamStore.get(c, scope.key)
        val value = BusinessParams.resolve(c, scope)
        if (value.isNotEmpty()) {
            val after = ParamStore.get(c, scope.key)
            val stale = after?.expired(System.currentTimeMillis()) ?: false
            set(if (stale) DEGRADED else READY, if (stale) "保留原业务作用域缓存" else "")
            return AsacResult(value, after != null && after.fetchedAt != (before?.fetchedAt ?: 0L), false, false)
        }
        set(PARAMS_PENDING, "该接口/场景无可用参数")
        return AsacResult("", false, false, false, true)
    }

    /** 错误分类，不重试。限流暂停整轮并持久化。 */
    @JvmStatic fun noteFailure(c: Context?, ret: String, msg: String): Boolean {
        if (c == null) return false
        val r = ret.uppercase()
        if (r.contains("ANTI_ATTACK") || r.contains("FREQUENT") || r.contains("1004") ||
            msg.contains("前方拥挤") || msg.contains("系统繁忙")) {
            pause(c, DEFAULT_PAUSE_MS, "服务端限流（$ret）")
            return true
        }
        // 人机验证（滑块）。**这是我们唯一必须停手并让用户自己上的错误** ——
        // 继续发请求只会把风控喂得更狠，而且验证页是宿主弹的，我们碰不到。
        // 暂停 1 小时：足够用户处理，也避免自动任务在验证期间反复撞墙。
        if (r.contains("USER_VALIDATE") || r.contains("RGV587") ||
            r.contains("CAPTCHA") || msg.contains("验证")) {
            pause(c, VALIDATE_PAUSE_MS, "需要人工验证（$ret）")
            Protocol.State.logLine("风控：请在闲鱼里完成滑块验证，期间不发任何请求")
            return true
        }
        if (r.contains("SESSION_EXPIRED") || r.contains("SESSION_INVALID") || r.contains("NEED_LOGIN")) {
            set(NO_ACCOUNT, "宿主会话失效")
            return false
        }
        if (r.contains("PARAM_ILLEGAL")) set(DEGRADED, "服务端拒绝业务参数，下轮检查")
        return false
    }

    @JvmStatic fun pause(c: Context?, durationMs: Long, why: String) {
        if (c == null) return
        Prefs.setLong(c, KEY_PAUSE_UNTIL, System.currentTimeMillis() + durationMs)
        set(PAUSED, why)
        Protocol.State.logLine("参数状态：暂停 " + durationMs / 60000L + " 分钟（" + why + "）")
    }

    @JvmStatic fun resume(c: Context?) {
        if (c == null) return
        Prefs.setLong(c, KEY_PAUSE_UNTIL, 0L)
        bootstrap(c)
    }

    @JvmStatic fun noteNoMtop() { set(NO_MTOP, "") }
}
