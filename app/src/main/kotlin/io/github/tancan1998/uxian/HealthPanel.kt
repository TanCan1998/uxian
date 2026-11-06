package io.github.tancan1998.uxian

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 「适配性 · 执行记录」二级页面。
 *
 * ## 这一页是给用户看的，不是给开发看的
 *
 * 之前的内容是给开发者排查用的（`装 3/4 · 调 12 · 中 0`），用户看不出
 * 「我的开屏广告到底还弹不弹」。现在按**功能**组织，每一行回答一个问题：
 * 这个功能现在可用还是失效、今天实际拦了几次。
 *
 * ## 观感约定
 *
 *   - **可用绿、失效红**（[Theme.okText] / [Theme.badText]），状态做成浅底胶囊，
 *     不靠字号或位置区分 —— 用户一眼扫过去就要看到哪个红了。
 *   - 未开启/未触发用灰（hint），不算故障：装上了但今天没遇到广告是正常的。
 *   - 二级页面一律 [PanelDialog]，说明文字一律 `Theme.hint()`。
 *   - 记录区**列宽写死**（时间 84dp / 功能 84dp / 结果自适应），
 *     中文字宽不一致，用 weight 分列会参差不齐。
 */
object HealthPanel {

    /** 尺寸统一走 [UiKit.dp]，不在这里再抄一份 */
    private fun dpf(ctx: Context, v: Float): Int = UiKit.dp(ctx, v)

    /**
     * 全部已实现功能（含业务链之外的两项响应过滤）。
     * 顺序即展示顺序：广告类在最前，用户最关心。
     */
    private val HOOKS = arrayOf(
        arrayOf("splash", "跳过开屏广告", "启动时直接进入首页，不展示开屏广告"),
        arrayOf("shake", "开屏摇一摇禁用", "开屏页不响应摇一摇跳转"),
        arrayOf("upgrade", "后台升级弹窗拦截", "后台检测到新版本时不弹升级窗"),
        arrayOf("searchad", "搜索结果去广告", "搜索结果中移除带广告标记的商品"),
        arrayOf("message", "消息页过滤服务号", "消息列表隐藏官方服务号会话"),
        arrayOf("kingkong", "金刚位排序", "调整首页金刚位顺序，可整块隐藏"),
        arrayOf("hometab", "首页 tab 排序", "调整首页顶部 tab 的顺序与显示"),
        arrayOf("track", "断埋点上报", "阻止行为埋点上报"),
        arrayOf("utdid", "断设备指纹上传", "阻止设备指纹上传"),
    )

    /** 一个功能行需要的全部信息（钩子与响应过滤共用一套渲染） */
    private class Row(
        val key: String,
        val title: String,
        val desc: String,
        val critical: Boolean,
        val on: Boolean,
        val total: Long,
        val detail: String,
    )

    private fun buildRows(ctx: Context, feats: Map<String, HookState.Feature>): ArrayList<Row> {
        val rows = ArrayList<Row>()
        for (meta in HOOKS) {
            val key = meta[0]
            val f = feats[key]
            val critical: Boolean
            var total = 0L
            var detail = ""
            if (key == "message") {
                critical = !Protocol.State.capFilter
                if (critical) detail = "数据过滤未启用，重启闲鱼试试"
            } else if (f == null) {
                critical = true
                detail = "未注册，重启闲鱼试试"
            } else {
                critical = f.critical()
                total = HookState.totalOf(key)
                if (critical) detail = "没装上（" + f.installed.size + "/" +
                    (f.installed.size + f.failed.size) + "），重启闲鱼试试"
            }
            rows.add(Row(key, meta[1], meta[2], critical, featureOn(ctx, key), total, detail))
        }
        return rows
    }

