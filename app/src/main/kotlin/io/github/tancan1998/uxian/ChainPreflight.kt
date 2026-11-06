package io.github.tancan1998.uxian

import android.content.Context

/** 预检按业务作用域读取公开配置；不把缺参数误判为未登录，不发送写请求。 */
object ChainPreflight {
    class Check(
        @JvmField val name: String,
        @JvmField val ready: Boolean,
        @JvmField val hint: String,
    )

    @JvmStatic fun run(ctx: Context): List<Check> {
        val out = ArrayList<Check>()
        if (ApiInit.paused(ctx)) {
            out.add(Check("业务请求", false, ApiInit.actionHint(ctx)))
            return out
        }
        out.add(checkScopes(ctx, "任务中心 496", arrayOf(BusinessParams.TASK_TRIGGER, BusinessParams.TASK_BENEFIT)))
        out.add(checkScopes(ctx, "现金红包", arrayOf(BusinessParams.CASH_MAIN, BusinessParams.CASH_OPEN)))
        out.add(checkScopes(ctx, "多日挑战", arrayOf(BusinessParams.CHALLENGE_AWARD)))
        out.add(checkScopes(ctx, "道具使用", arrayOf(BusinessParams.PROP_USE)))
        out.add(checkTreasure(ctx))
        return out
    }

    private fun checkScopes(ctx: Context, name: String, scopes: Array<BusinessParams.Scope>): Check {
        var ready = true
        var stale = false
        for (scope in scopes) {
            val value = try { BusinessParams.resolve(ctx, scope) } catch (ignored: Throwable) { "" }
            if (value.isEmpty()) ready = false
            val param = ParamStore.get(ctx, scope.key)
            if (param != null && param.expired(System.currentTimeMillis())) stale = true
        }
        if (ready) return Check(name, true, if (stale) "公开配置检查未更新，保留该业务缓存" else "")
        return Check(name, false, "该业务尚无匹配的接口/场景参数，已安排后台检查；本轮跳过，无需打开 H5")
    }

    private fun checkTreasure(ctx: Context): Check = try {
        val dice = Chains.peekTreasure(ctx)
        if (dice > 0) Check("寻宝地图", true, "") else Check(
            "寻宝地图", false, "当前骰子为 0。先完成任务中心的骰子奖励任务",
        )
    } catch (t: Throwable) {
        Check("寻宝地图", false, "查询失败：" + t.javaClass.simpleName)
    }

    @JvmStatic fun summary(ctx: Context): String {
        val checks = try { run(ctx) } catch (ignored: Throwable) { return "" }
        val bad = ArrayList<String>()
        for (check in checks) if (!check.ready) bad.add(check.name)
        if (bad.isEmpty()) return ""
        val out = StringBuilder("前置条件未就绪：")
        for (i in bad.indices) {
            if (i > 0) out.append("、")
            out.append(bad[i])
        }
        return out.toString()
    }
}
