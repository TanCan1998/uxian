package com.xiandehuang.u

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * channel 内的定时任务：不依赖前台、不依赖主进程、不依赖外部触发。
 *
 * 原理：`:channel` 里声明了 `ChannelAlarmBroadcastReceiver`
 * （action = `com.taobao.idlefish.channel.alarm.RECEIVER`），闲鱼自己就是用它
 * `setExactAndAllowWhileIdle` 唤醒 channel 的。我们复用同一条通道：
 *
 *   写任务到 queue.json → 排 AlarmManager → 到点 onReceive → 执行 → 结果落盘 → 排下一次
 *
 * 为什么这条能行：
 *   - AlarmManager 唤醒不需要进程在前台（setExactAndAllowWhileIdle 可穿透 Doze）
 *   - 不需要 root / Shizuku
 *   - 不需要 provider（provider 挂主进程，主进程死了就没了）
 *
 * 限制：
 *   - Doze 深睡时 setExactAndAllowWhileIdle 最小间隔约 9 分钟（系统限流）
 *   - 进程被杀后闹钟仍在（AlarmManager 由系统持有），但 onReceive 会拉起 channel
 */
object ChannelScheduler {

    private const val TAG = "xysidecar"

    /** 复用闲鱼自己的闹钟 action，保证系统能唤醒 :channel */
    private const val ALARM_ACTION = "com.taobao.idlefish.channel.alarm.RECEIVER"

    /** 结果目录（与 socket 通道共用） */
    private const val DIR = "ch"

