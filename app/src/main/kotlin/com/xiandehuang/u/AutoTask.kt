package com.xiandehuang.u

import android.content.Context
import android.util.Log
import java.util.Calendar

/**
 * 自动任务：主进程里的定时推进器（RPC 方案）。
 *
 * ## 为什么是「主进程 + 线程 sleep」，不是 AlarmManager / Service
 *
 * docs/research/research-background.md 里四种后台方案全被 ColorOS 挡过：
 * `AccsIPCProvider` 会把主进程拉起来、AlarmManager 被推迟 +3 天、
 * 系统冻结后 RPC 超时、只剩 `:channel` 时连网都走不通。
 *
 * 唯一可行的是**主进程常驻**（宿主本来就在跑），所以这里只做两件事：
 * 到点了、条件允许，就走 [Chains] 的同一套 RPC 调一遍。不弹界面、不抢前台、
 * 不改系统开关。宿主被杀，这个线程跟着消失 —— 这也是它可以随时被忽略的原因：
 * 下次宿主起来 [AutoRunner] 会补一轮。
 *
 * ## 为什么要分「全链」和「任务推进」
 *
 * | 轮次 | 内容 | 频率 |
 * |---|---|---|
 * | 全链 | [ChainPlan] 全部入口 | 跨天第一轮 |
 * | 任务推进 | 当前任务、现金、会员、游戏及衍生奖励 | 按用户配置间隔 |
 *
 * 签到、圈子签到及森林保留全链检查；任务会补发，获得的寻宝资源、
 * 圈数奖励、道具和活跃经验也应在本轮处理，不能拖到第二天。
 *
 * ## 三条纪律
 *
 * 1. **限流暂停期间不发任何请求**（[ApiInit.paused]）。暂停只放内存等于没暂停，
 *    这里连「下次执行时间」一起顺延 —— 用户重启宿主不会绕过它。
 * 2. **同一时刻只有一轮在跑**（[LOCK]）。`AutoRunner` 的首轮和定时轮都会真写业务，
 *    撞上就是重复请求。
 * 3. **失败不改开关、不拉黑任务**：单条任务的拒绝由 [Chains] 内部按任务逐条处理，
 *    这里是调度层，只记录结果。
 */
object AutoTask {

    private const val TAG = "xysidecar"

    /** 间隔（分钟）。0 = 关闭，默认 30。 */
    @JvmField
    val KEY_MIN = "auto_min"

    /** 上一轮结束的毫秒时间戳（也是「下次」的基准） */
    @JvmField
    val KEY_LAST = "auto_last"

    /** 上一轮的结论，面板直接显示 */
    @JvmField
    val KEY_TEXT = "auto_last_text"

    /** 上一次跑全链的日期索引（yyyyMMdd），跨天判断用 */
    @JvmField
    val KEY_DAY = "auto_day"

    private const val KEY_ANCHOR = "auto_schedule_anchor"
    private const val KEY_FULL_LAST = "auto_full_last"

    const val DEFAULT_MIN = 0

    /** 面板可选的间隔。0 = 关闭 */
    @JvmField
    val OPTIONS = intArrayOf(0, 15, 30, 60, 120)

    /**
     * 首轮与定时轮的互斥锁。
     *
     * 用一把锁而不是「上次执行时间」判定：时间是事件发生**之后**才写的，
     * 两轮同时起步时它拦不住任何一个。
     */
    @JvmField
    val LOCK = Any()

    @Volatile
    private var started = false

    @Volatile
    private var busy = false

    @Volatile
    private var busySince = 0L

    /** 锁最长持有时间：超过说明上一轮异常卡住，允许接管，避免永久锁死。 */
    private const val LOCK_TIMEOUT_MS = 15L * 60L * 1000L

    // ---------------------------------------------------------------- 配置

    @JvmStatic
    fun minutes(c: Context): Int {
        // 「自动执行」总开关关着时，定时轮也一起停 —— 面板上只有一个开关，
        // 关掉它却还在后台每 30 分钟发请求，是最容易让用户觉得「它没停」的行为。
        if (!Prefs.get(c, Prefs.AUTO_START, true)) return 0
        val v = Prefs.getInt(c, KEY_MIN, DEFAULT_MIN)
        return if (v < 0) 0 else v
    }

    @JvmStatic
    fun setMinutes(c: Context, v: Int) {
        Prefs.setInt(c, KEY_MIN, v)
        Prefs.setLong(c, KEY_ANCHOR, System.currentTimeMillis())
        schedule(c)
        Protocol.State.logLine(
            "自动任务：" + if (v <= 0) "已关闭" else "间隔 $v 分钟",
        )
    }

