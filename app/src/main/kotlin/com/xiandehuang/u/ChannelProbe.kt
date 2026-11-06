package com.xiandehuang.u

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 实验：进程压制 + channel 内 RPC。
 *
 * 目标（用户需求）：
 *   1. 不用 Shizuku / root，在模块层压制主进程
 *   2. 在 :channel 进程里完成 mtop RPC 调用
 *
 * 静态分析已确认的事实（mt，闲鱼 7.28.80）：
 *   - `TaoBaoApplication.bindService` 里调 `AliveApplication.isMainProcessRunning()`，
 *     不在就 `activeMainProcess()` —— 这是 :channel 拉主进程的唯一入口
 *   - `ChannelAliveComponentLimiter.preventStartMainService(ctx, intent)` 是宿主**自带的**
 *     限流器，黑名单含 mtopsdk.xstate.XStateService / OrangeApiService / NetworkService
 *     → 说明宿主自己也不想让 channel 拉起这些
 *   - `mtopsdk.xstate.XStateService` 是 XState 的跨进程共享通道（bind 后 setValue 会广播到
 *     所有进程），MtopBuilder 构造时读 XState.isAppBackground()
 *   - `:channel` 进程 maps 里 **0 个** mtop/securityguard/spdy/accs 的 so
 *     → Mtop 在 channel 里从未被初始化
 *
 * 本类先只做**探测**，不改行为：把 channel 进程里的实际状态打到 logcat，
 * 用真机数据判断「channel 内初始化 Mtop」是否可行，再决定要不要动手。
 */
object ChannelProbe {

    private const val TAG = "xysidecar"

    /** 主进程名（闲鱼没改包名，主进程就是包名本身） */
    private const val MAIN_PROCESS = "com.taobao.idlefish"
    private const val CHANNEL_PROCESS = "com.taobao.idlefish:channel"

    /** 当前进程名，onPackageLoaded 时确定 */
    @Volatile
    var processName: String? = null
        private set

    fun isChannel(): Boolean = processName == CHANNEL_PROCESS
    fun isMain(): Boolean = processName == MAIN_PROCESS

    /** 从 /proc/self/cmdline 读进程名：不依赖任何宿主 API，任何进程都能用 */
    fun resolveProcessName(): String? {
        try {
            val bytes = java.io.File("/proc/self/cmdline").readBytes()
            val end = bytes.indexOf(0.toByte())
            val n = if (end < 0) bytes.size else end
            return String(bytes, 0, n, Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "resolveProcessName failed: $t")
            return null
        }
    }

    /**
     * 安装探测钩子。只在 channel 进程里装，主进程不动。
     */
    fun install(module: XposedModule, cl: ClassLoader) {
        processName = resolveProcessName()
        val pn = processName ?: return
        Log.i(TAG, "ChannelProbe: process=$pn channel=${isChannel()}")

        // 尽早把 context 灌进 Protocol.State：RenderReaper / ChannelServer 都要用，
        // 而 channel 里 ActivityThread.currentApplication() 在某些时机拿不到
        try {
            currentApp()?.let { Protocol.State.app = it }
        } catch (ignored: Throwable) {
        }

        if (!isChannel()) {
            // 主进程（及渲染进程）侧：定期写心跳，channel 的看门狗靠它判断存活。
            // 只在主进程写，渲染进程不写（它比主进程活得久，会误报）。
            if (isMain()) {
                startMainHeartbeat(cl)
            }
            return
        }

        // channel 里把能查到的都查一遍，落到 logcat，供后续判断
        probeClasses(cl)
        probeMtopState(cl)
        probeXStateService(cl)
        probeMainProcessAlive(cl)

        // 压制主进程（开关控制，默认关）
        try {
            val on = Protocol.State.liveBool(
                currentApp(), Prefs.PROCESS_SUPPRESS, false,
            )
            ProcessSuppressor.install(module, cl, on)
        } catch (t: Throwable) {
            Log.w(TAG, "ChannelProbe ProcessSuppressor failed: $t")
        }

        // 探测完立刻试一次「channel 内自建 Mtop」，只发无参只读接口
        try {
            ChannelMtop.tryInitAndProbe(cl)
        } catch (t: Throwable) {
            Log.w(TAG, "ChannelProbe ChannelMtop failed: $t")
        }
    }

    /** channel 进程里这些类在不在 */
    private fun probeClasses(cl: ClassLoader) {
        val names = arrayOf(
            "mtopsdk.mtop.intf.Mtop",
            "mtopsdk.mtop.intf.MtopBuilder",
            "mtopsdk.mtop.domain.MtopRequest",
            "mtopsdk.mtop.intf.MtopSetting",
            "mtopsdk.mtop.global.MtopConfig",
            "mtopsdk.xstate.XState",
            "mtopsdk.xstate.XStateService",
            "mtopsdk.xstate.XStateLocalProxy",
            "com.taobao.android.middleware.compat.MtopInitializer",
            "com.taobao.tao.remotebusiness.MtopBusiness",
            "com.alibaba.wireless.security.open.SecurityGuardManager",
        )
        for (n in names) {
            val ok = try {
                Class.forName(n, false, cl)
                true
            } catch (t: Throwable) {
                false
            }
            Log.i(TAG, "ChannelProbe class $n = $ok")
        }
    }

