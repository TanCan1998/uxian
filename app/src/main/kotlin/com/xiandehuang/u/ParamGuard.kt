package com.xiandehuang.u

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * 「当天只试一次」——借芝麻粒的任务策略纪律。
 *
 * 芝麻粒对做不了的业务（非 BROWSE 类、交易类）**当天只试一次**，然后打当日标记，
 * 后续轮次不再发请求。理由很实在：这类任务的失败是稳定的，重试只会把风控喂大。
 *
 * 我们用同样的口径管住 `FAIL_BIZ_COMMON_PARAM_ILLEGAL`：
 * 同一任务当天被参数拒绝过一次，后面每 30 分钟一轮的自动任务就不再打它，
 * **跨天自动重试**（活动配置/令牌都可能在夜里更新）。
 *
 * 为什么不用内存 Set：宿主一天会被杀掉很多次，只放内存等于没管住。
 */
internal object ParamGuard {

    private const val FILE = "param_rejects.json"

    /** 进程内缓存，减少文件读 */
    private val mem = HashSet<String>()
    private var memDay = 0

    private fun today(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 +
            c.get(Calendar.DAY_OF_MONTH)
    }

    private fun file(c: Context): File = File(c.filesDir, FILE)

    private fun load(c: Context): HashSet<String> {
        val day = today()
        if (memDay == day) return mem
        mem.clear()
        memDay = day
        try {
            val f = file(c)
            if (f.exists()) {
                val o = JSONObject(f.readText())
                if (o.optInt("day", 0) == day) {
                    val arr = o.optJSONArray("keys")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val k = arr.optString(i, "")
                            if (k.isNotEmpty()) mem.add(k)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
        }
        return mem
    }

    private fun save(c: Context, keys: HashSet<String>) {
        try {
            val o = JSONObject()
            o.put("day", today())
            val arr = JSONArray()
            for (k in keys) arr.put(k)
            o.put("keys", arr)
            file(c).writeText(o.toString())
        } catch (t: Throwable) {
        }
    }

    /** 一次性释放历史误拦；后续新的失败仍可重新进入当天冷却。 */
    @Synchronized
    fun releaseCashLikeOnce(c: Context) {
        val prefs = c.getSharedPreferences("param_guard_migrations", Context.MODE_PRIVATE)
        if (prefs.getBoolean("cash_like_20261101", false)) return
        val keys = load(c)
        val released = arrayOf("496:104574", "496:104633", "496:104637", "496:106538", "496:106181")
        var changed = false
        for (key in released) if (keys.remove(key)) changed = true
        if (changed) save(c, keys)
        prefs.edit().putBoolean("cash_like_20261101", true).apply()
    }

    /** 旧版将有真实进度的点击多轮任务因未做满而冷却；按任务一次性迁移。 */
    @Synchronized
    fun releasePartialOnce(c: Context, key: String) {
        val prefs = c.getSharedPreferences("param_guard_migrations", Context.MODE_PRIVATE)
        val migration = "partial_loop_20261102:$key"
        if (prefs.getBoolean(migration, false)) return
        val keys = load(c)
        if (keys.remove(key)) save(c, keys)
        prefs.edit().putBoolean(migration, true).apply()
    }

    @JvmStatic
    fun blocked(c: Context?, key: String): Boolean {
        if (c == null || key.isEmpty()) return false
        return try {
            load(c).contains(key)
        } catch (t: Throwable) {
            false
        }
    }

    @JvmStatic
    fun mark(c: Context?, key: String) {
        if (c == null || key.isEmpty()) return
        try {
            val s = load(c)
            if (s.add(key)) save(c, s)
        } catch (t: Throwable) {
        }
    }
}
