package com.xiandehuang.u

import android.app.Activity
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsetsController
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * 给宿主（闲鱼）全局套一层「保留色相的亮度反转」滤镜，做出近似的深色效果。
 *
 * 为什么只能这么干：闲鱼包里**没有**任何夜间资源 —— res/ 下 0 个 -night 目录、
 * resources.arsc 里 dark/night/深色 命中 0，dex 里也搜不到
 * AppCompatDelegate / UiModeManager / isNightMode / ForceDark。
 * 它根本没有「深色主题」这套东西，改系统夜间模式不会有效果。
 *
 * 核心是 **AGSL RuntimeShader**（API 33+）而不是颜色矩阵：
 * 一个 pass 里同时判断每个像素的饱和度，
 *   - 低饱和（背景、文字、按钮、UI 底）→ 反色
 *   - 高饱和（商品照片、动图、视频）→ 保持原色
 * 这样不用去碰任何一个 ImageView，Fresco 的图、动图、WebView、
 * Flutter 纹理全都能一次覆盖到。
 *
 * 之前走过的弯路（都实测失败，别再试）：
 *   - 给 ImageView 的 drawable 设 ColorFilter → 闲鱼用 Fresco 的 SimpleDraweeView，
 *     由 DraweeHolder 直接画，不走 Drawable.setColorFilter
 *   - 给 ImageView 设 RenderEffect（反色 / 恒等）→ 对 Fresco 的图无效，
 *     截图像素 88% 仍是「只反一次」
 *   - ColorMatrix.setRotate(0,180) 做色相旋转 → 那是绕红轴转，且会把反色的
 *     +255 偏移一起转掉，整屏全黑
 *
 * 铁律：**只有 DarkForce 能写 decor.setRenderEffect()**。
 * 之前 UiInjector.setBlur() 直接写，一开面板就把反色覆盖成纯模糊，
 * 关面板又置 null，反色彻底丢失（就是「面包打开时反色失效」）。
 */
object DarkForce {

    private val decorRefs = ArrayList<WeakReference<View>>()

    /** 这个 decor 当前是否要模糊（面板打开时为 true） */
    private val blurred = WeakHashMap<View, Boolean>()

    /** decor 是否已套上反色（日志只打一次） */
    private val decorInverted = WeakHashMap<View, Boolean>()

    /** 属于 Activity 窗口的 decor —— 只有这些才去动系统栏图标颜色 */
    private val activityDecor = WeakHashMap<View, Boolean>()

    /**
     * 深色关闭时，是否还需要把 light bars 设回去一次。
     * 设完一次就清除 —— 之后宿主自己会把图标管理起来（比如进图片
     * 查看器/视频页时它会自己要求白图标），周期循环不能再强写覆盖，
     * 否则宿主黑底页面的白色图标会被我们改回深色，又看不见。
     */
    private val pendingRestore = WeakHashMap<View, Boolean>()

    private const val LIGHT_STATUS_BAR = 0x00002000
    private const val LIGHT_NAV_BAR = 0x00000010

    // ---- 圆形扩散动画（只作用于宿主背景） ----
    /**
     * 扩散进度：1 = 整屏反色，0 = 完全不反（半径收到 0）。
     * 注意这只是**背景**的动画，面板是独立窗口，不走这里。
     */
    @Volatile private var reveal = 1f
    @Volatile private var revealX = 0.5f
    @Volatile private var revealY = 0.5f
    private val UI = Handler(Looper.getMainLooper())
    private var anim: Runnable? = null
    @Volatile private var lastDecor: WeakReference<View>? = null

    /** 扩散时长；面板重开要等它播完 */
    const val REVEAL_MS = 520L

    /**
     * 从某个控件的屏幕位置起播扩散 —— 拨开关时圆就从那个开关长出来。
     *
     * @param toDark true = 扩散成深色；false = 收缩回浅色
     */
    @JvmStatic
    fun revealFrom(anchor: View?, toDark: Boolean) {
        val decor = lastDecor?.get()
        var nx = 0.5f
        var ny = 0.5f
        try {
            if (anchor != null && decor != null) {
                val a = IntArray(2)
                anchor.getLocationOnScreen(a)
                val d = IntArray(2)
                decor.getLocationOnScreen(d)
                val w = maxOf(1, decor.width)
                val h = maxOf(1, decor.height)
                nx = (a[0] - d[0] + anchor.width * 0.5f) / w
                ny = (a[1] - d[1] + anchor.height * 0.5f) / h
            }
        } catch (ignored: Throwable) {
        }
        startReveal(nx, ny, REVEAL_MS, toDark)
    }

