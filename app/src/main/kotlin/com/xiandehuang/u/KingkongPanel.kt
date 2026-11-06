package com.xiandehuang.u

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast

/** 拖拽移动、草稿保存；关闭编辑页不会提交。 */
object KingkongPanel {
    fun show(activity: Activity, onSaved: Runnable? = null) {
        val draft = KingkongOrder.items(activity)
        val box = UiKit.column(activity)
        val hint = UiKit.hintView(activity, if (draft.isEmpty())
            "先打开首页并下拉刷新，再回来设置。" else "按住右侧把手上下拖动排序，保存后重启闲鱼生效。")
        hint.setPadding(UiKit.dp(activity, 16f), UiKit.dp(activity, 8f), UiKit.dp(activity, 16f), UiKit.dp(activity, 12f))
        box.addView(hint)
        val list = UiKit.column(activity)
        val scroll = PanelDialog.scrollWrap(activity, list)
        box.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        var dialog: Dialog? = null

        val drag = KingkongDragList(activity, draft, list, scroll)
        drag.render()
        if (!draft.isEmpty()) {
            val footer = UiKit.row(activity)
            footer.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            val save = UiKit.pillButton(activity, "保存")
            footer.addView(save)
            save.setOnClickListener {
                if (KingkongOrder.save(activity, draft)) {
                    Toast.makeText(activity, "已保存，重启闲鱼后生效", Toast.LENGTH_SHORT).show()
                    dialog?.dismiss()
                    onSaved?.run()
                } else Toast.makeText(activity, "保存失败，请重试", Toast.LENGTH_SHORT).show()
            }
            box.addView(footer)
        }
        dialog = PanelDialog.show(activity, "金刚位排序", box,
            ratio = if (draft.isEmpty()) PanelDialog.AUTO else PanelDialog.FILL,
            onClose = Runnable { drag.cancel() },
            action = "恢复默认", onAction = Runnable {
                val confirmBox = UiKit.column(activity)
                val message = UiKit.hintView(activity, "清除自定义顺序，恢复首页默认排列？")
                val p = UiKit.dp(activity, 16f)
                message.setPadding(p, p, p, p)
                confirmBox.addView(message)
                val row = UiKit.row(activity)
                row.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                val reset = UiKit.pillButton(activity, "恢复默认")
                val th = Theme.of(activity)
                reset.background = UiKit.roundRect(th.warnText(), UiKit.dp(activity, 16f).toFloat())
                reset.setTextColor(android.graphics.Color.WHITE)
                row.addView(reset)
                confirmBox.addView(row)
                val confirm = PanelDialog.show(activity, "恢复默认", confirmBox, ratio = PanelDialog.AUTO)
                reset.setOnClickListener {
                    if (KingkongOrder.reset(activity)) {
                        confirm.dismiss()
                        dialog?.dismiss()
                        Toast.makeText(activity, "已恢复默认，重启闲鱼后生效", Toast.LENGTH_SHORT).show()
                        onSaved?.run()
                    } else Toast.makeText(activity, "保存失败，请重试", Toast.LENGTH_SHORT).show()
                }
            })
    }
}
