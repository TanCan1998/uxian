package io.github.tancan1998.uxian

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * 在闲鱼「我的」页标题栏插入口（紧挨「帮助与客服」），点开是仿设置页样式的面板。
 * 不新增 Activity、不改 manifest。
 */
internal object UiInjector {

    private val TAG: String = MtopBridge.tag()
    private const val TAG_ATTACHED = "xysidecar-entry"
    private const val TAG_COL = "xysidecar-col"

    /** 行 → 已插入的入口（每行一份，行被回收时自然失效） */
    private val rowMap = WeakHashMap<ViewGroup, View>()

    /** 行 → 该行入口的样式（true=纯文字），行高类别变化时重建 */
    private val rowStyle = WeakHashMap<ViewGroup, Boolean>()

    /** 入口文案的弱引用表，切调试态时统一刷新 */
    private val entryLabels = ArrayList<WeakReference<TextView>>()
    private val ICON_ENTRY = Any()
    private val GLOW_WRAP = Any()

    // ---------------------------------------------------------------- 入口注入

    @Volatile
    private var currentRef: WeakReference<Activity>? = null

    /** 当前前台 Activity（自动执行结束后按需弹结果用），弱引用避免持有页面。 */
    @JvmStatic
    fun current(): Activity? = currentRef?.get()

