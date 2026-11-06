package io.github.tancan1998.uxian

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * 带转圈动画 + 实时计时的 loading 对话框。
 *
 * 原来各功能只用静态文字「正在生成…」，用户看不出是真的在跑还是卡死了
 * （analyze 实测要 7-10 秒）。这里加一个 indeterminate 转圈和秒表，
 * 至少能确认「还活着」。
 *
 * 外壳统一走 [PanelDialog.buildDialog]：圆角、宽度、留白、遮罩都和面板/二级
 * 页面一致。之前这里用的是 AlertDialog，系统默认圆角和面板明显不是一套。
 */
object LoadingDialog {

    /**
     * 显示 loading，返回一个 close 回调。
     *
     * @param cancelable 是否显示「取消」。长任务（关联分析要扫几千条记录）
     *                   必须给，否则用户只能干等或杀宿主。
     * @param onCancel   点取消时的回调。**注意：这只关掉弹窗、让用户能继续用面板，
     *                   后台线程不会被打断** —— 关联分析是纯本地读库，跑完就结束，
     *                   强行中断反而要处理半截状态。回调里由调用方决定要不要丢弃结果。
     *
     * 用法：
     *   val close = LoadingDialog.show(activity, "关联分析", "259 条记录")
     *   Thread { ... ; Handler(main).post { close(); ... } }.start()
     */
    fun show(
        activity: Activity,
        title: String,
        subtitle: String,
        cancelable: Boolean = false,
        onCancel: Runnable? = null,
    ): () -> Unit {
        val ctx: Context = activity
        val th = Theme.of(ctx)

        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL

        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        val p = UiKit.dp(ctx, 18f)
        box.setPadding(p, p, p, if (cancelable) UiKit.dp(ctx, 6f) else p)

        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyle)
        bar.isIndeterminate = true
        // 用主题强调色着色，否则是系统默认色，跟面板配色不搭
        try {
            bar.indeterminateDrawable?.setColorFilter(
                th.accent(), android.graphics.PorterDuff.Mode.SRC_IN,
            )
        } catch (ignored: Throwable) {
        }
        val barSize = UiKit.dp(ctx, 28f)
        box.addView(bar, LinearLayout.LayoutParams(barSize, barSize))

        val texts = LinearLayout(ctx)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(UiKit.dp(ctx, 14f), 0, 0, 0)

        val t1 = UiKit.textView(ctx, title, 14f)

        val t2 = UiKit.hintView(ctx, subtitle, 12f)
        t2.setPadding(0, UiKit.dp(ctx, 3f), 0, 0)

        texts.addView(t1)
        texts.addView(t2)
        box.addView(texts)
        root.addView(box)

        // 「取消」用面板统一的文字样式（和底部「关闭」同款），
        // 右对齐、不带胶囊 —— 它不打断任务，只是个逃生口，不该抢视觉焦点
        var cancelBtn: TextView? = null
        if (cancelable) {
            val c = TextView(ctx)
            c.text = "取消"
            c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            c.setTextColor(th.text())
            c.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            c.setPadding(0, UiKit.dp(ctx, 6f), UiKit.dp(ctx, 18f), UiKit.dp(ctx, 12f))
            c.isClickable = true
            c.isFocusable = true
            root.addView(
                c,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            cancelBtn = c
        }

        val dlg = PanelDialog.buildDialog(activity, root)
        dlg.setCancelable(false)
        try {
            dlg.setCanceledOnTouchOutside(false)
        } catch (ignored: Throwable) {
        }
        dlg.show()

        // 每 200ms 刷新一次已用时间，让用户确认还在跑
        val start = SystemClock.elapsedRealtime()
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                val s = (SystemClock.elapsedRealtime() - start) / 1000.0
                t2.text = String.format(
                    java.util.Locale.US, "%s · 已用 %.1f 秒", subtitle, s,
                )
                handler.postDelayed(this, 200)
            }
        }
        handler.postDelayed(tick, 200)

        val close = {
            try {
                handler.removeCallbacks(tick)
            } catch (ignored: Throwable) {
            }
            try {
                dlg.dismiss()
            } catch (ignored: Throwable) {
            }
        }

        if (cancelBtn != null) {
            cancelBtn.setOnClickListener {
                close()
                onCancel?.run()
            }
        }
        return close
    }
}
