package io.github.tancan1998.uxian

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 「执行记录」二级页：每次跑链的结果，按时间倒序。
 *
 * 从适配性页拆出来的（用户要求）：适配性回答「模块挂没挂上」，
 * 执行记录回答「上次跑成什么样」，两件事不该挤在一页里。
 *
 * 列宽写死（时间 84dp / 功能 92dp / 结果自适应两行）—— 中文字宽不一致，
 * 用 weight 分列会参差不齐。
 */
object HistoryPanel {

    private fun dpf(ctx: Context, v: Float): Int = UiKit.dp(ctx, v)

    fun show(activity: Activity) {
        val ctx: Context = activity
        val th = Theme.of(ctx)
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        val p = dpf(ctx, 12f)
        box.setPadding(p, dpf(ctx, 2f), p, p)

        val list = try {
            ChainHistory.recent(ctx, 60)
        } catch (t: Throwable) {
            ArrayList<ChainHistory.Entry>()
        }

        val tip = UiKit.hintView(ctx, "每次执行按链记录，点「立即执行」就会多一轮。", 11f)
        tip.setPadding(dpf(ctx, 4f), dpf(ctx, 4f), dpf(ctx, 4f), dpf(ctx, 8f))
        box.addView(tip)

        if (list.isEmpty()) {
            box.addView(UiKit.hintView(ctx, "还没有执行记录。", 12f))
        } else {
            val card = UiKit.card(ctx)
            card.addView(header(ctx))
            for (i in list.indices) {
                card.addView(UiKit.divider(ctx))
                card.addView(row(ctx, list[i]))
            }
            box.addView(card)
        }

        PanelDialog.show(
            activity, "执行记录", PanelDialog.scrollWrap(ctx, box),
            ratio = PanelDialog.AUTO,
        )
    }

    private fun header(ctx: Context): View {
        val th = Theme.of(ctx)
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dpf(ctx, 14f), dpf(ctx, 8f), dpf(ctx, 14f), dpf(ctx, 6f))
        row.addView(col(ctx, "时间", 11f, th.hint()), fixed(dpf(ctx, 84f)))
        row.addView(col(ctx, "功能", 11f, th.hint()), fixed(dpf(ctx, 92f)))
        row.addView(col(ctx, "结果", 11f, th.hint()), weight())
        return row
    }

    private fun row(ctx: Context, e: ChainHistory.Entry): View {
        val th = Theme.of(ctx)
        val bad = e.failCount > 0
        val wrap = LinearLayout(ctx)
        wrap.orientation = LinearLayout.VERTICAL

        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dpf(ctx, 14f), dpf(ctx, 8f), dpf(ctx, 14f), dpf(ctx, 8f))
        r.addView(col(ctx, e.timeText(), 12f, th.hint()), fixed(dpf(ctx, 84f)))
        r.addView(col(ctx, zhChain(e.chain), 12f, th.text()), fixed(dpf(ctx, 92f)))
        val res = col(ctx, e.stateText(), 12f, if (bad) th.badText() else th.okText())
        res.maxLines = 2
        r.addView(res, weight())
        wrap.addView(r)

        if (e.note.length > 0 && bad) {
            val note = TextView(ctx)
            note.text = e.note
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            note.setTextColor(th.hint())
            note.setPadding(dpf(ctx, 14f), 0, dpf(ctx, 14f), dpf(ctx, 8f))
            wrap.addView(note)
        }
        return wrap
    }

    private fun col(ctx: Context, s: String, sp: Float, color: Int): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.setTextColor(color)
        t.maxLines = 1
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        return t
    }

    private fun fixed(w: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun weight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun zhChain(key: String): String = when (key) {
        "signIn" -> "每日签到"
        "forest" -> "森林"
        "cash" -> "现金红包"
        "tasks" -> "任务领奖"
        "countdown" -> "任务触发"
        "challenge" -> "多日挑战"
        "circle" -> "圈子签到"
        "treasure" -> "寻宝地图"
        "cycle" -> "圈数挑战"
        "props" -> "背包"
        "member" -> "会员中心"
        "game" -> "闲鱼游戏"
        "all" -> "全部执行"
        else -> key
    }
}