    private fun startReveal(nx: Float, ny: Float, durMs: Long, toDark: Boolean) {
        if (Build.VERSION.SDK_INT < 33) {
            // 31/32 的矩阵退路没有逐像素遮罩，直接切，别装作有动画
            refreshAll()
            return
        }
        revealX = nx.coerceIn(0f, 1f)
        revealY = ny.coerceIn(0f, 1f)
        anim?.let {
            UI.removeCallbacks(it)
            anim = null
        }
        val t0 = android.os.SystemClock.uptimeMillis()
        val dur = maxOf(120L, durMs)
        val from = if (toDark) 0f else 1f
        val to = if (toDark) 1f else 0f
        reveal = from
        val r = object : Runnable {
            override fun run() {
                val t = (android.os.SystemClock.uptimeMillis() - t0) / dur.toFloat()
                if (t >= 1f) {
                    reveal = to
                    anim = null
                    // 关的那一趟：anim 已置 null，want 跟着 wanted() 走，
                    // 刷完就把滤镜彻底撤掉
                    refreshAll()
                    return
                }
                val inv = 1f - t
                // easeOutCubic：起步快收尾慢，像一滴墨摊开
                reveal = from + (to - from) * (1f - inv * inv * inv)
                refreshAll()
                UI.postDelayed(this, 16L)
            }
        }
        anim = r
        UI.post(r)
    }

    /** 停掉动画并把进度复位到「完全反色」 */
    @JvmStatic
    fun stopAnim() {
        anim?.let {
            UI.removeCallbacks(it)
            anim = null
        }
        reveal = 1f
        revealX = 0.5f
        revealY = 0.5f
    }

    private fun animating(): Boolean = anim != null

    /** Activity 恢复时调用：按当前开关给它的 decor 套好滤镜 */
    @JvmStatic
    fun apply(activity: Activity?) {
        if (activity == null) return
        try {
            val decor: View? = activity.window?.decorView
            if (decor == null) return
            track(decor)
            activityDecor[decor] = java.lang.Boolean.TRUE
            val want = wanted()
            val first = want && java.lang.Boolean.TRUE != decorInverted[decor]
            applyTo(decor)
            if (first) {
                Protocol.State.logLine(
                    "深色：已对 " + activity.javaClass.simpleName + " 套用反色",
                )
            }
        } catch (t: Throwable) {
            Protocol.State.logLine("深色：套用失败 $t")
        }
    }

    /**
     * 同步系统栏图标颜色 —— **反色必须配对的一步**。
     *
     * 宿主窗口是 edge-to-edge 的，状态栏/导航栏底下就是它自己的内容，
     * 被我们反成黑底。但图标是 SystemUI 在窗口外面画的，颜色由
     * 宿主窗口的 appearance 决定：宿主开屏设的是
     * APPEARANCE_LIGHT_STATUS_BARS（浅底深图标），于是深图标画在
     * 我们的纯黑底上 —— 实测字形亮度 mean=0，时间/信号/电量**完全看不见**。
     *
     * 所以反色开启时把这几个 light 位清掉（改画浅色图标），并记下
     * "欠一次恢复"；关掉时**只补一次** light bars（浅底要深图标），
     * 补完立即撒手 —— 之后宿主对图标的一切自主设置（比如黑底页面
     * 自己要求白图标）都不再被我们覆盖。
     * 只在值真的变了时才写，否则每次 16ms 的动画帧都会触发一次 relayout。
     */
    private fun syncBars(decor: View?, want: Boolean) {
        if (decor == null || java.lang.Boolean.TRUE != activityDecor[decor]) return
        try {
            val mask = LIGHT_STATUS_BAR or LIGHT_NAV_BAR
            val flags = decor.systemUiVisibility
            if (want) {
                pendingRestore[decor] = java.lang.Boolean.TRUE
                // 两套 API 都要清：宿主是写 legacy flags 的（dumpsys 里
                // vsysui=... LIGHT_STATUS_BAR 就是它），而新 API 的
                // appearance 位是另一套存储，只清一边会漏
                if ((flags and mask) != 0) decor.systemUiVisibility = flags and mask.inv()
                clearAppearance(decor, true)
            } else if (pendingRestore.remove(decor) != null) {
                // 浅底 → 深图标。只补这一笔，之后不再碰
                decor.systemUiVisibility = flags or mask
                clearAppearance(decor, false)
            }
        } catch (ignored: Throwable) {
        }
    }

