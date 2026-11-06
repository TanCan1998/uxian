package io.github.tancan1998.uxian

/** 运行数据不持有 Activity/View；关闭进度窗口不会中断工作线程。 */
object RunProgress {
    class Line(val text: String, val failed: Boolean)
    class Session(val title: String) {
        val startedAt = System.currentTimeMillis()
        private val lines = ArrayList<Line>()
        @Volatile var running = true
        @Volatile var current = "准备执行"
        @Volatile var revision = 0
        private val finishListeners = ArrayList<Runnable>()
        @Synchronized fun onFinish(r: Runnable) { finishListeners.add(r) }
        @Synchronized fun drainFinish(): ArrayList<Runnable> {
            val out = ArrayList<Runnable>()
            out.addAll(finishListeners)
            finishListeners.clear()
            return out
        }
        var okCount = 0
        var skipCount = 0
        var failCount = 0
        fun step(value: String) { current = value; revision++ }
        @Synchronized fun append(text: String, failed: Boolean = false) {
            if (lines.size >= 400) lines.removeAt(0)
            val safe = try { chinese(text) } catch (_: Throwable) { text }
            lines.add(Line(safe, failed))
            revision++
        }
        @Synchronized fun snapshot(): ArrayList<Line> {
            val out = ArrayList<Line>()
            for (line in lines) out.add(line)
            return out
        }
    }
    private val local = ThreadLocal<Session>()
    /** 最近一次结束的会话，面板「上次执行结果」用 */
    @Volatile var lastFinished: Session? = null
        private set
    @Volatile var latest: Session? = null
        private set

    /** 总是创建新会话；是否允许并发由执行锁决定，不用会话状态二次拦截。 */
    @Synchronized fun begin(title: String): Session {
        val session = Session(title)
        latest = session
        return session
    }
    fun attach(session: Session) { local.set(session) }
    fun finish(session: Session) {
        try {
            session.append(
                "执行结束：成功 " + session.okCount + " · 跳过 " + session.skipCount +
                    " · 失败 " + session.failCount,
                session.failCount > 0,
            )
        } catch (ignored: Throwable) {
        }
        session.current = "执行结束"
        session.running = false
        session.revision++
        lastFinished = session
        val listeners = session.drainFinish()
        for (r in listeners) {
            try { r.run() } catch (_: Throwable) {}
        }
        local.remove()
    }
    fun step(text: String) {
        val session = local.get() ?: return
        val safe = try { chinese(text) } catch (_: Throwable) { text }
        session.step(safe)
    }
    fun line(text: String, failed: Boolean = false) { local.get()?.append(text, failed) }
    fun result(title: String, name: String, detail: String, ok: Boolean, skipped: Boolean) {
        val mark = if (skipped) "跳过" else if (ok) "成功" else "失败"
        val session = local.get()
        if (session != null) {
            if (skipped) session.skipCount++ else if (ok) session.okCount++ else session.failCount++
        }
        line("$title · $name：$mark，$detail", !ok)
    }
    fun request(api: String) {
        val action = when {
            api.contains("uploadOliverTaskTrigger") || api.endsWith(".trigger") -> "上报任务进度"
            api.contains("getolivertaskbenefit") || api.endsWith(".award") || api.endsWith(".reward") -> "领取任务奖励"
            api.endsWith(".exp.fetch") -> "领取活跃经验"
            api.endsWith(".cash.open") -> "拆现金红包"
            api.endsWith(".map.roll") -> "寻宝地图前进"
            api.endsWith(".prop.use") -> "使用背包道具"
            api.endsWith(".feed") -> "喂养森林动物"
            api.contains("sign.in") && !api.endsWith(".home") && !api.endsWith(".query") -> "检查并完成签到"
            api.contains(".complete") -> "上报游戏任务"
            else -> "查询并核对当前状态"
        }
        step(action)
    }
    /** 业务日志可保留机器码，用户进度页以中文呈现。 */
    fun chinese(text: String): String {
        var out = text
        val terms = arrayOf(
            "FAIL_BIZ_COMMON_PARAM_ILLEGAL" to "业务参数被拒绝",
            "FAIL_BIZ_ACTIVITY_TASK_CANNOT_AWARD" to "任务尚不可领奖",
            "FAIL_BIZ_ACTIVITY_TASK_NOT_COMPLICATE" to "任务尚未完成",
            "FAIL_SYS_USER_VALIDATE" to "需要在闲鱼完成验证",
            "FAIL_SYS_BIZPARAM_MISSED" to "缺少业务参数",
            "FAIL_BIZ_SERVER_ERROR" to "服务暂不可用",
            "HOST_RPC_TIMEOUT" to "请求超时，结果待确认",
            "HOST_RPC_UNAVAILABLE" to "宿主调用通道尚未就绪",
            "HOST_RPC_FAILED" to "宿主调用失败",
            "CALL_FAILED" to "请求失败",
            "PARAM_ILLEGAL" to "业务参数被拒绝",
            "TASK_NOT_FOUND" to "任务已失效或不在当前活动中",
            "NOT_COMPLICATE" to "任务尚未完成",
            "COMPLETE" to "已完成", "ACCEPTED" to "待完成",
            "AWARDING" to "待领奖", "AWARD" to "待领奖",
            "ALREADY" to "已处理", "PAUSED" to "已暂停",
            "taskInstanceId" to "任务实例编号", "deliveryId" to "任务编号",
            "taskParams" to "任务参数", "fromToken" to "当前任务凭据",
            "implId" to "任务实现编号", "asac" to "业务凭据",
            "recordId" to "记录编号", "feedId" to "喂养编号",
        )
        for (pair in terms) out = out.replace(pair.first, pair.second)
        // 未识别机器错误码也不直接展示给用户。
        // 不用 Kotlin 的 split(vararg)：R8 曾把这里的实现裁成 AbstractMethodError，
        // 直接手写扫描更稳。
        out = scrub(out, "FAIL_")
        out = scrub(out, "HOST_RPC_")
        return out
    }

    private fun scrub(text: String, prefix: String): String {
        var s = text
        var from = 0
        while (true) {
            val at = s.indexOf(prefix, from)
            if (at < 0) return s
            var end = at
            while (end < s.length) {
                val ch = s[end]
                if (!(ch in 'A'..'Z' || ch in '0'..'9' || ch == '_')) break
                end++
            }
            s = s.substring(0, at) + "请求未成功" + s.substring(end)
            from = at + 5
        }
    }
}