    @JvmStatic
    fun lastAt(c: Context): Long = Prefs.getLong(c, KEY_LAST, 0L)

    fun lastFullAt(c: Context): Long = Prefs.getLong(c, KEY_FULL_LAST, 0L)

    @JvmStatic
    fun lastText(c: Context): String = Prefs.getString(c, KEY_TEXT, "") ?: ""

    /** 下次执行时刻；关闭时返回 0 */
    @JvmStatic
    fun nextAt(c: Context): Long {
        val m = minutes(c)
        if (m <= 0) return 0L
        val last = lastAt(c)
        var anchor = Prefs.getLong(c, KEY_ANCHOR, 0L)
        if (anchor <= 0L) {
            anchor = System.currentTimeMillis()
            Prefs.setLong(c, KEY_ANCHOR, anchor)
        }
        val base = if (last > anchor) last else anchor
        val next = base + m * 60_000L
        val pause = ApiInit.pauseUntil(c)
        return if (pause > next) pause else next
    }

    /** 面板副标题：`每 30 分钟 · 上次 23:40 全链 30成/0败` */
    @JvmStatic
    fun summary(c: Context?): String {
        if (c == null) return "无 Context"
        val m = minutes(c)
        if (m <= 0) return "已关闭"
        val sb = StringBuilder("每 ")
        sb.append(m).append(" 分钟")
        val t = lastText(c)
        if (t.isNotEmpty()) sb.append(" · 上次 ").append(t)
        return sb.toString()
    }

    /** 下一轮的人话时刻，`HH:mm`；关闭返回空 */
    @JvmStatic
    fun nextText(c: Context?): String {
        if (c == null) return ""
        val n = nextAt(c)
        if (n <= 0L) return ""
        val cal = Calendar.getInstance()
        cal.timeInMillis = n
        return two(cal.get(Calendar.HOUR_OF_DAY)) + ":" + two(cal.get(Calendar.MINUTE))
    }

    private fun two(v: Int): String = if (v < 10) "0$v" else "" + v

    // ---------------------------------------------------------------- 调度

    /**
     * 起调度线程。幂等 —— 热重载会重复调 `installAllHooks`。
     *
     * 只在主进程调（和 [AutoRunner] 同一个位置）：`:channel` 里 mtop 是自建实例，
     * 拿不到登录态，跑链必然全失败。
     */
    @JvmStatic
    fun schedule(app: Context?) {
        if (app == null || started) return
        started = true
        val c = app.applicationContext ?: app
        val t = Thread({ loop(c) }, "AutoTask")
        t.isDaemon = true
        t.start()
    }