    fun show(activity: Activity) {
        val ctx: Context = activity
        val th = Theme.of(ctx)

        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        val p = dpf(ctx, 12f)
        box.setPadding(p, dpf(ctx, 2f), p, p)

        // ---- 先把数据算出来：任何一步出错都只影响一行字，不能让整页打不开 ----
        VersionGate.ensureChecked()
        val ver = VersionGate.currentVersion

        val featMap = HashMap<String, HookState.Feature>()
        try {
            for (meta in HOOKS) {
                val f = HookState.mapGet(meta[0])
                if (f != null) featMap[meta[0]] = f
            }
        } catch (t: Throwable) {
        }
        val rows = buildRows(ctx, featMap)

        // 只有「钩子没装上」才算不可用；开关关着不算失效（功能本身还能用）
        var okN = 0
        var badN = 0
        for (r in rows) {
            if (r.critical) badN++ else okN++
        }

        // ---- 汇总：一句话 + 状态胶囊 ----
        val head = UiKit.card(ctx)
        val hp = LinearLayout(ctx)
        hp.orientation = LinearLayout.HORIZONTAL
        hp.gravity = Gravity.CENTER_VERTICAL
        hp.setPadding(dpf(ctx, 14f), dpf(ctx, 14f), dpf(ctx, 14f), dpf(ctx, 14f))

        val big = TextView(ctx)
        big.text = if (badN > 0) "有 $badN 项失效" else "功能可用"
        big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        big.setTypeface(big.typeface, Typeface.BOLD)
        big.setTextColor(if (badN > 0) th.badText() else th.okText())
        hp.addView(big, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (okN > 0) hp.addView(chip(ctx, "可用 $okN", th.okText(), th.okBg()))
        if (badN > 0) {
            val gap = View(ctx)
            hp.addView(gap, LinearLayout.LayoutParams(dpf(ctx, 6f), 1))
            hp.addView(chip(ctx, "不可用 " + badN, th.badText(), th.badBg()))
        }
        head.addView(hp)

        val vline = TextView(ctx)
        // 版本号后面跟一个状态符号（用户要求）：绿 ✓ 完美 / 黄 ! 部分 / 红 × 失效。
        // 版本不认识或功能有失效项时用符号一眼标出来，文字仍写清楚原因。
        // 适配与否只看实际挂载点，不看版本号
        val mark: String
        val markColor: Int
        when {
            okN == 0 -> { mark = "×"; markColor = th.badText() }
            badN > 0 -> { mark = "!"; markColor = th.warnText() }
            else -> { mark = "✓"; markColor = th.okText() }
        }
        val base = "闲鱼 " + (if (ver.isEmpty()) "版本未知" else ver) + "  "
        val sb = android.text.SpannableStringBuilder(base + mark)
        sb.setSpan(
            android.text.style.ForegroundColorSpan(th.hint()),
            0, base.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        sb.setSpan(
            android.text.style.ForegroundColorSpan(markColor), base.length, sb.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        sb.setSpan(
            android.text.style.StyleSpan(Typeface.BOLD), base.length, sb.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        vline.text = sb
        vline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        vline.setPadding(dpf(ctx, 14f), 0, dpf(ctx, 14f), dpf(ctx, 12f))
        head.addView(vline)
        box.addView(head)

        // ---- 拦截与界面：与面板「广告拦截 / 外观与隐私」一一对应 ----
        box.addView(sectionTitle(ctx, "拦截与界面"))
        if (rows.isEmpty()) {
            box.addView(hint(ctx, "还没有装任何钩子。重启一次闲鱼，再回来看。"))
        } else {
            val card = UiKit.card(ctx)
            for (i in rows.indices) {
                if (i > 0) card.addView(UiKit.divider(ctx))
                card.addView(featureRow(ctx, rows[i]))
            }
            box.addView(card)
        }

        // ---- 自动任务：与面板「立即执行 / 逐项执行」同一清单，只报可用状态 ----
        box.addView(sectionTitle(ctx, "自动任务"))
        box.addView(hint(ctx, "按执行顺序排列；不可用表示宿主调用通道未就绪"))
        val ready = Protocol.State.hostRpcReady
        val chains = UiKit.card(ctx)
        var n = 0
        for (entry in ChainPlan.entries) {
            if (n++ > 0) chains.addView(UiKit.divider(ctx))
            chains.addView(chainRow(ctx, entry, ready))
        }
        box.addView(chains)

        PanelDialog.show(
            activity, "模块适配", PanelDialog.scrollWrap(ctx, box),
            ratio = PanelDialog.AUTO,
        )
    }

    // ------------------------------------------------------------ 行

    /** 功能行：名字 + 状态，下一行说清这个功能做什么 */
    private fun featureRow(ctx: Context, r: Row): View {
        val th = Theme.of(ctx)
        val color = if (r.critical) th.badText() else th.okText()
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dpf(ctx, 14f), dpf(ctx, 10f), dpf(ctx, 14f), dpf(ctx, 10f))

        val top = LinearLayout(ctx)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(dot(ctx, color))
        val name = TextView(ctx)
        name.text = r.title
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        name.setTextColor(th.text())
        val nlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        nlp.leftMargin = dpf(ctx, 10f)
        top.addView(name, nlp)
        if (r.critical) {
            top.addView(chip(ctx, "不可用", th.badText(), th.badBg()))
        } else {
            top.addView(chip(ctx, "可用", th.okText(), th.okBg()))
        }
        box.addView(top)

        var text = if (r.detail.isEmpty()) r.desc else r.desc + "；" + r.detail
        val counting = r.key != "kingkong" && r.key != "hometab" &&
            r.key != "track" && r.key != "utdid"
        if (counting && r.total > 0) text += " · 累计拦截 " + r.total + " 次"
        val desc = TextView(ctx)
        desc.text = text
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        desc.setTextColor(th.hint())
        desc.maxLines = 2
        desc.ellipsize = android.text.TextUtils.TruncateAt.END
        val dlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        dlp.leftMargin = dpf(ctx, 17f)
        box.addView(desc, dlp)
        return box
    }

    /** 业务链一行：名字 + 可用状态，下一行说明它做什么 */
    private fun chainRow(ctx: Context, entry: ChainPlan.Entry, available: Boolean): View {
        val th = Theme.of(ctx)
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dpf(ctx, 14f), dpf(ctx, 10f), dpf(ctx, 14f), dpf(ctx, 10f))

        val top = LinearLayout(ctx)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(dot(ctx, if (available) th.okText() else th.badText()))
        val name = TextView(ctx)
        name.text = entry.title
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        name.setTextColor(th.text())
        val nlp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        nlp.leftMargin = dpf(ctx, 10f)
        top.addView(name, nlp)
        if (available) {
            top.addView(chip(ctx, "可用", th.okText(), th.okBg()))
        } else {
            top.addView(chip(ctx, "不可用", th.badText(), th.badBg()))
        }
        box.addView(top)

        val desc = TextView(ctx)
        desc.text = entry.desc
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        desc.setTextColor(th.hint())
        desc.maxLines = 2
        desc.ellipsize = android.text.TextUtils.TruncateAt.END
        val dlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        dlp.leftMargin = dpf(ctx, 17f)
        box.addView(desc, dlp)
        return box
    }

    private fun fixed(w: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun weight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    // ------------------------------------------------------------ 小构件

    /** 状态圆点：6dp，颜色即状态 */
    private fun dot(ctx: Context, color: Int): View {
        val v = View(ctx)
        val d = dpf(ctx, 7f)
        val lp = LinearLayout.LayoutParams(d, d)
        v.layoutParams = lp
        v.background = UiKit.oval(color)
        return v
    }

    /** 胶囊：浅底 + 同色文字（和面板主体按钮区分：这里不可点） */
    private fun chip(ctx: Context, text: String, fg: Int, bg: Int): TextView {
        val t = TextView(ctx)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        t.setTextColor(fg)
        t.setPadding(dpf(ctx, 9f), dpf(ctx, 4f), dpf(ctx, 9f), dpf(ctx, 4f))
        t.background = UiKit.roundRect(bg, dpf(ctx, 11f).toFloat())
        return t
    }

    private fun sectionTitle(ctx: Context, text: String): View {
        val tv = UiKit.sectionTitle(ctx, text)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.setPadding(dpf(ctx, 4f), dpf(ctx, 14f), dpf(ctx, 4f), dpf(ctx, 6f))
        return tv
    }

    private fun hint(ctx: Context, text: String): View {
        val tv = UiKit.hintView(ctx, text, 11f)
        tv.setPadding(dpf(ctx, 4f), 0, dpf(ctx, 4f), dpf(ctx, 6f))
        return tv
    }

    // ------------------------------------------------------------ 文案

    /** 功能名 -> 对应开关的 Prefs 键（面板里的同名开关）。 */
    private fun prefKey(key: String): String? = when (key) {
        "splash" -> Prefs.SKIP_SPLASH
        "shake" -> Prefs.SKIP_SHAKE
        "upgrade" -> Prefs.SKIP_UPGRADE
        "track" -> Prefs.BLOCK_TRACK
        "utdid" -> Prefs.BLOCK_UTDID
        "message" -> Prefs.FILTER_SERVICE
        "searchad" -> Prefs.FILTER_SEARCH_AD
        // 素材下载没有单独开关，跟随开屏
        else -> null
    }

    private fun featureOn(ctx: Context, key: String): Boolean {
        val k = prefKey(key) ?: return true
        return Prefs.get(ctx, k, Prefs.defOf(k))
    }

}
