package com.xiandehuang.u

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.view.ViewGroup
import android.widget.TextView

/**
 * 面板 UI：仿设置页样式，全部用代码构建，不新增 Activity、不改 manifest。
 *
 * 只负责"画界面 + 接线"，不碰注入逻辑，改样式不会牵连入口注入。
 */
internal object SettingsPanel {

    /** 一级面板当前页面与宿主 Activity：子页面关闭后原地刷新数值，不重建页面。 */
    private var livePage: LinearLayout? = null
    private var liveActivity: java.lang.ref.WeakReference<Activity>? = null

    @JvmStatic
    fun dpf(c: Context, v: Float): Int =
        Math.round(v * c.resources.displayMetrics.density)

    /** 开关拨动回调，带行 View（扩散动画要拿它算圆心） */
    fun interface OnToggle {
        fun onToggle(checked: Boolean, row: View)
    }

    @JvmStatic
    fun show(activity: Activity) {
        // 面板是宿主的主线程 UI：这里抛任何异常都会把闲鱼带崩。
        // 统一兜一层 —— 出问题只记日志 + 一句 Toast，界面不动。
        try {
            showInner(activity)
        } catch (t: Throwable) {
            try {
                Protocol.State.logLine("面板异常：" + t.javaClass.simpleName)
                android.widget.Toast.makeText(
                    activity, "面板打开失败：" + t.javaClass.simpleName,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun showInner(activity: Activity) {
        val ctx: Context = activity
        val th = Theme.of(ctx)
        // 反色开关切换后需要按新配色重画整个面板：
        // dialog 在下面才创建，所以先放一个一元素数组，等创建后填进去
        val reopen = arrayOfNulls<Runnable>(1)

        val scroll = ScrollView(ctx)
        scroll.isVerticalScrollBarEnabled = false
        scroll.setBackgroundColor(Color.TRANSPARENT)

        val page = LinearLayout(ctx)
        livePage = page
        liveActivity = java.lang.ref.WeakReference(activity)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(dpf(ctx, 12f), dpf(ctx, 8f), dpf(ctx, 12f), dpf(ctx, 12f))
        scroll.addView(
            page,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        // 面板内容单独成函数：二级页关闭后原地重建，界面不闪、数据是新的
        lateinit var restart: ImageView
        lateinit var wrap: FrameLayout
        var dlgRef: Dialog? = null
        fun buildContent() {
        val head = LinearLayout(ctx)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        // 右边距 22dp：page(12) + 22 = 34dp，与下方开关行的右边缘（12+16=28）视觉对齐
        head.setPadding(dpf(ctx, 6f), dpf(ctx, 6f), dpf(ctx, 16f), dpf(ctx, 6f))

        val title = TextView(ctx)
        title.text = "U 闲"
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.setTextColor(th.text())
        head.addView(
            title,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        // 用 FrameLayout 包裹：黄圆是背景，图标固定尺寸居中，避免 padding+FitCenter 的舍入偏差
        val circle = dpf(ctx, 32f)
        val iconSz = dpf(ctx, 20f)
        wrap = FrameLayout(ctx)
        val rbg = GradientDrawable()
        // 跟随主题：调试态蓝底白图标，正常态黄底黑图标
        rbg.setColor(th.accent())
        rbg.shape = GradientDrawable.OVAL
        wrap.background = rbg

        restart = ImageView(ctx)
        // 系统内置刷新图标，形状标准；但它被 ROM 主题化成了彩色渐变，
        // 用 SRC_IN 颜色滤镜压成统一的深色，保证与面板文字同色
        restart.setImageResource(android.R.drawable.ic_popup_sync)
        restart.setColorFilter(th.onAccent(), PorterDuff.Mode.SRC_IN)
        restart.scaleType = ImageView.ScaleType.FIT_CENTER
        val ilp = FrameLayout.LayoutParams(iconSz, iconSz)
        ilp.gravity = Gravity.CENTER
        wrap.addView(restart, ilp)

        wrap.layoutParams = LinearLayout.LayoutParams(circle, circle)
        wrap.isClickable = true
        wrap.isFocusable = true
        head.addView(wrap)
        page.addView(head)

        val anyMissing = !Protocol.State.capSplash || !Protocol.State.capFilter ||
            !Protocol.State.capUi || !Protocol.State.capTap || !Protocol.State.capMtop
        // 优先显示「整体失效」的精确结论（HookState 的 critical 判定），
        // 再退回旧的 cap* 粗判 —— 后者只说明「至少装上一个」，半残也报正常。
        val hookBroken = try {
            HookState.summary()
        } catch (t: Throwable) {
            ""
        }
        // 不再用版本号提示：版本对上但类名变了照样失效，下面的实际挂载点判定更准
        val warnText = when {
            hookBroken.length > 0 -> hookBroken
            anyMissing -> "部分挂载点没装上，重启闲鱼试试"
            else -> ""
        }
        if (warnText.length > 0) {
            val warn = TextView(ctx)
            warn.text = warnText
            warn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            warn.setTextColor(th.warnText())
            warn.setPadding(dpf(ctx, 12f), dpf(ctx, 9f), dpf(ctx, 12f), dpf(ctx, 9f))
            warn.background = roundRect(th.warnBg(), dpf(ctx, 8f).toFloat())
            val wlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            wlp.bottomMargin = dpf(ctx, 8f)
            page.addView(warn, wlp)
        }

        // 抓包是常驻功能（业务链靠它取参数），不提供开关。
        //
        // 功能区的排布（用户明确要求）：
        //   「手动」一行，右侧带「执行」按钮，一次跑完全部链；
        //   各项功能的说明内联在标题后面（不再单独占一行）；
        //   自动执行改成「启动时自动执行」开关，不再是二级页面。
        // 适配性放最顶：这是「模块到底有没有生效」的总开关式信息，
        // 用户第一眼要看到的不是任务列表，而是"它工作不工作"。
        section(ctx, page, "适配性")
        val gTop = card(ctx)
        val st = adaptState(activity)
        gTop.addView(rowNavColored(activity, "模块适配", st.first, st.second, st.third) {
            HealthPanel.show(activity)
        })
        page.addView(gTop)

        // 分区（2026-11-01 重排，方案 A）：自动任务 / 广告拦截 / 外观与隐私 / 调试。
        // 原来的问题是「功能」里混着自动任务和系统入口、「通用」里混着广告拦截和深色外观，
        // 栏名和内容对不上。现在每栏只干一件事。
        section(ctx, page, "自动任务")
        val g0 = card(ctx)
        // 我的数据：闲鱼币/游戏币/红包/等级。值由业务链跑的时候顺手写进快照，
        // 面板只读缓存 —— 弹窗里绝不能等网络。
        g0.addView(
            rowNav(ctx, "我的数据", null, Runnable { showUserData(activity) }),
        )
        g0.addView(divider(ctx))
        g0.addView(
            rowAction(
                ctx, "立即执行", null, "执行",
                Runnable { FeaturePanel.show(activity) },
            ) { btn -> runAll(activity, btn) },
        )
        // 这一行点进去是逐项执行页，加「>」提示可点（和自动执行行一致）
        try {
            val execRow = g0.getChildAt(g0.childCount - 1) as LinearLayout
            val arrow = TextView(ctx)
            arrow.text = "›"
            arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            arrow.setTextColor(Theme.of(ctx).arrow())
            arrow.setPadding(dpf(ctx, 8f), 0, dpf(ctx, 2f), 0)
            execRow.addView(arrow)
        } catch (ignored: Throwable) {
        }
        g0.addView(divider(ctx))
        // 「启动时自动执行」与「自动任务」是同一件事，合成一个开关：
        // 关掉 = 启动不跑、定时也不跑；点行进入间隔设置。
        val autoRow = rowSwitch(
            ctx, "自动执行", autoModeText(activity), Prefs.AUTO_START, true,
            Runnable { Protocol.State.invalidateSwCache() }, true, null,
        )
        autoRow.setOnClickListener { showAutoTask(activity) }
        // 加「>」：这一行既能开关也能进二级页，没有箭头用户不知道能点
        try {
            val rowLl = autoRow as LinearLayout
            val arrow = TextView(ctx)
            arrow.text = "›"
            arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            arrow.setTextColor(Theme.of(ctx).arrow())
            arrow.setPadding(dpf(ctx, 6f), 0, 0, 0)
            rowLl.addView(arrow)
        } catch (ignored: Throwable) {
        }
        g0.addView(autoRow)
        g0.addView(divider(ctx))
        g0.addView(
            rowNav(
                ctx, "执行记录", lastRunText(activity),
                Runnable { HistoryPanel.show(activity) },
            ),
        )
        g0.addView(divider(ctx))
        g0.addView(
            rowNav(
                ctx, "任务黑名单", TaskBlacklist.count(activity).toString() + " 条",
                Runnable { showBlacklist(activity) },
            ),
        )
        page.addView(g0)

        // ---- 广告拦截（原「通用」里的拦截项，语义一致放一起）----
        section(ctx, page, "广告拦截")
        val g1 = card(ctx)
        g1.addView(
            rowSwitch(ctx, "跳过开屏广告", Prefs.SKIP_SPLASH, true, {
                Protocol.State.skipSplash = Prefs.get(activity, Prefs.SKIP_SPLASH, true)
            }, Protocol.State.capSplash),
        )
        g1.addView(divider(ctx))
        g1.addView(
            rowSwitch(
                ctx, "开屏摇一摇禁用", Prefs.SKIP_SHAKE, true, null,
                !HookState.feature("shake").installed.isEmpty(),
            ),
        )
        g1.addView(divider(ctx))
        g1.addView(
            rowSwitch(
                ctx, "后台升级弹窗拦截", Prefs.SKIP_UPGRADE, true, null,
                !HookState.feature("upgrade").installed.isEmpty(),
            ),
        )
        g1.addView(divider(ctx))
        g1.addView(
            rowSwitch(
                ctx, "搜索结果去广告", Prefs.FILTER_SEARCH_AD, false, null,
                !HookState.feature("searchad").installed.isEmpty(),
            ),
        )
        g1.addView(divider(ctx))
        g1.addView(
            rowSwitch(
                ctx, "消息页过滤服务号", Prefs.FILTER_SERVICE, true, null,
                Protocol.State.capFilter,
            ),
        )
        page.addView(g1)

        // ---- 外观与隐私 ----
        section(ctx, page, "外观与隐私")
        val g2 = card(ctx)
        g2.addView(rowNav(ctx, "金刚位排序", KingkongOrder.summary(ctx), Runnable {
            KingkongPanel.show(activity, reopen[0])
        }))
        g2.addView(divider(ctx))
        g2.addView(rowNav(ctx, "首页 tab 排序", HomeTabOrder.summary(ctx), Runnable {
            HomeTabPanel.show(activity)
        }))
        g2.addView(divider(ctx))
        g2.addView(
            rowSwitch(
                ctx, "隐藏金刚位", Prefs.KINGKONG_HIDE, false, null,
                !HookState.feature("kingkong").installed.isEmpty(),
            ),
        )
        g2.addView(divider(ctx))
        g2.addView(
            rowSwitch(
                ctx, "深色模式", "反色实现，图片会变负片", Prefs.DARK_FORCE, false,
                {
                    DarkForce.refreshAll()
                    reopen[0]?.run()
                },
                Build.VERSION.SDK_INT >= 31,
                OnToggle { checked, row -> DarkForce.revealFrom(row, checked) },
            ),
        )
        g2.addView(divider(ctx))
        g2.addView(
            rowSwitch(
                ctx, "断埋点上报", Prefs.BLOCK_TRACK, false, null,
                !HookState.feature("track").installed.isEmpty(),
            ),
        )
        g2.addView(divider(ctx))
        g2.addView(
            rowSwitch(
                ctx, "断设备指纹上传", Prefs.BLOCK_UTDID, false, null,
                !HookState.feature("utdid").installed.isEmpty(),
            ),
        )
        page.addView(g2)



        // 抓包和调试两栏只在调试模式下显示。
        // 普通模式下面板只留「通用」，避免把排查用的东西摆在日常界面上。
        if (Prefs.get(ctx, Prefs.DEBUG, false)) {
            // ---- 抓包（日志类开关合并到这里，开关即数据源）----
            section(ctx, page, "抓包")
            val g2 = card(ctx)

            val tempRow = UiKit.row(ctx)
            val tempLabel = TextView(ctx)
            tempLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            tempLabel.text = inlineLabel(ctx, "临时抓包", "关闭后删除临时数据")
            tempRow.addView(tempLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val tempSwitch = Switch(ctx)
            tempSwitch.isChecked = TempTraffic.session() != null
            tintSwitch(tempSwitch)
            tempSwitch.setOnCheckedChangeListener { button, checked ->
                try {
                    val msg = if (checked) TempTraffic.start() else TempTraffic.stop()
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    button.isEnabled = false
                    android.widget.Toast.makeText(ctx, "临时抓包操作失败，请关闭面板后重试：${t.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            tempRow.addView(tempSwitch)
            g2.addView(tempRow)
            g2.addView(divider(ctx))
            // 常驻抓包保留原有入口
            g2.addView(
                rowNav(ctx, "抓包数据", trafficSummary(), Runnable { showTraffic(activity) }),
            )
            g2.addView(divider(ctx))
            // 关联分析并进导出：用户要的是「拿到一份能看的报告」，
            // 而不是「先点分析、再点导出」两步；分析结果也只在导出里才有用。
            g2.addView(
                rowNav(ctx, "导出分析报告", null, Runnable { exportReport(activity) }),
            )
            g2.addView(divider(ctx))
            g2.addView(
                rowNav(ctx, "导入分析数据", null, Runnable { importReport(activity) }),
            )
            g2.addView(divider(ctx))
            g2.addView(
                rowNav(
                    ctx, "运行日志", "${Protocol.State.LOGS.size} 条",
                    Runnable { showLogs(activity) },
                ),
            )
            page.addView(g2)

            section(ctx, page, "调试")
            val g3 = card(ctx)
            g3.addView(
                rowSwitch(ctx, "模块日志（输出到 logcat）", Prefs.MODULE_LOG, false, null, true),
            )
            g3.addView(divider(ctx))
            // HTTP 调试口：仅给外部脚本 / 模块 App 用，面板自身不经过它
            g3.addView(
                rowSwitch(
                    ctx, "HTTP 调试接口（127.0.0.1:18787）", Prefs.HTTP_DEBUG, false,
                    Runnable { applyHttpDebug(activity) }, true,
                ),
            )
            g3.addView(divider(ctx))
            g3.addView(
                rowNav(
                    ctx, "重新绘制入口", "清空测量缓存并重插",
                    Runnable { UiInjector.resetEntry(activity) },
                ),
            )
            page.addView(g3)
        }

        val foot = TextView(ctx)
        foot.text = "U 闲 v0.2-hot"
        foot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        foot.setTextColor(th.sub())
        foot.gravity = Gravity.CENTER
        foot.setPadding(0, dpf(ctx, 16f), 0, 0)
        page.addView(foot)
            restart.contentDescription = "重启应用"
            // 点击实际落在父容器 wrap（黄圆背景）上，两个都要绑
            val doRestart = View.OnClickListener {
                val appCtx = activity.applicationContext
                Protocol.State.logLine("点击重启按钮")
                try {
                    dlgRef?.dismiss()
                } catch (ignored: Throwable) {
                }
                Handler(Looper.getMainLooper()).postDelayed(Runnable {
                    try {
                        RestartHelper.restartApp(appCtx)
                    } catch (t: Throwable) {
                        Protocol.State.logLine("重启调用失败：$t")
                    }
                }, 60)
            }
            restart.setOnClickListener(doRestart)
            wrap.setOnClickListener(doRestart)
        }

        buildContent()

        val container = LinearLayout(ctx)
        container.orientation = LinearLayout.VERTICAL
        container.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                PanelDialog.fitHeight(activity, scroll, PanelDialog.MAX_RATIO),
            ),
        )

        // 关闭按钮与二级页面同一套（[PanelDialog.closeButton]），
        // 以前这里又抄了一份 15sp/居中/13dp padding
        val close = PanelDialog.closeButton(ctx)
        container.addView(
            close,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val dialog = PanelDialog.buildDialog(activity, container)
        dlgRef = dialog
        close.setOnClickListener { dialog.dismiss() }
        // 二级页面（含确认框）关闭后**原地重建**一级内容：
        // 不关面板、不重开，用户视觉上只是数字/开关刷新了。
        PanelDialog.childClosed = Runnable {
            try {
                refreshLiveRows()
            } catch (ignored: Throwable) {
            }
        }
        dialog.setOnDismissListener { PanelDialog.childClosed = null }

        reopen[0] = Runnable {
            try {
                dialog.dismiss()
            } catch (ignored: Throwable) {
            }
            // 等背景扩完再弹面板：动画期间面板是关着的，正好不挡那圈扩散
            Handler(Looper.getMainLooper()).postDelayed(Runnable {
                try {
                    show(activity)
                } catch (ignored: Throwable) {
                }
            }, DarkForce.REVEAL_MS + 40L)
        }

        val hostDecor = activity.window.decorView
        dialog.setOnDismissListener { UiInjector.setBlur(hostDecor, false) }
        dialog.show()
        UiInjector.setBlur(hostDecor, true)
        // 注意：**不要**给面板窗口套 DarkForce 的反色。
        // 面板是独立 Dialog 窗口，宿主窗口的 RenderEffect 本来就管不到它；
        // 面板的深色由 Theme 自己给（读 dark_force 决定用哪套配色）。
        // 之前在这里调过 DarkForce.attach(panelDecor)，等于把深色配色再反一次，
        // 面板就变回浅色/发白 —— 那是错的，已移除。
    }

    // ---------------------------------------------------------------- 组件


    /**
     * 把「标题 + 说明」拼进同一个 TextView：标题粗体正文色，说明 hint 色 + 0.8 倍字号。
     *
     * 这是本面板的统一排法 —— 说明另起一行会把行高从 50dp 撑到 70dp，
     * 塞到行尾又会被挤成两三个字一行（中文比英文宽得多）。
     */
    private fun inlineLabel(
        ctx: Context,
        title: String,
        desc: String?,
    ): android.text.SpannableStringBuilder {
        val th = Theme.of(ctx)
        val sb = android.text.SpannableStringBuilder(title)
        sb.setSpan(
            android.text.style.ForegroundColorSpan(th.text()), 0, title.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        if (!desc.isNullOrEmpty()) {
            val at = sb.length
            sb.append("  ").append(desc)
            sb.setSpan(
                android.text.style.ForegroundColorSpan(th.hint()), at, sb.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            sb.setSpan(
                android.text.style.RelativeSizeSpan(0.8f), at, sb.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return sb
    }

    /**
     * 带右侧操作按钮的行：「标题  说明…………  [执行]」
     *
     * 说明文字**内联在标题后面**（同一行），而不是另起一行塞进行尾 ——
     * 行尾只有 ~120dp，中文说明会被挤成两三个字一行；另起一行又会把行高
     * 从 50dp 撑到 70dp，整块变得松散。折中方案：标题 + 说明用同一个
     * TextView 的两段 Spannable 拼接，说明段用 hint 色 + 小一号字。
     *
     * **行本身也可点**（[onRowClick]）：右侧按钮是「全部执行」，点行进二级
     * 页面逐项执行。两者不冲突 —— 按钮自己消费点击事件，不会冒泡到行上。
     *
     * @param action     按钮文案（如「执行」）
     * @param onRowClick 点击行（按钮以外区域）的动作，null 则整行不可点
     * @param onRun      点按钮的动作，参数是那个按钮本身 —— 调用方在后台任务
     *                   结束时用它调 [UiKit.resetRunButton] 恢复空闲态。
     */
    private fun rowAction(
        ctx: Context,
        title: String,
        desc: String?,
        action: String,
        onRowClick: Runnable?,
        onRun: (TextView) -> Unit,
    ): View {
        val row = UiKit.row(ctx)

        val label = TextView(ctx)
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        label.text = inlineLabel(ctx, title, desc)
        row.addView(
            label,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        val btn = UiKit.pillButton(ctx, action)
        btn.setOnClickListener {
            // 执行中再次点击 = 回到步骤页；结束后回调恢复按钮。
            btn.text = "执行中"
            onRun(btn)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.leftMargin = UiKit.dp(ctx, 10f)
        row.addView(btn, lp)

        if (onRowClick != null) {
            row.setOnClickListener { onRowClick.run() }
        }
        return row
    }

    private fun section(ctx: Context, parent: LinearLayout, title: String) {
        val th = Theme.of(ctx)
        val t = TextView(ctx)
        t.text = title
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        // 分区标题用 hint 而不是 sub：sub 在白底上像禁用态
        t.setTextColor(th.hint())
        t.setPadding(dpf(ctx, 6f), dpf(ctx, 12f), dpf(ctx, 6f), dpf(ctx, 6f))
        parent.addView(t)
    }

    /** 卡片：统一走 [UiKit.card]（圆角 / 裁剪只有一处定义） */
    private fun card(ctx: Context): LinearLayout = UiKit.card(ctx)

    private fun roundRect(color: Int, radius: Float): GradientDrawable =
        UiKit.roundRect(color, radius)

    private fun divider(ctx: Context): View = UiKit.divider(ctx)

    @JvmStatic
    /**
     * HTTP 调试开关的生效逻辑。
     *
     * 打开 → 在主进程起服务；关闭 → 停止。
     * 只影响外部调用（curl / 模块 App），面板功能一律直接调函数，不经过它。
     */
    private fun applyHttpDebug(activity: Activity) {
        val on = Prefs.get(activity, Prefs.HTTP_DEBUG, false)
        if (on) {
            try {
                val cl = Protocol.State.classLoader
                if (cl == null) {
                    android.widget.Toast.makeText(
                        activity, "classLoader 未就绪，稍后再试", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    return
                }
                ChannelHttp.start(cl)
                android.widget.Toast.makeText(
                    activity,
                    "HTTP 已启动\nhttp://127.0.0.1:18787",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                Protocol.State.logLine("HTTP 调试接口已开启（18787）")
            } catch (t: Throwable) {
                android.widget.Toast.makeText(
                    activity, "启动失败：${t.javaClass.simpleName}", android.widget.Toast.LENGTH_LONG,
                ).show()
                Protocol.State.logLine("HTTP 调试接口启动失败：$t")
            }
        } else {
            try {
                ChannelHttp.stop()
                Protocol.State.logLine("HTTP 调试接口已关闭")
            } catch (ignored: Throwable) {
            }
        }
    }

    fun rowSwitch(
        ctx: Context,
        text: String,
        key: String,
        def: Boolean,
        onChange: Runnable?,
    ): View = rowSwitch(ctx, text, key, def, onChange, true, null)

    @JvmStatic
    fun rowSwitch(
        ctx: Context,
        text: String,
        key: String,
        def: Boolean,
        onChange: Runnable?,
        available: Boolean,
    ): View = rowSwitch(ctx, text, key, def, onChange, available, null)

    @JvmStatic
    fun rowSwitch(
        ctx: Context,
        text: String,
        key: String,
        def: Boolean,
        onChange: Runnable?,
        available: Boolean,
        onToggle: OnToggle?,
    ): View = rowSwitch(ctx, text, null, key, def, onChange, available, onToggle)

    /**
     * 带说明的开关行：`标题  说明…………  [开关]`
     *
     * 说明内联在标题后（和「手动」行同一套排法，见 [rowAction]），
     * 不占额外行高。用来说明「这个开关具体怎么起作用」——
     * 比如深色模式是**反色实现的**，不写清楚用户会以为是换了套配色，
     * 结果发现图片变负片就跑来报 bug。
     */
    @JvmStatic
    fun rowSwitch(
        ctx: Context,
        text: String,
        desc: String?,
        key: String,
        def: Boolean,
        onChange: Runnable?,
        available: Boolean,
        onToggle: OnToggle?,
    ): View {
        val row = UiKit.row(ctx)

        val th = Theme.of(ctx)
        val t = TextView(ctx)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        t.text = inlineLabel(ctx, text, desc)
        row.addView(
            t,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        if (!available) {
            t.setTextColor(th.off())
            val tag = TextView(ctx)
            tag.text = "未适配"
            tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            tag.setTextColor(th.off())
            tag.setPadding(0, 0, dpf(ctx, 6f), 0)
            row.addView(tag)
        }

        val sw = Switch(ctx)
        sw.isChecked = available && Prefs.get(ctx, key, def)
        sw.isEnabled = available
        sw.alpha = if (available) 1f else 0.35f
        tintSwitch(sw)
        sw.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            if (!available) return@setOnCheckedChangeListener
            Prefs.set(ctx, key, checked)
            // Prefs.set 是异步 apply，紧接着的读取会命中旧缓存。
            // 清掉，让 Theme.of / effectiveTraffic 立刻看到新值。
            Protocol.State.invalidateSwCache()
            onToggle?.onToggle(checked, row)
            onChange?.run()
        }
        row.addView(sw)
        return row
    }

    private fun tintSwitch(sw: Switch) {
        try {
            val th = Theme.of(sw.context)
            sw.trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(th.trackOn(), th.trackOff()),
            )
            sw.thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(th.thumbOn(), th.thumbOff()),
            )
        } catch (ignored: Throwable) {
        }
    }

    @JvmStatic
    fun rowNav(ctx: Context, text: String, value: String?, onClick: Runnable?): View {
        val row = UiKit.row(ctx)

        val th = Theme.of(ctx)
        val t = TextView(ctx)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        t.setTextColor(th.text())
        row.addView(
            t,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        if (!value.isNullOrEmpty()) {
            val v = TextView(ctx)
            v.text = value
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            v.setTextColor(th.hint())
            v.setPadding(0, 0, dpf(ctx, 6f), 0)
            row.addView(v)
        }
        val arrow = TextView(ctx)
        arrow.text = "\u203A"
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        arrow.setTextColor(th.arrow())
        row.addView(arrow)

        if (onClick != null) {
            row.setOnClickListener { onClick.run() }
        } else {
            row.isEnabled = false
            row.alpha = 0.45f
        }
        return row
    }

    /** 面板上「抓包数据」那行的副标题：条数 + 体积 */
    /**
     * 导出抓包分析报告。
     *
     * 在后台线程生成（大库要几百毫秒，绝不能占主线程），完成后弹一个对话框
     * 显示路径。生成的 HTML 是完全自包含的，可以直接用系统浏览器打开，
     * 也可以分享给别人 —— 正文已经脱敏过。
     */
    private fun exportReport(activity: Activity) {
        val ctx: Context = activity
        val n = try {
            TrafficStore.count()
        } catch (t: Throwable) {
            0L
        }
        if (n <= 0) {
            android.widget.Toast.makeText(
                activity, "还没有抓包数据，先打开闲鱼逛一下",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val cancelled = booleanArrayOf(false)
        val closeLoading = LoadingDialog.show(
            activity, "正在生成分析报告", "$n 条记录",
            cancelable = true,
            onCancel = Runnable {
                cancelled[0] = true
                Protocol.State.logLine("导出已取消")
            },
        )

        Thread {
            var res: TrafficExport.Result? = null
            var err: String? = null
            var rel = 0
            try {
                ApiDoc.load(ctx)
                // 先做关联分析（导出时用得上），取消就立刻停手，不白跑导出
                if (!cancelled[0]) {
                    val rr = Relation.analyze(ctx)
                    rel = rr.relations
                }
                if (!cancelled[0]) res = TrafficExport.build(ctx, null)
            } catch (t: Throwable) {
                err = t.toString()
            }
            val r = res
            val e = err
            val relN = rel
            Handler(Looper.getMainLooper()).post {
                closeLoading()
                if (cancelled[0]) return@post
                if (r == null) {
                    Protocol.State.logLine("导出失败：$e")
                    android.widget.Toast.makeText(
                        activity, "导出失败：$e", android.widget.Toast.LENGTH_LONG,
                    ).show()
                    return@post
                }
                Protocol.State.logLine("导出完成：${r.apis} 个接口 / $relN 条关联 -> ${r.path}")
                showExportResult(activity, r)
            }
        }.start()
    }

    private fun showExportResult(activity: Activity, r: TrafficExport.Result) {
        val ctx: Context = activity
        val th = Theme.of(ctx)
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        val p = dpf(ctx, 18f)
        box.setPadding(p, p, p, dpf(ctx, 6f))

        val head = TextView(ctx)
        head.text = "${r.apis} 个接口 · ${fmtSize(r.bytes)}"
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        head.setTypeface(head.typeface, Typeface.BOLD)
        head.setTextColor(th.text())
        box.addView(head)

        val path = TextView(ctx)
        path.text = r.path
        path.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        path.typeface = Typeface.MONOSPACE
        path.setTextColor(th.sub())
        path.setPadding(0, dpf(ctx, 6f), 0, 0)
        path.setTextIsSelectable(true)
        box.addView(path)

        val note = TextView(ctx)
        note.text = "正文已脱敏（token / oaid / 手机号等）。" +
            "用浏览器打开即可搜索、筛选、对比；长按列表两行可以对比两个接口的结构差异。"
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        note.setTextColor(th.hint())
        note.setPadding(0, dpf(ctx, 10f), 0, 0)
        box.addView(note)

        // 操作按钮统一用 PanelDialog 的胶囊样式，不再用系统 AlertDialog 按钮
        val acts = ArrayList<Pair<String, Runnable>>(2)
        acts.add(Pair("打开", Runnable { openReport(activity, r.path) }))
        acts.add(Pair("分享", Runnable { shareReport(activity, r.path) }))
        box.addView(PanelDialog.actionRow(ctx, acts, null))

        val dlg = PanelDialog.show(
            activity, "导出完成", PanelDialog.scrollWrap(ctx, box),
            ratio = PanelDialog.AUTO,
        )
        Protocol.State.logLine("报告已就绪：${r.apis} 接口")
    }

    private fun fmtSize(b: Long): String =
        if (b < 1024) "$b B"
        else if (b < 1048576) "${b / 1024} KB"
        else String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)

    /** 用系统浏览器打开报告。MediaStore 插入时就拿到了 content:// uri，直接复用 */
    private fun openReport(activity: Activity, path: String) {
        try {
            val uri = TrafficExport.uriOf(activity, path)
                ?: throw IllegalStateException("找不到文件：$path")
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
            i.setDataAndType(uri, "text/html")
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(i)
        } catch (t: Throwable) {
            Protocol.State.logLine("打开报告失败：$t")
            android.widget.Toast.makeText(
                activity, "打开失败，请到文件管理里找：$path",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun shareReport(activity: Activity, path: String) {
        try {
            val uri = TrafficExport.uriOf(activity, path)
                ?: throw IllegalStateException("找不到文件：$path")
            val i = android.content.Intent(android.content.Intent.ACTION_SEND)
            i.type = "text/html"
            i.putExtra(android.content.Intent.EXTRA_STREAM, uri)
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(android.content.Intent.createChooser(i, "分享抓包报告"))
        } catch (t: Throwable) {
            Protocol.State.logLine("分享报告失败：$t")
        }
    }

    /** 关联摘要：显示已分析出多少条 */
    private fun relationSummary(): String =
        try {
            val n = Relation.allRelations().size
            if (n <= 0) "尚未分析" else "$n 条关联"
        } catch (t: Throwable) {
            ""
        }

    /**
     * 适配性摘要：优先报「整体失效」的功能（这是要用户行动的），
     * 其次是最近一次执行结果，最后是近 7 天概况。
     */
    /**
     * 适配结论：完美适配 / 部分适配 / 未适配。
     *
     * 判据（和 HealthPanel 的钩子体检同源，但这里只给结论）：
     *   - 一个钩子都没装上，或版本门禁不通过 → 未适配（红）
     *   - 有 critical（关键挂载点缺失）→ 部分适配（黄）—— 还能用，但功能残缺
     *   - 全部挂上且无失败 → 完美适配（绿）
     *   - 其余（装上但有部分失败）→ 部分适配（黄）
     */
    private fun adaptState(ctx: Context): Triple<String, Int, Int> {
        val th = Theme.of(ctx)
        return try {
            var installed = 0
            var failed = 0
            var critical = false
            // 只看实际挂载点：版本号相同也可能类名被改，版本不同也可能全都在
            for (f in HookState.all()) {
                installed += f.installed.size
                failed += f.failed.size
                if (f.critical()) critical = true
            }
            when {
                installed == 0 -> Triple("未适配（重启试试）", th.badText(), th.badBg())
                critical || failed > 0 -> Triple("部分适配", th.warnText(), th.warnBg())
                else -> Triple("完美适配", th.okText(), th.okBg())
            }
        } catch (t: Throwable) {
            Triple("未知", th.hint(), th.divider())
        }
    }

    /** 带彩色胶囊的导航行：值用 [fg] 上色、[bg] 做浅底，点击进二级页 */
    private fun rowNavColored(
        ctx: Context,
        title: String,
        value: String,
        fg: Int,
        bg: Int,
        onClick: Runnable,
    ): View {
        val row = UiKit.row(ctx)
        val th = Theme.of(ctx)
        val t = TextView(ctx)
        t.text = title
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        t.setTextColor(th.text())
        row.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val chip = TextView(ctx)
        chip.text = value
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        chip.setTextColor(fg)
        chip.setPadding(dpf(ctx, 10f), dpf(ctx, 4f), dpf(ctx, 10f), dpf(ctx, 4f))
        chip.background = UiKit.roundRect(bg, dpf(ctx, 11f).toFloat())
        val clp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        clp.rightMargin = dpf(ctx, 4f)
        row.addView(chip, clp)

        // 和普通导航行一致的「>」，暗示可点
        val arrow = TextView(ctx)
        arrow.text = "›"
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        arrow.setTextColor(th.arrow())
        arrow.setPadding(dpf(ctx, 6f), 0, 0, 0)
        row.addView(arrow)

        row.setOnClickListener { onClick.run() }
        return row
    }

    /** 「5 项可用 · 1 项失效」——只给结论，细节进二级页 */
    private fun healthSummary(): String =
        try {
            val app = Protocol.State.app
            var ok = 0
            var bad = 0
            for (f in HookState.all()) {
                if (f.installed.isEmpty() && f.failed.isEmpty()) continue
                if (f.critical()) bad++ else ok++
            }
            when {
                ok + bad == 0 -> "待重启"
                bad > 0 -> ok.toString() + " 可用 · " + bad + " 失效"
                else -> ok.toString() + " 项可用"
            }
        } catch (t: Throwable) {
            ""
        }

    /** 执行记录行右侧：最近一次的时间（`05:02`），没有就留空 */
    private fun lastRunText(activity: Activity): String? {
        return try {
            val list = ChainHistory.recent(activity, 1)
            if (list.isEmpty()) null else list[0].day() + " " + list[0].timeText()
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 子页面关闭后刷新一级页面上的动态数值，**不重建视图**。
     *
     * 只改文案，不动结构：结构变化（调试态、深色模式）仍由 reopen 处理。
     */
    private fun refreshLiveRows() {
        val page = livePage ?: return
        val act = liveActivity?.get() ?: return
        setRowValue(page, "自动执行", autoModeText(act))
        setRowValue(page, "执行记录", lastRunText(act) ?: "")
        setRowValue(page, "任务黑名单", TaskBlacklist.count(act).toString() + " 条")
        setRowValue(page, "抓包数据", trafficSummary())
        setRowValue(page, "模块适配", adaptState(act).first)
    }

    /** 在页面里按左侧标题找到行，把它右侧的值更新掉。 */
    private fun setRowValue(root: android.view.ViewGroup, label: String, value: String) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is android.view.ViewGroup) {
                if (labelOf(child) == label) {
                    updateValue(child, label, value)
                    return
                }
                setRowValue(child, label, value)
            }
        }
    }

    private fun labelOf(row: android.view.ViewGroup): String? {
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i)
            if (c is TextView && c.text != null && c.text.toString() != "›") return c.text.toString()
        }
        return null
    }

    private fun updateValue(row: android.view.ViewGroup, label: String, value: String) {
        var target: TextView? = null
        var arrowIndex = row.childCount
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i)
            if (c is TextView) {
                val text = c.text?.toString() ?: ""
                if (text == "›") { arrowIndex = i; continue }
                if (text != label) target = c
            }
        }
        if (target == null) {
            val ctx = row.context
            val th = Theme.of(ctx)
            val v = TextView(ctx)
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            v.setTextColor(th.hint())
            v.setPadding(0, 0, dpf(ctx, 6f), 0)
            row.addView(v, minOf(arrowIndex, row.childCount))
            target = v
        }
        if (target.text?.toString() != value) target.text = value
    }

    /** 自动执行的时机文案（显示在一级行的说明里） */
    private fun autoModeText(activity: Activity): String {
        return try {
            if (!Prefs.get(activity, Prefs.AUTO_START, true)) return "已关闭"
            val mode = Prefs.getString(activity, Prefs.AUTO_MODE, Prefs.AUTO_MODE_H4)
            when (mode) {
                Prefs.AUTO_MODE_DAILY -> "每天一次"
                Prefs.AUTO_MODE_H4 -> "每 4 小时"
                else -> "每次启动"
            }
        } catch (t: Throwable) {
            ""
        }
    }



    private fun showRelationResult(activity: Activity, r: Relation.Result) {
        val ctx: Context = activity
        val th = Theme.of(ctx)
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        val p = dpf(ctx, 18f)
        box.setPadding(p, p, p, dpf(ctx, 6f))

        // 找出「不可重放但有依赖」的接口 —— 这是分析的核心价值
        val rels = Relation.allRelations()
        val byFrom = HashMap<String, Int>()
        for (o in rels) {
            val f = o.optString("from")
            byFrom[f] = (byFrom[f] ?: 0) + 1
        }
        val meta = Relation.allApiMeta()
        val blocked = ArrayList<String>()
        for (m in meta) {
            if (m.optString("replay") != "no") continue
            val api = m.optString("api")
            if ((byFrom[api] ?: 0) > 0) blocked.add(api)
        }

        fun line(t1: String, t2: String, color: Int) {
            val tv = TextView(ctx)
            tv.text = t1
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            tv.setTextColor(color)
            tv.setPadding(0, dpf(ctx, 5f), 0, dpf(ctx, 5f))
            box.addView(tv)
        }

        line("${r.apis} 个接口 · ${r.relations} 条关联", "", th.text())
        line("耗时 ${r.ms} ms", "", th.sub())

        if (blocked.isNotEmpty()) {
            val h = TextView(ctx)
            h.text = "以下不可重放的接口找到了数据来源，先调依赖再拼参数可能可行："
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            h.setTextColor(th.warnText())
            h.setPadding(0, dpf(ctx, 10f), 0, dpf(ctx, 4f))
            box.addView(h)
            for (api in blocked.take(6)) {
                val tv = TextView(ctx)
                tv.text = "· $api\n   ← ${byFrom[api]} 个关联"
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                tv.typeface = Typeface.MONOSPACE
                tv.setTextColor(th.text())
                tv.setPadding(0, dpf(ctx, 3f), 0, dpf(ctx, 3f))
                box.addView(tv)
            }
        }

        val note = TextView(ctx)
        note.text = "关联关系会写进数据库，导出报告时会带上；" +
            "在报告的接口详情里点「关联」可以查看。"
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        note.setTextColor(th.hint())
        note.setPadding(0, dpf(ctx, 10f), 0, 0)
        box.addView(note)

        PanelDialog.show(
            activity, "分析完成", PanelDialog.scrollWrap(ctx, box),
            ratio = PanelDialog.AUTO,
        )
    }

    /**
     * 导入之前的报告。
     *
     * 不用系统文件选择器：面板本身是个 Dialog，没有 onActivityResult 通道，
     * 回调接不住。改成**列出 Download/U闲抓包/ 里的报告让用户选** ——
     * 报告本来就是本应用导出的，一定在那个目录，不需要任意目录权限。
     */
    private fun importReport(activity: Activity) {
        val ctx: Context = activity
        val th = Theme.of(ctx)
        val dir = java.io.File("/sdcard/Download/U闲抓包")
        val files = try {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".html") }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
        if (files.isEmpty()) {
            android.widget.Toast.makeText(
                activity, "Download/U闲抓包/ 里没有报告文件", android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        val names = files.map {
            it.name + "  (" + String.format(java.util.Locale.US, "%.0fKB", it.length() / 1024.0) + ")"
        }.toTypedArray()
        // 报告选择也用二级页面外壳：AlertDialog 的列表行是系统配色，
        // 和面板放一起明显两套观感（见 UiKit 观感约定）
        val box = UiKit.column(ctx)
        val pad = UiKit.dp(ctx, 12f)
        box.setPadding(pad, UiKit.dp(ctx, 2f), pad, pad)
        for (i in files.indices) {
            val f = files[i]
            val row = UiKit.row(ctx)
            val name = UiKit.textView(ctx, names[i], 14f)
            row.addView(
                name,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.setOnClickListener {
                doImport(activity, f)
            }
            box.addView(row)
            if (i < files.size - 1) box.addView(divider(ctx))
        }
        PanelDialog.show(
            activity, "选择要导入的报告", PanelDialog.scrollWrap(ctx, box),
            ratio = PanelDialog.AUTO,
        )
    }

    private fun doImport(activity: Activity, file: java.io.File) {
        val closeLoading = LoadingDialog.show(
            activity, "正在导入分析数据", file.name,
        )

        Thread {
            ApiDoc.load(activity)
            val r = TrafficImport.fromFile(file.absolutePath)
            Handler(Looper.getMainLooper()).post {
                closeLoading()
                val msg = if (r.error != null) {
                    "导入失败：${r.error}"
                } else {
                    "已恢复 ${r.apis} 个接口、${r.relations} 条关联、${r.fieldDoc} 条词典"
                }
                Protocol.State.logLine(msg)
                android.widget.Toast.makeText(
                    activity, msg, android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }.start()
    }

    private fun trafficSummary(): String =
        try {
            val n = TrafficStore.count()
            val b = TrafficStore.bodyBytes()
            if (n <= 0) {
                "暂无数据"
            } else {
                n.toString() + " 条 · " + (
                    if (b < 1024 * 1024) {
                        (b / 1024).toString() + " KB"
                    } else {
                        String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
                    }
                    )
            }
        } catch (t: Throwable) {
            ""
        }

    /**
     * 抓包数据浏览：按时间倒序列出最近 200 条，
     * 每条显示 时间 / 方向 / 接口 / 版本 / 大小。
     *
     * 顶部先给**结论**（条数 / 体积 / 丢弃数），再给列表 ——
     * 原来这段统计接在 200 条后面，要滚到底才看得到，
     * 而它恰恰是「这个库现在什么状况」最该一眼看到的东西。
     *
     * 「清空」在标题栏右上角（红字），点了还要二次确认 —— 清空是不可撤销的，
     * 而且清掉的是研究素材，误触代价很高。
     */
    private fun showTraffic(activity: Activity) {
        val ctx: Context = activity
        val th = Theme.of(ctx)
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        val p = dpf(ctx, 14f)
        root.setPadding(p, p, p, p)

        // 结论条：不跟着列表滚，固定在顶部
        val head = TextView(ctx)
        head.text = TrafficStore.recentSummary()
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        head.setTextColor(th.hint())
        head.setPadding(dpf(ctx, 4f), 0, dpf(ctx, 4f), dpf(ctx, 10f))
        root.addView(head)

        val tv = TextView(ctx)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tv.setTextColor(th.text())
        tv.typeface = Typeface.MONOSPACE
        tv.text = TrafficStore.recentText(200)

        val sv = ScrollView(ctx)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(tv)
        // 权重 1：内容区吃掉标题与关闭之间的全部高度，滚到底不会在下面留白
        root.addView(
            sv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ),
        )

        PanelDialog.show(
            activity, "抓包数据", root,
            ratio = PanelDialog.FILL,
            action = "清空",
            onAction = Runnable { confirmClear(activity) },
        )
    }

    /**
     * 清空抓包库的二次确认。
     *
     * 用面板同款弹窗而不是系统 AlertDialog：这是破坏性操作，用户会更认真地读，
     * 观感不一致反而更容易点错。
     */
    private fun confirmClear(activity: Activity) {
        val ctx: Context = activity
        val box = UiKit.column(ctx)
        val p = dpf(ctx, 16f)
        box.setPadding(p, dpf(ctx, 2f), p, p)

        val n = try {
            TrafficStore.count()
        } catch (t: Throwable) {
            0L
        }
        box.addView(
            UiKit.hintView(
                ctx,
                "将删除抓包库里的 $n 条记录，不可恢复。\n" +
                    "自动任务要用的参数（asac 等）单独存在参数库里，不受影响。",
                13f,
            ),
        )

        val acts = ArrayList<Pair<String, Runnable>>(1)
        acts.add(
            Pair(
                "确认清空",
                Runnable {
                    TrafficStore.clear()
                    Protocol.State.logLine("抓包数据已清空")
                    android.widget.Toast.makeText(
                        activity, "已清空", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                },
            ),
        )
        // 破坏性动作的按钮用红色（warnText 底），不用主操作色 ——
        // 主操作色（黄/蓝）在面板里意味着「执行」，这里的语义正好相反
        box.addView(dangerActionRow(ctx, acts))
        PanelDialog.show(
            activity, "确认清空", box,
            ratio = PanelDialog.AUTO,
        )
    }

    /**
     * 红底胶囊按钮行。破坏性操作专用（清空抓包库、删除记录这类）。
     *
     * 和 [PanelDialog.actionRow] 的差别只有配色：主操作走 `accent()`，
     * 破坏性操作走 `warnText()` 当底色。为什么单开一个而不是给 actionRow
     * 加参数 —— 这个用法目前只有一处，加参数会让那个通用函数多一个
     * 只在一种场景下有意义的开关。
     */
    private fun dangerActionRow(
        ctx: Context,
        actions: List<Pair<String, Runnable>>,
    ): View {
        val th = Theme.of(ctx)
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        row.setPadding(0, dpf(ctx, 8f), 0, 0)
        for (a in actions) {
            val b = TextView(ctx)
            b.text = a.first
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            b.setTextColor(0xFFFFFFFF.toInt())
            b.gravity = Gravity.CENTER
            b.setPadding(dpf(ctx, 14f), dpf(ctx, 7f), dpf(ctx, 14f), dpf(ctx, 7f))
            b.background = UiKit.roundRect(th.warnText(), dpf(ctx, 16f).toFloat())
            b.isClickable = true
            b.isFocusable = true
            val act = a.second
            b.setOnClickListener { act.run() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.leftMargin = dpf(ctx, 8f)
            row.addView(b, lp)
        }
        return row
    }

    /**
     * 手动执行：跑完所有业务链，结果用二级页面展示。
     *
     * 和启动自动执行共用 [Chains.all]，区别只是触发时机。
     * 结果页逐条列出每一步，失败能看到停在哪 —— 只给「成功/失败」
     * 用户没法判断是「今天没得领」还是「接口挂了」。
     */
    private fun runAll(activity: Activity, btn: TextView) {
        RunProgressPanel.launch(activity, activity.applicationContext ?: activity, "all", "正在执行全部功能", btn)
    }

    /**
     * 运行日志：内存里最近 200 条。
     *
     * 结构和抓包数据页完全一致（用户明确要求）：
     * 内容撑满到「关闭」上方，右上角红字「清空」，点了要二次确认。
     * 两页放一起看应该是同一套东西，只是数据源不同。
     */
    private fun showLogs(activity: Activity) {
        val ctx: Context = activity
        val th = Theme.of(ctx)
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        val p = dpf(ctx, 14f)
        root.setPadding(p, p, p, p)

        val tv = TextView(ctx)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        tv.setTextColor(th.text())
        tv.typeface = Typeface.MONOSPACE
        val sb = StringBuilder()
        synchronized(Protocol.State.LOGS) {
            for (line in Protocol.State.LOGS) {
                sb.append(line).append('\n')
            }
        }
        if (sb.isEmpty()) sb.append("暂无日志")
        tv.text = sb.toString()

        val sv = ScrollView(ctx)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(tv)
        // 权重 1：内容区吃掉标题与关闭之间的全部高度
        root.addView(
            sv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ),
        )

        // 日志比抓包数据短得多（内存里只留 200 条摘要行），撑满整屏太浪费。
        // AUTO + 统一上限：短日志收到实际行数，长日志封顶后滚动。
        PanelDialog.show(
            activity, "运行日志", root,
            ratio = PanelDialog.AUTO,
            action = "清空",
            onAction = Runnable { confirmClearLogs(activity) },
        )
    }

    /** 清空运行日志的二次确认（和清空抓包库同一套结构） */
    private fun confirmClearLogs(activity: Activity) {
        val ctx: Context = activity
        val box = UiKit.column(ctx)
        val p = dpf(ctx, 16f)
        box.setPadding(p, dpf(ctx, 2f), p, p)

        val n = try {
            Protocol.State.LOGS.size
        } catch (t: Throwable) {
            0
        }
        box.addView(
            UiKit.hintView(
                ctx,
                "将清空内存里的 $n 条运行日志，不可恢复。" +
                    "只影响日志显示，抓包数据和执行记录都不受影响。",
                13f,
            ),
        )

        val acts = ArrayList<Pair<String, Runnable>>(1)
        acts.add(
            Pair(
                "确认清空",
                Runnable {
                    synchronized(Protocol.State.LOGS) {
                        Protocol.State.LOGS.clear()
                    }
                    android.widget.Toast.makeText(
                        activity, "已清空", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                },
            ),
        )
        box.addView(dangerActionRow(ctx, acts))
        PanelDialog.show(
            activity, "确认清空", box,
            ratio = PanelDialog.AUTO,
        )
    }

    /**
     * 参数库页。
     *
     * 这一页存在的理由：用户遇到「任务链说没令牌」时，需要知道
     * **当前什么状态、能做什么**，而不是去看日志猜。
     */
    private fun showParams(activity: Activity) {
        val ctx: Context = activity
        val th = Theme.of(ctx)
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        val p = dpf(ctx, 14f)
        root.setPadding(p, p, p, p)

        val stateLine = TextView(ctx)
        stateLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        stateLine.setTextColor(th.text())
        stateLine.text = "状态：" + ApiInit.stateText()
        stateLine.setPadding(0, 0, 0, dpf(ctx, 6f))
        root.addView(stateLine)

        val hint = ApiInit.actionHint(activity)
        if (hint.isNotEmpty()) {
            val tip = UiKit.hintView(ctx, hint, 12f)
            tip.setPadding(0, 0, 0, dpf(ctx, 10f))
            root.addView(tip)
        } else {
            val tip = UiKit.hintView(
                ctx,
                "业务参数（asac 等）独立存放，清空抓包库不影响这些功能。",
                12f,
            )
            tip.setPadding(0, 0, 0, dpf(ctx, 10f))
            root.addView(tip)
        }

        val tv = TextView(ctx)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tv.setTextColor(th.text())
        val lines = ParamStore.lines(activity)
        val sb = StringBuilder()
        if (lines.isEmpty()) {
            sb.append("暂无记录")
        } else {
            for (line in lines) sb.append("· ").append(line).append('\n')
        }
        tv.text = sb.toString()

        val sv = ScrollView(ctx)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(tv)
        root.addView(
            sv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ),
        )

        PanelDialog.show(
            activity, "参数库", root,
            ratio = PanelDialog.AUTO,
            action = "重置",
            onAction = Runnable { confirmResetParams(activity) },
        )
    }

    private fun confirmResetParams(activity: Activity) {
        val ctx: Context = activity
        val box = UiKit.column(ctx)
        val p = dpf(ctx, 16f)
        box.setPadding(p, dpf(ctx, 2f), p, p)

        box.addView(
            UiKit.hintView(
                ctx,
                "清空缓存的业务参数。下次执行时如果宿主发过请求会自动重新记下，" +
                    "否则需要打开一次闲鱼币任务页。" +
                    "抓包库不受影响，执行记录和任务黑名单也不受影响。",
                13f,
            ),
        )

        val acts = ArrayList<Pair<String, Runnable>>(1)
        acts.add(
            Pair(
                "确认重置",
                Runnable {
                    ParamStore.clearAll()
                    ApiInit.resume(activity)
                    android.widget.Toast.makeText(
                        activity, "已重置", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                },
            ),
        )
        box.addView(dangerActionRow(ctx, acts))
        PanelDialog.show(
            activity, "确认重置", box,
            ratio = PanelDialog.AUTO,
        )
    }

    /**
     * 任务黑名单页。
     *
     * 三档顺序（永久 → 已拉黑 → 计数中）就是用户关心的顺序：
     * 「哪些任务我永远别指望了」排最前，最后才是「还差一次就拉黑」。
     */
    private fun showBlacklist(activity: Activity) {
        val ctx: Context = activity
        val th = Theme.of(ctx)
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        val p = dpf(ctx, 14f)
        root.setPadding(p, p, p, p)

        val tip = UiKit.hintView(
            ctx,
            "黑名单中的任务会暂时或长期跳过，具体状态见下方。" +
                "参数失败的当天冷却另行记录，次日可重试。" +
                "任务能否完成以服务端回读为准。",
            12f,
        )
        tip.setPadding(0, 0, 0, dpf(ctx, 10f))
        root.addView(tip)

        val tv = TextView(ctx)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tv.setTextColor(th.text())
        val lines = TaskBlacklist.lines(activity, 200)
        val sb = StringBuilder()
        if (lines.isEmpty()) {
            sb.append("暂无记录")
        } else {
            for (line in lines) sb.append("· ").append(line).append('\n')
        }
        tv.text = sb.toString()

        val sv = ScrollView(ctx)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(tv)
        root.addView(
            sv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ),
        )

        PanelDialog.show(
            activity, "任务黑名单", root,
            ratio = PanelDialog.AUTO,
            action = "清空",
            onAction = Runnable { confirmClearBlacklist(activity) },
        )
    }

    private fun confirmClearBlacklist(activity: Activity) {
        val ctx: Context = activity
        val box = UiKit.column(ctx)
        val p = dpf(ctx, 16f)
        box.setPadding(p, dpf(ctx, 2f), p, p)

        val n = TaskBlacklist.count(activity)
        val perm = TaskBlacklist.permanentCount(activity)
        val soft = n - perm
        box.addView(
            UiKit.hintView(
                ctx,
                "将清空 $soft 条「计数中」的记录，这些任务下次会重新尝试。\n" +
                    (if (perm > 0) {
                        "另有 $perm 条永久拉黑（交易/履约类），清掉也做不了，会保留。"
                    } else {
                        "没有永久拉黑的条目。"
                    }),
                13f,
            ),
        )

        val acts = ArrayList<Pair<String, Runnable>>(1)
        acts.add(
            Pair(
                "确认清空",
                Runnable {
                    TaskBlacklist.clearSoft(activity)
                    android.widget.Toast.makeText(
                        activity, "已清空", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                },
            ),
        )
        box.addView(dangerActionRow(ctx, acts))
        PanelDialog.show(
            activity, "确认清空", box,
            ratio = PanelDialog.AUTO,
        )
    }

    /**
     * 「我的数据」二级页。
     *
     * 值全部来自 [UserData] 的快照（业务链跑的时候顺手写的），这里不发请求 ——
     * 面板是弹窗，等网络就是让用户盯着一个白框。
     */
    private fun showUserData(activity: Activity) {
        val ctx: Context = activity
        val th = Theme.of(ctx)
        val root = UiKit.column(ctx)
        val p = dpf(ctx, 14f)
        root.setPadding(p, dpf(ctx, 10f), p, p)

        root.addView(
            UiKit.hintView(
                ctx,
                "这些数字是跑任务时顺手记下来的，点「立即执行」或打开对应页面后自动更新。" +
                    UserData.updated(activity) + "。",
                12f,
            ),
        )
        val card = UiKit.card(ctx)
        val rows = ArrayList<Pair<String, String>>()
        rows.add(Pair("闲鱼币", if (UserData.coin(activity) > 0) UserData.coin(activity).toString() else "—"))
        rows.add(Pair("游戏币", if (UserData.point(activity) > 0) UserData.point(activity).toString() else "—"))
        rows.add(Pair("现金红包", UserData.cashText(activity)))
        rows.add(Pair("待拆红包", UserData.red(activity).toString() + " 个"))
        rows.add(
            Pair(
                "等级",
                if (UserData.level(activity) > 0) {
                    "Lv" + UserData.level(activity) + " · 经验 " + UserData.exp(activity)
                } else {
                    "—"
                },
            ),
        )
        for (i in rows.indices) {
            if (i > 0) card.addView(divider(ctx))
            val row = Row(ctx)
            val k = android.widget.TextView(ctx)
            k.text = rows[i].first
            k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            k.setTextColor(th.text())
            row.addView(k, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val v = android.widget.TextView(ctx)
            v.text = rows[i].second
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            v.setTextColor(th.hint())
            row.addView(v)
            card.addView(row)
        }
        root.addView(card)

        PanelDialog.show(activity, "我的数据", root, ratio = PanelDialog.AUTO)
    }

    /** 带内边距的一行（UiKit.row 已经统一了高度/内外边距） */
    private fun Row(ctx: Context): LinearLayout {
        val r = UiKit.row(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        return r
    }

    /**
     * 自动执行设置页：**单选**两种时机。
     *
     * 之前这里是一排间隔（15/30/60/120 分钟），对用户来说是"定时器"思维；
     * 实际要回答的只有一句话 —— 什么时候跑。所以换成两个单选：
     *   - 每次启动：宿主每次冷启动跑一轮（默认）
     *   - 每天一次：当天第一次启动跑，之后重启不重复
     * 选中项用主题强调色画实心圆点，未选中画空心环。
     */
    private fun showAutoTask(activity: Activity) {
        val ctx: Context = activity
        val th = Theme.of(ctx)
        var dlg: Dialog? = null

        fun show() {
            val root = UiKit.column(ctx)
            val p = dpf(ctx, 14f)
            root.setPadding(p, dpf(ctx, 10f), p, 0)

            root.addView(
                UiKit.hintView(
                    ctx,
                    "只决定什么时候跑。跑什么由上一条「立即执行」里的功能清单决定，" +
                        "限流或遇到人机验证时会整轮顺延。",
                    12f,
                ),
            )

            val cur = Prefs.getString(activity, Prefs.AUTO_MODE, Prefs.AUTO_MODE_H4)
            val card = UiKit.card(ctx)
            // 频次从高到低倒序，默认选中「每 4 小时」
            val modes = arrayOf(
                Pair(Prefs.AUTO_MODE_H4, "每 4 小时"),
                Pair(Prefs.AUTO_MODE_DAILY, "每天一次"),
                Pair(Prefs.AUTO_MODE_ALWAYS, "每次启动"),
            )
            for (i in modes.indices) {
                if (i > 0) card.addView(divider(ctx))
                val key = modes[i].first
                val on = key == cur
                val row = UiKit.row(ctx)
                val t = TextView(ctx)
                t.text = modes[i].second
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                t.setTextColor(if (on) th.text() else th.sub())
                row.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                // 单选指示：选中 = 实心强调色圆点，未选中 = 空心环
                val dotV = View(ctx)
                val d = dpf(ctx, 18f)
                dotV.layoutParams = LinearLayout.LayoutParams(d, d)
                dotV.background = if (on) {
                    UiKit.oval(th.accent())
                } else {
                    val g = android.graphics.drawable.GradientDrawable()
                    g.shape = android.graphics.drawable.GradientDrawable.OVAL
                    g.setStroke(dpf(ctx, 1.5f), th.off())
                    g.setColor(0x00000000)
                    g
                }
                row.addView(dotV)

                row.setOnClickListener {
                    Prefs.setString(activity, Prefs.AUTO_MODE, key)
                    Protocol.State.invalidateSwCache()
                    dlg?.dismiss()
                    show()
                }
                card.addView(row)
            }
            root.addView(card)

            val status = UiKit.textView(ctx, "", 12f)
            status.setTextColor(th.hint())
            val last = AutoTask.lastAt(activity)
            status.text = if (last <= 0L) {
                "还没有跑过"
            } else {
                "上次：${fmtHm(last)}　${AutoTask.lastText(activity)}"
            }
            status.setPadding(dpf(ctx, 4f), dpf(ctx, 12f), dpf(ctx, 4f), dpf(ctx, 10f))
            root.addView(status)

            dlg = PanelDialog.show(
                activity, "自动执行", root,
                ratio = PanelDialog.AUTO,
            )
        }

        show()
    }

    /** 毫秒 → `HH:mm`（北京时间由系统时区决定） */
    private fun fmtHm(ms: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = ms
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val m = cal.get(java.util.Calendar.MINUTE)
        return (if (h < 10) "0$h" else "" + h) + ":" + (if (m < 10) "0$m" else "" + m)
    }
}
