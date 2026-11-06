package io.github.tancan1998.uxian

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 热重载诊断通道：xysidecar:hot[:reload]
 *
 * 用来回答一个具体问题：框架到底认不认我们这个模块可以热重载。
 * 通过 libxposed 官方的 XposedServiceHelper 拿到 XposedService，
 * 读 getRunningTargets() 看每个已注入进程的 state 和 loadedVersionCode。
 *
 * 之所以要这个通道：之前一直靠"重装后看现象"猜热更成不成，
 * 而框架侧的失败原因是拿不到诊断的。直接问框架最准。
 *
 * 用法：
 *   xysidecar:hot          → 列出 target（uid/pid/进程名/state/已加载版本）
 *   xysidecar:hot:reload   → 对每个 target 主动请求热重载，输出结果
 *
 * 迁移到 Kotlin 后不再需要动态代理绕 Record 类型 ——
 * 这正是当初用 Java + --release 8 时绕不过去的坑。
 */
object HotReloadProbe {

    @Volatile
    private var service: XposedService? = null

    private val bound = CountDownLatch(1)

    @Volatile
    private var listenerRegistered = false

    private fun ensureListener() {
        if (listenerRegistered) return
        synchronized(HotReloadProbe::class.java) {
            if (listenerRegistered) return
            try {
                XposedServiceHelper.registerListener(
                    object : XposedServiceHelper.OnServiceListener {
                        override fun onServiceBind(s: XposedService) {
                            service = s
                            bound.countDown()
                        }

                        override fun onServiceDied(s: XposedService) {
                            service = null
                        }
                    },
                )
                listenerRegistered = true
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * 主动向模块 App 的 XposedProvider 要 binder。
     *
     * 关键点：helper 只缓存"被框架推送过来的" binder，而推送发生在模块 App
     * 自己启动时。我们是在**宿主进程**里调用，从没走过那条路，所以缓存必然为空。
     * 这里按官方协议的 key（call 方法名 "SendBinder"、Bundle key "binder"）
     * 直接 call 一次，把 binder 喂给 helper。
     */
    private fun pullBinder(context: Context): Boolean {
        return try {
            val uri = Uri.parse("content://io.github.tancan1998.uxian.XposedService")
            val cr = context.contentResolver
            // 直接走 IContentProvider.call，绕开 ContentResolver 对目标包的可见性检查
            val provider = try {
                val acq = android.content.ContentResolver::class.java
                    .getDeclaredMethod("acquireProvider", Uri::class.java)
                    .apply { isAccessible = true }
                    .invoke(cr, uri)
                acq
            } catch (e: Throwable) {
                null
            }
            val bundle = if (provider != null) {
                val call = provider.javaClass.getMethod(
                    "call",
                    String::class.java, String::class.java, Bundle::class.java,
                )
                call.invoke(provider, "io.github.tancan1998.uxian", "SendBinder", null) as Bundle?
            } else {
                cr.call(uri, "SendBinder", null, null)
            }
            val binder = bundle?.getBinder("binder")
            if (binder == null) {
                false
            } else {
                // 走官方入口，让它完成 XposedService 包装 + 缓存 + 回调
                XposedServiceHelper::class.java
                    .getDeclaredMethod("onBinderReceived", IBinder::class.java)
                    .apply { isAccessible = true }
                    .invoke(null, binder)
                true
            }
        } catch (t: Throwable) {
            lastError = t.toString()
            false
        }
    }

    /** 最近一次拉取失败的原因，供诊断输出 */
    @Volatile
    var lastError: String? = null
        private set

    private fun await(seconds: Long): XposedService? {
        ensureListener()
        service?.let { return it }
        // helper 缓存为空时主动拉一次
        try {
            Protocol.State.app?.let { pullBinder(it) }
        } catch (ignored: Throwable) {
        }
        try {
            bound.await(seconds, TimeUnit.SECONDS)
        } catch (ignored: InterruptedException) {
        }
        return service
    }

    /** 列出框架眼中的注入目标 */
    @JvmStatic
    fun describe(): String {
        val s = await(3) ?: return "XposedService 未绑定；pullError=" + (lastError ?: "无异常，binder 为空")
        val sb = StringBuilder()
        sb.append("framework=").append(s.frameworkName)
            .append(' ').append(s.frameworkVersion)
            .append(" api=").append(s.apiVersion).append('\n')
        try {
            val targets = s.runningTargets
            if (targets.isNullOrEmpty()) {
                sb.append("targets: 空（框架没记录到本模块注入的进程）")
                return sb.toString()
            }
            sb.append("targets=").append(targets.size).append('\n')
            for (t in targets) {
                sb.append("  ").append(t.processName)
                    .append(" pid=").append(t.pid)
                    .append(" uid=").append(t.uid)
                    .append(" state=").append(t.state)
                    .append(" loadedVer=").append(t.loadedVersionCode)
                    .append('\n')
            }
        } catch (e: Throwable) {
            sb.append("getRunningTargets 失败: ").append(e)
        }
        return sb.toString()
    }

    /** 主动请求热重载 */
    @JvmStatic
    fun reload(): String {
        val s = await(3) ?: return "XposedService 未绑定"
        val sb = StringBuilder()
        try {
            val targets = s.runningTargets
            if (targets.isNullOrEmpty()) return "没有可重载的 target"
            for (t in targets) {
                val done = CountDownLatch(1)
                val r = StringBuilder()
                s.hotReloadModule(
                    t,
                    null as Bundle?,
                    object : XposedService.HotReloadCallback {
                        override fun onHotReloadResult(target: HookedTarget, result: HotReloadResult) {
                            r.append(describeResult(result))
                            done.countDown()
                        }
                    },
                )
                val ok = done.await(30, TimeUnit.SECONDS)
                sb.append(t.processName).append(": ")
                    .append(if (ok) r.toString() else "超时30s").append('\n')
            }
        } catch (e: Throwable) {
            sb.append("hotReloadModule 失败: ").append(e)
        }
        return sb.toString()
    }

    private fun describeResult(result: Any?): String {
        if (result == null) return "null"
        val sb = StringBuilder()
        for (m in arrayOf("status", "message", "refused", "generationChanged")) {
            try {
                val v = result.javaClass.getMethod(m).invoke(result)
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(m).append('=').append(v)
            } catch (ignored: Throwable) {
            }
        }
        return if (sb.isEmpty()) result.toString() else sb.toString()
    }
}
