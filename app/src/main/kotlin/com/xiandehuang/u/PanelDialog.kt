package com.xiandehuang.u

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 二级界面外壳。
 *
 * 面板（[SettingsPanel]）本身就是一个无标题栏的自绘 Dialog：圆角卡片背景、
 * 底部一个居中的「关闭」。功能页和结果页如果各自用 AlertDialog，会立刻显出
 * 两套观感（标题栏、按钮分隔线、系统配色），所以在二级页面上一律复用同一套
 * 外壳 —— 从面板点进去像是「进了一页」，而不是「弹了个系统对话框」。
 *
 * **圆角、宽度、留白、关闭按钮全部来自 [UiKit]**，和面板、loading 用的是
 * 同一组常量。以前这三处各写了一份，圆角 16dp / 12dp 混用过，改一处忘两处。
 */
object PanelDialog {

    /**
     * 弹出一个二级页面。
     *
     * @param title   顶部标题（为空则不显示标题行）
     * @param content 内容区，自己负责滚动
     * @param ratio   内容区高度策略：
     *                - [AUTO]（默认）：**按内容实际高度**，超过 [MAX_RATIO] 才滚动
     *                - 正数：兼容旧写法，表示该页的最大占比（仍自适应）
     *                - [FILL]：内容为加权列表，占满统一上限
     * @param maxRatio 统一最大占比：自适应高度达到这里才内部滚动
     * @param action  标题栏右上角的文字动作（如「清空」），null 则不显示
     * @param onAction 点右上角动作的回调
     * @param onClose 关闭时的回调（可为 null）
     */
    fun show(
        activity: Activity,
        title: String,
        content: View,
        ratio: Float = AUTO,
        maxRatio: Float = MAX_RATIO,
        action: String? = null,
        onAction: Runnable? = null,
        onClose: Runnable? = null,
    ): Dialog {
        val ctx: Context = activity
        val container = LinearLayout(ctx)
        container.orientation = LinearLayout.VERTICAL
        if (title.isNotEmpty() || action != null) {
            container.addView(header(ctx, title, action, onAction))
        }

        val fill = ratio == FILL
        // 统一高度策略：一律按内容自适应；超过统一上限才内部滚动。
        // 正数比例是旧写法，现只作为「该页最大占比」使用，不再固定高度。
        val contentLp = if (fill) {
            // FILL 页面内容本身是加权列表，高度由窗口上限决定。
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        } else {
            val cap = if (ratio > 0f) minOf(ratio, maxRatio) else maxRatio
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                fitHeight(activity, content, cap),
            )
        }
        container.addView(content, contentLp)

        val close = closeButton(ctx)
        container.addView(
            close,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val dialog = buildDialog(activity, container, fill)
        close.setOnClickListener {
            dialog.dismiss()
            onClose?.run()
        }
        dialog.setOnDismissListener {
            onClose?.run()
            notifyChildClosed()
        }
        dialog.show()

        return dialog
    }

    /** 二级页面关闭后通知一级面板刷新（由 [SettingsPanel] 注册） */
    @Volatile
    @JvmField
    var childClosed: Runnable? = null

    private var lastNotify = 0L

    private fun notifyChildClosed() {
        val cb = childClosed ?: return
        val now = System.currentTimeMillis()
        // 同一次关闭会走「按钮回调 + dismiss 监听」两条路，去重一次
        if (now - lastNotify < 250L) return
        lastNotify = now
        try {
            cb.run()
        } catch (ignored: Throwable) {
        }
    }

    /** [show] 的 ratio 传它表示「内容区撑满可用高度」 */
    const val FILL: Float = -1f

    /** [show] 的 ratio 传它表示「按内容自适应高度」 */
    const val AUTO: Float = -2f

    /** 内容区最大高度（[AUTO] 用）。要扣掉标题栏和「关闭」，否则窗口比屏幕高。 */
    private fun maxHeight(activity: Activity, maxRatio: Float): Int {
        val screen = UiKit.dialogContentHeight(activity, 1f)
        val chrome = UiKit.dp(activity, 118f)
        return minOf(UiKit.dialogContentHeight(activity, maxRatio), screen - chrome)
    }

    /** 统一最大占比：所有弹窗共用，列表型也只到这里。 */
    const val MAX_RATIO: Float = 0.72f

    /** 内容自适应高度：按实际内容收紧，最高不超过 [capRatio] 对应的可用高度。 */
    fun fitHeight(activity: Activity, content: View, capRatio: Float = MAX_RATIO): Int {
        val measured = measureAt(content, UiKit.dialogWidth(activity))
        val cap = maxHeight(activity, minOf(capRatio, MAX_RATIO))
        if (measured <= 0) return cap
        val floor = UiKit.dp(activity, 96f)
        return minOf(maxOf(measured, floor), cap)
    }

