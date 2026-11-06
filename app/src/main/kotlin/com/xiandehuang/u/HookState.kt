package com.xiandehuang.u

import android.util.Log

/**
 * 挂载点健康度：entry / hit / skip / critical 四级模型。
 *
 * 借鉴静途（QuietMap）的诊断设计，解决本项目原有的一个盲区：
 * `capSplash = n > 0` 只能说明「至少装上了一个钩子」。装上 4 个里的 1 个
 * 也算 true，面板不报警，实际功能早就半残。而闲鱼升级后类名/方法签名一变，
 * 所有 `Class.forName` / `getDeclaredMethod` 都会抛异常被 catch 吞掉，
 * 表现是「模块在，但什么都不干」—— 没有计数就完全看不出来。
 *
 * 四个级别的含义：
 *   - entry    钩子入口被调用次数。>0 说明钩子活着且路径被走到。
 *   - hit      命中抑制路径的次数。>0 才说明真的拦到了东西。
 *   - skip     装不上 / 方法缺失的原因（可选方法缺失属正常，不算失败）。
 *   - critical 该功能一个挂载点都没装上 —— 整体失效，面板要报警。
 *
 * ⚠️ R8 雷区：本文件不用 listOf/arrayListOf 字面量、不用 Regex、不用 ?.let{}。
 * 集合一律 ArrayList + add，判空一律显式写。
 */
object HookState {

    private const val TAG = "xysidecar"

    /** 单个功能的健康状态。字段直接暴露，面板和 diag 都读它。 */
    class Feature(val name: String) {
        @Volatile @JvmField var entry = 0L
        @Volatile @JvmField var hit = 0L

        /** 跨进程累计命中数（持久化，面板「已拦」用它） */
        @Volatile @JvmField var total = 0L

        /** total 是否已从磁盘载入 */
        @Volatile @JvmField var loaded = false

        /** 上次落盘时间（毫秒） */
        @Volatile @JvmField var lastSave = 0L

        /** 装上的挂载点标签，例如 "SplashAdController#loadSplashAd" */
        @JvmField val installed = ArrayList<String>()

        /** 装失败的挂载点标签 */
        @JvmField val failed = ArrayList<String>()

        /** 最后一次 skip 的原因（类不存在 / 方法缺失 / 框架类黑名单…） */
        @Volatile @JvmField var skip: String = ""

        /** 一个都没装上：整体失效 */
        fun critical(): Boolean = installed.isEmpty() && !failed.isEmpty()

        /** 有挂载点但一次都没被调用过（可能路径没走到，也可能钩错了） */
        fun silent(): Boolean = !installed.isEmpty() && entry == 0L
    }

    private val map = java.util.concurrent.ConcurrentHashMap<String, Feature>()

    @JvmStatic
    fun feature(name: String): Feature {
        val old = map.get(name)
        if (old != null) return old
        val f = Feature(name)
        val prev = map.putIfAbsent(name, f)
        return if (prev == null) f else prev
    }

    /** 已注册的全部功能（适配判定只看实际挂载点，不看版本号） */
    @JvmStatic
    fun all(): List<Feature> {
        val out = ArrayList<Feature>()
        try {
            out.addAll(map.values)
        } catch (_: Throwable) {
        }
        return out
    }

    /** 已存在的功能记录；不存在返回 null（不创建，避免面板把没装的功能算进来） */
    @JvmStatic
    fun mapGet(name: String): Feature? = map.get(name)

    /** 装上一个挂载点 */
    @JvmStatic
    fun installed(feature: String, label: String) {
        try {
            val f = feature(feature)
            synchronized(f.installed) {
                if (!f.installed.contains(label)) f.installed.add(label)
            }
        } catch (ignored: Throwable) {
        }
    }

    /** 一个挂载点装失败（类不存在也算，交给 critical() 判定严重性） */
    @JvmStatic
    fun failed(feature: String, label: String, why: String) {
        try {
            val f = feature(feature)
            synchronized(f.failed) {
                if (!f.failed.contains(label)) f.failed.add(label)
            }
            f.skip = why
            Log.w(TAG, "HookState: $feature 挂载点 $label 失败：$why")
        } catch (ignored: Throwable) {
        }
    }

    /** 入口计数（钩子被调用） */
    @JvmStatic
    fun entry(feature: String): Long {
        val f = feature(feature)
        f.entry = f.entry + 1
        return f.entry
    }

    /** 命中计数（真的拦到了）。同时累加历史值，跨进程保留。 */
    @JvmStatic
    fun hit(feature: String): Long {
        val f = feature(feature)
        f.hit = f.hit + 1
        val total = totalOf(feature) + 1
        f.total = total
        // 低频功能（开屏/摇一摇）必须立刻落盘，否则重启后归零；
        // 高频功能（埋点上千次）按 2 秒节流，避免频繁写盘
        val now = System.currentTimeMillis()
        if (total <= 5L || now - f.lastSave > 2000L) {
            persist(f)
            f.lastSave = now
        }
        return f.hit
    }

    /** 历史累计命中数。首次访问从磁盘读，之后走内存。 */
    @JvmStatic
    fun totalOf(feature: String): Long {
        val f = feature(feature)
        if (f.loaded) return f.total
        synchronized(f) {
            if (!f.loaded) {
                val c = Protocol.State.app
                f.total = if (c != null) Prefs.getLong(c, KEY_TOTAL + feature, 0L) else 0L
                f.loaded = true
            }
            return f.total
        }
    }

    private fun persist(f: Feature) {
        try {
            val c = Protocol.State.app ?: return
            Prefs.setLong(c, KEY_TOTAL + f.name, f.total)
        } catch (ignored: Throwable) {
        }
    }

    const val KEY_TOTAL = "hit_total_"

    /** 有挂载点整体失效的功能名 */
    @JvmStatic
    fun brokenFeatures(): List<String> {
        val out = ArrayList<String>()
        val it = map.values.iterator()
        while (it.hasNext()) {
            val f = it.next()
            if (f.critical()) out.add(f.name)
        }
        return out
    }

    /** 面板横幅用：一句中文摘要，没问题返回空串 */
    @JvmStatic
    fun summary(): String {
        val broken = brokenFeatures()
        if (broken.isEmpty()) return ""
        val sb = StringBuilder("部分功能未适配当前版本：")
        for (i in broken.indices) {
            if (i > 0) sb.append("、")
            sb.append(broken[i])
        }
        return sb.toString()
    }

    /**
     * diag 通道用：每个功能一行，格式
     *   `splash  entry=12 hit=12 reg=3/4`
     * `reg=3/4` 表示 4 个挂载点里装上 3 个；有 skip 原因时追加。
     */
    @JvmStatic
    fun report(): List<String> {
        val out = ArrayList<String>()
        val it = map.values.iterator()
        while (it.hasNext()) {
            val f = it.next()
            val reg = f.installed.size
            val all = reg + f.failed.size
            val sb = StringBuilder()
            sb.append(f.name)
            sb.append("  entry=").append(f.entry)
            sb.append(" hit=").append(f.hit)
            sb.append(" reg=").append(reg).append("/").append(all)
            if (f.skip.length > 0) sb.append(" skip=").append(f.skip)
            if (f.critical()) sb.append(" CRITICAL")
            out.add(sb.toString())
        }
        return out
    }

    /** 重置（热重载重装钩子前调用，避免计数叠加） */
    @JvmStatic
    fun reset() {
        try {
            map.clear()
        } catch (ignored: Throwable) {
        }
    }
}
