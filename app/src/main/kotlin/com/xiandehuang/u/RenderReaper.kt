package com.xiandehuang.u

import android.util.Log
import java.io.File

/**
 * 渲染进程回收。
 *
 * `:normal_render_proc0` 是 UC U4 内核的 webview 渲染进程（`U4CoreUtils.getProcName` 里写死的前缀）。
 * 它由主进程按需拉起，**主进程死后不会自己退出** —— 实测杀掉主进程后它还占着 253 MB。
 *
 * 这里不 hook、不注入，直接读 /proc 找同 uid 的渲染进程并发信号。
 * 普通 App 杀不了别人，但**同 uid 的兄弟进程可以**（`Process.sendSignal` 走的是
 * kill(2)，同 uid 有权限）。
 *
 * 注意：不要在用户正在浏览 H5 页面时调用，会把页面白屏。只在「确认主进程已死」后调用。
 */
object RenderReaper {

    private const val TAG = "xysidecar"
    private const val RENDER_PREFIX = ":normal_render_proc"

    /**
     * comm 匹配用的后缀：内核把 comm 截断到 15 字符，
     * `com.taobao.idlefish:normal_render_proc0` 只剩 `al_render_proc0`。
     */
    private const val RENDER_SUFFIX = "_render_proc"

    /**
     * 回收所有渲染进程。
     * @return 被杀掉的进程名列表
     */
    fun reap(): List<String> {
        val killed = ArrayList<String>()
        val selfPid = android.os.Process.myPid()
        val pkg = currentPackage() ?: return killed
        val myUid = android.os.Process.myUid()

        try {
            // 优先 ActivityManager（能拿到完整进程名）
            var found = false
            try {
                val am = appContext()?.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                    as? android.app.ActivityManager
                am?.runningAppProcesses?.forEach { pi ->
                    if (pi.pid != selfPid && pi.processName.startsWith("$pkg$RENDER_PREFIX")) {
                        found = true
                        if (pidExists(pi.pid) && kill(pi.pid)) {
                            killed.add(pi.processName)
                            Log.i(TAG, "RenderReaper: 已回收 ${pi.processName} (pid=${pi.pid})")
                        }
                    }
                }
            } catch (ignored: Throwable) {
            }

            // 兜底：/proc 扫描。cmdline 读不了（SELinux），但 /proc/<pid>/comm 同 uid 可读，
            // 只是被截断成 15 字符 —— 用「后缀匹配 + 同 uid + 非自己」识别渲染进程。
            if (!found) {
                File("/proc").list()?.forEach { name ->
                    val pid = name.toIntOrNull() ?: return@forEach
                    if (pid == selfPid || !pidExists(pid)) return@forEach
                    if (uidOf(pid) != myUid) return@forEach
                    val comm = commOf(pid) ?: return@forEach
                    // comm 形如 `al_render_proc0`（内核截断 15 字符），
                    // 不能用 endsWith("_render_proc") —— 结尾还有序号数字
                    if (!comm.contains(RENDER_SUFFIX)) return@forEach
                    if (kill(pid)) {
                        killed.add("$comm($pid)")
                        Log.i(TAG, "RenderReaper: 已回收 $comm (pid=$pid)")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "RenderReaper: reap failed: $t")
        }
        return killed
    }

    /** `/proc/<pid>/comm`：同 uid 可读，但内核截断到 15 字符 */
    private fun commOf(pid: Int): String? = try {
        File("/proc/$pid/comm").readText().trim()
    } catch (t: Throwable) {
        null
    }

    /** 读 /proc/<pid>/status 的 Uid 行；SELinux 允许读同 uid 进程的 status */
    private fun uidOf(pid: Int): Int? = try {
        File("/proc/$pid/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Uid:") }
                ?.removePrefix("Uid:")?.trim()
                ?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * 主进程是否还活着。
     *
     * 三次尝试，任一成功即返回：
     *  1. `ActivityManager.getRunningAppProcesses()` —— 官方 API，但 Android 14+
     *     对同 uid 的其他进程可能不返回（实测 channel 里看不到主进程）
     *  2. `/proc/<pid>` 存在性 —— 需要先知道 pid，pid 来源见 [mainPid]
     *  3. 心跳文件：主进程定期写 `main_beat`，超过阈值视为已死
     */
    fun isMainAlive(): Boolean {
        val pkg = currentPackage() ?: return false
        val selfPid = android.os.Process.myPid()

        // 1) ActivityManager
        try {
            val am = appContext()?.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as? android.app.ActivityManager
            am?.runningAppProcesses?.any { pi ->
                pi.pid != selfPid && pi.processName == pkg
            }?.let { if (it) return true }
        } catch (ignored: Throwable) {
        }

        // 2) 已知 pid 就查 /proc
        mainPid()?.let { pid ->
            if (pid != selfPid && pidExists(pid)) return true
        }

        // 3) 心跳文件（主进程侧负责写）
        return beatFresh()
    }

    private fun beatFile(): File? {
        val ctx = appContext() ?: return null
        return File(ctx.filesDir, "ch/main_beat")
    }

    /** 心跳年龄（毫秒），-1 表示文件不存在；供诊断 */
    fun beatAge(): Long = try {
        val f = beatFile()
        if (f == null || !f.exists()) -1L else System.currentTimeMillis() - f.lastModified()
    } catch (t: Throwable) {
        -1L
    }

    /** 主进程登记的 pid，供诊断 */
    fun mainPidValue(): Int? = mainPid()

    /** 主进程最近一次心跳是否新鲜（30 秒内） */
    private fun beatFresh(): Boolean = try {
        val f = beatFile() ?: return false
        f.exists() && (System.currentTimeMillis() - f.lastModified()) < 30_000
    } catch (t: Throwable) {
        false
    }

    /** 主进程 pid：由主进程写进 `main_pid` */
    private fun mainPid(): Int? = try {
        val ctx = appContext() ?: return null
        val f = File(ctx.filesDir, "ch/main_pid")
        if (!f.exists()) null else f.readText().trim().toIntOrNull()
    } catch (t: Throwable) {
        null
    }

    /** 主进程侧调用：登记自己还活着（channel 靠这个判断） */
    fun publishMainAlive() {
        try {
            val ctx = appContext() ?: return
            val dir = File(ctx.filesDir, "ch")
            dir.mkdirs()
            File(dir, "main_pid").writeText(android.os.Process.myPid().toString())
            File(dir, "main_beat").writeText(System.currentTimeMillis().toString())
        } catch (t: Throwable) {
            Log.w(TAG, "RenderReaper: publishMainAlive failed: $t")
        }
    }

    /** /proc/<pid> 是否存在：能穿透 ActivityManager 的残留记录 */
    private fun pidExists(pid: Int): Boolean = try {
        File("/proc/$pid").exists()
    } catch (t: Throwable) {
        false
    }

    private fun appContext(): android.content.Context? {
        // 优先用模块已缓存的：channel 进程里 ActivityThread.currentApplication()
        // 在某些时机拿不到，而 Protocol.State.app 在 provider/钩子首次调用时已填好
        Protocol.State.app?.let { return it }
        return try {
            val c = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context
            if (c != null) Protocol.State.app = c
            c
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 当前进程的包名前缀。
     *
     * 注意：`/proc/self/cmdline` 在 :channel 里读到的是
     * `com.taobao.idlefish:channel`，直接当包名去比主进程（`com.taobao.idlefish`）
     * 永远不相等 —— 必须截掉 `:` 之后的部分。
     */
    private fun currentPackage(): String? = try {
        val cmdline = readCmdline(android.os.Process.myPid()) ?: return null
        val idx = cmdline.indexOf(':')
        if (idx > 0) cmdline.substring(0, idx) else cmdline
    } catch (t: Throwable) {
        null
    }

    private fun readCmdline(pid: Int): String? = try {
        val bytes = File("/proc/$pid/cmdline").readBytes()
        val end = bytes.indexOf(0.toByte())
        val n = if (end < 0) bytes.size else end
        if (n <= 0) null else String(bytes, 0, n, Charsets.UTF_8)
    } catch (t: Throwable) {
        null
    }

    private fun kill(pid: Int): Boolean = try {
        android.os.Process.sendSignal(pid, android.os.Process.SIGNAL_KILL)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "RenderReaper: kill $pid failed: $t")
        false
    }
}
