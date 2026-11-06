package com.xiandehuang.u

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileReader
import java.util.Date

/**
 * 重启宿主（闲鱼）的完整流程。
 *
 * 这块是"杀进程 + 拉起中转页 + 灌开屏图"的一整套，
 * 和入口注入、面板 UI 没有任何关系，出问题时也不该混在一起看。
 *
 * 关键约束（真机实测，别再试）：
 *   - 普通 App 读不到别的进程 /proc/<pid>/status 之外的东西，但同 uid 的可以；
 *     闲鱼多进程同 uid，所以能扫出来。
 *   - 必须用 am start 而不是 monkey：monkey 会把系统自动旋转打开。
 *   - 子进程必须一起杀，否则系统会立刻把主进程拉起来，表现为"假重启"。
 */
internal object RestartHelper {

    private const val TAG = "xysidecar"

    @JvmStatic
    fun triggerRestart(any: Context?) {
        var ctx = any ?: Protocol.State.app
        if (ctx == null) {
            try {
                val app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null)
                if (app is Context) ctx = app
            } catch (ignored: Throwable) {
            }
        }
        if (ctx == null) throw IllegalStateException("no context available")
        restartApp(ctx)
    }

    /**
     * 杀掉本 App 的所有进程（主进程 + :xxx 子进程）。
     *
     * 2026-10-28 修正：原来只 killProcess(myPid())，只杀主进程。
     * 闲鱼是多进程架构（实测 ps 能看到 3 个），子进程还活着时系统会立刻
     * 把主进程重新拉起，表现为"点了重启但进程没真正重启"，
     * 导致模块加载的仍是旧 dex，改的代码不生效。
     *
     * 模块跑在宿主进程里，只能杀同 uid 的进程 —— 正好够用，
     * 闲鱼的子进程都是同一个 uid。
     */
    private fun killAllHostProcesses(pkg: String) {
        val self = android.os.Process.myPid()

        // 分多轮：先杀一轮 → 再扫 → 再杀，直到扫不到或轮数用完。
        // 注意：自己必须在最后一轮才杀，否则后面的循环不执行了。
        for (round in 0 until 4) {
            val targets = scanHostPids(pkg, self)
            if (targets.isEmpty()) break
            Protocol.State.logLine("重启第 ${round + 1} 轮：杀掉 $targets")
            for (pid in targets) {
                try {
                    android.os.Process.killProcess(pid)
                } catch (ignored: Throwable) {
                }
            }
            try {
                Thread.sleep(120)
            } catch (ignored: Throwable) {
            }
        }

        // 最后一轮：把所有剩余进程连同自己一起杀掉
        val rest = scanHostPids(pkg, -1)
        Protocol.State.logLine("重启：最终杀掉 $rest")
        Log.i(TAG, "KILLING final $rest")
        Protocol.State.dumpTo(
            "restart_kill.txt",
            "uid=" + android.os.Process.myUid() +
                " self=" + self +
                " final=" + rest +
                " at " + Date(),
        )

        for (pid in rest) {
            if (pid == self) continue
            try {
                android.os.Process.killProcess(pid)
            } catch (ignored: Throwable) {
            }
        }
        try {
            android.os.Process.killProcess(self)
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 扫描本 App 的所有进程。
     *
     * 2026-10-28 修：原来靠 /proc/<pid>/cmdline 匹配包名，实测只能找到主进程，
     * :channel / :normal_render_proc0 等子进程漏掉（Android 会重写部分进程的
     * cmdline，不可靠）。
     * 改成按 uid 匹配：读 /proc/<pid>/status 的 Uid: 行。同一 App 的所有进程
     * uid 相同，而且 status 不会被改写。
     *
     * @param exclude 要跳过的 pid，-1 表示不跳过
     */
    private fun scanHostPids(pkg: String, exclude: Int): List<Int> {
        val out = ArrayList<Int>()
        val myUid = android.os.Process.myUid()
        var dirs: Array<String>? = null
        try {
            dirs = File("/proc").list()
        } catch (t: Throwable) {
            Protocol.State.logLine("枚举 /proc 失败：$t")
        }
        if (dirs == null) return out

        for (d in dirs) {
            // 手写数字判断，不用 Regex：R8 收缩后 kotlin.text.Regex 的
            // 内部实现会缺方法，运行时抛 AbstractMethodError（实测整条链挂掉）
            var allDigits = d.isNotEmpty()
            var k = 0
            while (k < d.length) {
                val ch = d[k]
                if (ch < '0' || ch > '9') {
                    allDigits = false
                    break
                }
                k++
            }
            if (!allDigits) continue
            val pid = try { d.toInt() } catch (t: Throwable) { continue }
            if (pid == exclude) continue

            // 1) 优先按 uid 判断
            var sameUid = false
            var br: BufferedReader? = null
            try {
                br = BufferedReader(FileReader("/proc/$d/status"))
                while (true) {
                    val line = br.readLine() ?: break
                    if (!line.startsWith("Uid:")) continue
                    // 格式: Uid:	10123	10123	10123	10123
                    val parts = line.trim().split(' ').filter { it.isNotEmpty() }.toTypedArray()
                    if (parts.size >= 2) {
                        try {
                            sameUid = parts[1].toInt() == myUid
                        } catch (ignored: Throwable) {
                        }
                    }
                    break
                }
            } catch (ignored: Throwable) {
            } finally {
                try { br?.close() } catch (ignored: Throwable) {}
            }
            if (sameUid) {
                out.add(pid)
                continue
            }

            // 2) 兜底：cmdline 含包名也算
            var input: FileInputStream? = null
            try {
                input = FileInputStream(File("/proc/$d/cmdline"))
                val buf = ByteArray(256)
                val n = input.read(buf)
                if (n > 0) {
                    var end = 0
                    while (end < n && buf[end] != 0.toByte()) end++
                    val name = String(buf, 0, end)
                    if (name.contains(pkg)) out.add(pid)
                }
            } catch (ignored: Throwable) {
            } finally {
                try { input?.close() } catch (ignored: Throwable) {}
            }
        }
        return out
    }

    @JvmStatic
    fun restartApp(any: Context) {
        Protocol.State.logLine("restartApp 进入")
        Log.i(TAG, "restartApp ENTER")
        val ctx = any.applicationContext
        val pkg = ctx.packageName
        // 背景由中转页自己生成；这里额外把宿主（闲鱼）自己的 splash 图
        // 解码成字节传过去 —— 中转页跑在 U闲 进程里，拿不到闲鱼的资源表。
        val splash = hostSplashBytes(ctx)
        launchRelay(ctx, pkg, splash)
    }

    /**
     * 取宿主 App 自己的启动图（闲鱼是 res/drawable-xxhdpi-v4/init_launch.jpg），
     * 压成 JPEG 字节返回，供中转页做模糊背景。
     *
     * 为什么要传字节：中转页在 com.xiandehuang.u 进程里，
     * getResources() 是 U闲 自己的资源表，查不到闲鱼的 drawable。
     * 而模块这边跑在闲鱼进程内，可以直接查。
     */
    private fun hostSplashBytes(hostCtx: Context): ByteArray? {
        val names = arrayOf("init_launch", "splash_logo", "welcome_ponds_close")
        for (n in names) {
            try {
                var id = hostCtx.resources.getIdentifier(n, "drawable", hostCtx.packageName)
                if (id == 0) {
                    id = hostCtx.resources.getIdentifier(n, "drawable", "com.taobao.idlefish")
                }
                if (id == 0) continue
                val d = hostCtx.resources.getDrawable(id) ?: continue
                // 缩小到 240 宽即可（后面还要高斯模糊，细节没用）
                val w = 240
                val h = maxOf(
                    1,
                    (w * d.intrinsicHeight.toFloat() / d.intrinsicWidth).toInt(),
                )
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                d.setBounds(0, 0, w, h)
                d.draw(c)
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 70, bos)
                val out = bos.toByteArray()
                Protocol.State.logLine("重启背景：取到宿主启动图 $n ${out.size}B")
                return out
            } catch (t: Throwable) {
                Protocol.State.logLine("重启背景：$n 读取失败 $t")
            }
        }
        return null
    }

    private fun launchRelay(ctx: Context, pkg: String, splash: ByteArray?) {
        try {
            // 闸门：同一时刻只允许一个中转页在途。
            // 重复点击/重复触发时直接忽略，避免叠出两个页面。
            if (!RestartActivity.claim()) {
                Protocol.State.logLine("重启：已有中转页在途，忽略本次触发")
                return
            }
            val hop = Intent(RestartActivity.ACTION)
            hop.setClassName("com.xiandehuang.u", "com.xiandehuang.u.RestartActivity")
            hop.putExtra(RestartActivity.EXTRA_TARGET, pkg)
            if (splash != null && splash.isNotEmpty()) {
                hop.putExtra(RestartActivity.EXTRA_SPLASH, splash)
            }
            // 单实例：NEW_TASK + SINGLE_TOP + CLEAR_TOP，
            // 即使闸门失效也不会在任务栈里堆第二个中转页
            hop.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
            // 用 ActivityOptions 显式把进入/退出动画都设成 0：
            // 光靠 FLAG_ACTIVITY_NO_ANIMATION 在 ColorOS 上仍会播系统转场。
            var opts: android.os.Bundle? = null
            try {
                opts = ActivityOptions.makeCustomAnimation(ctx, 0, 0).toBundle()
            } catch (ignored: Throwable) {
            }
            if (opts != null) {
                ctx.startActivity(hop, opts)
            } else {
                ctx.startActivity(hop)
            }
            Protocol.State.logLine("重启：已交给中转页")
            Log.i(TAG, "restartApp relayed, killing in 320ms")
            Handler(Looper.getMainLooper()).postDelayed(
                Runnable { killAllHostProcesses(ctx.packageName) },
                320,
            )
        } catch (t: Throwable) {
            Protocol.State.logLine("重启失败：$t")
            // 拉起失败要释放闸门，否则模块进程（若没被杀）之后再点重启会被永久忽略
            RestartActivity.release()
            toastMain(ctx, "无法自启，请手动打开闲鱼")
            Handler(Looper.getMainLooper()).postDelayed(
                Runnable { killAllHostProcesses(ctx.packageName) },
                1600,
            )
        }
    }

    @JvmStatic
    fun toastMain(ctx: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            } catch (ignored: Throwable) {
            }
        }
    }
}