    private fun loop(app: Context) {
        while (true) {
            var waitMs = 60_000L
            try {
                if (minutes(app) > 0) {
                    val left = nextAt(app) - System.currentTimeMillis()
                    if (left > 0) {
                        // 睡到点，但一次最多睡一分钟 —— 用户改间隔/关开关要能立刻生效，
                        // 而这些都是本地文件读取，代价可以忽略。
                        if (left < waitMs) waitMs = left
                    } else {
                        runRound(app, 0)
                        waitMs = 60_000L
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "AutoTask loop: $t")
            }
            if (waitMs < 5000L) waitMs = 5000L
            try {
                Thread.sleep(waitMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    // ---------------------------------------------------------------- 执行

    /** 抢占执行权：同一时刻只允许一轮。失败说明上一轮还在跑。 */
    @JvmStatic
    fun acquire(): Boolean {
        synchronized(LOCK) {
            val now = System.currentTimeMillis()
            if (busy) {
                // 会话已结束却还占着锁，说明上一轮收尾异常；直接接管。
                if (RunProgress.latest?.running != true) {
                    Protocol.State.logLine("自动任务：上一次收尾未完成，接管执行锁")
                } else if (now - busySince < LOCK_TIMEOUT_MS) return false
                Protocol.State.logLine("自动任务：上一轮超过 15 分钟未结束，释放执行锁")
            }
            busy = true
            busySince = now
            return true
        }
    }

    @JvmStatic
    fun release() {
        synchronized(LOCK) {
            busy = false
            busySince = 0L
        }
    }

    /**
     * [AutoRunner] 首轮跑完后的登记。
     *
     * 首轮由 [AutoRunner] 自己等 mtop、自己延迟，这里只负责把结果写进
     * 与定时轮同一份状态里 —— 否则面板会出现「上次执行：无」而实际刚跑过。
     */
    @JvmStatic
    fun noteFullRun(app: Context, text: String, completed: Boolean = true) {
        try {
            Prefs.setLong(app, KEY_LAST, System.currentTimeMillis())
            Prefs.setString(app, KEY_TEXT, text)
            if (completed && !Thread.currentThread().isInterrupted) {
                Prefs.setInt(app, KEY_DAY, dayIndex())
                Prefs.setLong(app, KEY_FULL_LAST, System.currentTimeMillis())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "noteFullRun: $t")
        }
    }

    /**
     * 一轮的结论。
     *
     * 按**链**计数而不是按步骤：一条链里「没有可领的任务」也算成功步骤，
     * 按步数统计会得到「30 成 0 败」这种看着很漂亮、其实什么都没做的结论。
     * 三档与 [Chains.Result] 的语义对应 —— 成（真做了事）/ 跳（没有可做的）/
     * 败（有失败步骤）。
     */
    @JvmStatic
    fun tally(label: String, res: List<Chains.Result>): String {
        var ok = 0
        var skip = 0
        var bad = 0
        var mixed = 0
        for (r in res) {
            if (!r.healthy()) bad++
            else if (r.skipped && r.state.isNotEmpty() &&
                (r.state.contains("领") || r.state.contains("完成") || r.state.contains("上报")) &&
                !r.state.contains("未成") && !r.state.contains("无可领")) mixed++
            else if (r.skipped) skip++
            else ok++
        }
        if (ok == 0 && bad == 0 && mixed == 0) {
            // 全都在跳过：如实说「没事可做」，别写成「0 成」让人以为坏了
            return label + " 无事可做（" + skip + " 条链）"
        }
        val sb = StringBuilder(label)
        sb.append(' ').append(ok).append("成")
        if (mixed > 0) sb.append('/').append(mixed).append("部分完成")
        if (skip > 0) sb.append('/').append(skip).append("跳")
        if (bad > 0) sb.append('/').append(bad).append("败")
        return sb.toString()
    }

    /**
     * 跑一轮。返回一句话结论（与写进 [KEY_TEXT] 的一致）。
     *
     * @param force `0` 按日期自动判断；`1` 只跑任务推进；`2` 强制全链
     */
    @JvmStatic
    fun runRound(app: Context?, force: Int = 0): String {
        val c = app ?: return "无 Context"
        if (!acquire()) return "上一轮还在跑"
        var session: RunProgress.Session? = null
        try {
            if (ApiInit.paused(c)) {
                val left = (ApiInit.pauseUntil(c) - System.currentTimeMillis()) / 60_000L + 1
                val s = "暂停中（还有 $left 分钟）"
                Prefs.setLong(c, KEY_LAST, System.currentTimeMillis())
                Prefs.setString(c, KEY_TEXT, s)
                Protocol.State.logLine("自动任务：$s，本轮不发请求")
                return s
            }
            session = RunProgress.begin("自动执行")
            if (session != null) RunProgress.attach(session)
            val day = dayIndex()
            val full = force == 2 || (force != 1 && Prefs.getInt(c, KEY_DAY, 0) != day)
            val label = if (full) "全链" else "任务推进"
            val res = if (full) Chains.all(c) else Chains.pending(c)
            val txt = tally(label, res)
            Prefs.setLong(c, KEY_LAST, System.currentTimeMillis())
            Prefs.setString(c, KEY_TEXT, txt)
            if (full && !ApiInit.paused(c) && !Thread.currentThread().isInterrupted) {
                Prefs.setInt(c, KEY_DAY, day)
                Prefs.setLong(c, KEY_FULL_LAST, System.currentTimeMillis())
            }
            Protocol.State.logLine("自动任务：$txt")
            return txt
        } catch (t: Throwable) {
            val s = "异常 " + t.javaClass.simpleName
            Prefs.setLong(c, KEY_LAST, System.currentTimeMillis())
            Prefs.setString(c, KEY_TEXT, s)
            Protocol.State.logLine("自动任务：$s")
            return s
        } finally {
            val finished = session
            try {
                if (finished != null) RunProgress.finish(finished)
            } catch (ignored: Throwable) {
            } finally {
                release()
            }
        }
    }

    /** 日期索引 yyyyMMdd（AutoRunner 判「今天跑过没有」也要用） */
    @JvmStatic
    fun today(): Int = dayIndex()

    /** 日期索引 yyyyMMdd，用来判断「今天是否已经跑过全链」 */
    private fun dayIndex(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.YEAR) * 10000 +
            (cal.get(Calendar.MONTH) + 1) * 100 +
            cal.get(Calendar.DAY_OF_MONTH)
    }
}
