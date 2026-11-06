package com.xiandehuang.u

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 业务链的**逐项执行**页面。
 *
 * 面板上的「手动」是一次跑完全部；这一页是每项单独跑（有时候只想补签个到，
 * 不想把掷骰子也带上）。
 *
 * ## 布局（用户明确要求）
 *
 * 每项一行：`标题  说明…………  [执行]`
 * 说明内联在标题后面，不再单独占一行 —— 见 [SettingsPanel.rowAction] 的注释。
 *
 * ## 交互约束（用户明确要求，都是被否定过的方向）
 *  - 触发一律按钮点击，**不用长按**
 *  - 文案全中文，不出现纯英文
 *  - 执行过程逐条显示（用户要看到停在哪一步），不能只有「成功/失败」
 *  - 二级页面复用 [PanelDialog] 的外壳，跟面板同一套观感
 *
 * 线程模型：mtop 调用全在后台线程，结果回主线程。点击期间按钮置灰，
 * 防止重复提交（签到幂等，但掷骰子不是）。
 */
object FeaturePanel {

    // 与启动轮、定时轮和无头接口共用目录，新增入口只登记一次。
    private val ITEMS = ChainPlan.entries

    fun show(activity: Activity) {
        val ctx: Context = activity

        val box = UiKit.column(ctx)
        val p = UiKit.dp(ctx, 12f)
        box.setPadding(p, UiKit.dp(ctx, 2f), p, p)

        val tip = UiKit.hintView(ctx, "选择要执行的功能，过程会逐条显示", 12f)
        tip.setPadding(UiKit.dp(ctx, 4f), 0, UiKit.dp(ctx, 4f), UiKit.dp(ctx, 8f))
        box.addView(tip)

        // 前置条件预检：在用户点之前就把「asac 还没攒到」「骰子为 0」摆出来，
        // 而不是等执行到一半报「服务竟然出错」（那个文案完全没指向性）。
        //
        // ⚠️ 必须在后台线程跑：peekTreasure 会调 game.refresh（真网络请求），
        // 放主线程会把面板卡住几百毫秒到几秒。
        // 所以先占位、页面立即可见，结果回来再往里填。
        val slot = UiKit.column(ctx)
        box.addView(slot)
        Thread {
            val notReady = ArrayList<ChainPreflight.Check>()
            try {
                val checks = ChainPreflight.run(ctx)
                for (c in checks) {
                    if (!c.ready) notReady.add(c)
                }
            } catch (t: Throwable) {
                // 预检失败不能挡住功能页
            }
            if (notReady.isEmpty()) return@Thread
            Handler(Looper.getMainLooper()).post {
                for (c in notReady) {
                    val w = UiKit.warnBar(ctx, c.name + "：" + c.hint)
                    UiKit.marginBottom(ctx, w, 6f)
                    slot.addView(w)
                }
            }
        }.start()

        for (it in ITEMS) box.addView(itemRow(activity, it))

        PanelDialog.show(
            activity, "逐项执行", PanelDialog.scrollWrap(ctx, box),
            // 12 项固定内容本来就占满屏；AUTO 会先按内容量一次高度，
            // 内容接近屏高时窗口比屏幕还高，顶部被顶出屏幕露出黑条。
            // 固定比例撑满，内容多就在内部滚动。
            ratio = PanelDialog.AUTO,
        )
    }

    /** 一行功能：标题 + 内联说明 + 「执行」按钮 */
    private fun itemRow(activity: Activity, item: ChainPlan.Entry): View {
        val ctx: Context = activity
        val th = Theme.of(ctx)

        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val pad = UiKit.dp(ctx, 12f)
        row.setPadding(pad, UiKit.dp(ctx, 10f), pad, UiKit.dp(ctx, 10f))
        row.background = UiKit.roundRect(th.card(), UiKit.dp(ctx, 10f).toFloat())
        val rlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        rlp.bottomMargin = UiKit.dp(ctx, 8f)
        row.layoutParams = rlp

        // 标题 + 说明拼进同一个 TextView，说明用 hint 色 + 小一号
        val label = TextView(ctx)
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        val sb = android.text.SpannableStringBuilder(item.title)
        sb.setSpan(
            android.text.style.StyleSpan(Typeface.BOLD), 0, item.title.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        sb.setSpan(
            android.text.style.ForegroundColorSpan(th.text()), 0, item.title.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        if (item.desc.isNotEmpty()) {
            val at = sb.length
            sb.append("  ").append(item.desc)
            sb.setSpan(
                android.text.style.ForegroundColorSpan(th.hint()), at, sb.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            sb.setSpan(
                android.text.style.RelativeSizeSpan(0.8f), at, sb.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        label.text = sb
        row.addView(
            label,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        val btn = UiKit.pillButton(ctx, "执行")
        btn.setOnClickListener {
            // 执行中再次点击 = 回到步骤页；结束后按钮由回调恢复，无需置灰。
            btn.text = "执行中"
            RunProgressPanel.launch(activity, ctx, item.id, item.title, btn)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.leftMargin = UiKit.dp(ctx, 10f)
        row.addView(btn, lp)
        return row
    }

    /** 结果页：和功能页同一个外壳，逐步骤 ✓ / ✗。面板「手动」也用它。 */
    fun showResult(activity: Activity, title: String, results: List<Chains.Result>) {
        val ctx: Context = activity
        val th = Theme.of(ctx)

        val box = UiKit.column(ctx)
        val pad = UiKit.dp(ctx, 14f)
        box.setPadding(pad, UiKit.dp(ctx, 2f), pad, pad)

        for (r in results) {
            if (results.size > 1) {
                val h = TextView(ctx)
                h.text = r.title
                h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                h.setTypeface(h.typeface, Typeface.BOLD)
                h.setTextColor(th.text())
                h.setPadding(0, UiKit.dp(ctx, 8f), 0, UiKit.dp(ctx, 4f))
                box.addView(h)
            }
            if (r.steps.isEmpty()) {
                box.addView(UiKit.hintView(ctx, "没有执行任何步骤", 12f))
            }
            for (s in r.steps) {
                val tv = TextView(ctx)
                // ✓/✗ 比纯文字更快看出结果；颜色再强调一次
                val mark = if (s.skipped) "—" else if (s.ok) "✓" else "✗"
                tv.text = "$mark ${s.name}：${s.detail}"
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                tv.setTextColor(if (s.ok) th.text() else th.warnText())
                tv.setPadding(0, UiKit.dp(ctx, 3f), 0, UiKit.dp(ctx, 3f))
                box.addView(tv)
            }
        }

        // AUTO：按实际步骤数决定高度。一条链只有两三行时不该占半屏 ——
        // 底部一大片空白会被误认为「还在加载」。超过统一上限 屏才滚动。
        PanelDialog.show(
            activity, title, PanelDialog.scrollWrap(ctx, box),
            ratio = PanelDialog.AUTO,
        )
    }
}
