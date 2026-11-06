package io.github.tancan1998.uxian

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 重启中转页。
 *
 * 为什么需要它：模块跑在闲鱼进程里，重启必须「杀掉自己 + 重新回到前台」。
 * ColorOS 拦截后台启动 Activity，所以必须由一个真正可见的前台页面去拉起目标 App。
 *
 * 时序设计（尽量短，避免黑屏停留）：
 *   1. 本页 onCreate 立刻可见（深色背景，不是纯黑，减少突兀感）
 *   2. 等宿主进程真的消失后拉起目标
 *   3. 目标可见后立即 finishAndRemoveTask，不留在最近任务里
 */
class RestartActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private var pkg: String = DEFAULT_TARGET
    private var attempt = 0
    private var launched = false

    /** 读模块通过 Intent 传来的背景图 Uri */
    private var bgPath: String? = null

    /** 宿主（闲鱼）自己的启动图，模块取好压成 JPEG 传进来 */
    private var splashJpeg: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题用代码指定：manifest 里手写的 android:theme 系统读不到（实测 theme=0x0），
        // 必须在 setContentView 之前 setTheme 才有效。
        try {
            setTheme(0x7f020000)   // 手写 arsc 里的 UxianRestart
        } catch (ignored: Throwable) {
        }
        // 必须在 setContentView 之前：直接掐掉标题栏/ActionBar，
        // 不依赖主题是否生效（之前顶部 196px 的深色带就是它）
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        } catch (ignored: Throwable) {
        }
        try {
            setShowWhenLocked(false)
        } catch (ignored: Throwable) {
        }
        try {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            )
        } catch (ignored: Throwable) {
        }
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // 真全屏：FLAG_FULLSCREEN 隐藏状态栏，窗口才真正从 y=0 开始。
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            try {
                if (Build.VERSION.SDK_INT >= 28) {
                    val lp0 = window.attributes
                    lp0.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    window.attributes = lp0
                }
            } catch (ignored: Throwable) {
            }
            // 状态栏/导航栏透明 + 不再为系统栏留白
            try {
                window.statusBarColor = Color.TRANSPARENT
                window.navigationBarColor = Color.TRANSPARENT
            } catch (ignored: Throwable) {
            }
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    window.setDecorFitsSystemWindows(false)
                }
            } catch (ignored: Throwable) {
            }
            // 导航栏也隐掉，避免底部留一条
            try {
                window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            } catch (ignored: Throwable) {
            }
            // 屏蔽系统 Activity 切换动画（淡入淡出/缩放都不要）
            try {
                window.setWindowAnimations(0)
            } catch (ignored: Throwable) {
            }
            // 深色而非纯黑：切换时不那么刺眼
            window.setBackgroundDrawable(ColorDrawable(Color.parseColor("#1A1D22")))
        } catch (ignored: Throwable) {
        }

        // 进入动画也关掉（manifest 的 windowAnimationStyle 是兜底，这里显式再来一次）
        try {
            overridePendingTransition(0, 0)
        } catch (ignored: Throwable) {
        }

        val target = intent?.getStringExtra(EXTRA_TARGET)
        val bg = intent?.getStringExtra(EXTRA_BG)
        if (!bg.isNullOrEmpty()) bgPath = bg
        // 宿主自己的启动图（模块在闲鱼进程里取好，压成 JPEG 传过来）
        try {
            val sp = intent?.getByteArrayExtra(EXTRA_SPLASH)
            if (sp != null && sp.isNotEmpty()) splashJpeg = sp
        } catch (ignored: Throwable) {
        }
        if (!target.isNullOrEmpty()) pkg = target

        setContentView(buildView())
        // 必须先等宿主进程消失，再拉起。
        // 实测：若此时还有别的 App 占着前台，本页拉起目标会被判为「后台启动」直接拦掉。
        ui.postDelayed(Runnable { waitHostGone(0) }, 120)
    }

    /**
     * 轮询宿主进程是否已退出；退出后再稳一小段才拉起。
     *
     * 实测：旧进程在新 Activity START 后 261ms 才真正死掉，
     * 两者撞在一起会把新进程一起带走（has died: fg TOP）。
     * 所以「/proc 里查不到」之后还要再等一会儿，让系统把旧进程彻底清理。
     */
    private fun waitHostGone(waited: Int) {
        if (launched) return
        if (!hostAlive()) {
            // 进程已消失，再稳 400ms 让系统完成清理，避免新旧进程相撞
            ui.postDelayed(Runnable { if (!launched) tryLaunch() }, 400)
            return
        }
        if (waited >= 2400) {
            tryLaunch()
            return
        }
        ui.postDelayed(Runnable { waitHostGone(waited + 100) }, 100)
    }

    /** 宿主（闲鱼）主进程是否还在 */
    private fun hostAlive(): Boolean {
        try {
            val dirs = File("/proc").list() ?: return false
            for (d in dirs) {
                if (!d.matches(Regex("\\d+"))) continue
                var input: FileInputStream? = null
                try {
                    input = FileInputStream(File("/proc/$d/cmdline"))
                    val buf = ByteArray(128)
                    val n = input.read(buf)
                    if (n <= 0) continue
                    // /proc/<pid>/cmdline 是 \0 分隔的，trim() 去不掉 \0，
                    // 必须手动截断到第一个 \0，否则永远匹配不上（会导致误判宿主已死）
                    var end = 0
                    while (end < n && buf[end] != 0.toByte()) end++
                    val name = String(buf, 0, end)
                    if (name == pkg || name.contains(pkg)) return true
                } catch (ignored: Throwable) {
                } finally {
                    try { input?.close() } catch (ignored: Throwable) {}
                }
            }
        } catch (ignored: Throwable) {
        }
        return false
    }

    /**
     * 找到目标 App 的启动入口。
     *
     * 闲鱼没有 LAUNCHER intent（getLaunchIntentForPackage 返回 null），
     * 必须走 resolve-activity 的结果：com.taobao.fleamarket.home.activity.InitActivity。
     */
    private fun resolveLaunch(): Intent? {
        packageManager.getLaunchIntentForPackage(pkg)?.let { return it }
        // 兜底：用 MAIN 隐式意图解析
        try {
            val probe = Intent(Intent.ACTION_MAIN)
            probe.addCategory(Intent.CATEGORY_LAUNCHER)
            probe.setPackage(pkg)
            val ri = packageManager.resolveActivity(probe, 0)
            val ai = ri?.activityInfo
            if (ai != null) {
                return Intent(Intent.ACTION_MAIN).setClassName(ai.packageName, ai.name)
            }
        } catch (ignored: Throwable) {
        }
        // 闲鱼的已知入口
        if (DEFAULT_TARGET == pkg) {
            return Intent().setClassName(pkg, "com.taobao.fleamarket.home.activity.InitActivity")
        }
        return null
    }

    private fun tryLaunch() {
        if (launched) return
        attempt++
        try {
            val launch = resolveLaunch() ?: throw IllegalStateException("no launchable activity")
            launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
            // 走 PendingIntent.send()：以系统身份投递，绕过「调用方必须在前台」的限制。
            // 直接 startActivity 会被 ColorOS 判为后台启动（日志实证 22ms 后即被切后台）。
            var piFlags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 23) {
                piFlags = piFlags or PendingIntent.FLAG_IMMUTABLE
            }
            PendingIntent.getActivity(this, 2001, launch, piFlags).send()
            try {
                overridePendingTransition(0, 0)
            } catch (ignored: Throwable) {
            }
            launched = true
            Log.i(TAG, "restart: relaunched $pkg (attempt $attempt)")
            // 拉起成功后本页立刻 finish()：
            // 目标已进 NEW_TASK，finish 本页不会带走它；
            // 反而留在前台会让目标一直处于「后台启动」而被拦。
            ui.postDelayed(Runnable {
                try {
                    finish()
                } catch (ignored: Throwable) {
                }
            }, 250)
        } catch (t: Throwable) {
            Log.w(TAG, "restart: attempt $attempt failed: $t")
            if (attempt < 8) {
                ui.postDelayed(Runnable { tryLaunch() }, 150)
            } else {
                showFailed()
            }
        }
    }

    private fun finishQuietly() {
        try {
            finishAndRemoveTask()
        } catch (ignored: Throwable) {
            try {
                finish()
            } catch (ignored2: Throwable) {
            }
        }
    }

    private fun showFailed() {
        try {
            findViewById<TextView>(android.R.id.text1).text = "无法自启，请手动打开闲鱼"
        } catch (ignored: Throwable) {
        }
        ui.postDelayed(Runnable { finishQuietly() }, 1200)
    }

    /** 生成并应用背景位图（在首帧之后执行，避免拖慢启动） */
    private fun buildBackground(bg: ImageView) {
        try {
            var bmp: Bitmap? = null
            // 0) 宿主自己的启动图（闲鱼 init_launch.jpg）—— 最贴合，优先用
            val sp = splashJpeg
            if (sp != null && sp.isNotEmpty()) {
                try {
                    bmp = BitmapFactory.decodeByteArray(sp, 0, sp.size)
                    if (bmp != null) Log.i(TAG, "bg: using host splash ${sp.size}B")
                } catch (t: Throwable) {
                    Log.w(TAG, "bg: splash decode failed $t")
                }
            }
            // 1) 目标 App 图标（需要 manifest 声明 <queries>，否则 targetSdk 34 下不可见）
            if (bmp == null) {
                try {
                    val icon = packageManager.getApplicationIcon(pkg)
                    val sz = 240
                    val ib = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
                    val ic = Canvas(ib)
                    icon.setBounds(0, 0, sz, sz)
                    icon.draw(ic)
                    bmp = ib
                    Log.i(TAG, "bg: using app icon")
                } catch (t: Throwable) {
                    Log.w(TAG, "bg: app icon failed $t")
                }
            }
            // 2) 外部传进来的截图
            if (bmp == null && bgPath != null) {
                try {
                    val p = bgPath!!
                    if (p.startsWith("content://")) {
                        val input = contentResolver.openInputStream(
                            android.net.Uri.parse(p),
                        )
                        if (input != null) {
                            bmp = BitmapFactory.decodeStream(input)
                            input.close()
                        }
                    } else {
                        val p2 = if (p.startsWith("file://")) p.substring(7) else p
                        bmp = BitmapFactory.decodeFile(p2)
                    }
                    if (bmp != null) Log.i(TAG, "bg: using provided $p")
                } catch (t: Throwable) {
                    Log.w(TAG, "bg read failed: $t")
                }
            }
            // 3) 固定路径兜底
            if (bmp == null) {
                val f = findBgFile()
                if (f != null && f.exists()) {
                    bmp = BitmapFactory.decodeFile(f.absolutePath)
                    if (bmp != null) Log.i(TAG, "bg: using file $f")
                }
            }
            // 4) 最后兜底：程序化暖色光晕，保证背景层永远不是空白
            if (bmp == null) {
                bmp = warmGlow()
                Log.i(TAG, "bg: using procedural glow")
            }

            val full = coverScreen(bmp)
            bg.setImageBitmap(full)
            bg.alpha = 0.55f
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    bg.setRenderEffect(
                        RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.CLAMP),
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "bg blur failed $t")
                }
            }
            Log.i(TAG, "bg: ${full.width}x${full.height}")
        } catch (t: Throwable) {
            Log.w(TAG, "bg build failed $t")
        }
    }

    /**
     * 把任意比例的图裁成「屏幕比例」并铺满。
     *
     * 之前用 CENTER_INSIDE + 正方形位图，ImageView 只会在中间画一块方形，
     * 上下留出大片底色，看起来就像背景没生效。
     * 这里直接按屏幕宽高做一张 cover 裁剪的位图，交给 FIT_XY 全屏绘制。
     */
    private fun coverScreen(src: Bitmap): Bitmap {
        var w = 1080
        var h = 1920
        try {
            // 用真实屏幕尺寸（含状态栏/导航栏区域），否则全屏后上下会露底
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            if (dm.widthPixels > 0) w = dm.widthPixels
            if (dm.heightPixels > 0) h = dm.heightPixels
        } catch (t: Throwable) {
            val dm = resources.displayMetrics
            if (dm.widthPixels > 0) w = dm.widthPixels
            if (dm.heightPixels > 0) h = dm.heightPixels
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        // 先铺底色，避免源图带透明边时露出黑块
        c.drawColor(Color.parseColor("#1A1D22"))
        val sw = src.width.toFloat()
        val sh = src.height.toFloat()
        var scale = maxOf(w / sw, h / sh)
        // 图标类小方图额外放大，让色块铺满视野；整屏比例的图（如宿主 splash）不放大
        val isSquare = Math.abs(sw - sh) < maxOf(sw, sh) * 0.08f
        if (maxOf(sw, sh) <= 512 && isSquare) scale *= 1.6f
        val dw = sw * scale
        val dh = sh * scale
        val dst = Rect(
            Math.round((w - dw) / 2f), Math.round((h - dh) / 2f),
            Math.round((w + dw) / 2f), Math.round((h + dh) / 2f),
        )
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(src, null, dst, p)
        return out
    }

    /** 程序化兜底背景：中心暖黄光晕向外扩散到深色，保证背景层永远不是空白 */
    private fun warmGlow(): Bitmap {
        val w = 720
        val h = 1280
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(
            w * 0.5f, h * 0.45f, w * 1.05f,
            intArrayOf(
                Color.parseColor("#FEE512"),
                Color.parseColor("#8A6E12"),
                Color.parseColor("#2A2A20"),
                Color.parseColor("#1A1D22"),
            ),
            floatArrayOf(0f, 0.28f, 0.66f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return bmp
    }

    /** 旧兜底：找固定路径的背景图 */
    private fun findBgFile(): File? {
        val paths = arrayOf(
            "/sdcard/Pictures/.uxian_restart.png",
            "/sdcard/DCIM/.uxian_restart.png",
            "/data/local/tmp/uxian_restart.png",
        )
        for (p in paths) {
            val f = File(p)
            try {
                if (f.exists() && f.length() > 0) return f
            } catch (ignored: Throwable) {
            }
        }
        return null
    }

    private fun buildView(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#1A1D22"))

        // 背景：目标 App 图标放大 + 高斯模糊
        // 关键：先做一张「屏幕比例」的位图（图标居中裁剪铺满），再交给全屏 ImageView 绘制。
        // 否则 CENTER_INSIDE 会让图标只占中间一块，上下露出大片底色，看起来像「背景没生效」。
        val bg = ImageView(this)
        bg.scaleType = ImageView.ScaleType.FIT_XY
        // 先加进视图树（此时是空白的），背景位图放到下一帧再生成。
        // 之前在 buildView() 里同步做「解码图标 + 缩放 + 模糊」会拖慢首帧，
        // 表现为「先黑一下，再出现背景」，看起来像先后两个页面。
        root.addView(
            bg,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        ui.post { buildBackground(bg) }

        // 很淡的一层，只为保证文字可读（之前 #99 太暗，把背景全压没了）
        val scrim = View(this)
        scrim.setBackgroundColor(Color.parseColor("#40000000"))
        root.addView(
            scrim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER

        val title = TextView(this)
        title.id = android.R.id.text1
        title.text = "正在重启"
        title.setTextColor(Color.parseColor("#FEE512"))
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        title.gravity = Gravity.CENTER
        box.addView(title)

        val sub = TextView(this)
        sub.text = "U 闲"
        sub.setTextColor(Color.parseColor("#B0B5BD"))
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        sub.gravity = Gravity.CENTER
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = (resources.displayMetrics.density * 6).toInt()
        box.addView(sub, lp)

        root.addView(
            box,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        return root
    }

    override fun onNewIntent(intent: Intent) {
        // launchMode=singleInstance：重复触发会复用本实例而不是新开一个。
        // 此时必须重置状态重新计时，否则沿用旧的 launched/attempt 会立刻退场或卡住。
        super.onNewIntent(intent)
        try {
            setIntent(intent)
            launched = false
            attempt = 0
            ui.removeCallbacksAndMessages(null)
            val target = intent.getStringExtra(EXTRA_TARGET)
            if (!target.isNullOrEmpty()) pkg = target
            try {
                val sp = intent.getByteArrayExtra(EXTRA_SPLASH)
                if (sp != null && sp.isNotEmpty()) splashJpeg = sp
            } catch (ignored: Throwable) {
            }
            ui.postDelayed(Runnable { waitHostGone(0) }, 120)
        } catch (ignored: Throwable) {
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        // 页面销毁即释放闸门，允许下一次重启触发
        release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "xysidecar"
        const val ACTION = "io.github.tancan1998.uxian.RESTART"
        const val EXTRA_TARGET = "target"
        const val EXTRA_BG = "bg_uri"
        const val EXTRA_SPLASH = "splash_jpeg"
        const val DEFAULT_TARGET = "com.taobao.idlefish"

        /** 闸门：同一时刻只允许一个中转页在途 */
        private val IN_FLIGHT = AtomicBoolean(false)

        @JvmStatic
        fun claim(): Boolean = IN_FLIGHT.compareAndSet(false, true)

        @JvmStatic
        fun release() {
            IN_FLIGHT.set(false)
        }
    }
}
