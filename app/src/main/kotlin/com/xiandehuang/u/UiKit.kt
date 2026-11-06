package com.xiandehuang.u

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 面板 / 二级页面 / loading 共用的构件。
 *
 * ## 为什么要单开一个文件
 *
 * 之前 [SettingsPanel]、[FeaturePanel]、[HealthPanel]、[PanelDialog]、
 * [LoadingDialog] 各自抄了一份 `dpf()`、`roundRect()`、卡片背景、行高 50dp、
 * 说明文字 12sp… 结果是同一套观感散在五个文件里：改圆角要改五处，改配色要
 * 改五处，还出现过「二级页面按钮圆角和面板不一致」这种只有对比才看得出的
 * 差异。这里统一成唯一来源，**新写界面一律用这里的函数，不要自己 new
 * GradientDrawable**。
 *
 * ## 观感约定（用户明确要求，都是被否定过的方向）
 *
 *  - 卡片：`Theme.card()` + 12dp 圆角 + `clipToOutline`
 *  - 行：高 50dp、左右 16dp；说明文字用 `Theme.hint()`，**不用 `sub()`**
 *  - 分区标题：13sp、`Theme.hint()`
 *  - 主按钮：胶囊（高 16dp 圆角）、`accent()` 底 + `onAccent()` 字
 *  - 二级页面一律 [PanelDialog]，不用 AlertDialog
 *
 * ## R8 注意
 * 不用 `listOf/arrayListOf` 字面量、不用 `Regex`、不用 `?.let{}` —— 见
 * 用户要求：不用实验性 API，这里只用最朴素的 API。
 */
internal object UiKit {

    // ------------------------------------------------------------ 尺寸

    fun dp(ctx: Context, v: Float): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /** 行高（开关行 / 导航行 / 功能行统一） */
    fun rowHeight(ctx: Context): Int = dp(ctx, 50f)

    /** 卡片圆角 */
    fun cardRadius(ctx: Context): Float = dp(ctx, 12f).toFloat()

    /** 对话框圆角（面板与二级页面必须一致） */
    fun dialogRadius(ctx: Context): Float = dp(ctx, 16f).toFloat()

    /** 弹窗宽度：屏幕宽减去两侧留白 */
    fun dialogWidth(activity: android.app.Activity): Int {
        var w = activity.window.decorView.width
        if (w <= 0) w = activity.resources.displayMetrics.widthPixels
        return maxOf(dp(activity, 260f), w - dp(activity, 20f) * 2)
    }

    /**
     * 弹窗内容区高度：**可视区域**的比例。
     *
     * 不能用 decorView.height —— 它含状态栏/刘海区域，按整屏算出来的窗口
     * 会顶到屏幕外，顶部露出一块黑边（实测反馈）。这里取窗口可见显示区，
     * 再按需要扣掉底部导航。
     */
    fun dialogContentHeight(activity: android.app.Activity, ratio: Float): Int {
        var h = 0
        try {
            val v = activity.window?.decorView
            if (v != null) {
                val r = android.graphics.Rect()
                v.getWindowVisibleDisplayFrame(r)
                h = r.height()
            }
        } catch (ignored: Throwable) {
        }
        if (h <= 0) {
            val dm = activity.resources.displayMetrics
            h = dm.heightPixels - dp(activity, 24f) * 2
        }
        return (h * ratio).toInt()
    }

    // ------------------------------------------------------------ 背景

