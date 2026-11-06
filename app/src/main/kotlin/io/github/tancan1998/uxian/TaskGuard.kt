package io.github.tancan1998.uxian

/**
 * 任务可做性预判（照抄芝麻粒-M 的 `TaskAlternative` 过滤层）。
 *
 * ## 为什么要有这一层
 *
 * 实测结论（docs/api-callbook.md §2.1）：服务端对任务的判定是**按真实行为**的。
 * 我们硬 trigger 一批注定做不成的任务，代价是：
 *  - 每次 ~1.2 秒的 mtop 调用，十几个任务就是十几秒
 *  - 服务端看到同一个 deliveryId 反复被 trigger，是明显的机器人特征
 *  - 执行记录被「参数非法 / 任务未完成」刷满，真问题看不见
 *
 * 芝麻粒的做法是**先分类，能做的才发请求**：交易/履约类连 trigger 都不发，
 * 直接永久拉黑（它自己的黑名单里躺着「完成1笔旧衣回收」「去飞猪订酒店」这类）。
 *
 * ## 三类判据
 *
 * 1. **标题关键词**（中文）—— 交易/履约类。命中就永不尝试。
 * 2. **action 指向外部 App** —— 服务端要第三方回调，我们给不了。
 * 3. **loopTimes > 1 的点击类** —— 要真点够次数，一次 trigger 不够。
 *
 * 判据都在**本地**完成，不花任何网络请求。
 *
 * ## 为什么用中文关键词
 *
 * 芝麻粒对 `bizKey` 用的是拼音（`xiadan`/`zhifu`），因为支付宝的 bizKey
 * 是英文标识。闲鱼的任务标题是中文的（`materialDTO.title`），
 * 直接匹配中文更准，也不会被「xia dan」这种变形绕过。
 */
object TaskGuard {

    /**
     * 交易 / 履约类关键词。
     *
     * 命中任意一个 → 这个任务**永远不做**。理由是服务端要真实订单/支付流水，
     * 客户端伪造不了（实测 `FAIL_BIZ_COMMON_PARAM_ILLEGAL`）。
     *
     * ## 只放「明确表示要真实交易」的词
     *
     * 宁缺毋滥 —— 误杀一个能做的任务，代价比漏杀一个大得多：
     * 漏杀只是白试一次（3 次后自动进黑名单），误杀则永远拿不到那份奖励。
     * 所以下面这些**故意不放**：
     *  - `支付宝` / `余额宝` / `花呗` / `借呗` / `网商` —— 都是产品名，
     *    「去花呗翻卡」「去支付宝领积分」这类是纯浏览任务，实测能做。
     *    （芝麻粒把 yuebao 列进去了，但它匹配的是 bizKey 不是标题，
     *    而且日志里「去花呗翻卡」它是做成了的。）
     *  - `额度` —— 「一键完成网商额度申请」要拦，但「额度查询」不用，
     *    改成靠 `网商` + `申请` 组合判定（见 [judge]）
     */
    private val DEAL_WORDS = arrayOf(
        // —— 下单/支付类：要真实订单
        "下单", "支付", "购买", "付款", "结算", "收银台", "拍下",
        // —— 金融类：要真实账户动作
        "还款", "借款", "贷款", "提现", "转账", "缴费", "话费充值",
        "信用卡", "分期", "额度申请",
        // —— 物流/回收类：要真实寄件/回收单
        "快递", "寄件", "旧衣回收", "数码回收", "上门回收",
        // —— 出行/住宿类：要真实行程
        "酒店", "机票", "火车票", "打车", "租车", "租赁", "订房", "门票",
        // —— 保险/理财类
        "保险", "理财", "基金", "投保", "参保",
        // —— 抽奖付费类
        "付费", "充值卡", "开通会员",
    )

    /**
     * 匹配前先删掉的产品名。
     *
     * `支付宝` 里含 `支付`，不删的话「去支付宝领积分」会被误判成交易类
     * （实测踩到，黑名单里躺了一条）。
     */
    private val CLEAN_WORDS = arrayOf(
        "支付宝", "余额宝", "花呗", "借呗", "网商银行", "蚂蚁保",
    )

    /**
     * 指向**外部 App** 的 action 特征。
     *
     * 这类任务服务端靠第三方 App 的回调判定完成，我们调 trigger 只会
     * 拿到 `TASK_IS_NOT_COMPLICATE`。芝麻粒也是全部拉黑。
     */
    private val EXTERNAL_SCHEMES = arrayOf(
        "alipays://", "alipayqr://",
        "imeituan://", "dianping://", "tb.ele.me://", "eleme://",
        "diandon.cn", "market://", "taobao://", "tmall://",
        "openapp.jdmobile://", "pinduoduo://", "snssdk1128://",
        "kwai://", "qqmusic://", "orpheus://",
    )

