package io.github.tancan1998.uxian

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 首页 tab：拖拽排序 + 点击隐藏；保存后重启闲鱼生效。 */
object HomeTabPanel {
    private class Drag(
        val ctx: Activity,
        val items: ArrayList<HomeTabOrder.Item>,
        val list: LinearLayout,
        val scroll: ScrollView,
        val rerender: () -> Unit,
    ) {
        private var from = -1
        private var target = -1
        private var fingerY = 0f
        private var grab = 0f
        private val rowH = UiKit.rowHeight(ctx)
        private val loc = IntArray(2)
        private val edge = UiKit.dp(ctx, 44f)
        private val tick = object : Runnable {
            override fun run() {
                if (from < 0) return
                scroll.getLocationOnScreen(loc)
                val y = fingerY - loc[1]
                var d = 0
                if (y < edge) d = -UiKit.dp(ctx, 8f)
                else if (y > scroll.height - edge) d = UiKit.dp(ctx, 8f)
                if (d != 0) {
                    scroll.scrollBy(0, d)
                    update()
                }
                scroll.postDelayed(this, 16L)
            }
        }

        fun handle(i: Int, row: LinearLayout): TextView {
            val h = UiKit.textView(ctx, "☰", 22f)
            h.gravity = Gravity.CENTER
            h.contentDescription = "拖动排序：" + items[i].title
            h.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        from = i
                        target = i
                        fingerY = e.rawY
                        row.getLocationOnScreen(loc)
                        grab = fingerY - loc[1]
                        scroll.requestDisallowInterceptTouchEvent(true)
                        row.background = UiKit.roundRect(Theme.of(ctx).card(), UiKit.dp(ctx, 8f).toFloat())
                        row.alpha = 0.92f
                        scroll.removeCallbacks(tick)
                        scroll.post(tick)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> { if (from >= 0) { fingerY = e.rawY; update() }; true }
                    MotionEvent.ACTION_UP -> { finish(true); true }
                    MotionEvent.ACTION_CANCEL -> { finish(false); true }
                    else -> true
                }
            }
            return h
        }

        private fun rows(): ArrayList<LinearLayout> {
            val out = ArrayList<LinearLayout>()
            for (i in 0 until list.childCount) {
                val v = list.getChildAt(i)
                if (v is LinearLayout && v.tag == "row") out.add(v)
            }
            return out
        }

        private fun update() {
            if (from < 0) return
            val rs = rows()
            if (from >= rs.size) return
            list.getLocationOnScreen(loc)
            val top = (fingerY - loc[1] - grab).coerceIn(0f, ((rs.size - 1) * rowH).toFloat())
            target = ((top + rowH / 2f) / rowH).toInt().coerceIn(0, rs.size - 1)
            for (i in rs.indices) {
                rs[i].translationY = when {
                    i == from -> top - rs[i].top
                    from < target && i > from && i <= target -> -rowH.toFloat()
                    target < from && i >= target && i < from -> rowH.toFloat()
                    else -> 0f
                }
            }
        }

        private fun finish(commit: Boolean) {
            scroll.removeCallbacks(tick)
            if (from < 0) return
            if (commit && target >= 0 && target != from && target < items.size) {
                items.add(target, items.removeAt(from))
            }
            from = -1
            target = -1
            scroll.requestDisallowInterceptTouchEvent(false)
            val y = scroll.scrollY
            rerender()
            scroll.post { scroll.scrollTo(0, y) }
        }

        fun cancel() { finish(false) }
    }

    fun show(activity: Activity, onSaved: Runnable? = null) {
        val items = HomeTabOrder.items(activity)
        val box = UiKit.column(activity)
        val hint = UiKit.hintView(
            activity,
            if (items.isEmpty()) "先打开首页，等顶部 tab 加载出来，再回来设置。"
            else "拖右侧把手排序，点「隐藏」把不用的 tab 收起来；保存后重启闲鱼生效。",
        )
        val hpad = UiKit.dp(activity, 16f)
        hint.setPadding(hpad, UiKit.dp(activity, 8f), hpad, UiKit.dp(activity, 12f))
        box.addView(hint)

        val list = UiKit.column(activity)
        val scroll = PanelDialog.scrollWrap(activity, list)
        box.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        var dialog: Dialog? = null
        var drag: Drag? = null

        fun render() {
            list.removeAllViews()
            for (i in items.indices) {
                val item = items[i]
                val row = UiKit.row(activity)
                row.tag = "row"
                val title = UiKit.textView(activity, (i + 1).toString() + ". " + item.title, 14f)
                title.alpha = if (item.hidden) 0.45f else 1f
                row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
                val toggle = UiKit.textView(activity, if (item.hidden) "显示" else "隐藏", 13f)
                toggle.gravity = Gravity.CENTER
                toggle.contentDescription = (if (item.hidden) "显示" else "隐藏") + item.title
                toggle.setPadding(UiKit.dp(activity, 10f), 0, UiKit.dp(activity, 10f), 0)
                toggle.setOnClickListener {
                    if (!item.hidden) {
                        var visible = 0
                        for (x in items) if (!x.hidden) visible++
                        if (visible <= 1) {
                            Toast.makeText(activity, "至少要保留一个 tab", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                    }
                    items[i] = HomeTabOrder.Item(item.id, item.title, !item.hidden)
                    render()
                }
                row.addView(toggle, LinearLayout.LayoutParams(UiKit.dp(activity, 52f), -1))
                val handle = drag?.handle(i, row)
                if (handle != null) row.addView(handle, LinearLayout.LayoutParams(UiKit.dp(activity, 44f), -1))
                list.addView(row)
            }
        }
        drag = Drag(activity, items, list, scroll) { render() }
        render()

        if (items.isNotEmpty()) {
            val footer = UiKit.row(activity)
            footer.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            val save = UiKit.pillButton(activity, "保存")
            footer.addView(save)
            save.setOnClickListener {
                if (HomeTabOrder.save(activity, items)) {
                    Toast.makeText(activity, "已保存，重启闲鱼后生效", Toast.LENGTH_SHORT).show()
                    dialog?.dismiss()
                    onSaved?.run()
                } else Toast.makeText(activity, "保存失败，请重试", Toast.LENGTH_SHORT).show()
            }
            box.addView(footer)
        }

        dialog = PanelDialog.show(
            activity, "首页 tab", box,
            ratio = if (items.isEmpty()) PanelDialog.AUTO else PanelDialog.FILL,
            onClose = Runnable { drag.cancel() },
            action = "恢复默认", onAction = Runnable {
                val confirmBox = UiKit.column(activity)
                val msg = UiKit.hintView(activity, "清除排序和隐藏，恢复默认的四个 tab？")
                val p = UiKit.dp(activity, 16f)
                msg.setPadding(p, p, p, p)
                confirmBox.addView(msg)
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
                    if (HomeTabOrder.reset(activity)) {
                        confirm.dismiss()
                        dialog?.dismiss()
                        Toast.makeText(activity, "已恢复默认，重启闲鱼后生效", Toast.LENGTH_SHORT).show()
                        onSaved?.run()
                    } else Toast.makeText(activity, "保存失败，请重试", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}