    /** channel 里 Mtop 实例是否已存在（不主动初始化，只读） */
    private fun probeMtopState(cl: ClassLoader) {
        try {
            val mtopCls = Class.forName("mtopsdk.mtop.intf.Mtop", false, cl)
            // Mtop.instance() 无参会抛/返回 null（未 init）；带 ctx 的会尝试创建
            val inst = try {
                mtopCls.getMethod("instance").invoke(null)
            } catch (t: Throwable) {
                Log.i(TAG, "ChannelProbe Mtop.instance() threw: ${t.cause ?: t}")
                null
            }
            Log.i(TAG, "ChannelProbe Mtop.instance() = $inst")
        } catch (t: Throwable) {
            Log.w(TAG, "ChannelProbe probeMtopState failed: $t")
        }
    }

    /** XStateService 能否在 channel 里被 bind 到（跨进程共享的钥匙） */
    private fun probeXStateService(cl: ClassLoader) {
        try {
            val app = currentApp()
            if (app == null) {
                Log.i(TAG, "ChannelProbe no Application yet")
                return
            }
            val svcCls = Class.forName("mtopsdk.xstate.XStateService", false, cl)
            val intent = Intent().setClassName(app.packageName, svcCls.name)
            Log.i(TAG, "ChannelProbe XStateService intent=$intent")
            // 只记录，不真的 bind —— bind 会拉起进程，违反"压制"目标
        } catch (t: Throwable) {
            Log.w(TAG, "ChannelProbe probeXStateService failed: $t")
        }
    }

    /** 宿主自己的"主进程活着吗"判断 */
    private fun probeMainProcessAlive(cl: ClassLoader) {
        try {
            val cls = Class.forName(
                "com.taobao.fleamarket.business.user_growth.alive.AliveApplication",
                false, cl,
            )
            val inst = cls.getMethod("inst").invoke(null)
            val alive = cls.getMethod("isMainProcessRunning").invoke(inst)
            val isCh = cls.getMethod("isChannelProcess").invoke(inst)
            Log.i(TAG, "ChannelProbe isMainProcessRunning=$alive isChannelProcess=$isCh")
        } catch (t: Throwable) {
            Log.w(TAG, "ChannelProbe probeMainProcessAlive failed: ${t.cause ?: t}")
        }
    }

    private fun currentApp(): Application? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Application
    } catch (t: Throwable) {
        null
    }

    /**
     * 主进程心跳：每 10 秒写一次 `files/ch/main_beat`。
     *
     * 为什么需要它：channel 里判断主进程存活时，`getRunningAppProcesses()`
     * 在 Android 14+ 对同 uid 其他进程不可见，`/proc/<pid>` 也需要先知道 pid。
     * 心跳文件是唯一不依赖任何系统 API 可见性的方案。
     */
    private fun startMainHeartbeat(cl: ClassLoader) {
        val t = Thread({
            // 等 Application 就绪（onPackageLoaded 时还没有）
            var app: Application? = null
            for (i in 0 until 30) {
                app = currentApp()
                if (app != null) break
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
            if (app == null) {
                Log.w(TAG, "ChannelProbe: 主进程心跳放弃（Application 未就绪）")
                return@Thread
            }
            Protocol.State.app = app
            Log.i(TAG, "ChannelProbe: 主进程心跳已启动")

            // HTTP 调试口：默认不起，由面板「调试 → HTTP 调试接口」开关控制。
            // 面板自身功能一律直接调宿主函数，不经过 HTTP；它只给外部脚本用。
            try {
                if (Protocol.State.liveBool(app, Prefs.HTTP_DEBUG, false)) {
                    Protocol.State.logLine("HTTP: 开关已开启，启动 18787")
                    ChannelHttp.start(cl)
                }
            } catch (t: Throwable) {
                Protocol.State.logLine("HTTP 启动失败: $t")
            }

            // 启动时自动执行业务链（开关在面板「功能 → 启动时自动执行」）。
            // 放在这里而不是 installAllHooks：这里已经拿到 Application，
            // 而且不会在渲染进程里误跑（那段提前 return 了）。
            try {
                AutoRunner.schedule(app)
            } catch (t: Throwable) {
                Protocol.State.logLine("自动执行调度失败: $t")
            }

            while (true) {
                try {
                    RenderReaper.publishMainAlive()
                    Thread.sleep(10_000)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                } catch (t: Throwable) {
                    try {
                        Thread.sleep(10_000)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }
        }, "MainHeartbeat")
        t.isDaemon = true
        t.start()
    }
}