    /** 新 API 的 appearance 位：dark=true 清掉（浅图标），false 设上（深图标） */
    private fun clearAppearance(decor: View, dark: Boolean) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val c: WindowInsetsController = decor.windowInsetsController ?: return
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            val target = if (dark) 0 else mask
            if (c.systemBarsAppearance != target) {
                c.setSystemBarsAppearance(target, mask)
            }
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 把任意窗口（比如面板的 Dialog window）登记进来，跟着一起反色。
     * 面板是独立窗口，Activity 的 decor 滤镜管不到它。
     */
    @JvmStatic
    fun attach(windowDecor: View?) {
        if (windowDecor == null) return
        try {
            track(windowDecor)
            applyTo(windowDecor)
        } catch (ignored: Throwable) {
        }
    }

    /** 面板打开/关闭时由 UiInjector 调用：模糊和反色是同一条链，必须在这里合成 */
    @JvmStatic
    fun setBlur(decor: View?, on: Boolean) {
        if (decor == null) return
        try {
            blurred[decor] = on
            track(decor)
            applyTo(decor)
        } catch (ignored: Throwable) {
        }
    }

    /** 面板里切换开关、或周期性同步时调用 */
    @JvmStatic
    fun refreshAll() {
        val want = wanted()
        try {
            for (r in ArrayList(decorRefs)) {
                r.get()?.let { applyTo(it) }
            }
        } catch (ignored: Throwable) {
        }
        if (!want) {
            try {
                decorInverted.clear()
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * 周期性调用。
     *
     * 现在没有「给子 View 补滤镜」这回事了 —— 一个着色器就够。
     * 这个名字保留是为了不改 UiInjector 的调用点，实际只做窗口级同步
     * （顺带让无头改 dark_force 免重启实时生效）。
     */
    @JvmStatic
    fun sync(root: View?) {
        if (root == null) return
        try {
            applyTo(root)
        } catch (ignored: Throwable) {
        }
    }

    // ------------------------------------------------------------ 内部实现

    private fun wanted(): Boolean {
        val c = Protocol.State.app
        // 走 liveBool 而不是 Prefs.get：provider 写入的值在别的进程看不到
        // （getSharedPreferences 是进程内单例缓存），那样无头改开关必须重启才生效
        return c != null && Protocol.State.liveBool(c, Prefs.DARK_FORCE) &&
            Build.VERSION.SDK_INT >= 31
    }

    private fun applyTo(decor: View) {
        try {
            lastDecor = WeakReference(decor)
            // 动画期间即使 prefs 已经写成 false，也要继续套着滤镜把收缩播完
            val want = wanted() || animating()
            val b = java.lang.Boolean.TRUE == blurred[decor]
            // 系统栏图标颜色跟滤镜同生共死：黑底 + 深图标 = 看不见（实测）
            syncBars(decor, want)
            if (!want) {
                decorInverted.remove(decor)
                if (b) {
                    // 反色关了但面板还开着 —— 只留模糊
                    decor.setRenderEffect(
                        RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP),
                    )
                } else {
                    decor.setRenderEffect(null)
                }
                return
            }
            decor.setRenderEffect(buildEffect(b, reveal, decor.width, decor.height))
            decorInverted[decor] = java.lang.Boolean.TRUE
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 按"最近使用优先"返回已登记的窗口 decor。
     * 供 ViewTreeDump 诊断用 —— provider 进程拿不到 Activity，
     * 只能靠这里跟踪到的引用。
     */
    @JvmStatic
    fun decorsNewestFirst(): List<View> {
        val out = ArrayList<View>()
        try {
            // lastDecor 是最近一次 applyTo 的，优先放最前
            val last = lastDecor?.get()
            if (last != null) out.add(last)
            for (i in decorRefs.indices.reversed()) {
                val v = decorRefs[i].get()
                if (v != null && v !== last) out.add(v)
            }
        } catch (ignored: Throwable) {
        }
        return out
    }

    private fun track(decor: View) {
        for (r in ArrayList(decorRefs)) {
            if (r.get() === decor) return
        }
        decorRefs.add(WeakReference(decor))
        while (decorRefs.size > 8) decorRefs.removeAt(0)
    }

    /**
     * 反色着色器 + 可选模糊。
     *
     * createBlurEffect(rx, ry, input, tileMode) 的 input 是先执行的，
     * 所以这里是「先反色再模糊」—— 深色背景下糊成一片，不会糊出亮边。
     */
    private fun buildEffect(blur: Boolean, radius: Float, w: Int, h: Int): RenderEffect? {
        if (Build.VERSION.SDK_INT < 31) return null
        val color = invertEffect(radius, w, h)
        if (!blur) return color
        return RenderEffect.createBlurEffect(20f, 20f, color, Shader.TileMode.CLAMP)
    }

    /** 缓存着色器：AGSL 编译一次就够，动画期间每帧只更新 uniform */
    private var shader: RuntimeShader? = null

    private fun shader(): RuntimeShader {
        var s = shader
        if (s == null) {
            s = RuntimeShader(SHADER)
            s.setFloatUniform("satLow", 0.10f)
            s.setFloatUniform("satHigh", 0.38f)
            shader = s
        }
        return s
    }

    /** API 33+ 用着色器（能保住照片原色）；31/32 退回颜色矩阵（照片会变负片） */
    private fun invertEffect(radius: Float, w: Int, h: Int): RenderEffect? {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val sh = shader()
                sh.setFloatUniform("radius", radius * 1.6f)
                sh.setFloatUniform("center", floatArrayOf(revealX, revealY))
                sh.setFloatUniform("size", floatArrayOf(maxOf(1, w).toFloat(), maxOf(1, h).toFloat()))
                return RenderEffect.createRuntimeShaderEffect(sh, "content")
            } catch (t: Throwable) {
                Protocol.State.logLine("深色：着色器不可用，退回矩阵 $t")
            }
        }
        return RenderEffect.createColorFilterEffect(matrixFilter())
    }

    /**
     * AGSL：按饱和度决定反不反色。
     *
     *   sat = 该像素的「彩度」(max-min)
     *   sat 低  → 背景/文字/图标 → 反色（白底黑字变黑底白字）
     *   sat 高  → 照片/动图/视频 → 原样保留（颜色不变）
     *   中间     → smoothstep 平滑过渡，避免照片边缘出现硬边
     *
     * 反色用「保留色相的亮度反转」：绕灰轴 (1,1,1)/√3 转 180°，
     * 展开就是 inv = 1 - (R+G+B)/3，再以该灰度为基准保持色差。
     * 效果：白→黑、纯红还是红（不是青色）。
     */
    private const val SHADER =
        "uniform shader content;\n" +
            "uniform float satLow;\n" +
            "uniform float satHigh;\n" +
            "uniform float radius;\n" +
            "uniform float2 center;\n" +
            "uniform float2 size;\n" +
            "half4 main(float2 xy) {\n" +
            "    half4 c = content.eval(xy);\n" +
            "    half mx = max(c.r, max(c.g, c.b));\n" +
            "    half mn = min(c.r, min(c.g, c.b));\n" +
            "    half sat = mx - mn;\n" +
            "    // 保留色相的反色：整体亮度翻过来，色差留着\n" +
            "    half l = dot(c.rgb, half3(0.299, 0.587, 0.114));\n" +
            "    half3 inv = c.rgb + (1.0 - 2.0 * l);\n" +
            "    inv = clamp(inv, half3(0.0), half3(1.0));\n" +
            "    half w = smoothstep(satLow, satHigh, sat);\n" +
            "    half3 rgb = mix(inv, c.rgb, w);\n" +
            "    // 圆形扩散遮罩：圈内反色，边缘 0.12 宽软过渡\n" +
            "    // radius 已乘 1.6，归一化坐标最远角是 √2≈1.415，够盖满\n" +
            "    float2 uv = xy / size;\n" +
            "    float d = distance(uv, center);\n" +
            "    float m = 1.0 - smoothstep(radius - 0.12, radius, d);\n" +
            "    rgb = mix(c.rgb, rgb, half(m));\n" +
            "    return half4(rgb, c.a);\n" +
            "}\n"

    /**
     * 31/32 的退路：颜色矩阵版（照片会一起被反成负片）。
     * 矩阵 = 绕灰轴 (1,1,1)/√3 旋转 180° × 反色，展开后固定为：
     *   [ 1/3 -2/3 -2/3 | 255 ]
     *   [-2/3  1/3 -2/3 | 255 ]
     *   [-2/3 -2/3  1/3 | 255 ]
     * 白→0、黑→255、纯红→(255,85,85) 仍是红。
     */
    private fun matrixFilter(): ColorMatrixColorFilter =
        ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    1f / 3, -2f / 3, -2f / 3, 0f, 255f,
                    -2f / 3, 1f / 3, -2f / 3, 0f, 255f,
                    -2f / 3, -2f / 3, 1f / 3, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
}
