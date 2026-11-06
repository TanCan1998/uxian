package io.github.tancan1998.uxian

import android.content.Context
import org.json.JSONObject

/**
 * 业务链（把接口当 API 用）。
 *
 * 每一条链对应 `docs/api-callbook.md` 里一个实测跑通的业务模型：
 * 参数从哪个接口拿、调用顺序、状态怎么判。这里只放「接口怎么调」，
 * 界面全在 [FeaturePanel]。
 *
 * 三条铁律（都是踩过的坑，别改）：
 *  1. **报 FAIL_BIZ_SERVER_ERROR 先试版本号** —— energy.issue 只有 2.0 通，
 *     1.0/3.0 都报「服务开小差」，看起来像服务端挂了。
 *  2. **幂等提示不是错误** —— 已签到返回 FAIL_BIZ_PLAY_SIGNED_IN，按成功处理。
 *  3. **每次调用要现取参数** —— bubbleId / locationBefore / idempotentKey
 *     都是一次性的，复用上一次的值必然失败。
 */
object Chains {

    /** 公开版不预置维护者的位置；空值交由宿主/服务端默认策略处理。 */
    const val AD_CODE = ""
    const val GPS = ""

    /**
     * 「当天只试一次」现在由 [ParamGuard] 统一管（落盘、跨天自动重试）。
     * 早先这里是进程内 Set，宿主被杀就失效，等于每轮重打一遍注定失败的写请求。
     */

    // ---------------------------------------------------------------- 结果

    class Step(val name: String, val ok: Boolean, val detail: String, val skipped: Boolean = false)

    /**
     * 一条链的执行结果。
     * steps 按执行顺序记录，界面上逐行显示，失败能看到停在哪一步。
     */
    class Result(val title: String) {
        val steps = ArrayList<Step>()
        var skipped = false

        /**
         * 一句话结果，用于面板/适配性页的**列表行**（如「已签」「已领 2 个」「无骰子」）。
         *
         * 为什么不用「跳过」：那个词只说明「没做事」，用户真正想知道的是
         * **事情最后是什么状态** —— 是今天已经签过了，还是根本没东西可签。
         * 两者都「跳过」，但含义完全不同。
         *
         * 由各条链在末尾显式设置（`r.state = "已签"`），不靠关键词猜：
         * 猜法对新增的链必然漏，而显式赋值改起来只动一行。
         * 留空时 [ChainHistory] 会退回「完成 / 有失败」。
         */
        var state: String = ""

        fun ok(name: String, detail: String) {
            steps.add(Step(name, true, detail))
        }
        fun fail(name: String, detail: String) {
            steps.add(Step(name, false, detail))
        }
        fun skip(name: String, detail: String) {
            skipped = true
            steps.add(Step(name, true, detail, true))
        }
        fun healthy(): Boolean = steps.none { !it.ok }
        fun summary(): String = if (healthy()) "完成" else "有失败"
    }

    // ---------------------------------------------------------------- 底层

    /**
     * 调一个接口并解析响应。
     *
     * 失败时抛 [ApiError]，带上服务端 ret，调用方据此判断
     * 「是幂等提示」还是「真失败」（map 里的 msg 会一起带出来）。
     */
    class ApiError(val ret: String, val msg: String) :
        Exception(if (msg.isEmpty()) ret else "$ret：$msg")

    /**
     * 执行一条链并落历史。
     *
     * 所有对外入口（signIn / forest / cash / circle / treasure）都经这里，
     * 保证「面板点了」「无头通道调了」「一键全做跑了」三种情况都留痕。
     * 异常也记一条失败，不能因为抛异常就丢掉「今天试过」这个事实。
     */
    private fun rec(ctx: Context, name: String, body: () -> Result): Result {
        RunProgress.step("正在执行：" + ChainPlan.titleOf(name))
        var r: Result? = null
        try {
            r = body()
            ChainHistory.record(ctx, r)
            return r
        } catch (t: Throwable) {
            // 没拿到 Result 时临时造一个，只为把失败写进历史
            val bad = Result(name)
            bad.fail("执行", t.message ?: t.javaClass.simpleName)
            ChainHistory.record(ctx, bad)
            if (r != null) throw t
            return bad
        }
    }

    /**
     * 参数取值类型必须和抓包一致 —— **数字型 id 传成字符串会报
     * FAIL_BIZ_SERVER_ERROR / FAIL_BIZ_PARAMETER_ERROR**，而这两个错误
     * 的文案完全没有提示性（「服务开小差啦」）。实测汇总：
     *
     *   数字：feedId / taskInstanceId / idempotentKey / deliveryId / outId /
     *         locationBefore / sceneId / benefitId / configId / id / playId / version
     *   字符串：activityId（注意！虽然是 id 但是字符串）/ circleId / bizCode /
     *         diceType / mapVersion / asac / fromToken / implId / awardIndexList
     *
     * 这里用 [num] 显式包一层，避免再踩。
     */
    class Num(val v: Long)

    private fun env(): JSONObject = JSONObject()
        .put("fishAdCode", AD_CODE)
        .put("gps", GPS)

    private fun data(vararg kv: Pair<String, Any?>): String {
        val o = env()
        for ((k, v) in kv) {
            when (v) {
                null -> {}
                is Num -> o.put(k, v.v)
                is Int -> o.put(k, v)
                is Long -> o.put(k, v)
                is Boolean -> o.put(k, v)
                is String -> o.put(k, v)
                is JSONObject -> o.put(k, v)
                else -> o.put(k, v.toString())
            }
        }
        return o.toString()
    }

