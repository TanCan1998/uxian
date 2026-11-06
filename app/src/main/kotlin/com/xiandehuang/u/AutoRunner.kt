package com.xiandehuang.u

import android.content.Context
import android.util.Log

/**
 * 启动时自动执行业务链。
 *
 * ## 为什么放在主进程的 Handler 里，而不是 Service / AlarmManager
 *
 * docs/research/research-background.md 记录过四种后台方案，全部被 ColorOS 挡住
 * （`AccsIPCProvider` 会拉起主进程、AlarmManager 被推迟 +3 天、冻结后 RPC 超时）。
 * 芝麻粒（同类工具）的做法是「主进程 + `Handler.postDelayed`」，这里照抄：
 * 宿主冷启动 → 等 mtop 就绪 → 延迟几秒跑一遍 → 结束。
 *
 * 好处是**不弹界面、不抢前台、不常驻**：整条链都是网络调用，跑完线程就退出。
 *
 * ## 启动模式与互斥
 *
 * 按用户选择的每次启动、每天一次或四小时间隔判断，再交给 AutoTask 执行。
 * 所有入口共享互斥；各链查询当前状态后才提交业务请求：
 *
 * | 动作 | 重复调用时服务端返回 | 代码处理 |
 * |---|---|---|
 * | 签到 | `FAIL_BIZ_PLAY_SIGNED_IN` | 记「今天已签」 |
 * | 任务领奖 | `FAIL_BIZ_ACTIVITY_TASK_CANNOT_AWARD` | 记「已经领过了」 |
 * | 多日挑战 | 同上 | 同上 |
 * | 圈子签到 | `ALREADY_SIGNED` | 记「今日已签」 |
 * | 森林喂养 | 满 3 次后 `FAIL_BIZ_SERVER_ERROR` | 记「今日已喂满」 |
 * | 寻宝掷骰 | `diceNum=0` 时链内直接跳过 | 不掷 |
 *
 * 完整全链时间与定时推进时间分别落盘，避免轻量轮影响启动间隔；
 * 遇风控立即停止，本轮中断时不写入当天已完成全链。
 *
 * ## 时机
 *
 * 冷启动后 `MtopLauncher.initMtopSdk` 才把 mtop 初始化好，那时
 * [Protocol.State.markReady] 会放行。这里再额外等 [START_DELAY_MS]：
 * 冷启动前几秒宿主自己在抢带宽和 CPU（首页 178 个请求），
 * 这时候插进去跑业务链既慢又容易触发风控（实测批量调用要 ~1.2s/条）。
 */
object AutoRunner {

    private const val TAG = "xysidecar"

    /** mtop 就绪后再等这么久，避开冷启动高峰 */
    private const val START_DELAY_MS = 8000L

    /** 等 mtop 就绪的上限 */
    private const val READY_TIMEOUT_MS = 30000L

    /** 防重入：同一个进程里只跑一次（热重载会重新进 installAllHooks） */
    @Volatile
    private var started = false

    /**
     * 在主进程里排一次自动执行。
     *
     * 只在**主进程**调 —— `:channel` 里 mtop 是自建实例，拿不到登录态
     * （实测报 `ANDROID_SYS_NETWORK_ERROR`），跑链必然全失败。
     */
    @JvmStatic
    fun schedule(app: Context?) {
        if (app == null) return
        if (started) return
        started = true
        if (!Prefs.get(app, Prefs.AUTO_START, true)) {
            // 总开关同时控制启动轮和定时轮；保留调度线程以响应后续设置。
            Protocol.State.logLine("自动执行：开关关闭，跳过启动轮")
            AutoTask.schedule(app)
            return
        }
        val t = Thread({
            try {
                runBlocking(app)
            } catch (t: Throwable) {
                Log.w(TAG, "AutoRunner failed: $t")
                Protocol.State.logLine("自动执行异常：${t.javaClass.simpleName}")
            } finally {
                AutoTask.schedule(app.applicationContext ?: app)
            }
        }, "AutoRunner")
        t.isDaemon = true
        t.start()
    }

    /** 回前台检查的去抖：内部页面切换会连续触发 onResume。 */
    private const val FG_DEBOUNCE_MS = 120_000L

    @Volatile
    private var lastFgCheck = 0L

