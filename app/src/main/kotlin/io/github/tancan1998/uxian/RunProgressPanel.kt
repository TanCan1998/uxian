package io.github.tancan1998.uxian

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * 执行进度与结果窗口。
 *
 * 高度始终跟随内容：步骤增加就长高，达到统一上限后在窗口内滚动。
 * 执行中提供「后台执行」，关闭只隐藏窗口；结束后保留全部步骤与汇总。
 */
object RunProgressPanel {

    fun launch(activity: Activity, ctx: Context, name: String, title: String, btn: TextView): Boolean {
        // 已有一轮在跑：直接把进度页拉回来，不重复启动。
        val active = RunProgress.latest
        if (active != null && active.running) {
            show(activity, active)
            return true
        }
        val session = Chains.startManual(ctx.applicationContext ?: ctx, name, title)
        bindButton(session, btn)
        show(activity, session)
        return true
    }

    /** 执行结束回调：按钮恢复可点，不靠轮询线程。 */
    private fun bindButton(session: RunProgress.Session, btn: TextView) {
        val done = Runnable {
            Handler(Looper.getMainLooper()).post(Runnable {
                try { UiKit.resetRunButton(btn, "执行") } catch (_: Throwable) {}
            })
        }
        session.onFinish(done)
        if (!session.running) done.run()
    }

    fun show(activity: Activity, session: RunProgress.Session) {
        val ctx: Context = activity
        val th = Theme.of(ctx)
        val running = session.running

        val root = UiKit.column(ctx)
        val pad = UiKit.dp(ctx, 14f)
        root.setPadding(pad, UiKit.dp(ctx, 2f), pad, UiKit.dp(ctx, 4f))

        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val spinner = ProgressBar(ctx, null, android.R.attr.progressBarStyleSmall)
        spinner.isIndeterminate = true
        try { spinner.indeterminateDrawable?.setColorFilter(th.accent(), android.graphics.PorterDuff.Mode.SRC_IN) } catch (_: Throwable) {}
        if (!running) spinner.visibility = View.GONE
        head.addView(spinner, LinearLayout.LayoutParams(UiKit.dp(ctx, 20f), UiKit.dp(ctx, 20f)))
        val current = UiKit.hintView(ctx, session.current, 12f)
        current.setPadding(UiKit.dp(ctx, 8f), 0, 0, 0)
        head.addView(current, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(head)

        val list = UiKit.column(ctx)
        list.setPadding(0, UiKit.dp(ctx, 6f), 0, 0)
        root.addView(list)
        val scroller = PanelDialog.scrollWrap(ctx, root)

        var closed = false
        var dialog: Dialog? = null
        fun lines() {
            list.removeAllViews()
            for (line in session.snapshot()) list.addView(lineView(ctx, line))
        }
        /** 按当前内容重算高度；窗口是 WRAP_CONTENT，改内容高度即生效。 */
        fun fit() {
            try {
                val h = PanelDialog.fitHeight(activity, scroller)
                val lp = scroller.layoutParams ?: return
                if (lp.height != h) {
                    lp.height = h
                    scroller.layoutParams = lp
                    root.requestLayout()
                }
            } catch (_: Throwable) {
            }
        }
        lines()

        dialog = PanelDialog.show(
            activity,
            "执行详情",
            scroller,
            ratio = PanelDialog.AUTO,
            action = if (running) "后台执行" else null,
            onAction = Runnable {
                closed = true
                try { dialog?.dismiss() } catch (_: Throwable) {}
            },
        )
        try { dialog?.setCanceledOnTouchOutside(false) } catch (_: Throwable) {}
        scroller.post(Runnable { fit() })

        if (!running) return
        val handler = Handler(Looper.getMainLooper())
        var seen = -1
        val tick = object : Runnable {
            override fun run() {
                if (closed) return
                if (session.revision != seen) {
                    seen = session.revision
                    current.text = session.current
                    lines()
                    fit()
                    if (!session.running) {
                        spinner.visibility = View.GONE
                        return
                    }
                }
                handler.postDelayed(this, 400)
            }
        }
        handler.post(tick)
    }

    private fun lineView(ctx: Context, line: RunProgress.Line): View {
        val th = Theme.of(ctx)
        val tv = TextView(ctx)
        tv.text = line.text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tv.setTextColor(if (line.failed) th.warnText() else th.text())
        tv.setPadding(0, UiKit.dp(ctx, 3f), 0, UiKit.dp(ctx, 3f))
        return tv
    }
}