    fun roundRect(color: Int, radius: Float): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = radius
        return d
    }

    fun oval(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.shape = GradientDrawable.OVAL
        return d
    }

    // ------------------------------------------------------------ 文字

    /** 正文（行标题） */
    fun textView(ctx: Context, s: String, sp: Float): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.setTextColor(Theme.of(ctx).text())
        return t
    }

    /** 说明文字：统一走 hint()，别用 sub() */
    fun hintView(ctx: Context, s: String, sp: Float = 12f): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.setTextColor(Theme.of(ctx).hint())
        return t
    }

    /** 分区标题 */
    fun sectionTitle(ctx: Context, s: String): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        t.setTextColor(Theme.of(ctx).hint())
        t.setPadding(dp(ctx, 6f), dp(ctx, 12f), dp(ctx, 6f), dp(ctx, 6f))
        return t
    }

    /** 分区标题（直接挂到父容器） */
    fun section(ctx: Context, parent: LinearLayout, title: String) {
        parent.addView(sectionTitle(ctx, title))
    }

    // ------------------------------------------------------------ 容器

    /** 一张卡片（圆角 + 裁剪） */
    fun card(ctx: Context): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.background = roundRect(Theme.of(ctx).card(), cardRadius(ctx))
        c.clipToOutline = true
        c.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        return c
    }

    /** 卡片内分隔线：左侧留 16dp，和文字左边缘对齐 */
    fun divider(ctx: Context): View {
        val v = View(ctx)
        v.setBackgroundColor(Theme.of(ctx).divider())
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            maxOf(1, dp(ctx, 0.5f)),
        )
        lp.leftMargin = dp(ctx, 16f)
        v.layoutParams = lp
        return v
    }

    /** 竖排容器（内容区常见） */
    fun column(ctx: Context): LinearLayout {
        val b = LinearLayout(ctx)
        b.orientation = LinearLayout.VERTICAL
        return b
    }

    /**
     * 行容器：水平、垂直居中、高 50dp、左右 16dp。
     * 所有设置行共用它，保证行高与左右对齐一致。
     */
    fun row(ctx: Context): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(ctx, 16f), 0, dp(ctx, 16f), 0)
        r.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            rowHeight(ctx),
        )
        return r
    }

    /**
     * 左侧文字块：主标题 + 说明，占满剩余宽度。
     * 说明放在标题**同一块**里（换行显示），而不是塞到行尾 ——
     * 行尾只有 ~120dp，中文说明会被挤成两三个字一行。
     */
    fun titleBlock(ctx: Context, title: String, desc: String?, sp: Float = 15f): LinearLayout {
        val th = Theme.of(ctx)
        val b = LinearLayout(ctx)
        b.orientation = LinearLayout.VERTICAL

        val t = TextView(ctx)
        t.text = title
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        // 行标题统一常规字重：之前开关行加粗、导航行不加粗，同一张卡片里粗细不一
        t.setTypeface(t.typeface, Typeface.NORMAL)
        // 主标题必须用正文色：浅灰会让人以为整行不可点
        t.setTextColor(th.text())
        b.addView(t)

        if (!desc.isNullOrEmpty()) {
            val d = TextView(ctx)
            d.text = desc
            d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            d.setTextColor(th.hint())
            d.setPadding(0, dp(ctx, 2f), 0, 0)
            b.addView(d)
        }
        return b
    }

    // ------------------------------------------------------------ 按钮

    /**
     * 胶囊按钮。`small=true` 用行内尺寸（高 ~30dp），否则是块级按钮。
     *
     * 返回的 TextView 已经设好点击态与背景，调用方只需 setOnClickListener。
     */
    fun pillButton(ctx: Context, label: String, small: Boolean = true): TextView {
        val th = Theme.of(ctx)
        val b = TextView(ctx)
        b.text = label
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 13f else 15f)
        b.setTextColor(th.onAccent())
        b.gravity = Gravity.CENTER
        if (small) {
            b.setPadding(dp(ctx, 14f), dp(ctx, 7f), dp(ctx, 14f), dp(ctx, 7f))
            b.background = roundRect(th.accent(), dp(ctx, 16f).toFloat())
        } else {
            b.setPadding(0, dp(ctx, 11f), 0, dp(ctx, 11f))
            b.background = roundRect(th.accent(), dp(ctx, 22f).toFloat())
        }
        b.isClickable = true
        b.isFocusable = true
        return b
    }

    /**
     * 行内「执行」按钮。
     *
     * 执行期间置灰 + 文案变「执行中」，返回的 [Runner] 负责状态切换 ——
     * 防止重复提交（签到幂等，但掷骰子不是）。
     */
    fun runButton(
        ctx: Context,
        label: String,
        onRun: () -> Unit,
    ): TextView {
        val b = pillButton(ctx, label)
        val idle = label
        b.setOnClickListener {
            if (!b.isEnabled) return@setOnClickListener
            b.isEnabled = false
            b.alpha = 0.5f
            b.text = "执行中"
            try {
                onRun()
            } catch (t: Throwable) {
                // 回调自己负责恢复；这里兜底一次，别把按钮永久卡死
                b.isEnabled = true
                b.alpha = 1f
                b.text = idle
            }
        }
        return b
    }

    /** 恢复行内执行按钮的空闲态 */
    fun resetRunButton(b: TextView, label: String) {
        b.isEnabled = true
        b.alpha = 1f
        b.text = label
    }

    // ------------------------------------------------------------ 提示条

    /** 警告条：前置条件未就绪、整体失效这类需要用户行动的提示 */
    fun warnBar(ctx: Context, text: String): TextView {
        val th = Theme.of(ctx)
        val w = TextView(ctx)
        w.text = text
        w.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        w.setTextColor(th.warnText())
        w.setPadding(dp(ctx, 10f), dp(ctx, 8f), dp(ctx, 10f), dp(ctx, 8f))
        w.background = roundRect(th.warnBg(), dp(ctx, 8f).toFloat())
        w.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        return w
    }

    /** 给某个 View 加下边距（链式写法省一个变量） */
    fun marginBottom(ctx: Context, v: View, dpValue: Float): View {
        val lp = v.layoutParams
        if (lp is ViewGroup.MarginLayoutParams) {
            lp.bottomMargin = dp(ctx, dpValue)
            v.layoutParams = lp
        }
        return v
    }
}
