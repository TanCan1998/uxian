package com.xiandehuang.u

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/** 拖拽柄立即起拖，列表边缘自动滚动；保持触摸目标挂载直到松手。 */
internal class KingkongDragList(
    private val ctx: Context,
    private val draft: ArrayList<KingkongOrder.Item>,
    private val list: LinearLayout,
    private val scroll: ScrollView,
) {
    private val rows = ArrayList<LinearLayout>()
    private var from = -1
    private var target = -1
    private var fingerY = 0f
    private var grabOffset = 0f
    private val rowHeight = UiKit.rowHeight(ctx)
    private val location = IntArray(2)
    private val edge = UiKit.dp(ctx, 48f)
    private val tick = object : Runnable {
        override fun run() {
            if (from < 0) return
            scroll.getLocationOnScreen(location)
            val y = fingerY - location[1]
            var delta = 0
            if (y < edge) delta = -UiKit.dp(ctx, 8f)
            else if (y > scroll.height - edge) delta = UiKit.dp(ctx, 8f)
            if (delta != 0) {
                scroll.scrollBy(0, delta)
                update()
            }
            scroll.postDelayed(this, 16L)
        }
    }

    fun render() {
        list.removeAllViews()
        rows.clear()
        for (i in 0 until draft.size) {
            val row = UiKit.row(ctx)
            val label = UiKit.textView(ctx, "${i + 1}. ${draft[i].title}", 14f)
            label.maxLines = 2
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            val handle = UiKit.textView(ctx, "☰", 22f)
            handle.gravity = Gravity.CENTER
            handle.contentDescription = "拖动排序：" + draft[i].title
            handle.isFocusable = true
            row.addView(handle, LinearLayout.LayoutParams(UiKit.dp(ctx, 48f), -1))
            handle.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        from = i
                        target = i
                        fingerY = event.rawY
                        row.getLocationOnScreen(location)
                        grabOffset = fingerY - location[1]
                        scroll.requestDisallowInterceptTouchEvent(true)
                        row.elevation = UiKit.dp(ctx, 8f).toFloat()
                        row.background = UiKit.roundRect(Theme.of(ctx).card(), UiKit.dp(ctx, 8f).toFloat())
                        row.alpha = 0.92f
                        scroll.removeCallbacks(tick)
                        scroll.post(tick)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (from >= 0) { fingerY = event.rawY; update() }
                        true
                    }
                    MotionEvent.ACTION_UP -> { finish(true); true }
                    MotionEvent.ACTION_CANCEL -> { finish(false); true }
                    else -> true
                }
            }
            // 拖拽之外仍可用无障碍「前移/后移」动作。
            handle.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    if (i > 0) info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(0x01020001, "前移"))
                    if (i + 1 < draft.size) info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(0x01020002, "后移"))
                }
                override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                    val to = if (action == 0x01020001) i - 1 else if (action == 0x01020002) i + 1 else -1
                    if (to >= 0 && to < draft.size) {
                        draft.add(to, draft.removeAt(i))
                        render()
                        return true
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            }
            rows.add(row)
            list.addView(row)
        }
    }

    private fun update() {
        if (from < 0 || from >= rows.size) return
        list.getLocationOnScreen(location)
        val top = (fingerY - location[1] - grabOffset).coerceIn(0f, ((rows.size - 1) * rowHeight).toFloat())
        target = ((top + rowHeight / 2f) / rowHeight).toInt().coerceIn(0, rows.size - 1)
        for (i in 0 until rows.size) {
            rows[i].translationY = when {
                i == from -> top - rows[i].top
                from < target && i > from && i <= target -> -rowHeight.toFloat()
                target < from && i >= target && i < from -> rowHeight.toFloat()
                else -> 0f
            }
        }
    }

    private fun finish(commit: Boolean) {
        scroll.removeCallbacks(tick)
        if (from < 0) return
        if (commit && target >= 0 && target != from) draft.add(target, draft.removeAt(from))
        from = -1
        target = -1
        scroll.requestDisallowInterceptTouchEvent(false)
        val y = scroll.scrollY
        render()
        scroll.post { scroll.scrollTo(0, y) }
    }

    fun cancel() { finish(false) }
}