    /**
     * 以固定宽度、不限高度量一次内容，返回它**真正需要**的高度。
     *
     * 不能直接 `content.measure(UNSPECIFIED)` 然后读 `measuredHeight`：
     * 内容区里常带 `layoutParams(height=0, weight=1)` 的子 View（抓包页/日志页
     * 都是这个结构，为了让列表撑满），weight 在 UNSPECIFIED 下算不出高度，
     * 结果量出来是 0，弹窗会被压扁成一条。
     *
     * 所以这里按「子 View 高度之和 + 自身 padding」自己算一遍：
     * 带 weight 的子 View 用它的 `measuredHeight`（内容高度），其余用 layoutParams。
     */
    private fun measureAt(content: View, width: Int): Int {
        val wSpec = View.MeasureSpec.makeMeasureSpec(
            maxOf(width, 1), View.MeasureSpec.EXACTLY,
        )
        val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        content.measure(wSpec, hSpec)

        val group = content as? android.view.ViewGroup
        if (group == null) return content.measuredHeight

        var total = content.paddingTop + content.paddingBottom
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) ?: continue
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams
            var h = child.measuredHeight
            if (h <= 0 && lp != null && lp.height > 0) h = lp.height
            if (h <= 0) {
                // 还没量过：单独量一次（同样是 UNSPECIFIED 高度）
                child.measure(wSpec, hSpec)
                h = child.measuredHeight
            }
            var extra = 0
            if (lp is android.view.ViewGroup.MarginLayoutParams) {
                extra = lp.topMargin + lp.bottomMargin
            }
            total += maxOf(h, 0) + extra
        }
        return maxOf(total, content.measuredHeight)
    }

    // ------------------------------------------------------------ 构件

    /**
     * 标题栏：左侧标题，右侧可选文字动作（红字，如「清空」）。
     *
     * 动作做成**纯文字**而不是胶囊按钮：它跟「关闭」是同一类操作（都是文字），
     * 但破坏性更强，所以用 `warnText()` 红字区分。胶囊按钮留给「执行」这种
     * 主操作 —— 一个弹窗里最多一个主操作，其余都退成文字。
     */
    private fun header(
        ctx: Context,
        title: String,
        action: String?,
        onAction: Runnable?,
    ): View {
        val th = Theme.of(ctx)
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(UiKit.dp(ctx, 16f), UiKit.dp(ctx, 12f), UiKit.dp(ctx, 16f), UiKit.dp(ctx, 6f))

        if (title.isNotEmpty()) {
            val t = TextView(ctx)
            t.text = title
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            t.setTypeface(t.typeface, Typeface.BOLD)
            t.setTextColor(th.text())
            row.addView(
                t,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        } else {
            row.addView(
                View(ctx),
                LinearLayout.LayoutParams(0, 1, 1f),
            )
        }

        if (action != null) {
            val a = TextView(ctx)
            a.text = action
            a.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            a.setTextColor(th.warnText())
            a.setPadding(UiKit.dp(ctx, 12f), UiKit.dp(ctx, 4f), 0, UiKit.dp(ctx, 4f))
            a.isClickable = true
            a.isFocusable = true
            if (onAction != null) {
                a.setOnClickListener { onAction.run() }
            }
            row.addView(a)
        }
        return row
    }

    /** 底部居中「关闭」：颜色取 Theme.text()，不另设色 */
    fun closeButton(ctx: Context): TextView {
        val t = TextView(ctx)
        t.text = "关闭"
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        t.setTextColor(Theme.of(ctx).text())
        t.gravity = Gravity.CENTER
        t.setPadding(0, UiKit.dp(ctx, 13f), 0, UiKit.dp(ctx, 13f))
        return t
    }

    /**
     * 一排胶囊操作按钮（结果页的「打开 / 分享」这类）。
     *
     * 全部等分宽度、右对齐排列；`actions` 最多 3 个 —— 再多就挤不下了，
     * 真需要更多动作就改成竖排列表行。
     *
     * @param onDone 每个按钮点完后都会调用（一般用来关掉当前弹窗）
     */
    fun actionRow(
        ctx: Context,
        actions: List<Pair<String, Runnable>>,
        onDone: Runnable?,
    ): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        row.setPadding(UiKit.dp(ctx, 14f), UiKit.dp(ctx, 8f), UiKit.dp(ctx, 14f), UiKit.dp(ctx, 4f))
        for (a in actions) {
            val b = UiKit.pillButton(ctx, a.first)
            val act = a.second
            b.setOnClickListener {
                act.run()
                onDone?.run()
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.leftMargin = UiKit.dp(ctx, 8f)
            row.addView(b, lp)
        }
        return row
    }

    /**
     * 建一个统一外观的 Dialog（无标题栏、圆角 [UiKit.dialogRadius]、45% 遮罩）。
     *
     * [LoadingDialog] 也走这里 —— loading 之前用 AlertDialog，圆角是系统默认的
     * 4dp 直角感，和面板放一起明显不是一套东西。
     *
     * @param fillHeight true 时窗口高度固定为统一上限 [MAX_RATIO]，
     *                   让内容区的权重能撑开；false 时高度随内容（调用方已算好）。
     */
    fun buildDialog(activity: Activity, container: View, fillHeight: Boolean = false): Dialog {
        val ctx: Context = activity
        val th = Theme.of(ctx)
        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setContentView(container)
        dialog.window?.let { win ->
            win.setBackgroundDrawable(UiKit.roundRect(th.bg(), UiKit.dialogRadius(ctx)))
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val wp = win.attributes
            wp.dimAmount = 0.45f
            wp.width = UiKit.dialogWidth(activity)
            wp.height = if (fillHeight) {
                UiKit.dialogContentHeight(activity, MAX_RATIO)
            } else {
                WindowManager.LayoutParams.WRAP_CONTENT
            }
            win.attributes = wp
        }
        return dialog
    }

    /** 把一串 View 包成可竖滚的容器（二级页面的内容区都这么用） */
    fun scrollWrap(ctx: Context, inner: View): ScrollView {
        val sv = ScrollView(ctx)
        sv.isVerticalScrollBarEnabled = false
        sv.setBackgroundColor(Color.TRANSPARENT)
        sv.addView(
            inner,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return sv
    }
}
