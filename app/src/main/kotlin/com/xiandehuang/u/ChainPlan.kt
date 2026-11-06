package com.xiandehuang.u

/** 自动、手动与无头入口共用的顺序；任务先产出奖励，再消耗资源、领取衍生奖励。 */
object ChainPlan {
    class Entry(val id: String, val title: String, val desc: String, val recurring: Boolean = true)

    // 手写 ArrayList，避免 R8 对集合字面量的历史兼容问题。
    val entries = ArrayList<Entry>()
    init {
        entries.add(Entry("signin", "每日签到", "闲鱼币与小游戏签到活动", false))
        entries.add(Entry("tasks", "任务领奖", "先领取待领奖励，释放多轮任务"))
        entries.add(Entry("countdown", "任务触发", "动态任务上报、逐次领奖并回读"))
        entries.add(Entry("cashstay", "现金停留任务", "按现金页等待信息执行独立任务"))
        entries.add(Entry("cash", "现金红包", "现金任务 → 领奖 → 拆红包"))
        entries.add(Entry("member", "会员中心", "会员任务上报并领奖"))
        entries.add(Entry("game", "闲鱼游戏", "频道、关卡、动作及商业任务"))
        entries.add(Entry("circle", "圈子任务", "已加入圈子签到、查询任务进度", false))
        entries.add(Entry("forest", "森林", "领能量、喂养及答题", false))
        entries.add(Entry("challenge", "多日挑战", "做任务后领取已达成档位"))
        entries.add(Entry("treasure", "寻宝地图", "使用任务所得资源，最多前进 8 次"))
        entries.add(Entry("cycle", "圈数挑战池", "寻宝后领取已达成圈数奖励"))
        entries.add(Entry("props", "背包", "处理寻宝及挑战获得的非骰子道具"))
        entries.add(Entry("experience", "活跃经验", "最后查询并领取可领活跃经验"))
    }

    /** 链 id -> 面板中文名，进度窗口用。 */
    fun titleOf(id: String): String {
        val normalized = when (id) {
            "signIn", "signin" -> "signin"
            "game" -> "game"
            else -> id
        }
        for (e in entries) if (e.id == normalized) return e.title
        return id
    }

    fun select(name: String): ArrayList<Entry> {
        val out = ArrayList<Entry>()
        val id = if (name == "sign") "signin" else name
        for (entry in entries) {
            if (id == "all" || (id == "pending" && entry.recurring) || id == entry.id) out.add(entry)
        }
        return out
    }
}