    data class Task(
        val api: String,
        val version: String,
        val data: String,
        val intervalMs: Long,
        val lastRunAt: Long = 0,
        val lastRet: String = "",
    )

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }
    private fun queueFile(ctx: Context) = File(dir(ctx), "queue.json")
    private fun resultFile(ctx: Context) = File(dir(ctx), "result.json")

    /** 读任务队列 */
    fun loadTasks(ctx: Context): MutableList<Task> {
        val out = ArrayList<Task>()
        try {
            val f = queueFile(ctx)
            if (!f.exists()) return out
            val arr = org.json.JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Task(
                        api = o.optString("api"),
                        version = o.optString("version", "1.0"),
                        data = o.optString("data", "{}"),
                        intervalMs = o.optLong("intervalMs", 0),
                        lastRunAt = o.optLong("lastRunAt", 0),
                        lastRet = o.optString("lastRet", ""),
                    ),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ChannelScheduler: loadTasks failed: $t")
        }
        return out
    }

    fun saveTasks(ctx: Context, tasks: List<Task>) {
        try {
            val arr = org.json.JSONArray()
            for (t in tasks) {
                arr.put(
                    JSONObject()
                        .put("api", t.api)
                        .put("version", t.version)
                        .put("data", t.data)
                        .put("intervalMs", t.intervalMs)
                        .put("lastRunAt", t.lastRunAt)
                        .put("lastRet", t.lastRet),
                )
            }
            queueFile(ctx).writeText(arr.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "ChannelScheduler: saveTasks failed: $t")
        }
    }

    /** 排下一次闹钟 */
    fun schedule(ctx: Context, delayMs: Long) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingIntent(ctx)
            val at = System.currentTimeMillis() + delayMs.coerceAtLeast(60_000)

            // setExactAndAllowWhileIdle 在 Doze 下会被系统大幅推迟
            // （实测 adjustment=+2d23h，因为 App 被判为 idle 状态）。
            // setAlarmClock 是唯一不受 Doze 限制的接口：系统保证准时投递，
            // 代价是会在状态栏显示闹钟图标 —— 但这是能用的唯一办法。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val info = AlarmManager.AlarmClockInfo(at, pi)
                am.setAlarmClock(info, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
            }
            Log.i(TAG, "ChannelScheduler: 已排 setAlarmClock ${delayMs / 1000}s 后")
        } catch (t: Throwable) {
            Log.w(TAG, "ChannelScheduler: schedule failed: $t")
        }
    }

    fun cancel(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pendingIntent(ctx))
            Log.i(TAG, "ChannelScheduler: 已取消")
        } catch (t: Throwable) {
            Log.w(TAG, "ChannelScheduler: cancel failed: $t")
        }
    }

    private fun pendingIntent(ctx: Context): PendingIntent {
        // 用显式 Intent 指向宿主的 ChannelAlarmBroadcastReceiver：
        // 它是 exported=false，同 uid 内部投递不受限，且声明在 :channel
        val i = Intent(ALARM_ACTION).setPackage(ctx.packageName)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(ctx, 0x5E5A, i, flags)
    }

    /**
     * 动态注册接收器到当前进程（channel）。
     *
     * 为什么不靠 manifest：模块 App 的 manifest 里声明的 receiver 跑在**模块自己的
     * uid/进程**，闹钟响时拉起的是模块进程，拿不到闲鱼的 classLoader。
     * 动态注册在 channel 的 Context 上，onReceive 就在 channel 进程里执行。
     */
    fun registerReceiver(ctx: Context) {
        try {
            val filter = android.content.IntentFilter(ALARM_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 必须 EXPORTED：闹钟广播由 AlarmManager（system_server）投递，
                // 属于「外部来源」，NOT_EXPORTED 会静默丢弃
                ctx.registerReceiver(
                    ChannelAlarmReceiver(),
                    filter,
                    Context.RECEIVER_EXPORTED,
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(ChannelAlarmReceiver(), filter)
            }
            Log.i(TAG, "ChannelScheduler: 接收器已注册到 ${ctx.packageName} 进程")
        } catch (t: Throwable) {
            Log.w(TAG, "ChannelScheduler: registerReceiver failed: $t")
        }
    }

    /**
     * 执行一轮：跑所有到期任务，结果写 result.json，然后排下一次。
     * 由 ChannelAlarmReceiver 在闹钟响时调用。
     */
    fun runDueTasks(ctx: Context, cl: ClassLoader) {
        val tasks = loadTasks(ctx)
        if (tasks.isEmpty()) {
            Log.i(TAG, "ChannelScheduler: 无任务，不排下一次")
            return
        }
        val now = System.currentTimeMillis()
        val results = JSONObject()
        var nextDelay = Long.MAX_VALUE

        for ((idx, t) in tasks.withIndex()) {
            val due = t.lastRunAt == 0L || (now - t.lastRunAt) >= t.intervalMs
            if (due) {
                val r = try {
                    val resp = ChannelMtop.request(cl, t.api, t.version, t.data)
                    val o = JSONObject(resp)
                    val ret = o.optString("ret")
                    tasks[idx] = t.copy(lastRunAt = now, lastRet = ret)
                    o
                } catch (t2: Throwable) {
                    JSONObject().put("ok", false)
                        .put("error", "${t2.javaClass.simpleName}: ${t2.message}")
                }
                results.put(t.api, r)
                Log.i(TAG, "ChannelScheduler: 跑了 ${t.api} -> ${r.optString("ret", r.optString("error"))}")
            }
            // 算最近的下次时间
            val remain = (tasks[idx].lastRunAt + tasks[idx].intervalMs) - now
            if (remain < nextDelay) nextDelay = remain
        }

        saveTasks(ctx, tasks)
        try {
            resultFile(ctx).writeText(
                JSONObject()
                    .put("at", now)
                    .put("results", results)
                    .toString(),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "ChannelScheduler: 写结果失败: $t")
        }

        if (nextDelay != Long.MAX_VALUE) {
            schedule(ctx, nextDelay)
        }
    }
}

/**
 * 闹钟接收器。**必须注册在 :channel 进程**（见 manifest），
 * 否则闹钟响时会拉起主进程 —— 那不是我们要的。
 */
class ChannelAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "ChannelAlarmReceiver: 收到闹钟 ${intent.action}")
        try {
            val cl = Protocol.State.classLoader ?: context.classLoader
            ChannelScheduler.runDueTasks(context, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "ChannelAlarmReceiver: err: $t")
        }
    }

    companion object {
        private const val TAG = "xysidecar"
    }
}