    /** 站内地址特征（这些是可以做的） */
    private val INTERNAL_HINTS = arrayOf(
        "goofish.com", "fleamarket://", "h5.m.goofish", "ssr.m.goofish",
        "m.tb.cn", "pages/", "/h5/",
    )

    /** 判定结果 */
    class Verdict(
        /** 能否尝试 */
        @JvmField val canTry: Boolean,
        /** 不能试的原因（界面直接显示） */
        @JvmField val why: String,
        /** 是否「永不再试」（交易类/外部 App）；false = 只是本轮跳过 */
        @JvmField val permanent: Boolean,
        /** 类别：见 [KIND_*]，用于统计和决定是否写黑名单 */
        @JvmField val kind: Int,
    )

    /** 可以做 */
    const val KIND_OK = 0
    /** 交易/履约类 */
    const val KIND_DEAL = 1
    /** 指向外部 App */
    const val KIND_EXTERNAL = 2
    /** 其他原因（点击次数不够等） */
    const val KIND_OTHER = 3

    private val OK = Verdict(true, "", false, KIND_OK)

    private fun no(why: String, permanent: Boolean, kind: Int): Verdict =
        Verdict(false, why, permanent, kind)

    /**
     * 标题里是否含交易类关键词。
     *
     * 先删掉产品名（`支付宝` 含 `支付`）再匹配，否则「去支付宝领积分」
     * 会被误判 —— 实测踩过，黑名单里躺了一条。
     *
     * 注意：只匹配标题，不匹配 action URL —— action 里带 `pay` 之类的
     * 英文片段太容易误伤（芝麻粒也是匹配 bizKey 而不是完整 URL）。
     */
    @JvmStatic
    fun isDeal(title: String): Boolean {
        if (title.isEmpty()) return false
        var t = title
        for (w in CLEAN_WORDS) {
            t = t.replace(w, "")
        }
        for (w in DEAL_WORDS) {
            if (t.contains(w)) return true
        }
        return false
    }

    /** action 是否指向外部 App */
    @JvmStatic
    fun isExternal(action: String): Boolean {
        if (action.isEmpty()) return false
        val low = action.lowercase()
        for (s in EXTERNAL_SCHEMES) {
            if (low.contains(s)) return true
        }
        return false
    }

    /** action 是否是站内地址（明确可做） */
    @JvmStatic
    fun isInternal(action: String): Boolean {
        if (action.isEmpty()) return false
        val low = action.lowercase()
        for (s in INTERNAL_HINTS) {
            if (low.contains(s)) return true
        }
        return false
    }

    /**
     * 综合判定一个任务能不能试。
     *
     * @param title   `materialDTO.title`
     * @param action  `materialDTO.action`（可能是 URL，也可能为空）
     * @param loopTimes 需要点几次（>1 表示一次 trigger 不够）
     * @param isCountDown 是否倒计时类（这类是等时间，不按关键词拦）
     */
    @JvmStatic
    fun judge(title: String, action: String, loopTimes: Int, isCountDown: Boolean): Verdict {
        // 1. 交易/履约类：连试都不试，永久拉黑
        if (isDeal(title)) {
            return no("交易/履约类，服务端要真实凭证", true, KIND_DEAL)
        }
        // 2. 指向外部 App：要第三方回调
        if (isExternal(action)) {
            return no("指向外部 App，需第三方回调", true, KIND_EXTERNAL)
        }
        // 3. 多次点击类：一次 trigger 不够
        //    倒计时类不适用（它本来就要等）
        if (!isCountDown && loopTimes > 1) {
            return no("需点击 $loopTimes 次，一次触发不够", false, KIND_OTHER)
        }
        // 4. action 非空但既不是站内也不是外部 —— 多半是相对路径或协议未知，
        //    按「可试」处理，让服务端自己判定（宁可试一次也别漏掉）
        return OK
    }

    /**
     * 给界面看的分类统计文案。
     * 例：`12 个目标，跳过 5 个（3 个交易类 / 2 个外部 App）`
     */
    @JvmStatic
    fun summary(total: Int, dealCount: Int, extCount: Int, otherCount: Int): String {
        val b = StringBuilder()
        b.append(total).append(" 个目标")
        val skipped = dealCount + extCount + otherCount
        if (skipped > 0) {
            b.append("，跳过 ").append(skipped).append(" 个（")
            val parts = ArrayList<String>(3)
            if (dealCount > 0) parts.add("$dealCount 个交易类")
            if (extCount > 0) parts.add("$extCount 个外部 App")
            if (otherCount > 0) parts.add("$otherCount 个其他")
            for (i in 0 until parts.size) {
                if (i > 0) b.append(" / ")
                b.append(parts[i])
            }
            b.append("）")
        }
        return b.toString()
    }
}
