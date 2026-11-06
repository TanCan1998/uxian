package io.github.tancan1998.uxian

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * channel 进程内的 RPC 服务端。
 *
 * 为什么用 LocalSocket 而不是 content provider：
 *   provider 挂在主进程（`LocalizationProvider` 未声明 android:process），
 *   主进程一死通道就没了。而 :channel 独立存活，把服务端放这里才能真正做到
 *   「主进程不在也能调接口」。
 *
 * 为什么用 LocalSocket 而不是文件轮询：
 *   文件轮询 1 秒延迟、要 root 改文件、还要处理 SELinux 标签（踩过）。
 *   LocalSocket 是同 UID 内的 unix domain socket，零延迟、不需要任何权限。
 *
 * 协议：一行一个 JSON 请求，一行一个 JSON 响应，连接可复用。
 *   → {"api":"mtop.xxx","version":"1.0","data":{...}}
 *   ← {"ok":true,"ret":"SUCCESS","body":{...}}
 *   ← {"ok":false,"error":"..."}
 */
object ChannelServer {

    private const val TAG = "xysidecar"

    /** 抽象命名空间：不落文件系统，不受 scoped storage / SELinux 影响 */
    const val SOCKET_NAME = "io.github.tancan1998.uxian.channel"

    private val started = AtomicBoolean(false)

    /** 看门狗心跳计数：status 里能看到，用于确认线程真的在跑 */
    private val watchdogTicks = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile
    private var classLoader: ClassLoader? = null

    fun start(cl: ClassLoader) {
        if (!started.compareAndSet(false, true)) return
        classLoader = cl
        val t = Thread({ serveLoop(cl) }, "ChannelServer")
        t.isDaemon = true
        t.start()
        Log.i(TAG, "ChannelServer: 已启动 @$SOCKET_NAME")
        startMainWatchdog()
    }

