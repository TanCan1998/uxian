package io.github.tancan1998.uxian

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 埋点与设备指纹上传断源（默认**关**）。
 *
 * ## 为什么默认关
 *
 * 抓包库是本项目的核心研究资产：埋点请求正是「闲鱼在偷偷传什么」的一手证据。
 * 默认放行，需要干净库、或想省流量时再打开。
 * 当前抓包样本（traffic3.db，1792 条 host 请求）里上报类占 72 条：
 * ```
 * ut.log.report      10    highway.upload     12
 * strategy.report    30    device.report      14
 * ```
 * 约 4%，不算多，但噪声集中在 `api=""` 的 NOTE 行附近，分析时确实碍事。
 *
 * ## 挂载点（mt 对 7.28.80 实测）
 *
 * - `com.ut.mini.UTTracker#send(Map)V` —— UT 统计的**总出口**。
 *   挂这一个就够，不用去挂 UT.send / UTAnalytics 那一层（那些最终都走这里）。
 *   同类的 `sendLog(Map;ILjava/util/List;)V` 是 private 内部实现，也挂上做双保险。
 * - `com.ta.audid.upload.UtdidUploadTask#upload / uploadFromCache / run` ——
 *   设备指纹上传。**只断上传，不改 utdid 的生成/读取**：改返回值可能 NPE
 *   或触发服务端风控（静途的结论，逻辑一致）。
 *
 * ## 不碰的东西
 *
 * - `AppUtdid.getInstance()` / `getUtdid()`：读取要正常，否则宿主自己算签名会崩。
 * - `mtop.taobao.search.highway.upload` 这类**业务**埋点：有些是搜索链路的
 *   正常请求，断了会影响推荐质量，也可能触发风控。只断 UT/audid 两个明确的上报出口。
 */
object TrackerBlocker {

    private const val TAG = "xysidecar"

    private const val UT_TRACKER = "com.ut.mini.UTTracker"
    private const val UTDID_TASK = "com.ta.audid.upload.UtdidUploadTask"

    private const val F_TRACK = "track"
    private const val F_UTDID = "utdid"

    fun install(module: XposedModule, cl: ClassLoader): Int {
        var n = 0
        n += hookUt(module, cl)
        n += hookUtdid(module, cl)
        return n
    }

    /** UT 统计总出口 */
    private fun hookUt(module: XposedModule, cl: ClassLoader): Int {
        val cls = HookKit.loadClass(UT_TRACKER, cl)
        if (cls == null) {
            HookState.failed(F_TRACK, "UTTracker", "类不存在")
            return 0
        }
        var n = 0
        // 公开入口 send(Map)
        val send = HookKit.findByName(cls, "send")
        if (HookKit.install(
                module, F_TRACK, "send", send,
                HookKit.skipVoid(F_TRACK, "send") { trackEnabled() },
            )
        ) n++
        // 私有内部实现 sendLog(Map;I;List)，双保险
        try {
            for (m in cls.declaredMethods) {
                if (m.name != "sendLog") continue
                if (HookKit.install(
                        module, F_TRACK, "sendLog", m,
                        HookKit.skipVoid(F_TRACK, "sendLog") { trackEnabled() },
                    )
                ) n++
            }
        } catch (t: Throwable) {
            Log.w(TAG, "TrackerBlocker: 遍历 sendLog 失败: $t")
        }
        return n
    }

    /** 设备指纹上传 */
    private fun hookUtdid(module: XposedModule, cl: ClassLoader): Int {
        val cls = HookKit.loadClass(UTDID_TASK, cl)
        if (cls == null) {
            HookState.failed(F_UTDID, "UtdidUploadTask", "类不存在")
            return 0
        }
        var n = 0
        val names = arrayOf("upload", "uploadFromCache", "run")
        for (name in names) {
            val m = HookKit.findByName(cls, name)
            if (HookKit.install(
                    module, F_UTDID, name, m,
                    HookKit.skipVoid(F_UTDID, name) { utdidEnabled() },
                )
            ) n++
        }
        return n
    }

    private fun trackEnabled(): Boolean = try {
        val app = Protocol.State.app ?: return false
        Prefs.get(app, Prefs.BLOCK_TRACK, false)
    } catch (t: Throwable) {
        false
    }

    private fun utdidEnabled(): Boolean = try {
        val app = Protocol.State.app ?: return false
        Prefs.get(app, Prefs.BLOCK_UTDID, false)
    } catch (t: Throwable) {
        false
    }
}