    /**
     * 调接口。成功返回响应 JSONObject；失败抛 ApiError。
     *
     * MtopBridge 有两种失败形态：
     *   - 抛异常（反射/网络层）
     *   - 返回 `{"ret":"FAIL_...","_diag":...}` 这种带 ret 的 JSON
     * 两种都要识别，否则失败会被当成「成功但空数据」。
     */
    private fun call(
        ctx: Context,
        api: String,
        ver: String,
        body: String,
    ): JSONObject {
        if (Thread.currentThread().isInterrupted) throw ApiError("INTERRUPTED", "执行已中断")
        if (ApiInit.paused(ctx)) throw ApiError("PAUSED", "当前暂停中，本次未发请求")
        RunProgress.request(api)
        val raw = try {
            HostRpc.request(ctx, api, ver, body)
        } catch (t: Throwable) {
            // MtopBridge 内部对「服务端返回了非 SUCCESS」也是抛异常，
            // 真实 ret 只存在于 message 里（形如
            //   IllegalStateException: ret=FAIL_BIZ_SERVER_ERROR ... msg=服务开小差啦）
            // 必须把它解出来，否则上游拿 e.ret 只能看到 CALL_FAILED，
            // 「是不是次数满了 / 是不是版本错」这类判断全部失效。
            //
            // 用 indexOf 手动切，不用 Regex：R8 收缩后 kotlin.text.Regex 的
            // 内部实现会缺方法，运行时抛 AbstractMethodError（实测整条链全挂）。
            val msg = t.message ?: t.javaClass.simpleName
            var ret = "CALL_FAILED"
            val at = msg.indexOf("ret=")
            if (at >= 0) {
                var e = at + 4
                while (e < msg.length && (msg[e].isLetterOrDigit() || msg[e] == '_')) e++
                if (e > at + 4) ret = msg.substring(at + 4, e)
            }
            var detail = msg
            val mt = msg.indexOf("msg=")
            if (mt >= 0) {
                var e = mt + 4
                // msg 后面紧跟 " retType=" 或行尾
                while (e < msg.length && msg[e] != 'r') e++
                if (e > mt + 4) detail = msg.substring(mt + 4, e).trim()
            }
            ApiInit.noteFailure(ctx, ret, detail)
            throw ApiError(ret, detail)
        }
        if (raw.isBlank()) throw ApiError("EMPTY", "空响应")
        val o = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            throw ApiError("BAD_JSON", raw.take(120))
        }
        val ret = o.optString("ret", "")
        if (ret.isNotEmpty() && !ret.startsWith("SUCCESS")) {
            ApiInit.noteFailure(ctx, ret, o.optString("msg", ""))
            throw ApiError(ret, o.optString("msg", ""))
        }
        return o
    }

    /**
     * 版本号逐个试。
     *
     * 实测 `FAIL_BIZ_SERVER_ERROR` 九成是版本号不对（energy.issue 只有 2.0 通），
     * 而 mtop 不会报 API_NOT_FOUNDED —— 所以只能挨个试，并记住能用的那个。
     */
    private fun callVersioned(
        ctx: Context,
        api: String,
        body: String,
        versions: List<String>,
    ): Pair<JSONObject, String> {
        var last: ApiError? = null
        for (v in versions) {
            try {
                return Pair(call(ctx, api, v, body), v)
            } catch (e: ApiError) {
                last = e
                // 只有「版本不对」这类通用错误才值得换版本重试；
                // 明确业务拒绝（FAIL_BIZ_PLAY_SIGNED_IN 之类）立刻返回。
                val ret = e.ret
                val worthRetry = ret.contains("SERVER_ERROR") ||
                    ret.contains("API_NOT_FOUNDED") ||
                    ret.contains("COMMON_PARAM_ILLEGAL") ||
                    ret == "EMPTY"
                if (!worthRetry) throw e
            }
        }
        throw last ?: ApiError("UNKNOWN", "全部版本都失败")
    }

    /** mtop 的布尔/数字字段类型不稳定，统一归一 */
    private fun flag(v: Any?): Boolean {
        if (v is Boolean) return v
        val s = v?.toString()?.trim()?.lowercase() ?: return false
        return s == "true" || s == "1"
    }

    private fun int(v: Any?, d: Int = 0): Int = when (v) {
        is Int -> v
        is Long -> v.toInt()
        is Number -> v.toInt()
        else -> {
            // 不用 ?.let / toDoubleOrNull 之类的 Kotlin 扩展：R8 收缩后
            // 这些内部实现会缺方法（AbstractMethodError）。手写解析最稳。
            val s = if (v == null) null else v.toString()
            if (s == null) d else {
                val n = s.toDoubleOrNull()
                if (n == null) d else n.toInt()
            }
        }
    }

    // ================================================================ 1 每日签到

    /** playId 是「活动 id」不是接口配置，同一接口传不同 playId 返回各自独立状态 */
    private val SIGN_PLAYS: ArrayList<Pair<Int, String>> = ArrayList()

    init {
        // 不用 listOf(...)：R8 收缩会裁掉 Collection.toArray 的默认实现
        SIGN_PLAYS.add(Pair(4, "闲鱼币页签到"))
        SIGN_PLAYS.add(Pair(101, "闲鱼币"))
        SIGN_PLAYS.add(Pair(110, "小游戏"))
    }

    /**
     * 每日签到：两套活动一起做。
     *
     * 调用链（callbook 4.2/4.3）：
     *   101 → play.sign.in.home 查 → play.sign.in 签
     *   110 → mini.game.sign.in.query 查 → play.sign.in(playId=110) 签
     */
    fun signIn(ctx: Context): Result {
        return rec(ctx, "signIn") {
            signInInner(ctx)
        }
    }

    private fun signInInner(ctx: Context): Result {
        val r = Result("每日签到")
        var signedNow = 0
        var already = 0
        for ((playId, name) in SIGN_PLAYS) {
            try {
                // 查状态（两套用不同查询接口）
                val signed: Boolean
                val detail: String
                if (playId == 110) {
                    val q = call(
                        ctx, "mtop.taobao.idle.mini.game.sign.in.query", "1.0",
                        data("playId" to Num(playId.toLong())),
                    )
                    signed = flag(q.optString("signedToday"))
                    val days = int(q.opt("continuousDays"))
                    detail = "已连续 $days 天"
                } else {
                    val q = call(
                        ctx, "mtop.taobao.idle.play.sign.in.home", "1.0",
                        data("playId" to Num(playId.toLong())),
                    )
                    signed = flag(q.optString("signInAwarded"))
                    detail = "已连续 ${int(q.opt("signInDays"))} 天"
                }
                if (signed) {
                    already++
                    r.skip(name, "今天已签（$detail）")
                    continue
                }
                // 执行
                val res = call(
                    ctx, "mtop.taobao.idle.play.sign.in", "1.0",
                    data("playId" to Num(playId.toLong())),
                )
                val days = int(res.opt("signInDays"))
                signedNow++
                r.ok(name, if (flag(res.opt("signedIn"))) "签到成功，连续 $days 天" else "已提交")
            } catch (e: ApiError) {
                // 已签到是幂等提示，不是失败
                if (e.ret.contains("SIGNED_IN")) {
                    already++
                    r.skip(name, "今天已签（${e.msg}）")
                } else {
                    r.fail(name, e.message ?: e.ret)
                }
            }
        }
        r.state = when {
            signedNow > 0 -> "已签 " + signedNow
            already > 0 -> "已签过"
            else -> ""
        }
        return r
    }

    // ================================================================ 2 森林

    /**
     * 森林：领能量 → 喂养 → 答题。
     *
     * 顺序有讲究（callbook 4.9）：先领能量，喂养要消耗能量；
     * 答完题会再冒出一个能量气泡，所以最后再领一次。
     */
    fun forest(ctx: Context): Result {
        return rec(ctx, "forest") {
            forestInner(ctx)
        }
    }

    private fun forestInner(ctx: Context): Result {
        val r = Result("森林")
        // 提到最外层：末尾拼结果摘要要用
        var collected = 0
        var fed = 0
        // ---- 1. 领能量气泡 ----
        try {
            val list = call(
                ctx, "mtop.taobao.idle.forest.energy.list", "1.0",
                data("pageNum" to 1, "pageSize" to 30, "startId" to ""),
            )
            val arr = list.optJSONArray("data")
            val n = arr?.length() ?: 0
            var done = 0
            for (i in 0 until n) {
                val b = arr!!.optJSONObject(i) ?: continue
                if (b.optString("status") != "AVAILABLE") continue
                val bubbleId = b.optString("bubbleId")
                if (bubbleId.isEmpty()) continue
                try {
                    // 版本必须 2.0：1.0/3.0 都报 SERVER_ERROR（callbook 2.2）
                    val res = call(
                        ctx, "mtop.taobao.idle.forest.energy.issue", "2.0",
                        data(
                            "bubbleId" to bubbleId,
                            "secondScene" to b.optString("secondScene"),
                            "from" to "wode",
                        ),
                    )
                    collected += int(res.opt("collectEnergy"))
                    done++
                } catch (e: ApiError) {
                    // 单个气泡失败不影响其余
                }
            }
            if (n == 0) r.skip("领能量", "没有待领气泡") else r.ok("领能量", "$done 个气泡，+$collected")
        } catch (e: ApiError) {
            r.fail("领能量", e.message ?: e.ret)
        }

        // ---- 2. 喂养 ----
        try {
            val list = call(
                ctx, "mtop.taobao.idle.forest.animal.list", "1.0", data(),
            )
            val arr = list.optJSONArray("result")
            val n = arr?.length() ?: 0
            var target = ""
            var todayFed = -1   // 本级今日已喂次数（level0 的 feedTimes）
            for (i in 0 until n) {
                val a = arr!!.optJSONObject(i) ?: continue
                val fi = a.optJSONObject("feedInfo") ?: continue
                if (fi.optString("feedStatus") != "Feeding") continue
                val feedId = fi.optString("feedId")
                if (feedId.isEmpty()) continue
                target = a.optString("condition", "")
                // levelInfo[level=0].feedTimes 就是「本轮/今日已喂次数」，
                // 服务端把数字也包成字符串（"3"），统一走 int() 转。
                val lv = fi.optJSONArray("levelInfo")
                if (lv != null) {
                    for (k in 0 until lv.length()) {
                        val it = lv.optJSONObject(k) ?: continue
                        if (int(it.opt("level"), -1) == 0) {
                            todayFed = int(it.opt("feedTimes"), -1)
                        }
                    }
                }
                try {
                    val res = call(
                        ctx, "mtop.taobao.idle.forest.animal.feed", "1.0",
                        data("feedId" to Num(feedId.toLongOrNull() ?: 0L)),
                    )
                    // canFeedTime=0 表示可喂，非 0 是冷却剩余
                    val cd = res.opt("canFeedTime")
                    if (int(cd, 0) != 0) {
                        r.fail("喂养", "冷却中（canFeedTime=$cd）")
                    } else {
                        fed++
                    }
                } catch (e: ApiError) {
                    // 每日次数用满时服务端只回 FAIL_BIZ_SERVER_ERROR（「服务开小差啦」），
                    // 完全看不出是次数限制 —— 实测 level0 的 feedTimes 每天到 3 就不再接受，
                    // 前 3 次依次是 1/2/3。所以这里要按「今日已喂满」提示，
                    // 而不是把用户吓一跳去排查接口。
                    if (e.ret.contains("SERVER_ERROR")) {
                        // 每日次数用满时服务端只回「服务开小差啦」，完全看不出是次数限制。
                        // 实测 level0 的 feedTimes 到 3 就不再接受（前三次依次 1/2/3）。
                        val n2 = if (todayFed >= 0) "（本级今日已喂 $todayFed 次）" else ""
                        r.skip("喂养", "今日已喂满$n2")
                    } else {
                        r.fail("喂养", e.message ?: e.ret)
                    }
                }
                break // 只喂主动物
            }
            if (fed > 0) r.ok("喂养", "已喂养${if (target.isEmpty()) "" else "（$target）"}")
            else if (r.steps.none { it.name == "喂养" }) r.skip("喂养", "没有可喂养的动物")
        } catch (e: ApiError) {
            r.fail("喂养", e.message ?: e.ret)
        }

        // ---- 3. 答题（响应直接给答案）----
        try {
            val q = call(ctx, "mtop.taobao.idle.forest.quiz.info", "1.0", data())
            val question = q.optString("question")
            val idx = int(q.opt("correctIndex"), -1)
            if (question.isEmpty() || idx < 0) {
                r.skip("答题", "当前没有题目")
            } else {
                r.ok("答题", "题目：${question.take(30)}｜答案 $idx")
                // 触发任务：activityId=103786 是绿色答题活动。
                // taskInstanceId 要从 oliver.show 的 taskList 里按**任务标题**挑：
                // 这个活动下有 7 个任务（买闲置/扫鱼塘/邀好友…），随便取第一个
                // 会拿到完全不相干的实例，服务端回 FAIL_BIZ_PARAMETER_ERROR
                // （文案还是「参数错误请稍后再试」，看不出是挑错任务）。
                val show = call(
                    ctx, "mtop.taobao.idle.oliver.show", "2.0",
                    data(
                        "activityId" to "103786", "extend" to JSONObject(),
                        "needBenefits" to true, "needDetails" to false,
                        "needHadWin" to false, "showEmptyInventory" to false,
                        "withInventoryDetail" to false,
                    ),
                )
                val found = findQuizTask(show)
                if (found == null) {
                    r.skip("答题领奖", "活动 103786 里没有答题任务")
                } else if (found.second == "COMPLETE") {
                    r.skip("答题领奖", "今日答题奖励已领")
                } else if (found.second != "AWARD") {
                    // ACCEPTED：任务还没被判完成，触发不会有奖励
                    r.skip("答题领奖", "答题任务状态 ${found.second}，尚未完成")
                } else {
                    val taskId = found.first
                    val trig = call(
                        ctx, "mtop.taobao.idle.cube.task.trigger", "1.0",
                        data(
                            "activityId" to "103786",
                            "taskInstanceId" to Num(taskId.toLongOrNull() ?: 0L),
                            "idempotentKey" to Num(System.currentTimeMillis()),
                        ),
                    )
                    val st = trig.optJSONObject("idleTaskDTO")?.optString("idleTaskStatus") ?: ""
                    if (st == "AWARD") {
                        // 触发成功 → 领奖
                        try {
                            call(
                                ctx, "mtop.taobao.idle.joy.community.activity.task.award",
                                "1.0",
                                data(
                                    "activityId" to "103786",
                                    "taskId" to Num(taskId.toLongOrNull() ?: 0L),
                                    "awardIndexList" to "1",
                                ),
                            )
                            r.ok("答题领奖", "已领取")
                        } catch (e: ApiError) {
                            // 领奖失败不影响答题本身
                            r.skip("答题领奖", "已触发，领奖未成功（${e.ret}）")
                        }
                    } else {
                        r.skip("答题领奖", "任务状态 $st，无需领奖")
                    }
                }
            }
        } catch (e: ApiError) {
            r.fail("答题", e.message ?: e.ret)
        }

        // 结果摘要按「今天实际拿到什么」拼，不写「跳过」：
        // 用户要知道的是能量领了多少、喂了没有、答没答，不是「这一步被跳过了」。
        val bits = ArrayList<String>()
        if (collected > 0) bits.add("+$collected 能量")
        if (fed > 0) bits.add("已喂养")
        if (bits.isEmpty()) {
            // 一个动作都没做成：按「最接近用户关心的那一步」概括。
            // 优先级 喂养 > 能量 > 答题 —— 喂养是森林的核心动作，
            // 「今日已喂满」比「没有待领气泡」更能说明今天的状态。
            val want = arrayOf("喂养", "领能量", "答题")
            for (name in want) {
                var done = false
                for (st in r.steps) {
                    if (st.name == name && st.detail.length > 0) {
                        bits.add(shortOf(st.detail))
                        done = true
                        break
                    }
                }
                if (done) break
            }
        }
        r.state = joinBits(bits)
        return r
    }

    /** 把一句长说明压成列表行能放下的短语 */
    private fun shortOf(detail: String): String {
        val at = detail.indexOf('（')
        if (at > 0) return detail.substring(0, at)
        if (detail.length <= 8) return detail
        return detail.substring(0, 8)
    }

    /** 拼接摘要，最多 2 段，避免列表行被撑爆 */
    private fun joinBits(bits: List<String>): String {
        if (bits.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        for (b in bits) {
            if (i >= 2) break
            if (i > 0) sb.append(" · ")
            sb.append(b)
            i++
        }
        return sb.toString()
    }

    /**
     * 从 oliver.show 的 taskList 里挑出答题任务，返回 (taskInstanceId, 状态)。
     *
     * 不能「取第一个 taskInstanceId」：同一活动下挂着 7 个任务
     * （购买闲置/扫鱼塘/邀好友/答题…），随便挑一个会报 FAIL_BIZ_PARAMETER_ERROR。
     * 按 materialDTO.title 里的「答题」定位才可靠。
     */
    private fun findQuizTask(show: JSONObject): Pair<String, String>? {
        val list = show.optJSONArray("taskList") ?: return null
        for (i in 0 until list.length()) {
            val t = list.optJSONObject(i) ?: continue
            val md = t.optJSONObject("materialDTO")
            val title = md?.optString("title", "") ?: ""
            val desc = md?.optString("desc", "") ?: ""
            if (title.contains("答题") || desc.contains("答题")) {
                val id = t.optString("taskInstanceId", "")
                if (id.isNotEmpty()) {
                    return Pair(id, t.optString("idleTaskStatus", ""))
                }
            }
        }
        return null
    }

    // ================================================================ 3 现金红包

    /** 缺少该 api/scene 的业务参数只跳过，不记未登录或任务失败。 */
    private fun requireAsac(ctx: Context, r: Result, step: String, scope: BusinessParams.Scope): String? {
        val ar = ApiInit.ensureAsac(ctx, scope)
        if (ar.refreshed) r.ok("业务参数", "已按接口和场景更新")
        if (ar.paused) {
            r.skip(step, ApiInit.actionHint(ctx))
            r.state = "暂停中"
            return null
        }
        if (ar.value.isEmpty()) {
            r.skip(step, ApiInit.actionHint(ctx))
            r.state = "业务参数待获取"
            return null
        }
        return ar.value
    }

    /** 只读该业务作用域的持久化值，不扫描任意接口请求头。 */
    fun cachedAsac(ctx: Context, scope: BusinessParams.Scope): String = BusinessParams.cached(ctx, scope)

    /** 从公开 HTML 动态定位业务 JS；不会调用活动 API 来给任务填参数。 */
    fun refreshAsac(ctx: Context, scope: BusinessParams.Scope): String = BusinessParams.resolve(ctx, scope)

    /**
     * 供 [ChainPreflight] 用的只读入口：查寻宝地图当前骰子数。
     * 只调 `game.refresh`（只读），不掷骰、不消耗次数。
     */
    fun peekTreasure(ctx: Context): Int {
        val g = call(
            ctx, "mtop.taobao.idle.treasure.hunt.map.game.refresh", "1.0", data(),
        )
        val ui = g.optJSONObject("refreshGameData")?.optJSONObject("userInfo")
            ?: throw ApiError("NO_USERINFO", "响应里没有 userInfo")
        return int(ui.opt("diceNum"))
    }

    /**
     * 现金签到拆红包：main 查有没有 → open 拆。
     *
     * 两个接口都必须带 `asac`（缺了报 FAIL_SYS_BIZPARAM_MISSED「服务竟然出错」），
     * 参数按两个接口分别取；公开配置没有对应包装器时明确跳过。
     */
    fun cash(ctx: Context): Result {
        return rec(ctx, "cash") {
            // 顺序很重要：**先做任务再拆红包** —— 任务完成会发红包（redEnvNum+1），
            // 拆红包读的是同一份状态；反过来做，这轮拿到的红包要等下一轮才拆。
            val r = cashTasksInner(ctx)
            try {
                val c = cashInner(ctx)
                for (st in c.steps) r.steps.add(st)
                if (c.skipped) r.skipped = true
                val tail = c.state
                if (tail.isNotEmpty()) r.state = (r.state + " · " + tail).trim(' ', '·')
            } catch (e: Throwable) {
                r.skip("拆红包", "执行异常 " + e.javaClass.simpleName)
            }
            r
        }
    }

    /** 现金停留奖励属于独立 Oliver 场景，不能混入 7066 的任务引擎。 */
    fun cashStay(ctx: Context): Result = rec(ctx, "cashstay") {
        val r = Result("现金停留任务")
        val existing = tasksInner(ctx, CASH_SCENE)
        for (step in existing.steps) r.steps.add(step)
        r.skipped = existing.skipped
        r.state = existing.state
        val asac = requireAsac(ctx, r, "停留信息", BusinessParams.CASH_MAIN)
        if (asac != null) {
            try {
                val main = call(ctx, "mtop.idle.play.signin.cash.main", "1.0", data("asac" to asac))
                val task = main.optJSONObject("timeTask")
                val wait = int(task?.opt("time"), -1)
                if (wait < 0 || wait > WAIT_LIMIT_SEC) {
                    r.skip("停留任务", if (wait < 0) "现金页未提供等待信息，本轮未上报" else "剩余 $wait 秒，留待后续执行")
                } else {
                    val progress = countdownInner(ctx, CASH_SCENE, wait)
                    for (step in progress.steps) r.steps.add(step)
                    if (progress.skipped) r.skipped = true
                    r.state = progress.state
                }
            } catch (e: ApiError) {
                r.fail("停留信息", e.message ?: e.ret)
            }
        }
        r
    }

    private fun cashInner(ctx: Context): Result {
        val r = Result("现金红包")
        val asac = requireAsac(ctx, r, "拆红包", BusinessParams.CASH_MAIN) ?: return r
        val openAsac = requireAsac(ctx, r, "拆红包", BusinessParams.CASH_OPEN) ?: return r
        try {
            val m = call(
                ctx, "mtop.idle.play.signin.cash.main", "1.0",
                data("asac" to asac),
            )
            val num = int(m.opt("signCashNum"))
            val threshold = int(m.opt("signCashThreshold"))
            // 能不能拆看 `redEnvNum`（页面上「还有 N 个可拆」），
            // 不是 currentBonus —— 实测红包页显示「还有两个可拆」时 currentBonus 是 false
            val envNum = int(m.opt("redEnvNum"))
            if (!flag(m.opt("currentBonus")) && envNum <= 0) {
                UserData.cash(ctx, num, threshold, envNum)
                r.skip("拆红包", "当前没有可拆红包（已攒 $num/$threshold）")
                r.state = "已攒 $num/$threshold"
                return r
            }
            // 红包可能有好几个（页面上「剩余 N 个」）：按 redEnvNum 连着拆完，
            // 一次只拆一个的话，剩下的要等下一轮 —— 用户看到的就是「明明还有 11 个」。
            var opened = 0
            var totalAmount = 0
            val cap = if (envNum > 30) 30 else if (envNum > 0) envNum else 1
            while (opened < cap) {
                val o = call(
                    ctx, "mtop.idle.play.signin.cash.open", "1.0",
                    data("asac" to openAsac),
                )
                totalAmount += int(o.opt("amount"))
                opened++
            }
            val after = call(
                ctx, "mtop.idle.play.signin.cash.main", "1.0",
                data("asac" to asac),
            )
            val left = int(after.opt("redEnvNum"))
            UserData.cash(
                ctx, int(after.opt("signCashNum")), int(after.opt("signCashThreshold")), left,
            )
            r.ok(
                "拆红包",
                "拆 $opened 个共 $totalAmount 分，累计 ${int(after.opt("signCashNum"))}/$threshold" +
                    if (left > 0) "，还剩 $left 个" else "",
            )
            r.state = "拆 $opened 个 +$totalAmount 分"
        } catch (e: ApiError) {
            r.fail("拆红包", e.message ?: e.ret)
        }
        return r
    }

    /**
     * 现金红包页的任务（pentaprism 场景 7066）。
     *
     * 抓包实证（2026-11-01 02:40，用户手动领两个红包时）：
     * ```
     * pentaprism.scene.query   {sceneId:7066}            → model[] 15 条任务
     * pentaprism.scene.trigger {taskParams + fishAdCode/gps} → 完成 / 领红包
     * ```
     * `taskParams` 就是查询结果里每条任务自带的：
     * `{activityId:100104, awardIndex:1, deliveryId, fromToken, implId, samplingRate, sceneId:7066}`。
     *
     * 处理策略（每轮最多 16 次写，避免一次打太多写接口）：
     *   - `status=AWARDING`：已完成待领，直接 trigger 领红包
     *   - `status=ACCEPTED` 且 `taskType=system_task_customize`：站内行为类
     *     （「每天从我的进入」「看视频得红包」「逛商城 15s」），直接 trigger 上报
     *   - 其余（task_jump 外跳、小游戏、浏览商品）：跳过，等真实行为
     */
    private fun cashTasksInner(ctx: Context): Result {
        val r = Result("现金任务")
        try {
            val q = call(
                ctx, "mtop.taobao.pentaprism.scene.query", "1.0",
                data("sceneId" to Num(CASH_SCENE_PENTA.toLong())),
            )
            val arr = q.optJSONArray("model")
            if (arr == null || arr.length() == 0) {
                r.skip("查询", "现金页没有任务")
                r.state = "无任务"
                return r
            }
            var done = 0
            var skipped = 0
            var tried = 0
            for (i in 0 until arr.length()) {
                if (tried >= W_CASH) break
                val m = arr.optJSONObject(i) ?: continue
                val status = m.optString("status", "")
                val type = m.optString("taskType", "")
                val title = m.optJSONObject("assets")?.optString("title", "") ?: ""
                if (status == "COMPLETE") continue
                val claim = status == "AWARDING"
                // 站内行为类 + 小游戏类都能自助上报（实测「消了还想消闯1关」
                // trigger 后 ACCEPTED → AWARDING）；只有外跳类要真实第三方行为
                val act = m.optJSONObject("assets")?.optString("action", "") ?: ""
                // 以下类型已逐项验证可走 Oliver 上报，现金页仍用场景接口领奖。
                val useOliver = type == "system_task_customize" ||
                    type == "system_task_browse" || type == "task_customize" ||
                    // 鱼塘/活动跳转同样接受 Oliver 上报（实测 0→1），不再一律跳过
                    type == "task_jump"
                val inline = useOliver || act.contains("minigame") || act.contains("fish-game")
                if (!claim && !inline) {
                    skipped++
                    continue
                }
                // 现金页的 system_task_customize 是站内广告/浏览任务，
                // action 虽是 fleamarket:// 或 H5 链接，但服务端接受 Oliver 上报；
                // 不能按「外跳 App」过滤，否则视频/商城/搜索会被整批跳过（实测）。
                val tp = m.optJSONObject("taskParams")
                if (tp == null) {
                    r.skip(title, "缺少 taskParams")
                    continue
                }
                // 已完成待领（AWARDING）走 **scene.award**，只有做任务才走 scene.trigger。
                // 抓包实证（02:21:24）：宿主点「领红包」调的是 pentaprism.scene.award，
                // 参数和 trigger 完全一样 —— 之前用 trigger 领，服务端不认，状态一直挂 AWARDING。
                val api = if (claim) "mtop.taobao.pentaprism.scene.award" else "mtop.taobao.pentaprism.scene.trigger"
                try {
                    if (useOliver) {
                        val did = tp.optLong("deliveryId")
                        val before = m.optJSONObject("progress")?.optInt("times", 0) ?: 0
                        var current = m
                        var awards = 0
                        while (tried < W_CASH) {
                            val state = current.optString("status", "")
                            val params = current.optJSONObject("taskParams") ?: break
                            val progress = current.optJSONObject("progress") ?: break
                            if (state == "AWARDING") {
                                tried++
                                val award = call(ctx, "mtop.taobao.pentaprism.scene.award", "1.0", data(
                                    "activityId" to Num(params.optLong("activityId")),
                                    "awardIndex" to Num(params.optLong("awardIndex")),
                                    "deliveryId" to Num(did),
                                    "fromToken" to params.optString("fromToken", ""),
                                    "implId" to params.optString("implId", ""),
                                    "samplingRate" to Num(params.optLong("samplingRate")),
                                    "sceneId" to Num(params.optLong("sceneId")),
                                ))
                                val rewards = award.optJSONObject("model")?.optJSONArray("rewards")
                                var sent = false
                                if (rewards != null) for (k in 0 until rewards.length()) {
                                    if (rewards.optJSONObject(k)?.optString("errorCode") == "SENT") sent = true
                                }
                                if (!sent) { r.skip(title, "领奖未确认发放"); break }
                                awards++
                            } else if (state == "ACCEPTED" && progress.optInt("needTimes", 0) > 0) {
                                tried++
                                val reported = call(ctx, "mtop.taobao.idle.task.uploadOliverTaskTrigger", "1.0", data(
                                    "deliveryId" to did.toString(),
                                    "sceneId" to params.optString("sceneId", ""),
                                    "implId" to Num(System.currentTimeMillis()),
                                ))
                                val after = reported.optJSONObject("taskDTO")?.optJSONObject("progressDTO")?.optInt("times", -1) ?: -1
                                if (after <= progress.optInt("times", 0)) { r.skip(title, "上报后进度未增加"); break }
                            } else break
                            try { Thread.sleep(1200L) } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                                r.skip(title, "任务已中断")
                                break
                            }
                            val refreshed = call(ctx, "mtop.taobao.pentaprism.scene.query", "1.0", data("sceneId" to Num(CASH_SCENE_PENTA.toLong())))
                            val models = refreshed.optJSONArray("model") ?: break
                            var found: JSONObject? = null
                            for (k in 0 until models.length()) {
                                val item = models.optJSONObject(k) ?: continue
                                if (item.optJSONObject("taskParams")?.optLong("deliveryId") == did) { found = item; break }
                            }
                            current = found ?: break
                        }
                        val after = current.optJSONObject("progress")?.optInt("times", before) ?: before
                        if (after > before || awards > 0) { done++; r.ok(title, "进度 $before → $after，领取 $awards 次") }
                        continue
                    }
                    call(
                        ctx, api, "1.0",
                        data(
                            "activityId" to Num(tp.optLong("activityId")),
                            "awardIndex" to Num(tp.optLong("awardIndex")),
                            "deliveryId" to Num(tp.optLong("deliveryId")),
                            "fromToken" to tp.optString("fromToken", ""),
                            "implId" to tp.optString("implId", ""),
                            "samplingRate" to Num(tp.optLong("samplingRate")),
                            "sceneId" to Num(tp.optLong("sceneId")),
                        ),
                    )
                    done++
                    tried++
                    r.ok(title, if (claim) "已领取" else "已上报")
                } catch (e: ApiError) {
                    tried++
                    if (ApiInit.noteFailure(ctx, e.ret, e.message ?: "")) {
                        r.skip("现金任务", "服务端限流，本轮中止")
                        r.state = "触发限流，已暂停"
                        return r
                    }
                    r.skip(title, e.ret)
                }
            }
            if (skipped > 0) r.skip("跳过", "$skipped 条任务不在当前支持的类型中")
            r.state = when {
                done > 0 -> "完成 $done 条"
                skipped > 0 -> "有任务未处理"
                else -> "无任务"
            }
        } catch (e: ApiError) {
            r.fail("查询", e.message ?: e.ret)
        }
        return r
    }

    /**
     * 闲鱼会员/等级页（`/fish-er-home`）能自动做的部分。
     *
     * 抓包（2026-11-01 02:46，用户打开会员页）：
     * ```
     * idle.user.level.page.head   {ipId,topNum}      → 等级页全部数据，含 task[] 和 activeExp
     * idle.user.level.exp.fetch   {expId:1,expType:2} → 领活跃经验（activeExp.fetchable 时才可领）
     * idle.user.asset.medal.list  {medalsAsList,self} → 勋章
     * ```
     * `task[]` 目前是空数组（这账号没有可做的会员任务），所以先把「领活跃经验」
     * 做掉；`task[]` 有内容时把字段打进日志，下一轮照着补 trigger。
     */
    fun member(ctx: Context): Result {
        return rec(ctx, "member") {
            val r = Result("会员中心")
            try {
                // 会员任务不是 level.page.head（那个只给等级/皮肤/活跃经验），
                // 而是 oliver 的 **activityId=102178**（抓包 2026-11-01 02:50，
                // 页面打开时宿主调的就是它，返回 taskList 4 条）。
                val show = call(
                    ctx, "mtop.taobao.idle.oliver.show", "2.0",
                    data(
                        "activityId" to MEMBER_ACTIVITY,
                        "extend" to org.json.JSONObject(),
                        "needBenefits" to true,
                        "needDetails" to false,
                        "needHadWin" to false,
                        "showEmptyInventory" to false,
                        "withInventoryDetail" to false,
                    ),
                )
                val arr = show.optJSONArray("taskList")
                if (arr == null || arr.length() == 0) {
                    r.skip("会员任务", "活动没有任务")
                    r.state = "无任务"
                    return@rec r
                }
                // 领奖要带活动自己的 asac（`activityDO.asac`）—— 抓包实证：
                // 宿主 02:28:24 的 award 请求里带 asac=3A25C19O…，不带它只会回「已领过」/失败
                val actAsac = show.optJSONObject("activityDO")?.optString("asac", "") ?: ""
                var claimed = 0
                var waiting = 0
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    val m = t.optJSONObject("materialDTO")
                    val title = m?.optString("title", "") ?: ""
                    val pg = t.optJSONObject("progressDTO")
                    val times = int(pg?.opt("times"))
                    val maxTimes = int(pg?.opt("maxTimes"), 1)
                    val st = t.optString("idleTaskStatus", "")
                    val done = times >= maxTimes
                    val inst = t.opt("taskInstanceId")?.toString()?.toLongOrNull()
                    if (inst == null || inst <= 0L) {
                        r.skip(title, "缺 taskInstanceId")
                        continue
                    }
                    if (!done) {
                        // cube 任务可以**自己上报完成**：抓包实证（03:02:27，用户点
                        // 「去逛逛个性装扮」）宿主调的就是这个，参数只有
                        // {activityId, taskInstanceId, idempotentKey}，
                        // 回来后 idleTaskStatus 直接变 AWARD。
                        try {
                            call(
                                ctx, "mtop.taobao.idle.cube.task.trigger", "1.0",
                                data(
                                    "activityId" to MEMBER_ACTIVITY,
                                    "taskInstanceId" to Num(inst),
                                    "idempotentKey" to Num(System.currentTimeMillis()),
                                ),
                            )
                            r.ok(title, "已上报完成")
                        } catch (e: ApiError) {
                            waiting++
                            r.skip(title, "上报失败（" + e.ret + "），" + times + "/" + maxTimes)
                            continue
                        }
                    }
                    try {
                        call(
                            ctx, "mtop.taobao.idle.joy.community.activity.task.award", "1.0",
                            data(
                                "activityId" to MEMBER_ACTIVITY,
                                "taskId" to Num(inst),
                                "awardIndexList" to "1",
                                "asac" to actAsac,
                            ),
                        )
                        claimed++
                        r.ok(title, if (st.isEmpty()) "已领" else "已领（$st）")
                    } catch (e: ApiError) {
                        if (e.ret.contains("CANNOT_AWARD") || e.ret.contains("ALREADY")) {
                            r.skip(title, "已领过")
                        } else {
                            r.skip(title, e.ret)
                        }
                    }
                }
                r.state = when {
                    claimed > 0 -> "领到 $claimed 个"
                    waiting > 0 -> "$waiting 个待完成"
                    else -> "无可领"
                }
            } catch (e: ApiError) {
                r.fail("查询", e.message ?: e.ret)
            }
            r
        }
    }

    /**
     * 闲鱼游戏频道任务（`mini.game.task.channel.query`，28 条）。
     *
     * 抓包（03:16，用户打开游戏频道）：
     * ```
     * mini.game.task.channel.query {fishAdCode,gps} → tasks[] 28 条
     *    字段：taskId/activityId/taskType/taskAction/taskStatus/currentValue/taskValue
     *    taskType：level(16) / channel_revisit(2) / channel_click / action(4) / commercial(4)
     * mini.game.task.channel.revisit.complete {reportId:"revisit-<ms>-<rand>", sourceId}
     * ```
     * 目前只接「进频道/桌面入口」这类（channel_revisit）—— 实测
     * `revisit.complete` 回 `{"result":true}`；`level` 类要等抓到游戏内的上报接口。
     */
    fun gameTasks(ctx: Context): Result {
        return rec(ctx, "game") {
            val r = Result("闲鱼游戏")
            try {
                // 游戏赚币是独立列表，先处理，避免频道无任务时提前返回把它跳过。
                val coin = coinGameTasks(ctx, r)
                // 顺手取一次游戏币余额（同一个体系，面板要用）
                try {
                    val pt = call(
                        ctx, "mtop.taobao.idle.mini.game.point.withdraw.query", "1.0", data(),
                    )
                    UserData.point(ctx, int(pt.opt("gamePointBalance")))
                } catch (ignored: ApiError) {
                }
                val q = call(
                    ctx, "mtop.taobao.idle.mini.game.task.channel.query", "1.0", data(),
                )
                val arr = q.optJSONArray("tasks")
                if (arr == null || arr.length() == 0) {
                    r.skip("查询", "频道没有任务")
                    r.state = "无任务"
                    return@rec r
                }
                var done = 0
                var claimed = 0
                var failed = 0
                var skipped = 0
                val gameAsac = try {
                    BusinessParams.resolve(ctx, BusinessParams.TASK_TRIGGER)
                } catch (e: Throwable) {
                    ""
                }
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    val type = t.optString("taskType", "")
                    val action = t.optString("taskAction", "")
                    val st = t.optString("taskStatus", "")
                    val title = t.optString("title", "")
                    val taskId = t.optString("taskId", "")
                    if (st == "COMPLETE" || taskId.isEmpty()) continue

                    // ① 已达成待领 → mini.game.task.award（抓包 03:22:45）
                    if (st == "AWARD") {
                        try {
                            call(
                                ctx, "mtop.taobao.idle.mini.game.task.award", "1.0",
                                data(
                                    "taskId" to taskId,
                                    "taskScene" to t.optString("taskScene", "GAME_CHANNEL"),
                                    "asac" to gameAsac,
                                ),
                            )
                            claimed++
                            r.ok(title, "已领")
                        } catch (e: ApiError) {
                            if (ApiInit.noteFailure(ctx, e.ret, e.message ?: "")) {
                                r.fail(title, "服务端限流，本轮中止")
                                return@rec r
                            }
                            if (e.ret.contains("TASK_NOT_FOUND")) {
                                try {
                                    val activityId = t.optString("activityId", "")
                                    val show = call(ctx, "mtop.taobao.idle.oliver.show", "2.0", data(
                                        "activityId" to activityId, "extend" to JSONObject(),
                                        "needBenefits" to true, "needDetails" to false,
                                        "needHadWin" to false, "showEmptyInventory" to false,
                                        "withInventoryDetail" to false,
                                    ))
                                    val activityAsac = show.optJSONObject("activityDO")?.optString("asac", "") ?: ""
                                    val instance = taskId.toLongOrNull() ?: 0L
                                    if (activityId.isEmpty() || activityAsac.isEmpty() || instance == 0L) {
                                        r.skip(title, "活动领奖参数缺失")
                                    } else {
                                        val award = call(ctx, "mtop.taobao.idle.joy.community.activity.task.award", "1.0", data(
                                            "activityId" to activityId, "taskId" to Num(instance),
                                            "awardIndexList" to "1", "asac" to activityAsac,
                                        ))
                                        val fails = award.optJSONArray("failIndexList")
                                        if (fails != null && fails.length() == 0) {
                                            claimed++; r.ok(title, "活动奖励已领取")
                                        } else r.skip(title, "活动奖励未确认")
                                    }
                                } catch (fallback: ApiError) {
                                    if (ApiInit.noteFailure(ctx, fallback.ret, fallback.message ?: "")) {
                                        r.fail(title, "服务端限流，本轮中止")
                                        return@rec r
                                    }
                                    r.skip(title, "活动领奖失败（${fallback.ret}）")
                                }
                            } else r.skip(title, "领奖失败（" + e.ret + "）")
                        }
                        continue
                    }

                    // ② 进频道类 → revisit.complete（带 sourceId）
                    // ③ 其余（关卡 / 动作 / 点击）→ channel.complete 自助上报
                    //    抓包 03:21:53：{reportId:"click-<ms>-<rand>", taskId}
                    //    → {"completed":true,"currentValue":1,"targetValue":1,"taskStatus":"AWARD"}
                    // 三类上报接口（抓包对应）：
                    //   channel_revisit → revisit.complete {reportId, sourceId}
                    //   channel_click / 无类型 → channel.complete {reportId, taskId}
                    //   action（游戏内动作）→ task.complete
                    //        {action:taskAction, appkey, payload:{value:taskValue},
                    //         reportId:<uuid>, taskId, taskScene}
                    //       抓包 03:37:08：action=baozi10_complete / value=20 → AWARD
                    //   level（过关）→ 还没抓到，跳过
                    val clickable = type.isEmpty() || type == "channel_click"
                    val actType = type == "action" && action.isNotEmpty()
                    // level 类：抓包 03:44:40 —— {appkey, progressValue:1, reportId, taskId, taskScene}
                    // 注意用的是 progressValue，不是 action+payload
                    val levelType = type == "level"
                    if (!clickable && !actType && !levelType && type != "channel_revisit" && type != "commercial") {
                        skipped++
                        r.skip(title, "未识别的任务类型：$type")
                        continue
                    }
                    val res = try {
                        if (type == "commercial") {
                            val response = call(ctx, "mtop.taobao.idle.task.uploadOliverTaskTrigger", "1.0", data(
                                "deliveryId" to taskId,
                                "sceneId" to t.optString("activityId", ""),
                                "implId" to Num(System.currentTimeMillis()),
                            ))
                            val status = response.optJSONObject("taskDTO")?.optString("idleTaskStatus", "") ?: ""
                            JSONObject().put("completed", status == "AWARD").put("taskStatus", status)
                        } else if (type == "channel_revisit") {
                            call(
                                ctx, "mtop.taobao.idle.mini.game.task.channel.revisit.complete", "1.0",
                                data(
                                    "reportId" to "revisit-" + System.currentTimeMillis() + "-" + (1000..9999).random(),
                                    "sourceId" to (if (action.isEmpty()) "zhurukou" else action),
                                ),
                            )
                        } else if (levelType) {
                            val target = if (int(t.opt("taskValue"), 0) > 0) int(t.opt("taskValue")) else 1
                            call(
                                ctx, "mtop.taobao.idle.mini.game.task.complete", "1.0",
                                data(
                                    "appkey" to (t.optJSONObject("game")?.optString("appkey", "") ?: ""),
                                    "progressValue" to Num(target.toLong()),
                                    "reportId" to java.util.UUID.randomUUID().toString(),
                                    "taskId" to taskId,
                                    "taskScene" to t.optString("taskScene", "GAME_CHANNEL"),
                                ),
                            )
                        } else if (actType) {
                            call(
                                ctx, "mtop.taobao.idle.mini.game.task.complete", "1.0",
                                data(
                                    "action" to action,
                                    "appkey" to (t.optJSONObject("game")?.optString("appkey", "") ?: ""),
                                    "payload" to org.json.JSONObject().put(
                                        "value", if (int(t.opt("taskValue"), 0) > 0) int(t.opt("taskValue")) else 1,
                                    ),
                                    "reportId" to java.util.UUID.randomUUID().toString(),
                                    "taskId" to taskId,
                                    "taskScene" to t.optString("taskScene", "GAME_CHANNEL"),
                                ),
                            )
                        } else {
                            call(
                                ctx, "mtop.taobao.idle.mini.game.task.channel.complete", "1.0",
                                data(
                                    "reportId" to "click-" + System.currentTimeMillis() + "-" + (1000..9999).random(),
                                    "taskId" to taskId,
                                ),
                            )
                        }
                    } catch (e: ApiError) {
                        if (ApiInit.noteFailure(ctx, e.ret, e.message ?: "")) {
                            r.fail(title, "服务端限流，本轮中止")
                            return@rec r
                        }
                        failed++
                        r.skip(title, e.ret)
                        continue
                    }
                    val okDone = flag(res.opt("completed")) || flag(res.opt("result"))
                    if (okDone) {
                        done++
                        if (res.optString("taskStatus", "") == "AWARD") {
                            try {
                                val award = call(ctx, "mtop.taobao.idle.mini.game.task.award", "1.0", data(
                                    "taskId" to taskId,
                                    "taskScene" to t.optString("taskScene", "GAME_CHANNEL"),
                                    "asac" to gameAsac,
                                ))
                                if (flag(award.opt("awarded"))) {
                                    claimed++
                                    r.ok(title, "已上报并领取")
                                } else r.skip(title, "已上报，领取结果待核实")
                            } catch (e: ApiError) {
                                if (ApiInit.noteFailure(ctx, e.ret, e.message ?: "")) {
                                    r.fail(title, "服务端限流，本轮中止")
                                    return@rec r
                                }
                                r.skip(title, "已上报，领奖失败（${e.ret}）")
                            }
                        } else r.ok(title, "已上报")
                    } else {
                        failed++
                        r.skip(title, "未完成")
                    }
                    try { Thread.sleep(300L) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    if (done + claimed >= W_GAME_CHANNEL) break
                }
                r.state = when {
                    claimed > 0 || done > 0 -> "上报 $done · 领 $claimed"
                    coin[0] > 0 || coin[1] > 0 -> "赚币 上报 " + coin[0] + " · 领 " + coin[1]
                    failed > 0 -> "本轮未成"
                    skipped > 0 -> "只剩广告任务"
                    else -> "无可做"
                }
                if (coin[0] + coin[1] > 0) {
                    r.ok("游戏赚币合计", "上报 " + coin[0] + " · 领取 " + coin[1])
                    r.state = (r.state + " · 赚币 领 " + coin[1]).trim(' ', '·')
                }
            } catch (e: ApiError) {
                r.fail("查询", e.message ?: e.ret)
            }
            r
        }
    }

    /**
     * 游戏赚币列表：`mini.game.task.center.query`。
     *
     * 每个游戏带一个任务（level / action）。关卡用 progressValue，
     * 动作用 action + payload.value；上报后状态变 AWARD，再用
     * `mini.game.task.award`（只带 taskId）领取。每轮最多 24 个任务（48 次写），间隔 250ms。
     *
     * @return [上报数, 领取数]
     */
    private fun coinGameTasks(ctx: Context, r: Result): IntArray {
        var done = 0
        var awarded = 0
        try {
            val center = call(ctx, "mtop.taobao.idle.mini.game.task.center.query", "1.0", data())
            val games = center.optJSONArray("games") ?: return intArrayOf(0, 0)
            for (i in 0 until games.length()) {
                if (done + awarded >= W_GAME_COIN || ApiInit.paused(ctx) || Thread.currentThread().isInterrupted) break
                val g = games.optJSONObject(i) ?: continue
                val t = g.optJSONObject("task") ?: continue
                val taskId = t.optString("taskId", "")
                if (taskId.isEmpty()) continue
                val st = t.optString("taskStatus", "")
                if (st == "COMPLETE") continue
                val title = t.optString("title", "游戏赚币")
                if (st == "ACCEPTED") {
                    val type = t.optString("taskType", "")
                    val value = int(t.opt("taskValue"), 1)
                    try {
                        val res = when (type) {
                            "action" -> call(ctx, "mtop.taobao.idle.mini.game.task.complete", "1.0", data(
                                "action" to t.optString("taskAction", ""),
                                "appkey" to g.optString("appkey", ""),
                                "payload" to JSONObject().put("value", value),
                                "reportId" to java.util.UUID.randomUUID().toString(),
                                "taskId" to taskId,
                            ))
                            "level", "duration" -> call(ctx, "mtop.taobao.idle.mini.game.task.complete", "1.0", data(
                                "appkey" to g.optString("appkey", ""),
                                "progressValue" to Num(value.toLong()),
                                "reportId" to java.util.UUID.randomUUID().toString(),
                                "taskId" to taskId,
                            ))
                            else -> {
                                r.skip("赚币·" + title, "未识别类型：" + type)
                                continue
                            }
                        }
                        if (flag(res.opt("completed")) || res.optString("taskStatus") == "AWARD") {
                            done++
                            r.ok("赚币·" + title, "已上报")
                        } else {
                            r.skip("赚币·" + title, "上报未完成")
                            continue
                        }
                    } catch (e: ApiError) {
                        if (ApiInit.noteFailure(ctx, e.ret, e.message ?: "")) break
                        r.skip("赚币·" + title, e.ret)
                        continue
                    }
                }
                try {
                    val award = call(ctx, "mtop.taobao.idle.mini.game.task.award", "1.0", data("taskId" to taskId))
                    if (flag(award.opt("awarded"))) {
                        awarded++
                        r.ok("赚币·" + title, "已领取")
                    } else r.skip("赚币·" + title, "领奖未确认")
                } catch (e: ApiError) {
                    if (ApiInit.noteFailure(ctx, e.ret, e.message ?: "")) break
                    r.skip("赚币·" + title, e.ret)
                }
                try { Thread.sleep(250L) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        } catch (e: ApiError) {
            r.skip("游戏赚币", "查询失败（" + e.ret + "）")
        }
        return intArrayOf(done, awarded)
    }

    /**
     * 主动拉一次用户数据（闲鱼币 / 现金红包 / 游戏币 / 等级）。
     *
     * 为什么需要它：这三个值以前只在「寻宝/现金/会员」链跑到时才写快照，
     * 一旦那条链被跳过（没骰子、没红包、已经做过），面板就一直是「—」。
     * 启动时先查一次，之后每次执行也会更新。
     *
     * 每个查询各自兜底：一个接口挂了不影响其它三个数字。
     */
    @JvmStatic
    fun refreshUserData(ctx: Context): String {
        var ok = 0
        // 闲鱼币：寻宝地图的 userInfo.coinNum（最权威的余额来源）
        try {
            val ui = refreshMap(ctx)
            val coin = int(ui?.opt("coinNum"))
            if (coin > 0) {
                UserData.coin(ctx, coin)
                ok++
            }
        } catch (ignored: Throwable) {
        }
        // 现金红包
        try {
            val asac = requireAsacQuiet(ctx, BusinessParams.CASH_MAIN)
            val m = call(
                ctx, "mtop.idle.play.signin.cash.main", "1.0", data("asac" to asac),
            )
            UserData.cash(
                ctx, int(m.opt("signCashNum")), int(m.opt("signCashThreshold")),
                int(m.opt("redEnvNum")),
            )
            ok++
        } catch (ignored: Throwable) {
        }
        // 游戏币
        try {
            val pt = call(ctx, "mtop.taobao.idle.mini.game.point.withdraw.query", "1.0", data())
            UserData.point(ctx, int(pt.opt("gamePointBalance")))
            ok++
        } catch (ignored: Throwable) {
        }
        // 等级/经验
        try {
            val h = call(
                ctx, "mtop.idle.user.level.page.head", "1.0", data("ipId" to "107", "topNum" to 5),
            )
            UserData.level(ctx, int(h.opt("level")), int(h.opt("exp")))
            ok++
        } catch (ignored: Throwable) {
        }
        return "数据 $ok/4"
    }

    /** 活跃经验独立于会员 cube 活动；即使活动无任务也必须检查。 */
    fun experience(ctx: Context): Result = rec(ctx, "experience") {
        val r = Result("活跃经验")
        try {
            val head = call(ctx, "mtop.idle.user.level.page.head", "1.0", data("ipId" to "107", "topNum" to 5))
            UserData.level(ctx, int(head.opt("level")), int(head.opt("exp")))
            val active = head.optJSONObject("activeExp")
            if (active == null || !flag(active.opt("fetchable"))) {
                r.skip("领取", "当前没有可领取的活跃经验")
                r.state = "无可领"
            } else {
                // expId/expType 是该领取入口的协议参数；服务端下发时优先采用。
                call(ctx, "mtop.idle.user.level.exp.fetch", "1.0", data(
                    "expId" to Num(active.optLong("expId", 1L)),
                    "expType" to Num(active.optLong("expType", 2L)),
                ))
                val after = call(ctx, "mtop.idle.user.level.page.head", "1.0", data("ipId" to "107", "topNum" to 5))
                UserData.level(ctx, int(after.opt("level")), int(after.opt("exp")))
                val remaining = after.optJSONObject("activeExp")
                if (remaining != null && !flag(remaining.opt("fetchable"))) {
                    r.ok("领取", "已领取 ${int(active.opt("exp"))} 活跃经验，回读已不可领")
                    r.state = "已领活跃经验"
                } else {
                    r.skip("核验", "已提交领取，回读尚未确认；本轮不重发")
                    r.state = "领取待确认"
                }
            }
        } catch (e: ApiError) {
            r.fail("活跃经验", e.message ?: e.ret)
        }
        r
    }

    /** 取 asac，失败返回空串（查询类接口大多允许空 asac，别因此整块跳过） */
    private fun requireAsacQuiet(ctx: Context, scope: BusinessParams.Scope): String {
        return try {
            BusinessParams.resolve(ctx, scope)
        } catch (t: Throwable) {
            ""
        }
    }

    // ================================================================ 4 寻宝地图    // ================================================================ 4 寻宝地图

    /**
     * 寻宝地图掷骰子。
     *
     * 关键（callbook 4.5）：
     *   - locationBefore 必须等于 game.refresh 的 userInfo.location，防重放
     *   - mapVersion 取 init 的 11
     *   - diceNum=0 时先做任务攒骰子，不能硬掷
     *   - 每掷一次位置就变，必须重新 refresh 再掷下一次
     */
    fun treasure(ctx: Context, maxRolls: Int = 3): Result {
        return rec(ctx, "treasure") {
            treasureInner(ctx, maxRolls)
        }
    }

    /** 寻宝地图的版本号：抓包里宿主一直传 11，改版时这里要跟着变 */
    private const val MAP_VERSION = "11"

    /** 背包里的可前进道具（`ROCKET_DICE` / `DOUBLE_DICE` 这类） */
    private class DiceProp(
        @JvmField val id: Int,
        @JvmField val configId: Int,
        @JvmField val type: String,
        @JvmField val name: String,
        @JvmField var amount: Int,
    )

    /**
     * 读背包里的骰子类道具。
     *
     * 接口：`play.prop.list`，`{"bizCode":"coinMap","pageNumber":1,"pageSize":50}`
     * （参数照抄抓包里宿主自己发的，bizCode 就是地图的 mapGroupCode）。
     *
     * 道具**不是**用 `play.prop.use` 消耗的 —— 骰子类直接在 roll 里带
     * `diceId=configId` + `diceType=propType`（实测宿主 `diceId:2 / ROCKET_DICE`）。
     */
    private fun diceProps(ctx: Context, r: Result?): ArrayList<DiceProp> {
        val out = ArrayList<DiceProp>()
        try {
            val res = call(
                ctx, "mtop.taobao.idle.play.prop.list", "1.0",
                data("bizCode" to "coinMap", "pageNumber" to 1, "pageSize" to 50),
            )
            val arr = res.optJSONArray("propInfos") ?: return out
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val amount = int(p.opt("amount"))
                val type = p.optString("propType", "")
                if (amount <= 0 || !type.endsWith("_DICE")) continue
                out.add(
                    DiceProp(
                        int(p.opt("id")), int(p.opt("configId")), type,
                        p.optString("name", type), amount,
                    ),
                )
            }
        } catch (e: ApiError) {
            // 背包读不到不影响走步数，别把它记成失败
            if (r != null) r.ok("背包", "读取失败（${e.ret}），本轮只用步数")
        }
        return out
    }

    /** 发一次 roll。`diceId` 只在用道具时带 —— 步数模式带上会被当成「用道具」 */
    private fun rollMap(ctx: Context, location: Int, diceType: String, diceId: Int): JSONObject {
        val body = if (diceId > 0) {
            data(
                "diceId" to Num(diceId.toLong()),
                "diceType" to diceType,
                "locationBefore" to Num(location.toLong()),
                "mapVersion" to MAP_VERSION,
                "outId" to Num(System.currentTimeMillis()),
                "version" to 3,
            )
        } else {
            data(
                "diceType" to diceType,
                "locationBefore" to Num(location.toLong()),
                "mapVersion" to MAP_VERSION,
                "outId" to Num(System.currentTimeMillis()),
                "version" to 3,
            )
        }
        return call(ctx, "mtop.taobao.idle.treasure.hunt.map.roll", "1.0", body)
    }

    /**
     * 触发一条 oliver 任务。
     *
     * 前端有两套写法（`pages/home/index.js`）：
     * ```
     *   N7(e,t): {deliveryId:e, implId:t, sceneId:496, asac}
     *   N9(e,t): {deliveryId:t, sceneId:e, asac}            ← 不带 implId
     * ```
     * 先用带 `implId` 的（更贴近宿主），只在 `COMMON_PARAM_ILLEGAL` 时退回不带的那种
     * —— 这条错误正是「少传/传错字段」的表现，值得换一种形状再试一次。
     */
    private fun triggerTask(
        ctx: Context,
        scene: Int,
        deliveryId: Long,
        implId: String,
    ): JSONObject {
        if (implId.startsWith("other_36_")) {
            return call(ctx, "mtop.taobao.pentaprism.scene.trigger", "1.0", data(
                "deliveryId" to Num(deliveryId), "implId" to implId,
                "sceneId" to Num(scene.toLong()),
            ))
        }
        return call(ctx, "mtop.taobao.idle.task.uploadOliverTaskTrigger", "1.0", data(
            "deliveryId" to deliveryId.toString(), "sceneId" to scene.toString(),
            "implId" to Num(System.currentTimeMillis()),
        ))
    }

    /**
     * 桌面上有没有闲鱼币小组件。
     *
     * 这是「添加闲鱼币到桌面」这个任务的**申报渠道**：宿主查任务列表时会带
     * `isWidgetInstalled`（抓包实证：加了组件后宿主传 true），服务端据此判定完成。
     * 我们一直硬编码 false，所以这条任务永远做不成。
     *
     * 用 `AppWidgetManager.installedProviders` 判断，不猜具体 Provider 类名
     * （类名混淆过，而且不同版本不一样）。
     */
    private fun widgetInstalled(ctx: Context): Boolean {
        return try {
            val mgr = android.appwidget.AppWidgetManager.getInstance(ctx)
            val providers = mgr.installedProviders ?: return false
            for (p in providers) {
                if (p.provider != null && p.provider.packageName == ctx.packageName) return true
            }
            false
        } catch (t: Throwable) {
            false
        }
    }

    private fun refreshMap(ctx: Context): JSONObject? = try {
        call(ctx, "mtop.taobao.idle.treasure.hunt.map.game.refresh", "1.0", data())
            .optJSONObject("refreshGameData")?.optJSONObject("userInfo")
    } catch (e: ApiError) {
        null
    }

    private fun treasureInner(ctx: Context, maxRolls: Int): Result {
        val r = Result("寻宝地图")
        try {
            val ui = refreshMap(ctx) ?: throw ApiError("NO_USERINFO", "刷新地图失败")
            // 三种前进资源：步数（点宝贝攒的 remainStep）、骰子（任务给的 diceNum）、
            // 背包道具（ROCKET_DICE / DOUBLE_DICE，roll 时带 diceId）。
            var dice = int(ui.opt("diceNum"))
            var steps = int(ui.opt("remainStep"))
            var location = int(ui.opt("location"))
            val coinBefore = int(ui.opt("coinNum"))
            // 背包道具：骰子用完可能又掉新道具，所以下面每用掉一个就重读一次
            var props = diceProps(ctx, r)
            r.ok(
                "状态",
                "道具 " + props.size + " 种｜骰子 " + dice + "｜步数 " + steps +
                    "｜位置 " + location + "｜闲鱼币 " + coinBefore,
            )

            var rolled = 0
            var gained = 0
            var byProp = 0
            while (rolled < maxRolls) {
                var type = ""
                var id = 0
                var from = ""
                // 顺序：**先投骰子**（换步数）→ 再走步数 → 最后用道具。
                // 前进本身会随机掉道具（多面骰/好运骰…），那些道具又能继续前进，
                // 所以道具放在最后一环，走一轮就能把「骰子→步数→道具→再前进」串起来。
                var p: DiceProp? = null
                if (dice > 0) {
                    type = "FORWARD_STEP"
                    from = "骰子"
                } else if (steps > 0) {
                    type = "FORWARD_STEP"
                    from = "步数"
                } else {
                    for (x in props) {
                        if (x.amount > 0) {
                            p = x
                            break
                        }
                    }
                    if (p != null) {
                        type = p.type
                        id = p.configId
                        from = p.name
                        p.amount = p.amount - 1
                    } else {
                        break
                    }
                }
                val res = rollMap(ctx, location, type, id)
                gained += int(res.opt("stepCount"))
                rolled++
                if (p != null) byProp++
                // 位置变了，locationBefore 必须是新值 —— 不刷新下一次必然参数错
                // 前进可能随机掉道具（多面骰/好运骰），用掉一个就重读背包，
                // 否则新掉的道具这一轮永远用不上
                if (p != null || byProp == 0) props = diceProps(ctx, null)
                val ui2 = refreshMap(ctx) ?: break
                val newLoc = int(ui2.opt("location"))
                r.ok("前进", from + "：位置 " + location + " → " + newLoc + "（" + type + "）")
                location = newLoc
                dice = int(ui2.opt("diceNum"))
                steps = int(ui2.opt("remainStep"))
            }
            if (rolled == 0) {
                r.skip("前进", "没有道具、骰子、步数，先在任务中心攒")
                r.state = "无可用资源"
                return r
            }
            val uiAfter = refreshMap(ctx)
            val coinAfter = int(uiAfter?.opt("coinNum"))
            if (coinAfter > 0) UserData.coin(ctx, coinAfter)
            r.ok(
                "合计",
                rolled.toString() + " 次（道具 " + byProp + " 次），前进 " + gained + " 步，剩余 " + steps + " 步" +
                    if (coinAfter != coinBefore) "，闲鱼币 " + coinBefore + "→" + coinAfter else "",
            )
            r.state = "前进 " + rolled + " 次"
        } catch (e: ApiError) {
            r.fail("前进", e.message ?: e.ret)
        }
        return r
    }

    /**
     * 圈数挑战池：`interact.cycle.challenge.info` → `...reward`。
     *
     * 档位表 `tierList[]` 带 `cycle` / `status`（`AWARD` 已领、`CHALLENGING` 当前、
     * `LOCKED` 未解锁）。判据：**当前圈数 ≥ 档位圈数且不是 AWARD** 才领 ——
     * 提前领只会拿到一个业务错误，白挨一次请求。
     */
    fun cycle(ctx: Context): Result {
        return rec(ctx, "cycle") {
            val r = Result("圈数挑战池")
            try {
                val info = call(ctx, "mtop.idle.interact.cycle.challenge.info", "1.0", data())
                val recordId = info.optString("recordId", "")
                val cur = int(info.opt("currentCycle"))
                val arr = info.optJSONArray("tierList")
                if (arr == null || arr.length() == 0) {
                    r.skip("查询", "没有档位信息")
                    r.state = "无档位"
                    return@rec r
                }
                var claimed = 0
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    val cyc = int(t.opt("cycle"))
                    val st = t.optString("status", "")
                    if (st == "AWARD") {
                        r.skip("第 " + cyc + " 圈", "已领")
                        continue
                    }
                    if (cur < cyc) {
                        r.skip("第 " + cyc + " 圈", "还差 " + (cyc - cur) + " 圈")
                        continue
                    }
                    if (recordId.isEmpty()) {
                        r.skip("第 " + cyc + " 圈", "缺 recordId")
                        continue
                    }
                    try {
                        call(
                            ctx, "mtop.idle.interact.cycle.challenge.reward", "1.0",
                            data(
                                "cycle" to Num(cyc.toLong()),
                                "recordId" to recordId,
                            ),
                        )
                        claimed++
                        r.ok("第 " + cyc + " 圈", t.optString("rewardTitle", "已领取"))
                    } catch (e: ApiError) {
                        if (e.ret.contains("AWARD") || e.ret.contains("ALREADY")) {
                            r.skip("第 " + cyc + " 圈", "已领过")
                        } else {
                            r.skip("第 " + cyc + " 圈", e.ret)
                        }
                    }
                }
                r.state = if (claimed > 0) "领到 " + claimed + " 档" else "第 " + cur + " 圈，无可领"
            } catch (e: ApiError) {
                r.fail("查询", e.message ?: e.ret)
            }
            return@rec r
        }
    }

    /**
     * 背包：列出道具，**非骰子类直接使用**（`play.prop.use` 只发权益）。
     *
     * 骰子类不在这一条里用 —— 它们要在寻宝地图的 roll 里带 `diceId`，
     * 由 [treasureInner] 自动消耗，这里只报数量。
     */
    fun props(ctx: Context): Result {
        return rec(ctx, "props") {
            val r = Result("背包")
            try {
                val res = call(
                    ctx, "mtop.taobao.idle.play.prop.list", "1.0",
                    data("bizCode" to "coinMap", "pageNumber" to 1, "pageSize" to 50),
                )
                val arr = res.optJSONArray("propInfos")
                if (arr == null || arr.length() == 0) {
                    r.skip("查询", "背包是空的")
                    r.state = "空"
                    return@rec r
                }
                var used = 0
                var diceN = 0
                val diceNames = StringBuilder()
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    val amount = int(p.opt("amount"))
                    if (amount <= 0) continue
                    val type = p.optString("propType", "")
                    val name = p.optString("name", type)
                    if (type.isEmpty()) {
                        r.skip(name, "道具类型缺失，未使用")
                        continue
                    }
                    if (type.endsWith("_DICE")) {
                        diceN += amount
                        diceNames.append(name).append("×").append(amount).append(' ')
                        continue
                    }
                    try {
                        call(
                            ctx, "mtop.taobao.idle.play.prop.use", "1.0",
                            data(
                                "bizCode" to p.optString("bizCode", "coinMap"),
                                "configId" to Num(int(p.opt("configId")).toLong()),
                                "id" to Num(int(p.opt("id")).toLong()),
                                "outId" to Num(System.currentTimeMillis()),
                            ),
                        )
                        used++
                        r.ok(name + "×" + amount, "已使用")
                    } catch (e: ApiError) {
                        r.skip(name + "×" + amount, "用不了（" + e.ret + "）")
                    }
                }
                if (diceN > 0) r.ok("骰子类", diceNames.toString().trim() + "，由寻宝地图使用")
                r.state = when {
                    used > 0 -> "用了 " + used + " 种"
                    diceN > 0 -> "骰子 " + diceN + " 个待用"
                    else -> "无可使用道具"
                }
            } catch (e: ApiError) {
                r.fail("查询", e.message ?: e.ret)
            }
            return@rec r
        }
    }

    // ================================================================ 5 任务领奖

    /**
     * 任务中心领奖（oliver 引擎）。
     *
     * 数据来源是新抓包补上的一条完整链路（2026-10-31 实测）：
     * ```
     * task.queryolivertasklist(sceneId=496)   → taskList[].idleTaskStatus
     *   idleTaskStatus=AWARD  → task.getolivertaskbenefit(deliveryId)   ← 直接领
     *   idleTaskStatus=ACCEPTED → 要先去做任务，调了也报 CANNOT_AWARD
     * ```
     * 判定必须用 `idleTaskStatus` 字符串，**不能**用 `idleTaskStatusValue`：
     * 同一个 deliveryId 在不同时间 value 会变（AWARD=1 / COMPLETE=2），
     * 而服务端只认字符串。踩过 —— 按 value 判会把已领过的再领一遍，
     * 返回 `FAIL_BIZ_ACTIVITY_TASK_CANNOT_AWARD`（「领奖失败，请稍后再试~」），
     * 文案完全不提「已经领过」。
     *
     * 领奖需要 `asac`（缺了 FAIL_SYS_BIZPARAM_MISSED），所以整条链先预检。
     */
    fun tasks(ctx: Context, scene: Int = TASK_SCENE): Result {
        return rec(ctx, "tasks") {
            tasksInner(ctx, scene)
        }
    }

    /** 任务中心场景 id：496 = 闲鱼币任务中心（抓包实测，全库唯一） */
    private const val TASK_SCENE = 496

    /**
     * 现金红包页用的是**同一套 oliver 任务**，只是 sceneId 不同。
     *
     * 实测 `queryolivertasklist {sceneId:7022}` 返回 deliveryId 61676
     * （`cash.main` 里的 timeTask 就是它），times 1/2。所以「现金任务链」
     * 不是新接口，而是同一套链换个 scene。
     */
    private const val CASH_SCENE = 7022

    /** 现金红包页任务所在的 pentaprism 场景（抓包实测） */
    private const val CASH_SCENE_PENTA = 7066

    /** 闲鱼会员中心的任务活动 id（抓包实证：oliver.show activityId=102178） */
    private const val MEMBER_ACTIVITY = "102178"

    private fun tasksInner(ctx: Context, scene: Int = TASK_SCENE): Result {
        val r = Result("任务领奖")
        val asac = requireAsac(ctx, r, "领奖", BusinessParams.TASK_BENEFIT) ?: return r
        val list = try {
            call(
                ctx, "mtop.taobao.idle.task.queryolivertasklist", "2.0",
                data(
                    "sceneId" to Num(scene.toLong()),
                    "platform" to "Android",
                    "osVersion" to android.os.Build.VERSION.RELEASE,
                    "brand" to android.os.Build.BRAND,
                    "isWidgetInstalled" to false,
                ),
            )
        } catch (e: ApiError) {
            r.fail("查询任务", e.message ?: e.ret)
            return r
        }

        val arr = list.optJSONArray("taskList")
        if (arr == null || arr.length() == 0) {
            r.skip("查询任务", "任务列表为空")
            r.state = "无任务"
            return r
        }

        // 先扫一遍统计，再逐个领 —— 用户能一眼看出「24 个任务里 2 个可领」
        var claimable = 0
        var accepted = 0
        val todo = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val st = t.optString("idleTaskStatus", "")
            if (st == "AWARD") {
                claimable++
                todo.add(t)
            } else if (st == "ACCEPTED") {
                accepted++
            }
        }
        r.ok("查询任务", "共 ${arr.length()} 个，可领 $claimable，待完成 $accepted")
        if (claimable == 0) {
            r.skip("领奖", "没有待领取的任务（待完成的要先去做）")
            r.state = "无可领"
            return r
        }

        var got = 0
        var failed = 0
        val claimed = ArrayList<Long>()
        for (t in todo) {
            val deliveryId = t.opt("deliveryId") ?: continue
            val m = t.optJSONObject("materialDTO")
            val title = if (m == null) "" else m.optString("title", "")
            val desc = if (m == null) "" else m.optString("desc", "")
            try {
                val did = deliveryId.toString().toLongOrNull() ?: 0L
                // fromToken 是这条任务的会话票据（每次查询都变），带上更符合宿主行为
                val tok = t.optString("fromToken", "")
                call(
                    ctx, "mtop.taobao.idle.task.getolivertaskbenefit", "1.0",
                    data(
                        "asac" to asac,
                        "deliveryId" to Num(did),
                        "sceneId" to Num(scene.toLong()),
                        "fromToken" to tok,
                    ),
                )
                got++
                if (did > 0L) claimed.add(did)
                r.ok(if (title.isEmpty()) "领奖" else title, if (desc.isEmpty()) "已领取" else desc)
            } catch (e: ApiError) {
                if (ApiInit.noteFailure(ctx, e.ret, e.message ?: "")) {
                    r.skip("领奖", "服务端限流，本轮中止")
                    r.state = "触发限流，已暂停"
                    return r
                }
                if (e.ret.contains("PARAM_ILLEGAL")) {
                    // 单条任务要额外业务参数时领奖也回这个错。**逐条跳过，不终止整轮**
                    // —— 一条注定失败的任不该挡住后面能领的那些。
                    val did = deliveryId.toString().toLongOrNull() ?: 0L
                    if (did > 0L) ParamGuard.mark(ctx, blackKey(did, scene))
                    r.skip(if (title.isEmpty()) "领奖" else title, "领奖要额外参数，跳过")
                    continue
                }
                // 已领过 / 状态不符 —— 服务端只回通用文案，按「跳过」处理不报错
                if (e.ret.contains("CANNOT_AWARD") || e.ret.contains("ALREADY")) {
                    r.skip(if (title.isEmpty()) "领奖" else title, "已经领过了")
                } else {
                    failed++
                    r.fail(if (title.isEmpty()) "领奖" else title, e.message ?: e.ret)
                }
            }
        }
        // ---- 回读核验：接口返回 COMPLETE 不等于服务端真置了完成态 ----
        //
        // 这是「面板说已领、页面上还挂着去完成」的根因：领奖接口的成功只代表
        // 这次请求被受理。所以按芝麻粒 `TaskAlternative.g` 的做法重查列表，
        // 只有**任务从列表里消失或变成 COMPLETE** 才算真领到。
        var verified = got
        if (claimed.size > 0) {
            RunProgress.step("正在回读领奖结果")
            try {
                Thread.sleep(2500L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            try {
                val re = call(
                    ctx, "mtop.taobao.idle.task.queryolivertasklist", "2.0",
                    data(
                        "sceneId" to Num(scene.toLong()),
                        "platform" to "Android",
                        "osVersion" to android.os.Build.VERSION.RELEASE,
                        "brand" to android.os.Build.BRAND,
                        "isWidgetInstalled" to widgetInstalled(ctx),
                    ),
                )
                var stillThere = 0
                val arr2 = re.optJSONArray("taskList")
                if (arr2 != null) {
                    for (i in 0 until arr2.length()) {
                        val t2 = arr2.optJSONObject(i) ?: continue
                        val id2 = t2.opt("deliveryId")?.toString()?.toLongOrNull() ?: continue
                        if (!claimed.contains(id2)) continue
                        val state2 = t2.optString("idleTaskStatus", "")
                        // 多轮视频领奖后回到 ACCEPTED，表示已领本次、可继续下一次。
                        val pg2 = t2.optJSONObject("progressDTO")
                        val progressed = (pg2?.optInt("times", 0) ?: 0) > 0
                        val remaining = pg2?.optInt("needTimes", 0) ?: 0
                        if (state2 != "COMPLETE" && !(state2 == "ACCEPTED" && progressed && remaining > 0)) stillThere++
                    }
                }
                verified = claimed.size - stillThere
                r.ok(
                    "核验",
                    "$got 个领奖请求里 $verified 个真的完成" +
                        if (stillThere > 0) "，$stillThere 个服务端没认（仍在列表里）" else "",
                )
            } catch (e: ApiError) {
                r.ok("核验", "回读失败（" + e.ret + "），按接口返回记")
            }
        }
        if (got > 0) r.ok("合计", "领到 $got 个任务奖励")
        if (failed > 0) r.fail("合计", "$failed 个领取失败")
        r.state = when {
            got > 0 && verified == got -> "已领 $got 个"
            got > 0 -> "领 $verified/$got 个"
            failed > 0 -> "领奖失败"
            else -> "已领过"
        }
        return r
    }

    // ================================================================ 6 多日挑战

    /**
     * 多日挑战领奖（`joy.community.activity.task.award`）。
     *
     * 进度配置：3 / 6 / 10 次任务各一档奖励（100 / 300 / 500 闲鱼币）。
     * `subProgressInfos[].status`：0=未达成、1=可领、2=已领。
     *
     * ⚠️ 实测坑：`status=2`（已领）时再调领奖，服务端返回的**不是**幂等提示，
     * 而是 `FAIL_BIZ_ACTIVITY_TASK_CANNOT_AWARD`（「领奖失败，请稍后再试~」）。
     * 所以必须先在本地按 status 筛，只对 `status==1` 的调接口。
     *
     * 参数形状与 oliver 领奖不同（同一族接口两种约定，别混）：
     *   oliver   → `deliveryId`（数字）+ `sceneId`
     *   joy      → `activityId` + `taskId`（数字）+ `awardIndexList`（字符串）
     */
    fun challenge(ctx: Context): Result {
        return rec(ctx, "challenge") {
            challengeInner(ctx)
        }
    }

    private fun challengeInner(ctx: Context): Result {
        val r = Result("多日挑战")
        // 当前公开包装器不提供旧 awardIndexList 链所需的 asac，不借用任务或活动值。
        val asac = requireAsac(ctx, r, "领奖", BusinessParams.CHALLENGE_AWARD) ?: return r
        val info = try {
            call(ctx, "mtop.idle.multi.day.challenge.info", "1.0", data())
        } catch (e: ApiError) {
            r.fail("查询进度", e.message ?: e.ret)
            return r
        }
        val dto = info.optJSONObject("idleTaskDTO")
            ?: info.optJSONObject("idleJoyTaskDTO")
        if (dto == null) {
            // 活动未开放时接口返回空对象，属于当前没有挑战而非查询失败。
            if (info.length() == 0) {
                r.skip("查询进度", "当前没有开放的多日挑战")
                r.state = "暂无挑战"
            } else {
                r.fail("查询进度", "响应结构不认识（缺少任务信息）")
            }
            return r
        }
        val ext = dto.optJSONObject("extend")
        val activityId = if (ext == null) "" else ext.optString("activityId", "")
        val taskId = if (ext == null) "" else ext.optString("taskInstanceId", "")
        val prog = dto.optJSONObject("idleJoyTaskProgressDTO")
        val subs = if (prog == null) null else prog.optJSONArray("subProgressInfos")
        if (activityId.isEmpty() || taskId.isEmpty() || subs == null) {
            r.fail("查询进度", "响应结构不认识（activityId / taskInstanceId 缺失）")
            return r
        }

        var done = 0
        var ready = 0
        val claim = ArrayList<Int>()
        for (i in 0 until subs.length()) {
            val s = subs.optJSONObject(i) ?: continue
            val st = int(s.opt("status"))
            val idx = int(s.opt("index"))
            if (st == 2) done++
            if (st == 1) {
                ready++
                claim.add(idx)
            }
        }
        r.ok("查询进度", "已领 $done 档，可领 $ready 档")
        if (claim.isEmpty()) {
            r.skip("领奖", "没有可领的档位（要再做任务攒次数）")
            r.state = "已领 $done 档"
            return r
        }

        var got = 0
        for (idx in claim) {
            try {
                call(
                    ctx, "mtop.taobao.idle.joy.community.activity.task.award", "1.0",
                    data(
                        "asac" to asac,
                        "activityId" to activityId,
                        "taskId" to Num(taskId.toLongOrNull() ?: 0L),
                        "awardIndexList" to idx.toString(),
                    ),
                )
                got++
                r.ok("第 $idx 档", "已领取")
            } catch (e: ApiError) {
                if (e.ret.contains("CANNOT_AWARD")) {
                    r.skip("第 $idx 档", "已经领过了")
                } else {
                    r.fail("第 $idx 档", e.message ?: e.ret)
                }
            }
        }
        r.state = if (got > 0) "领 $got 档" else "已领过"
        return r
    }

    // ================================================================ 7 圈子

    /**
     * 圈子签到：遍历**已加入的全部圈子**（2026-10-31 实测账号有 5 个，
     * 每个每日 +5 成长值；只签一个是白丢奖励）。
     *
     * 圈子列表来自 `circle.user.circle.list.show` + `queryType=JOINED`，
     * 分页 pageSize=20；hasNext=true 时翻页（一般 5 个一页够）。
     *
     * 签到接口幂等：已签返回 `signStatus=ALREADY_SIGNED`，按跳过处理。
     */
    fun circle(ctx: Context): Result {
        return rec(ctx, "circle") {
            circleInner(ctx)
        }
    }

    private fun circleInner(ctx: Context): Result {
        val r = Result("圈子任务")
        // ---- 1. 拉已加入的圈子 ----
        val ids = ArrayList<Pair<String, String>>()   // circleId → 名字
        var pageNum = 1
        while (ids.size < 60) {   // 防御性上限，正常一页就完
            try {
                val page = call(
                    ctx, "mtop.idle.circle.user.circle.list.show", "1.0",
                    data(
                        "pageNum" to pageNum,
                        "pageSize" to 20,
                        "queryType" to "JOINED",
                    ),
                )
                val arr = page.optJSONArray("list")
                val n = arr?.length() ?: 0
                for (i in 0 until n) {
                    val o = arr!!.optJSONObject(i) ?: continue
                    val id = o.optString("circleId", "")
                    if (id.isEmpty()) continue
                    ids.add(Pair(id, o.optString("circleName", "")))
                }
                if (!flag(page.opt("hasNext")) || n == 0) break
                pageNum++
            } catch (e: ApiError) {
                r.fail("圈子列表", e.message ?: e.ret)
                return r
            }
        }
        if (ids.isEmpty()) {
            r.skip("圈子列表", "没有已加入的圈子")
            r.state = "无已加入圈子"
            return r
        }
        r.ok("圈子列表", "${ids.size} 个已加入")

        // ---- 2. 逐个签到 ----
        var got = 0
        var already = 0
        var failed = 0
        for ((circleId, name) in ids) {
            try {
                val res = call(
                    ctx, "mtop.taobao.idle.circle.point.sign.in", "1.0",
                    data("circleId" to circleId),
                )
                val status = res.optString("signStatus")
                val growth = int(res.opt("awardedGrowth"))
                val total = int(res.opt("totalGrowth"))
                if (status == "ALREADY_SIGNED" || flag(res.opt("dailyLimitReached"))) {
                    already++
                    val total = int(res.opt("totalGrowth"), -1)
                    r.skip(name, if (total >= 0) "今日已签（累计 $total）" else "今日已签")
                } else if (status == "SIGNED") {
                    got++
                    r.ok(name, "+$growth 成长值，累计 $total")
                } else {
                    got++
                    r.ok(name, "状态 $status，累计 $total")
                }
            } catch (e: ApiError) {
                // 已签也可能是服务端报错形态，认出来按跳过
                if (e.ret.contains("ALREADY") || e.ret.contains("SIGNED")) {
                    already++
                    r.skip(name, "今日已签")
                } else {
                    failed++
                    r.fail(name, e.message ?: e.ret)
                }
            }
        }
        // 圈子任务完成后自动增加成长值，无须领奖；具体上报触发链尚未确认。
        // 只展示实时状态，避免把查询或商品详情请求误报成完成任务。
        var pending = 0
        var taskQueryFailed = 0
        for ((circleId, name) in ids) {
            try {
                val home = call(ctx, "mtop.taobao.idle.circle.level.home", "1.0", data("circleId" to circleId))
                val tasks = home.optJSONObject("taskModule")?.optJSONArray("taskList")
                if (tasks == null) {
                    taskQueryFailed++
                    r.fail("$name · 圈子任务", "任务列表缺失")
                    continue
                }
                for (i in 0 until tasks.length()) {
                    val task = tasks.optJSONObject(i) ?: continue
                    if (flag(task.opt("finished")) || task.optString("buttonStatus") == "CLAIMED") continue
                    val title = task.optString("taskName", "圈子任务")
                    val progress = int(task.opt("progress"), 0)
                    val target = int(task.opt("target"), 0)
                    val status = task.optString("buttonStatus", "")
                    pending++
                    r.skip("$name · $title", "进度 $progress/$target，状态 $status；需实际完成后回读")
                }
            } catch (e: ApiError) {
                taskQueryFailed++
                r.fail("$name · 圈子任务", e.message ?: e.ret)
                if (e.ret.contains("USER_VALIDATE") || e.ret.contains("RGV587") || (e.message ?: "").contains("验证")) break
            }
        }
        r.state = when {
            failed > 0 || taskQueryFailed > 0 -> "部分查询或签到失败"
            pending > 0 -> "${pending} 项圈子任务待完成"
            got > 0 -> "已签到 $got 个圈子"
            already > 0 -> "已签到，任务无待完成"
            else -> "无待处理任务"
        }
        return r
    }

    /** 在任意 JSON 里按 key 找第一个字符串值（响应结构不固定时用） */
    private fun findKey(o: Any?, key: String): String {
        when (o) {
            is JSONObject -> {
                val direct = o.optString(key, "")
                if (direct.isNotEmpty()) return direct
                val it = o.keys()
                while (it.hasNext()) {
                    val v = findKey(o.opt(it.next()), key)
                    if (v.isNotEmpty()) return v
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until o.length()) {
                    val v = findKey(o.opt(i), key)
                    if (v.isNotEmpty()) return v
                }
            }
        }
        return ""
    }

    // ================================================================ 8 倒计时任务

    /**
     * 倒计时任务（「停留 30/60 秒得骰子」这类）+ 看视频任务。
     *
     * 抓包实测（2026-10-31，db/traffic.db id 3124→3142）：
     * ```
     * queryolivertasklist(sceneId=496)
     *   → taskList[] 里 materialDTO.extra.isCountDownTask=true 的任务
     *     （「倒计时30s获得1个骰子」deliveryId=58232 / 60s=58233 / 1h=73002）
     * 倒计时走完 → task.uploadOliverTaskTrigger(deliveryId, sceneId)  ← 宿主发的
     *   → 返回 taskDTO.idleTaskStatus（可能仍是 ACCEPTED，等几秒再查）
     * task.getolivertaskbenefit(deliveryId, asac, sceneId)  → 领奖励
     * ```
     *
     * **服务端按页面停留期间的请求判定停留时间**（用户实测结论）——
     * 所以不能一进链就 trigger，要按 extend.diceTaskCountDown.remainTime
     * 等到时间走完再发；一次性等太久（>90s）的不做，留给下次跑链时兜。
     *
     * 看视频任务（「看15秒视频立即领奖」actionType=VISIT、action 是
     * `fleamarket://showAds`）同走 trigger→benefit，视频本身不在判定链里。
     */
    fun countdown(ctx: Context, scene: Int = TASK_SCENE): Result {
        return rec(ctx, "countdown") {
            countdownInner(ctx, scene)
        }
    }

    /** 等待上限：超过这个剩余秒数的倒计时任务不等待（1h 得 3 步那种留给页面自己跑） */
    private const val WAIT_LIMIT_SEC = 90

    /**
     * 单条任务最多点几次。
     *
     * 多次点击类任务（`progressDTO.needTimes`）服务端按次数判定，
     * 一次 trigger 只推进一步。上限存在的理由：每条 trigger ~1.2s，
     * 一次点满 10 次要 12 秒，两条任务就把整轮拖到半分钟。
     * 差得超过这个数的本轮先不做，下一轮接着推进。
     */
    private const val MAX_CLICKS = 40

    /** 单轮各链写请求预算：尽量一轮做完，仍留间隔避免扎堆。 */
    private const val W_COUNTDOWN = 160
    private const val W_CASH = 60
    private const val W_GAME_COIN = 160
    private const val W_GAME_CHANNEL = 60

    /** 一条待执行任务：id、标题、需等待秒数、需点几次、已点几次 */
    private class Target(
        @JvmField val deliveryId: Long,
        @JvmField val title: String,
        @JvmField val waitSec: Int,
        @JvmField val clicks: Int,
        @JvmField val doneTimes: Int,
        /** 这条任务的会话票据，每次查询都会变；带上更贴近宿主行为 */
        @JvmField val fromToken: String,
        /**
         * `implId`（形如 `other_156_3212004_102369_0`）。
         *
         * 前端有两套 trigger：带 implId 的（`N7`）和不带的（`N9`）。
         * 文档早先写「不要传 implId」，那次用的是**从抓包库翻出来的旧值**
         * —— implId 里带序号（other_155 → other_156），每次查询都会变，
         * 拿旧值当然失败。现在用**当前列表里的值**，并保留不带 implId 的退路。
         */
        @JvmField val implId: String,
    )

    private fun countdownInner(ctx: Context, scene: Int = TASK_SCENE, cashWaitSec: Int = -1): Result {
        val r = Result("任务触发")
        val asac = requireAsac(ctx, r, "预检", BusinessParams.TASK_TRIGGER) ?: return r
        val benefitAsac = requireAsac(ctx, r, "预检", BusinessParams.TASK_BENEFIT) ?: return r
        // ---- 1. 拉任务列表 ----
        val list = try {
            call(
                ctx, "mtop.taobao.idle.task.queryolivertasklist", "2.0",
                data(
                    "sceneId" to Num(scene.toLong()),
                    "platform" to "Android",
                    "osVersion" to android.os.Build.VERSION.RELEASE,
                    "brand" to android.os.Build.BRAND,
                    "isWidgetInstalled" to false,
                ),
            )
        } catch (e: ApiError) {
            r.fail("查询任务", e.message ?: e.ret)
            return r
        }
        val arr = list.optJSONArray("taskList")
        if (arr == null || arr.length() == 0) {
            r.skip("查询任务", "任务列表为空")
            r.state = "无任务"
            return r
        }

        // ---- 2. 挑目标：倒计时任务 + 可触发的浏览/视频任务 ----
        //
        // 三层过滤（照抄芝麻粒 TaskAlternative 的分层思路，见 TaskGuard）：
        //   ① 黑名单 —— 失败过 3 次的不再发请求
        //   ② 交易/履约类 —— 服务端要真实凭证，连试都不试，永久拉黑
        //   ③ 外部 App —— 要第三方回调，同样永久拉黑
        //   剩下的才进 targets，才花网络请求。
        //
        // target: deliveryId, 标题, 需等待秒数（0 = 无需等待）
        ParamGuard.releaseCashLikeOnce(ctx)
        // 108327 已实测上报并成功领取一次，撤销历史永久黑名单。
        TaskBlacklist.clear(ctx, blackKey(108327L, TASK_SCENE))
        val targets = ArrayList<Target>()
        var deferred = 0      // 倒计时剩余太久，本轮跳过
        var blacklisted = 0   // 黑名单里躺着
        var deal = 0          // 交易类
        var extCount = 0      // 外部 App
        var otherSkip = 0     // 其他（点击次数不够等）
        var paramSkip = 0     // 本进程内已被参数拒绝（不再重复打）
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            if (t.optString("idleTaskStatus") != "ACCEPTED") continue
            val m = t.optJSONObject("materialDTO") ?: continue
            val title = m.optString("title", "")
            val ext = m.optJSONObject("extra")
            val deliveryId = t.opt("deliveryId")?.toString()?.toLongOrNull() ?: continue
            val actionType = m.optString("actionType")
            val action = m.optString("action", "")

            // ① 黑名单：key 用 sceneId + deliveryId，标题只作展示。
            //    不用标题当 key —— 标题文案会变（「逛一逛」→「逛精选」），
            //    变了就绕过了黑名单，白试一轮。
            val key = blackKey(deliveryId, scene)
            if (TaskBlacklist.isBlocked(ctx, key)) {
                blacklisted++
                continue
            }
            // ② 本进程里已经被参数拒过：跳过。自动任务每 30 分钟一轮，
            //    不记这一笔就等于每轮重打同样的注定失败的请求。
            val partial = t.optJSONObject("progressDTO")
            if ((partial?.optInt("times", 0) ?: 0) > 0 &&
                (partial?.optInt("needTimes", 0) ?: 0) > 0 &&
                ext?.optString("customActionType", "") == "idleClickMulti") {
                ParamGuard.releasePartialOnce(ctx, key)
            }
            // 桌面组件任务的判定来自组件拉数据，旧版曾把它误记为失败冷却；
            // 这类任务每轮都可以安全重试。
            val desktopTask = title.indexOf("桌面") >= 0
            if (!desktopTask && ParamGuard.blocked(ctx, key)) {
                paramSkip++
                continue
            }

            val isCountDown = ext != null && flag(ext.opt("isCountDownTask"))

            // 进度在 progressDTO 里，不在 materialDTO —— 实测列表每一项都带：
            //   {"cdTime":"-1","loopTimes":"10","maxTimes":"10",
            //    "needTimes":"0","period":"821","reachLimit":"true","times":"10"}
            // 注意字段全是**字符串**，int() 能处理。
            //
            // 语义（实测反推，任务「点击指定频道好物」needTimes=10 已完成）：
            //   loopTimes / maxTimes  这个任务总共要几次
            //   needTimes             还差几次（0 = 已达上限，等领奖）
            //   times                 已完成几次
            //   reachLimit            true = 已达上限
            // 之前代码读的是 materialDTO.loopTimes（那个字段根本不存在，
            // 永远拿到默认值 1），所以多次点击类任务一律只点一次 —— 真 bug。
            val pg = t.optJSONObject("progressDTO")
            val needTimes = int(pg?.opt("needTimes"), 0)
            val totalTimes = int(pg?.opt("loopTimes"), 1)
            val times = int(pg?.opt("times"), 0)

            // ②③ 交易类 / 外部 App / 点击次数
            // 判「要不要跳过」用的是**还差几次**（needTimes），不是总次数：
            // 看视频类 loopTimes=4 但 needTimes=1 时一次就能推进完，按 totalTimes
            // 判会把它当成「需点 4 次」直接跳过 —— 这就是视频任务一直不动的根因。
            // 下面会按 needTimes 循环上报；此处只检查交易和外跳。
            // 黑名单控制排除项；其余新任务以服务端返回结果决定能否完成。
            val v = TaskGuard.judge("", "", 1, isCountDown)
            if (!v.canTry) {
                when (v.kind) {
                    TaskGuard.KIND_DEAL -> {
                        deal++
                        TaskBlacklist.blockForever(ctx, key, title)
                    }
                    TaskGuard.KIND_EXTERNAL -> {
                        // 外跳类**不永久拉黑**（照芝麻粒的判据：外跳类本应去尝试，
                        // 把它当「已知做不了」永久拒绝，等于提前放弃一份奖励）。
                        // 改为普通计数：试 3 次后冷却 3 天，期间不再发请求。
                        extCount++
                        ParamGuard.mark(ctx, key)
                    }
                    else -> otherSkip++
                }
                continue
            }

            if (isCountDown) {
                // 剩余时间在 extend.diceTaskCountDown.remainTime（秒）
                val cd = t.optJSONObject("extend")?.optJSONObject("diceTaskCountDown")
                val remain = int(cd?.opt("remainTime"), -1)
                if (remain < 0 || remain > WAIT_LIMIT_SEC) {
                    deferred++
                    continue
                }
                targets.add(
                    Target(
                        deliveryId, title, remain, 1, times,
                        t.optString("fromToken", ""), t.optString("implId", ""),
                    ),
                )
            } else if (actionType.isEmpty() && title.isEmpty()) {
                // 现金红包页那条任务（deliveryId 61676）的 materialDTO 是**空的**：
                // 标题/动作都在 `cash.main` 的 timeTask 里（time=停留秒数）。
                // 这类没有 actionType 的，按「停留后上报」处理。
                if (scene == CASH_SCENE && cashWaitSec < 0) {
                    deferred++
                    continue
                }
                val wait = if (scene == CASH_SCENE) cashWaitSec else 0
                targets.add(
                    Target(
                        deliveryId, if (scene == CASH_SCENE) "现金任务" else "任务",
                        wait, if (needTimes > 1) needTimes else 1, times,
                        t.optString("fromToken", ""), t.optString("implId", ""),
                    ),
                )
            } else if (actionType == "VISIT" || actionType == "PLAINVISIT" ||
                actionType == "clickFinish"
            ) {
                // 站内浏览类任务（含看视频 showAds、逛商城、点击频道）：
                // trigger 是服务端判定完成的唯一入口。
                //
                // 多次点击类要按 needTimes 循环 —— 一次 trigger 只推进一步，
                // 服务端按次数判定（实测「点击指定频道好物」要 10 次）。
                // 上限 12 次：再多的话单条任务就吃掉半分钟，不如留给下一轮。
                // 这两条视频每推进一次就变为 AWARD，必须先领取再继续。
                // 每轮推进一次，下一轮读取新状态后继续。
                // 多次任务可能每次上报后都要领奖，统一单次推进后回读。
                val clicks = 1
                targets.add(
                    Target(
                        deliveryId, title, 0, clicks, times,
                        t.optString("fromToken", ""), t.optString("implId", ""),
                    ),
                )
            }
        }
        r.ok(
            "查询任务",
            TaskGuard.summary(targets.size, deal, extCount, otherSkip) +
                if (deferred > 0) "，$deferred 个倒计时剩余过久" else "",
        )
        if (paramSkip > 0) {
            r.skip("当天冷却", "$paramSkip 个任务今天已有失败记录，本轮跳过")
        }
        if (blacklisted > 0) {
            r.skip("黑名单", "$blacklisted 个任务被黑名单拦截，本轮跳过")
        }
        if (targets.isEmpty()) {
            r.skip("执行", "没有可做的倒计时/视频任务")
            r.state = "无可做"
            return r
        }

        // 每个任务按最新状态推进：先上报，只有 AWARD 才领，再回读决定是否继续。
        var progressed = 0
        var completed = 0
        var awards = 0
        var writes = 0
        for (tg in targets) {
            if (writes >= W_COUNTDOWN || ApiInit.paused(ctx) || Thread.currentThread().isInterrupted) break
            val key = blackKey(tg.deliveryId, scene)
            var current = queryOliverTask(ctx, scene, tg.deliveryId)
            if (current == null) continue
            if (tg.waitSec > 0) {
                RunProgress.step("等待 ${tg.waitSec} 秒（模拟页面停留）")
                r.ok(tg.title, "等待 ${tg.waitSec} 秒")
                try { Thread.sleep(tg.waitSec * 1000L + 1500L) } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    r.skip(tg.title, "执行已中断")
                    break
                }
            }
            var attempts = 0
            var advanced = false
            while (writes < W_COUNTDOWN && current != null && !ApiInit.paused(ctx)) {
                val state = current.optString("idleTaskStatus", "")
                if (state == "COMPLETE") { completed++; break }
                if (state != "ACCEPTED" && state != "AWARD") break
                val before = current.optJSONObject("progressDTO")?.optInt("times", 0) ?: 0
                try {
                    if (state == "AWARD") {
                        writes++
                        val reply = call(ctx, "mtop.taobao.idle.task.getolivertaskbenefit", "1.0", data(
                            "asac" to benefitAsac, "deliveryId" to Num(tg.deliveryId),
                            "sceneId" to Num(scene.toLong()), "fromToken" to current.optString("fromToken", ""),
                        ))
                        pauseTaskStep()
                        val after = queryOliverTask(ctx, scene, tg.deliveryId)
                        if (after == null) {
                            awards++
                            completed++
                            r.ok(tg.title, "领奖后重新查询，任务已从列表移除")
                            break
                        }
                        val nextState = after.optString("idleTaskStatus", "")
                        if (nextState == "AWARD") {
                            r.skip(tg.title, "领奖后仍待领，本轮不重发")
                            break
                        }
                        if (nextState == "COMPLETE" || nextState == "ACCEPTED") {
                            awards++
                            r.ok(tg.title, "已领取本次奖励，回读 $nextState")
                        } else {
                            r.skip(tg.title, "领奖结果待确认（$nextState）")
                            break
                        }
                        current = after
                        continue
                    }
                    if (attempts >= MAX_CLICKS) { r.skip(tg.title, "已达本轮上报上限，后续轮继续"); break }
                    if (ParamGuard.blocked(ctx, key)) { r.skip(tg.title, "当天参数拒绝冷却"); break }
                    attempts++
                    writes++
                    val report = if (tg.title.contains("桌面")) {
                        call(ctx, "mtop.taobao.idle.coin.widget", "1.0", data(
                            "apiName" to "mtop.taobao.idle.coin.widget", "apiVersioin" to "1.0",
                            "brand" to android.os.Build.BRAND, "platform" to "Android",
                            "osVersion" to android.os.Build.VERSION.RELEASE, "originJson" to false,
                        ))
                    } else triggerTask(ctx, scene, tg.deliveryId, current.optString("implId", ""))
                    pauseTaskStep()
                    val after = queryOliverTask(ctx, scene, tg.deliveryId)
                    if (after == null) { r.skip(tg.title, "上报已提交，任务回读缺失，本轮不重发"); break }
                    val times = after.optJSONObject("progressDTO")?.optInt("times", 0) ?: 0
                    val nextState = after.optString("idleTaskStatus", "")
                    if (times <= before && nextState == "ACCEPTED") {
                        r.skip(tg.title, "上报后进度未增加，本轮停止；不记参数拒绝")
                        break
                    }
                    if (times > before) {
                        if (!advanced) { progressed++; advanced = true }
                        r.ok(tg.title, "进度 $before → $times")
                    }
                    current = after
                } catch (e: ApiError) {
                    if (ApiInit.paused(ctx)) { r.skip(tg.title, "触发暂停，停止剩余任务"); break }
                    if (e.ret.contains("PARAM_ILLEGAL")) {
                        ParamGuard.mark(ctx, key)
                        r.skip(tg.title, "该任务参数被拒，本日冷却（${e.ret}）")
                    } else r.skip(tg.title, "${e.ret}，本轮停止且不重复提交")
                    break
                }
            }
        }
        if (ApiInit.paused(ctx) || Thread.currentThread().isInterrupted) {
            r.skip("执行", "已停止剩余请求")
            r.state = "推进 $progressed 项 · 领 $awards 次 · 已暂停"
            return r
        }
        val latest = queryOliverList(ctx, scene).optJSONArray("taskList")
        var left = 0
        var ready = 0
        if (latest != null) for (i in 0 until latest.length()) {
            when (latest.optJSONObject(i)?.optString("idleTaskStatus", "")) {
                "ACCEPTED" -> left++
                "AWARD" -> ready++
            }
        }
        r.ok("回读", "完成 $completed 项，推进 $progressed 项，领奖 $awards 次；待完成 $left，待领 $ready")
        r.state = "完成 $completed 项 · 领 $awards 次 · 剩 $left 项"
        return r
    }

    private fun queryOliverList(ctx: Context, scene: Int): JSONObject = call(
        ctx, "mtop.taobao.idle.task.queryolivertasklist", "2.0", data(
            "sceneId" to Num(scene.toLong()), "platform" to "Android",
            "osVersion" to android.os.Build.VERSION.RELEASE, "brand" to android.os.Build.BRAND,
            "isWidgetInstalled" to widgetInstalled(ctx),
        ),
    )

    private fun queryOliverTask(ctx: Context, scene: Int, id: Long): JSONObject? {
        val arr = queryOliverList(ctx, scene).optJSONArray("taskList")
            ?: throw ApiError("NO_TASK_LIST", "回读未提供任务列表，结果待确认")
        for (i in 0 until arr.length()) {
            val task = arr.optJSONObject(i) ?: continue
            if (task.optLong("deliveryId", 0L) == id) return task
        }
        return null
    }

    private fun pauseTaskStep() {
        try { Thread.sleep(1200L) } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ApiError("INTERRUPTED", "执行已中断")
        }
    }

    /**
     * 黑名单 key：`sceneId:deliveryId`。
     *
     * 不用标题 —— 标题文案会变（「逛一逛」→「逛精选好物」），
     * 一变就绕过黑名单，白试一轮。deliveryId 是投递 id，任务换了就换，
     * 正是我们想要的粒度。
     */
    private fun blackKey(deliveryId: Long, scene: Int = TASK_SCENE): String = "$scene:$deliveryId"

    /** 命中次数文案：`2/3` */
    private fun hitText(ctx: Context, key: String): String {
        return TaskBlacklist.hitText(ctx, key)
    }
    // ================================================================ 9 全部

    /** 自动、手动全执行使用同一张顺序表。互斥由调用入口 acquire/release 管理。 */
    fun all(ctx: Context): List<Result> = executePlan(ctx, "all")

    /** 定时轮补齐可刷新的任务与后续奖励，避免任务得了资源却一直留到次日。 */
    fun pending(ctx: Context): List<Result> = executePlan(ctx, "pending")

    /** 界面及 HTTP/provider 的统一入口；忙碌、暂停和未知名称均不发请求。 */
    fun manual(ctx: Context, name: String): List<Result> {
        val out = ArrayList<Result>()
        if (ChainPlan.select(name).isEmpty()) {
            val r = Result("执行")
            r.fail("入口", "未知功能：$name")
            RunProgress.result("执行", "入口", "未知功能：$name", false, false)
            out.add(r)
            return out
        }
        if (!AutoTask.acquire()) {
            val r = Result("执行")
            r.skip("执行", "已有自动或手动任务正在运行，请等待结束")
            RunProgress.step("已有任务正在执行")
            RunProgress.result("执行", "开始", "已有任务在跑，本次跳过", true, true)
            out.add(r)
            return out
        }
        try {
            out.addAll(executePlan(ctx, name))
            if (name == "all") AutoTask.noteFullRun(ctx, AutoTask.tally("手动全链", out), !ApiInit.paused(ctx))
            return out
        } finally {
            AutoTask.release()
        }
    }

    /** 手动入口创建会话后执行；窗口关闭时线程照常运行。 */
    fun startManual(ctx: Context, name: String, title: String): RunProgress.Session {
        val session = RunProgress.begin(title)
        val thread = Thread({
            RunProgress.attach(session)
            RunProgress.step("准备执行：" + title)
            try { manual(ctx, name) } catch (t: Throwable) {
                session.append("执行异常：" + t.javaClass.simpleName, true)
            } finally { RunProgress.finish(session) }
        }, "ManualChain")
        thread.isDaemon = true
        thread.start()
        return session
    }

    private fun executePlan(ctx: Context, name: String): List<Result> {
        val out = ArrayList<Result>()
        for (entry in ChainPlan.select(name)) {
            if (ApiInit.paused(ctx) || Thread.currentThread().isInterrupted) {
                val r = Result(entry.title)
                r.skip("执行", if (ApiInit.paused(ctx)) "当前暂停，本轮剩余功能未执行" else "执行已中断")
                r.state = "未执行"
                out.add(r)
                break
            }
            Protocol.State.logLine("执行：${entry.title}")
            RunProgress.step("正在执行：" + entry.title)
            addSafe(out, entry.title) {
                when (entry.id) {
                    "signin" -> signIn(ctx)
                    "tasks" -> tasks(ctx)
                    "countdown" -> countdown(ctx)
                    "cashstay" -> cashStay(ctx)
                    "cash" -> cash(ctx)
                    "member" -> member(ctx)
                    "game" -> gameTasks(ctx)
                    "circle" -> circle(ctx)
                    "forest" -> forest(ctx)
                    "challenge" -> challenge(ctx)
                    "treasure" -> treasure(ctx, 8)
                    "cycle" -> cycle(ctx)
                    "props" -> props(ctx)
                    "experience" -> experience(ctx)
                    else -> throw IllegalArgumentException("未知功能：${entry.id}")
                }
            }
        }
        // 在所有奖励处理后刷新快照；暂停/中断后不能再发查询请求。
        if (out.isNotEmpty() && !ApiInit.paused(ctx) && !Thread.currentThread().isInterrupted) {
            RunProgress.step("正在更新我的数据")
            refreshUserData(ctx)
        }
        return out
    }

    /** 跑一条链，任何 Throwable 都收成一条「失败」结果，绝不外抛 */
    private fun addSafe(out: ArrayList<Result>, name: String, body: () -> Result) {
        try {
            out.add(body())
        } catch (t: Throwable) {
            val r = Result(name)
            r.fail("异常", t.javaClass.simpleName + (t.message?.let { "：" + it.take(60) } ?: ""))
            out.add(r)
        }
    }
}