    /** 把看门狗每轮的判断写进文件，logcat 不可靠时靠它诊断 */
    private fun dumpWatchdogState(alive: Boolean) {
        try {
            val ctx = Protocol.State.app ?: return
            val f = java.io.File(ctx.filesDir, "ch/watchdog.txt")
            f.parentFile?.mkdirs()
            val sb = StringBuilder()
            sb.append("t=").append(System.currentTimeMillis()).append('\n')
            sb.append("tick=").append(watchdogTicks.get()).append('\n')
            sb.append("mainAlive=").append(alive).append('\n')
            sb.append("beat=").append(RenderReaper.beatAge()).append('\n')
            sb.append("pid=").append(RenderReaper.mainPidValue() ?: -1).append('\n')
            f.writeText(sb.toString())
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 主进程死亡监视：主进程一死就自动回收渲染进程。
     *
     * 为什么必须放在 channel 里自动做：provider 挂在主进程，主进程一死外部就
     * 没有入口了（模块 App 是另一个 uid，抽象 socket 跨 uid 被 SELinux 拒），
     * 所以"外部触发 reap"这条路在主进程死后走不通，只能自己看着办。
     */
    private fun startMainWatchdog() {
        val t = Thread({
            var wasAlive = true
            var idleTicks = 0
            while (true) {
                try {
                    Thread.sleep(5000)
                    watchdogTicks.incrementAndGet()
                    val alive = RenderReaper.isMainAlive()
                    // 把判断结果落盘：logcat 在真机上经常被冲掉，文件才可靠
                    dumpWatchdogState(alive)
                    if (wasAlive && !alive) {
                        // 主进程刚死：给系统几秒回收，再清渲染进程
                        Log.i(TAG, "ChannelServer: 检测到主进程已死，5s 后回收渲染进程")
                        Thread.sleep(5000)
                        val killed = RenderReaper.reap()
                        Log.i(TAG, "ChannelServer: 自动回收 -> ${killed.joinToString(",")}")
                        wasAlive = false
                        idleTicks = 0
                    } else if (!alive) {
                        // 持续死着：每 30 秒再看一眼有没有残留的渲染进程
                        idleTicks++
                        if (idleTicks % 6 == 0) {
                            val killed = RenderReaper.reap()
                            if (killed.isNotEmpty()) {
                                Log.i(TAG, "ChannelServer: 补刀回收 -> ${killed.joinToString(",")}")
                            }
                        }
                    } else {
                        wasAlive = true
                        idleTicks = 0
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                } catch (t: Throwable) {
                    Log.w(TAG, "ChannelServer: watchdog err: $t")
                }
            }
        }, "ChannelServer-Watchdog")
        t.isDaemon = true
        t.start()
    }

    private fun serveLoop(cl: ClassLoader) {
        var server: LocalServerSocket? = null
        // 绑不上就重试几次：进程刚起来时可能有上一次的残留
        for (attempt in 0 until 5) {
            try {
                server = LocalServerSocket(SOCKET_NAME)
                Log.i(TAG, "ChannelServer: bind 成功（第 ${attempt + 1} 次）")
                break
            } catch (t: Throwable) {
                Log.w(TAG, "ChannelServer: bind 失败（第 ${attempt + 1} 次）: $t")
                try {
                    Thread.sleep(300)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
        if (server == null) {
            Log.e(TAG, "ChannelServer: bind 全部失败，服务不可用")
            started.set(false)
            return
        }

        while (true) {
            try {
                val client = server.accept() ?: continue
                val worker = Thread({ handle(client, cl) }, "ChannelServer-Conn")
                worker.isDaemon = true
                worker.start()
            } catch (t: Throwable) {
                Log.w(TAG, "ChannelServer: accept err: $t")
                try {
                    Thread.sleep(200)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    private fun handle(client: LocalSocket, cl: ClassLoader) {
        try {
            val reader = BufferedReader(InputStreamReader(client.inputStream, Charsets.UTF_8))
            val writer = OutputStreamWriter(client.outputStream, Charsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                Log.i(TAG, "ChannelServer: 收到请求 len=${line.length}")
                val resp = try {
                    handleOne(cl, line)
                } catch (t: Throwable) {
                    JSONObject().put("ok", false)
                        .put("error", "${t.javaClass.simpleName}: ${t.message}")
                }
                writer.write(resp.toString())
                writer.write("\n")
                writer.flush()
                Log.i(TAG, "ChannelServer: 已回 len=${resp.toString().length}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ChannelServer: conn err: $t")
        } finally {
            try {
                client.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun handleOne(cl: ClassLoader, line: String): JSONObject {
        val req = JSONObject(line)

        // ---- 管理命令：不经过 mtop ----
        val cmd = req.optString("cmd")
        if (cmd.isNotEmpty()) {
            return when (cmd) {
                "ping" -> JSONObject().put("ok", true).put("pong", System.currentTimeMillis())
                "status" -> JSONObject().put("ok", true)
                    .put("mainAlive", RenderReaper.isMainAlive())
                    .put("suppress", ProcessSuppressor.enabled)
                    .put("socket", ChannelServer.SOCKET_NAME)
                    .put("watchdog", watchdogTicks.get())
                    .put("ctx", Protocol.State.app != null)
                    .put("beat", RenderReaper.beatAge())
                    .put("pid", RenderReaper.mainPidValue()?.toString() ?: "-")
                "reap" -> {
                    // 只有主进程确实死了才回收渲染进程，否则会把正在用的页面杀掉
                    if (RenderReaper.isMainAlive()) {
                        JSONObject().put("ok", false).put("error", "main process alive, skip")
                    } else {
                        val killed = RenderReaper.reap()
                        JSONObject().put("ok", true).put("killed", killed.joinToString(","))
                    }
                }
                // ---- 定时任务 ----
                "bench" -> {
                    // 在 channel 内并发调 mtop，验证 SDK 层是否真能并行。
                    // 注意：这里刻意只用 Java 原生 API（不用 Kotlin 的 map/forEach），
                    // 因为 R8 收缩后 Kotlin stdlib 的扩展函数可能被裁掉，触发
                    // AbstractMethodError: Iterable.iterator()
                    val n = req.optInt("n", 8)
                    val api = req.optString("api", "mtop.common.getTimestamp")
                    val ver = req.optString("version", "1.0")
                    val d = req.optString("data", "{}")
                    val lat = java.util.Collections.synchronizedList(java.util.ArrayList<Long>())
                    val t0 = System.currentTimeMillis()
                    val threads = arrayOfNulls<Thread>(n)
                    for (i in 0 until n) {
                        threads[i] = Thread({
                            val s = System.currentTimeMillis()
                            try {
                                ChannelMtop.request(cl, api, ver, d)
                            } catch (ignored: Throwable) {
                            }
                            lat.add(System.currentTimeMillis() - s)
                        }, "bench-$i")
                    }
                    for (i in 0 until n) threads[i]!!.start()
                    for (i in 0 until n) threads[i]!!.join(60_000)
                    val total = System.currentTimeMillis() - t0
                    val sorted = java.util.ArrayList(lat)
                    java.util.Collections.sort(sorted)
                    JSONObject().put("ok", true)
                        .put("n", n)
                        .put("totalMs", total)
                        .put("minMs", if (sorted.isEmpty()) -1 else sorted[0])
                        .put("maxMs", if (sorted.isEmpty()) -1 else sorted[sorted.size - 1])
                        .put(
                            "avgMs",
                            if (sorted.isEmpty()) -1 else sorted.sumOf { it } / sorted.size,
                        )
                }
                "task.add" -> {
                    val ctx = Protocol.State.app
                    if (ctx == null) {
                        JSONObject().put("ok", false).put("error", "no context")
                    } else {
                        val tasks = ChannelScheduler.loadTasks(ctx)
                        tasks.add(
                            ChannelScheduler.Task(
                                api = req.optString("api"),
                                version = req.optString("version", "1.0"),
                                data = req.optString("data", "{}"),
                                intervalMs = req.optLong("intervalMs", 3_600_000),
                            ),
                        )
                        ChannelScheduler.saveTasks(ctx, tasks)
                        ChannelScheduler.schedule(ctx, 60_000)
                        JSONObject().put("ok", true).put("count", tasks.size)
                    }
                }
                "task.list" -> {
                    val ctx = Protocol.State.app
                    if (ctx == null) {
                        JSONObject().put("ok", false).put("error", "no context")
                    } else {
                        val tasks = ChannelScheduler.loadTasks(ctx)
                        val arr = org.json.JSONArray()
                        tasks.forEach {
                            arr.put(
                                JSONObject().put("api", it.api)
                                    .put("intervalMs", it.intervalMs)
                                    .put("lastRet", it.lastRet),
                            )
                        }
                        JSONObject().put("ok", true).put("tasks", arr)
                    }
                }
                "task.clear" -> {
                    val ctx = Protocol.State.app
                    if (ctx == null) {
                        JSONObject().put("ok", false).put("error", "no context")
                    } else {
                        ChannelScheduler.cancel(ctx)
                        ChannelScheduler.saveTasks(ctx, emptyList())
                        JSONObject().put("ok", true)
                    }
                }
                "task.run" -> {
                    val ctx = Protocol.State.app
                    if (ctx == null) {
                        JSONObject().put("ok", false).put("error", "no context")
                    } else {
                        ChannelScheduler.runDueTasks(ctx, cl)
                        JSONObject().put("ok", true).put("ran", true)
                    }
                }
                else -> JSONObject().put("ok", false).put("error", "unknown cmd: $cmd")
            }
        }

        val api = req.optString("api")
        val version = req.optString("version", "1.0")
        val data = req.opt("data")?.let {
            if (it is String) it else it.toString()
        } ?: "{}"

        if (api.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "missing api")
        }

        val raw = ChannelMtop.request(cl, api, version, data)
        return try {
            JSONObject(raw)
        } catch (t: Throwable) {
            JSONObject().put("ok", true).put("raw", raw)
        }
    }

    /** 供同进程内（或调试）判断服务是否起来 */
    fun isStarted(): Boolean = started.get()
}