    @JvmStatic
    fun attach(activity: Activity) {
        currentRef = WeakReference(activity)
        Log.i("xysidecar", "attach ENTER: " + activity.javaClass.simpleName)
        try {
            // 强制深色：渲染滤镜挂在 decor 上，必须在任何 UI 操作之前套好，
            // 否则会先闪一帧原色
            try {
                DarkForce.apply(activity)
            } catch (ignored: Throwable) {
            }
            val decor = activity.window.decorView
            Log.i("xysidecar", "attach decor tag=" + decor.tag)
            if (TAG_ATTACHED == decor.tag) {
                // 已经挂过监听：页面切回来时补几次同步就能恢复入口，
                // 不必等下次冷启动（用户反馈：切换页面后入口偶尔消失）。
                for (delay in longArrayOf(80L, 400L, 1200L)) {
                    decor.postDelayed(Runnable {
                        try {
                            sync(activity, decor)
                        } catch (ignored: Throwable) {
                        }
                    }, delay)
                }
                return
            }
            decor.tag = TAG_ATTACHED
            Log.i("xysidecar", "attach: " + activity.javaClass.simpleName)

            // 布局稳定后强制重建两次，规避首帧尺寸不准
            for (delay in longArrayOf(600L, 1500L)) {
                decor.postDelayed(Runnable {
                    try {
                        clearRows()
                        sync(activity, decor)
                    } catch (ignored: Throwable) {
                    }
                }, delay)
            }

            // 定时兜底：onResume 时机不可靠时也能把入口挂上
            decor.postDelayed(object : Runnable {
                var n = 0
                override fun run() {
                    try {
                        sync(activity, decor)
                    } catch (ignored: Throwable) {
                    }
                    try {
                        // 窗口级同步：让无头改 dark_force 免重启实时生效
                        DarkForce.sync(decor)
                    } catch (ignored: Throwable) {
                    }
                    if (++n < 100000) decor.postDelayed(this, 1200L)
                }
            }, 300)

            val last = longArrayOf(0)
            decor.viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        val now = System.currentTimeMillis()
                        if (now - last[0] < 300) return
                        last[0] = now
                        sync(activity, decor)
                    }
                },
            )
        } catch (t: Throwable) {
            // 注入失败绝不能把宿主界面带崩：这里吞掉异常，最多弹一次提示
            Log.e(TAG, "attach failed: $t")
            notifyAttachFail(activity, "注入异常 " + t.javaClass.simpleName)
        }
    }

    /**
     * 注入失败提示的节流状态。
     *
     * 为什么需要：入口挂不上时用户只看到「面板不见了」，会以为模块没生效，
     * 而真正的原因（版本不兼容、锚点改名）只在日志里。Toast 一次就够 ——
     * 每次 onResume 都弹会把界面烦死，所以**一个进程只提示一次**。
     */
    @Volatile
    private var attachFailNotified = false

    /** 找不到锚点的连续次数（只用来说明日志，不据此判定失败） */
    private var anchorMiss = 0
    private var anchorMissSince = 0L

    /** 「没有锚点」的日志每进程只写一次，别把运行日志刷满 */
    private var missLogged = false

    private fun notifyAttachFail(activity: Activity, why: String) {
        if (attachFailNotified) return
        attachFailNotified = true
        try {
            Protocol.State.logLine("面板注入失败：" + why)
        } catch (ignored: Throwable) {
        }
        try {
            // 模块跑在宿主主线程，这里必须回主线程弹（不然 Looper 不对直接抛）
            activity.runOnUiThread {
                try {
                    Toast.makeText(
                        activity,
                        "U闲：面板入口注入失败（" + why + "），可能是闲鱼版本不兼容",
                        Toast.LENGTH_LONG,
                    ).show()
                } catch (ignored: Throwable) {
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun sync(activity: Activity, decor: View) {
        try {
            if (decor !is ViewGroup) return
            var anchor = ViewFinder.findAnchor(decor, arrayOf("帮助与客服", "帮助"))
            if (anchor == null) anchor = ViewFinder.findAnchor(decor, arrayOf("设置"))
            if (anchor == null) {
                // 锚点不存在是**正常状态**：首页、搜索页、消息页都没有「设置」。
                // 所以这里只记一笔日志（每进程一次），绝不弹 Toast ——
                // 实测按「连续 3 秒找不到」判失败会在首页立刻误报一次。
                val now = System.currentTimeMillis()
                if (anchorMissSince == 0L) anchorMissSince = now
                anchorMiss++
                if (anchorMiss == 12 && now - anchorMissSince > 3000L && !missLogged) {
                    missLogged = true
                    Protocol.State.logLine("面板注入：当前页面没有入口锚点（首页/消息页正常现象）")
                }
                return
            }
            anchorMiss = 0
            anchorMissSince = 0L

            var item: View = anchor
            var row: View? = null
            for (d in 0 until 4) {
                val parent = item.parent as? View ?: break
                if (parent is ViewGroup && parent.childCount >= 3) {
                    row = parent
                    break
                }
                item = parent
            }
            val rowGroup = row as? ViewGroup ?: return
            // 列表滚动会不停换图，父层滤镜会把它们变成负片，
            // 这里周期性给图片的 drawable 补一次同款滤镜（双重反色 = 原色）
            DarkForce.sync(decor)
            val anchorItem: View = item

            val textOnly = !ViewFinder.hasBigChild(rowGroup, dpf(activity, 45f))

            val existing = rowMap[rowGroup]
            val oldStyle = rowStyle[rowGroup]
            // 自愈：宿主重建这一行时我们的列可能被摘掉或量成 0 宽，
            // 残留在 rowMap 里的记录会让 sync 误以为「还在」，于是永远不再挂。
            if (existing != null) {
                val alive = existing.isAttachedToWindow && existing.parent === rowGroup &&
                    existing.visibility == View.VISIBLE && existing.width > 0
                if (!alive) {
                    (existing.parent as? ViewGroup)?.removeView(existing)
                    rowMap.remove(rowGroup)
                    rowStyle.remove(rowGroup)
                }
            }
            if (existing != null && existing.isAttachedToWindow &&
                existing.parent === rowGroup && oldStyle != null && oldStyle == textOnly
            ) {
                return
            }
            if (existing != null) {
                (existing.parent as? ViewGroup)?.removeView(existing)
                rowMap.remove(rowGroup)
                rowStyle.remove(rowGroup)
            }

            var protoCol = ViewFinder.findColumnWithText(rowGroup, "设置")
            if (protoCol == null) protoCol = ViewFinder.findColumnWithText(rowGroup, "帮助与客服")
            if (protoCol != null && (protoCol.width <= 0 || protoCol.height <= 0)) return

            var protoIcon: View? = null
            var protoLabel: View? = null
            if (protoCol is ViewGroup && protoCol.childCount >= 2) {
                protoIcon = protoCol.getChildAt(0)
                protoLabel = protoCol.getChildAt(1)
            }

            val col = buildColumn(activity, textOnly, anchor, protoCol, protoIcon, protoLabel)

            val cr = IntArray(2)
            rowGroup.getLocationInWindow(cr)
            var statusH = 0
            try {
                val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
                if (id > 0) statusH = activity.resources.getDimensionPixelSize(id)
            } catch (ignored: Throwable) {
            }
            val deficit = maxOf(0, statusH - cr[1])

            val lp: LinearLayout.LayoutParams
            val plp = protoCol?.layoutParams as? LinearLayout.LayoutParams
            if (protoCol != null && plp != null) {
                val w = if (plp.width > 0) plp.width else protoCol.width
                val h = if (plp.height > 0) {
                    plp.height
                } else if (protoCol.height > 0) {
                    protoCol.height
                } else {
                    LinearLayout.LayoutParams.MATCH_PARENT
                }
                // 外侧边距属于父容器，点上去会穿到后面的兄弟视图
                // → 改成我们自己的内边距，整块都可点，视觉间距不变
                val side = maxOf(plp.leftMargin, plp.rightMargin)
                lp = LinearLayout.LayoutParams(w + side * 2, h)
                lp.gravity = if (plp.gravity == 0) Gravity.CENTER_VERTICAL else plp.gravity
                lp.leftMargin = 0
                lp.rightMargin = 0
                lp.topMargin = plp.topMargin + deficit
                lp.bottomMargin = plp.bottomMargin
                col.setPadding(side, 0, side, 0)
            } else {
                lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                )
                lp.gravity = Gravity.CENTER_VERTICAL
                lp.topMargin = deficit
            }

            var idx = rowGroup.indexOfChild(anchorItem)
            if (idx < 0) idx = 0
            col.tag = TAG_COL
            (col as? ViewGroup)?.let {
                it.clipChildren = false
                it.clipToPadding = false
            }
            rowGroup.clipChildren = false
            rowGroup.clipToPadding = false
            (rowGroup.parent as? ViewGroup)?.let {
                it.clipChildren = false
                it.clipToPadding = false
            }
            rowGroup.addView(col, idx, lp)
            rowMap[rowGroup] = col
            rowStyle[rowGroup] = textOnly
            removeOtherEntries(decor, col)

            col.post {
                val p1 = IntArray(2)
                col.getLocationInWindow(p1)
                Log.i(
                    "xysidecar",
                    "entry style=" + (if (textOnly) "text" else "icon") +
                        " size=${col.width}x${col.height} at=${p1[0]},${p1[1]}",
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "sync failed: $t")
        }
    }

    /** 光晕容器：光晕在图标外侧向外扩散，图标保持原尺寸、居中绘制在光晕之上 */
    private class GlowWrap(c: Context) : FrameLayout(c) {
        private val gp = Paint(Paint.ANTI_ALIAS_FLAG)
        private var curGlow = 0

        init {
            setWillNotDraw(false)
            clipChildren = false
            clipToPadding = false
        }

        fun setGlow(color: Int) {
            curGlow = color
            invalidate()
        }

        override fun dispatchDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w > 0 && h > 0) {
                val cx = w / 2f
                val cy = h / 2f
                val outer = minOf(w, h) / 2f + dpf(context, 9f)
                val cc = curGlow and 0x00FFFFFF
                gp.shader = RadialGradient(
                    cx, cy, outer,
                    intArrayOf(
                        cc or 0xC8000000.toInt(),
                        cc or 0x8C000000.toInt(),
                        cc or 0x4A000000,
                        cc or 0x1A000000,
                        cc or 0x00000000,
                    ),
                    floatArrayOf(0.34f, 0.52f, 0.70f, 0.86f, 1f),
                    Shader.TileMode.CLAMP,
                )
                gp.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, outer, gp)
                gp.shader = null
            }
            super.dispatchDraw(canvas)
        }
    }

    /** 图标本体：保持原尺寸，只画圆角底色与文字；光晕由外层 GlowWrap 负责 */
    private fun applyEntryIcon(icon: TextView) {
        val c = icon.context
        val debug = Prefs.get(c, Prefs.DEBUG, false)
        // 配色统一走 Theme：调试态蓝、正常态黄，面板里的开关也用同一套
        icon.setTextColor(if (debug) ThemeColors.ON_DEBUG_ACCENT else ThemeColors.ON_ACCENT)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.setColor(if (debug) ThemeColors.DEBUG_ACCENT else ThemeColors.ACCENT)
        bg.cornerRadius = dpf(c, 6f).toFloat()
        icon.background = bg
        (icon.parent as? GlowWrap)?.setGlow(if (debug) ThemeColors.DEBUG_GLOW else 0xFFFFD84D.toInt())
    }

    /** 把图标包进光晕容器：容器 = 图标 + 四周 glowMargin */
    private fun wrapGlow(ctx: Context, icon: TextView, debug: Boolean, iconSizePx: Int): GlowWrap {
        val wrap = GlowWrap(ctx)
        wrap.setGlow(if (debug) ThemeColors.DEBUG_GLOW else 0xFFFFD84D.toInt())
        val ilp = FrameLayout.LayoutParams(iconSizePx, iconSizePx)
        ilp.gravity = Gravity.CENTER
        wrap.addView(icon, ilp)
        return wrap
    }

    /** 入口文案 */
    private fun entryText(c: Context): String =
        if (Prefs.get(c, Prefs.DEBUG, false)) "调试" else "U 闲"

    private fun registerLabel(tv: TextView) {
        try {
            val it = entryLabels.iterator()
            while (it.hasNext()) {
                if (it.next().get() == null) it.remove()
            }
            entryLabels.add(WeakReference(tv))
        } catch (ignored: Throwable) {
        }
    }

    private fun refreshLabels() {
        try {
            for (ref in entryLabels) {
                val t = ref.get() ?: continue
                t.text = entryText(t.context)
                val pv = t.parent as? View
                val wrap = pv?.let { ViewFinder.findTagged(it, GLOW_WRAP) }
                if (wrap is GlowWrap) {
                    wrap.setGlow(
                        if (Prefs.get(t.context, Prefs.DEBUG, false)) {
                            ThemeColors.DEBUG_GLOW
                        } else {
                            0xFFFFD84D.toInt()
                        },
                    )
                }
                val ic = pv?.let { ViewFinder.findTagged(it, ICON_ENTRY) }
                if (ic is TextView) applyEntryIcon(ic)
            }
        } catch (ignored: Throwable) {
        }
    }

    /** 长按切调试模式：入口文案和配色一起换 */
    private fun toggleDebug(v: View): Boolean {
        // 用 liveBool 读，而不是 Prefs.get（后者是进程内单例缓存，
        // 和 SP 文件可能不同步）
        val on = !Protocol.State.liveBool(v.context, Prefs.DEBUG, false)
        Prefs.set(v.context, Prefs.DEBUG, on)
        // **必须清掉 liveBool 的缓存**：Prefs.set 是异步 apply，
        // 紧接着 SettingsPanel.show 里 Theme.of 会去读 debug，
        // 命中 700ms 内的旧缓存 → 强调色不更新。
        Protocol.State.invalidateSwCache()
        refreshLabels()
        Toast.makeText(
            v.context,
            if (on) "调试模式已开启" else "调试模式已关闭",
            Toast.LENGTH_SHORT,
        ).show()
        // **不自动打开面板**。
        // 踩过的坑：这里直接 show() 时，面板用的是切换前算好的 Theme，
        // 强调色还是旧的（用户实测「第一次自动打开面板强调色没更新」）。
        // 让用户自己再点一次入口 —— 那时 Theme.of 会在新状态下重算，
        // 配色一定是对的。
        return true
    }

    private fun buildEntry(activity: Activity): View {
        val ctx: Context = activity
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.isClickable = true
        box.isFocusable = true
        box.setOnClickListener { SettingsPanel.show(activity) }
        box.isLongClickable = true
        box.setOnLongClickListener { v -> toggleDebug(v) }

        val icon = TextView(ctx)
        icon.text = "U"
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        icon.setTypeface(icon.typeface, Typeface.BOLD)
        icon.gravity = Gravity.CENTER
        val isz = dpf(ctx, 24.7f)
        icon.tag = ICON_ENTRY
        applyEntryIcon(icon)
        val glow = wrapGlow(ctx, icon, Prefs.get(ctx, Prefs.DEBUG, false), isz)
        glow.tag = GLOW_WRAP
        box.addView(glow, LinearLayout.LayoutParams(isz, isz))

        val label = TextView(ctx)
        label.text = entryText(ctx)
        registerLabel(label)
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, 35f)
        // 入口文案画在强调色底上，跟随主题（调试蓝底白字 / 黄底黑字）
        label.setTextColor(Theme.of(ctx).onAccent())
        label.gravity = Gravity.CENTER
        val llp = LinearLayout.LayoutParams(dpf(ctx, 24.7f), dpf(ctx, 15.7f))
        llp.topMargin = dpf(ctx, 4.7f)
        box.addView(label, llp)
        return box
    }

    private fun buildColumn(
        activity: Activity,
        textOnly: Boolean,
        anchorView: View,
        protoCol: View?,
        protoIcon: View?,
        protoLabel: View?,
    ): View {
        val ctx: Context = activity
        val box = LinearLayout(ctx)
        box.orientation = if (textOnly) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.isClickable = true
        box.isFocusable = true
        box.setOnClickListener { SettingsPanel.show(activity) }
        box.isLongClickable = true
        box.setOnLongClickListener { v -> toggleDebug(v) }

        var textSize = 35f
        var textColor = Color.parseColor("#1A1A1A")
        var tf: Typeface = Typeface.DEFAULT
        if (anchorView is TextView) {
            textSize = anchorView.textSize
            textColor = anchorView.currentTextColor
            anchorView.typeface?.let { tf = it }
        }

        if (!textOnly) {
            val icon = TextView(ctx)
            icon.text = "U"
            icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            icon.setTypeface(icon.typeface, Typeface.BOLD)
            icon.gravity = Gravity.CENTER
            val iconW = ViewFinder.lpWidth(protoIcon, dpf(ctx, 24.7f))
            val iconH = ViewFinder.lpHeight(protoIcon, dpf(ctx, 24.7f))
            icon.tag = ICON_ENTRY
            applyEntryIcon(icon)
            val glow = wrapGlow(ctx, icon, Prefs.get(ctx, Prefs.DEBUG, false), minOf(iconW, iconH))
            glow.tag = GLOW_WRAP
            box.addView(glow, LinearLayout.LayoutParams(iconW, iconH))
        }

        val label = TextView(ctx)
        label.text = entryText(ctx)
        registerLabel(label)
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
        label.isSingleLine = true
        label.setAutoSizeTextTypeUniformWithConfiguration(
            8,
            maxOf(8, (textSize / ctx.resources.displayMetrics.scaledDensity).toInt()),
            1,
            TypedValue.COMPLEX_UNIT_SP,
        )
        label.setTextColor(textColor)
        label.typeface = tf
        label.gravity = Gravity.CENTER
        if (textOnly) {
            box.addView(label)
        } else {
            val llp = LinearLayout.LayoutParams(
                ViewFinder.lpWidth(protoLabel, dpf(ctx, 24.7f)),
                ViewFinder.lpHeight(protoLabel, dpf(ctx, 15.7f)),
            )
            llp.topMargin = ViewFinder.lpTopMargin(protoLabel, dpf(ctx, 4.7f))
            box.addView(label, llp)
        }
        return box
    }

    private fun clearRows() {
        try {
            for (v in ArrayList(rowMap.values)) {
                (v.parent as? ViewGroup)?.removeView(v)
            }
            rowMap.clear()
            rowStyle.clear()
        } catch (ignored: Throwable) {
        }
    }

    private fun removeOtherEntries(root: View, keep: View) {
        try {
            if (root !is ViewGroup) return
            for (i in root.childCount - 1 downTo 0) {
                val c = root.getChildAt(i)
                if (c === keep) continue
                if (TAG_COL == c.tag) {
                    root.removeViewAt(i)
                    rowMap.values.remove(c)
                    continue
                }
                removeOtherEntries(c, keep)
            }
        } catch (ignored: Throwable) {
        }
    }

    @JvmStatic
    fun dpf(c: Context, v: Float): Int =
        (v * c.resources.displayMetrics.density).toInt()

    @JvmStatic
    fun resetEntry(activity: Activity) {
        try {
            clearRows()
            Toast.makeText(
                activity, "已重置，正在重新绘制入口", Toast.LENGTH_SHORT,
            ).show()
            Protocol.State.logLine("已重置入口")
        } catch (t: Throwable) {
            Protocol.State.logLine("重置失败：$t")
        }
    }

    /**
     * 面板打开时模糊宿主背景。
     *
     * 2026-10-29 修：原来直接 decor.setRenderEffect(模糊)，会把「反色」滤镜
     * **整个覆盖掉**，关面板时又置 null —— 表现就是「一打开面板反色就失效，
     * 关了也回不来」。现在装饰层的 RenderEffect 由 DarkForce 独占并合成。
     */
    @JvmStatic
    fun setBlur(decor: View?, on: Boolean) {
        try {
            DarkForce.setBlur(decor, on)
        } catch (ignored: Throwable) {
        }
    }
}