    /**
     * 回到前台时的检查。启动时由 [schedule] 负责；这里补「从后台切回来」这个时机。
     *
     * 仍受同一套条件约束：总开关、暂停、每天一次/四小时、以及与定时轮共用的锁。
     * 去抖 2 分钟，避免在 App 内切页时反复进入判断；真正是否执行由模式决定。
     */
    @JvmStatic
    fun onForeground(app: Context?) {
        val c = app?.applicationContext ?: app ?: return
        if (!Prefs.get(c, Prefs.AUTO_START, true)) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastFgCheck < FG_DEBOUNCE_MS) return
            lastFgCheck = now
        }
        val t = Thread({
            try {
                checkForeground(c)
            } catch (t: Throwable) {
                Log.w(TAG, "foreground check failed: $t")
            }
        }, "AutoForeground")
        t.isDaemon = true
        t.start()
    }

    private fun checkForeground(app: Context) {
        if (ApiInit.paused(app)) return
        if (!Protocol.State.awaitReady(8000L)) return
        val mode = Prefs.getString(app, Prefs.AUTO_MODE, Prefs.AUTO_MODE_H4)
        if (mode == Prefs.AUTO_MODE_DAILY &&
            Prefs.getInt(app, AutoTask.KEY_DAY, 0) == AutoTask.today()
        ) return
        val last = AutoTask.lastFullAt(app)
        if (mode == Prefs.AUTO_MODE_H4) {
            if (last > 0L && System.currentTimeMillis() - last < 4L * 60L * 60L * 1000L) return
        }
        // 「每次启动」只约束冷启动；回前台属于同一会话，至少间隔 30 分钟再跑，
        // 否则频繁切 App 会把写请求打成连发。
        if (mode == Prefs.AUTO_MODE_ALWAYS &&
            last > 0L && System.currentTimeMillis() - last < 30L * 60L * 1000L) return
        val text = AutoTask.runRound(app, 2)
        Protocol.State.logLine("回前台检查：$text")
        flashResult()
    }

    /** 自动执行结束后，若宿主有前台页面就把本次结果窗口按内容高度展示。 */
    private fun flashResult() {
        val session = RunProgress.lastFinished ?: return
        if (session.snapshot().isEmpty()) return
        val act = UiInjector.current() ?: return
        act.runOnUiThread(Runnable {
            try {
                RunProgressPanel.show(act, session)
            } catch (ignored: Throwable) {
            }
        })
    }

    private fun runBlocking(app: Context) {
        // 参数档案表：登记已知参数的来源与 TTL（幂等，不动已有值）。
        // 放在等 mtop 之前 —— 它只读写本地 JSON，不需要网络。
        try {
            ApiInit.bootstrap(app)
        } catch (t: Throwable) {
            Log.w(TAG, "参数库初始化失败: $t")
        }
        // 等 mtop 就绪（冷启动时 initMtopSdk 还没跑）
        val t0 = System.currentTimeMillis()
        val ok = Protocol.State.awaitReady(READY_TIMEOUT_MS)
        if (!ok) {
            Protocol.State.logLine("自动执行：mtop 未就绪，跳过本次")
            AutoTask.schedule(app)
            return
        }
        // 避峰的总预算而不是固定再睡 8 秒：mtop 就绪本身常常已经花了 5-8 秒，
        // 那段时间正好错过了首页请求高峰，再睡满 8 秒纯属让用户白等。
        val waited = System.currentTimeMillis() - t0
        val delay = if (waited >= START_DELAY_MS) 1500L else START_DELAY_MS - waited
        try {
            Thread.sleep(delay)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }
        // 睡眠期间用户可能刚把开关关掉，再读一次
        if (!Prefs.get(app, Prefs.AUTO_START, true)) return
        // 「每天一次」模式：当天已经跑过就跳过（AutoTask.noteFullRun 会写 auto_day）
        val mode = Prefs.getString(app, Prefs.AUTO_MODE, Prefs.AUTO_MODE_H4)
        if (mode == Prefs.AUTO_MODE_DAILY &&
            Prefs.getInt(app, AutoTask.KEY_DAY, 0) == AutoTask.today()
        ) {
            Protocol.State.logLine("自动执行：今天已跑过（每天一次），跳过")
            AutoTask.schedule(app)
            return
        }
        // 每 4 小时：和「上次执行」比时间差。注意判据是**启动时**检查，
        // 不做后台定时（ColorOS 会把 AlarmManager 推迟，见 docs/research/research-background.md）。
        if (mode == Prefs.AUTO_MODE_H4) {
            val last = AutoTask.lastFullAt(app)
            val gap = System.currentTimeMillis() - last
            if (last > 0L && gap < 4L * 60L * 60L * 1000L) {
                val left = (4L * 60L * 60L * 1000L - gap) / 60000L + 1L
                Protocol.State.logLine("自动执行：距上次不足 4 小时（还差 $left 分钟），跳过")
                AutoTask.schedule(app)
                return
            }
        }

        // 所有轮次共用 runRound 的互斥、暂停检查与状态登记。
        try {
            Protocol.State.logLine("自动执行：开始")
            val text = AutoTask.runRound(app.applicationContext ?: app, 2)
            Protocol.State.logLine("自动执行：$text")
            flashResult()
        } finally {
            // 就绪超时或执行异常也必须启动后续调度。
            AutoTask.schedule(app.applicationContext ?: app)
        }
    }
}
