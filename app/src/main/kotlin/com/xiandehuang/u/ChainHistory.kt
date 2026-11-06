package com.xiandehuang.u

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 业务链执行历史（落盘）。
 *
 * ## 为什么需要它
 *
 * 「自动」面板现在点完只弹一次结果，关掉就没了。用户第二天不知道
 * 昨天签到成功没有、森林喂了几次、寻宝是不是因为没骰子跳过的。
 * 而「为什么失败」往往不是当次能看出来的 —— 需要看最近几天的趋势：
 * 一直是「参数缺失（asac 没攒到）」还是「今天开始报版本错」。
 *
 * 参考静途的诊断思路：**把「没走到」和「走到了但失败」分开记录**，
 * 而不是只记一个成功/失败。
 *
 * ## 存哪儿
 *
 * `filesDir/chain_history.jsonl`，一行一条 JSON。追加写，不做事务。
 * 每条控制在 ~200 字节，跑一年也就几十 KB，不需要清理策略。
 * 读的时候倒序取最近 N 条（文件小，直接全读）。
 *
 * ## R8 雷区
 * 不用 listOf/arrayListOf 字面量、不用 Regex、不用 ?.let{}。
 */
object ChainHistory {

    private const val FILE = "chain_history.jsonl"

    /** 最多保留的条数（超过就整体重写，防止无限增长） */
    private const val MAX_LINES = 2000

    /** 一条历史记录 */
    class Entry(
        @JvmField val time: Long,
        @JvmField val chain: String,
        @JvmField val okCount: Int,
        @JvmField val failCount: Int,
        @JvmField val skipped: Boolean,
        /** 第一条失败/跳过步骤的说明，用于「为什么没成」 */
        @JvmField val note: String,
        /**
         * 一句话结果（「已签」「已领 2 个」「无骰子」）。
         * 老记录里没有这个字段，解析时为空，[stateText] 会退回旧文案。
         */
        @JvmField val state: String,
    ) {
        fun day(): String {
            val f = SimpleDateFormat("MM-dd", Locale.CHINA)
            return f.format(Date(time))
        }

        fun timeText(): String {
            val f = SimpleDateFormat("HH:mm", Locale.CHINA)
            return f.format(Date(time))
        }

        /**
         * 列表行右侧的状态词。
         *
         * 优先用链自己给的结果（「已签」「已领 2 个」「无骰子」），
         * 只有在拿不到时才退回「完成 / 跳过」——
         * 「跳过」只说明没做事，用户看不出到底是今天已经做过了，还是根本没得做。
         */
        fun stateText(): String = when {
            failCount > 0 -> "失败 $failCount 步"
            state.isNotEmpty() -> state
            skipped -> "未做"
            else -> "完成"
        }
    }

    private fun file(c: Context): File = File(c.filesDir, FILE)

    /** 记一条。任何时候调用都不会抛异常（失败就静默丢弃，不能影响业务链本身）。 */
    @JvmStatic
    fun record(c: Context?, r: Chains.Result?) {
        if (c == null || r == null) return
        for (s in r.steps) RunProgress.result(r.title, s.name, s.detail, s.ok, s.skipped)
        try {
            var ok = 0
            var bad = 0
            var note = ""
            for (s in r.steps) {
                if (s.skipped) {
                    if (note.isEmpty()) note = s.name + "：" + s.detail
                } else if (s.ok) {
                    ok++
                } else {
                    bad++
                }
                if (note.length == 0 && !s.ok) {
                    note = s.name + "：" + s.detail
                }
            }
            if (note.length == 0 && r.skipped) {
                for (s in r.steps) {
                    if (s.detail.length > 0) {
                        note = s.name + "：" + s.detail
                        break
                    }
                }
            }
            val o = JSONObject()
            o.put("t", System.currentTimeMillis())
            o.put("c", r.title)
            o.put("ok", ok)
            o.put("bad", bad)
            o.put("skip", r.skipped)
            o.put("note", clip(note, 120))
            o.put("st", r.state)
            append(c, o.toString())
        } catch (ignored: Throwable) {
        }
    }

    private fun clip(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(0, max) + "…"

    private fun append(c: Context, line: String) {
        try {
            val f = file(c)
            // 超过上限就重写保留后半段
            if (f.exists() && f.length() > 400_000L) {
                trim(c, f)
            }
            f.appendText(line)
            f.appendText("\n")
        } catch (ignored: Throwable) {
        }
    }

    private fun trim(c: Context, f: File) {
        try {
            val all = readRaw(f)
            val keep = ArrayList<String>()
            val from = if (all.size > MAX_LINES / 2) all.size - MAX_LINES / 2 else 0
            for (i in from until all.size) {
                keep.add(all[i])
            }
            val sb = StringBuilder()
            for (ln in keep) {
                sb.append(ln).append("\n")
            }
            f.writeText(sb.toString())
        } catch (ignored: Throwable) {
        }
    }

    private fun readRaw(f: File): List<String> {
        val out = ArrayList<String>()
        try {
            if (!f.exists()) return out
            // 不用 forEachLine：那是 kotlin.io 的扩展函数，R8 收缩后
            // 内部实现（Charset/Closeable 那套）可能缺方法，运行时才炸。
            // 用 BufferedReader 手写循环最稳。
            val br = java.io.BufferedReader(java.io.InputStreamReader(java.io.FileInputStream(f), "UTF-8"))
            try {
                while (true) {
                    val ln = br.readLine() ?: break
                    if (ln.length > 0) out.add(ln)
                }
            } finally {
                try {
                    br.close()
                } catch (ignored: Throwable) {
                }
            }
        } catch (ignored: Throwable) {
        }
        return out
    }

    /**
     * 读最近 N 条，**倒序**（最新在前）。
     * 解析失败的行直接跳过 —— 历史文件坏一行不该让整页打不开。
     */
    @JvmStatic
    fun recent(c: Context?, max: Int): List<Entry> {
        val out = ArrayList<Entry>()
        if (c == null) return out
        try {
            val lines = readRaw(file(c))
            var i = lines.size - 1
            while (i >= 0 && out.size < max) {
                val e = parse(lines[i])
                if (e != null) out.add(e)
                i--
            }
        } catch (ignored: Throwable) {
        }
        return out
    }

    private fun parse(line: String): Entry? = try {
        val o = JSONObject(line)
        Entry(
            o.optLong("t", 0L),
            o.optString("c", "?"),
            o.optInt("ok", 0),
            o.optInt("bad", 0),
            o.optBoolean("skip", false),
            o.optString("note", ""),
            o.optString("st", ""),
        )
    } catch (t: Throwable) {
        null
    }

    /**
     * 近 N 天的按链聚合，供面板「执行记录」页顶部摘要。
     * 返回形如 `签到 3/3 · 森林 2/3（1 次跳过）` 的一行。
     */
    @JvmStatic
    fun digest(c: Context?, days: Int): String {
        val list = recent(c, 500)
        if (list.isEmpty()) return "还没有执行记录"
        val cutoff = System.currentTimeMillis() - days * 24L * 3600_000L
        // 链名 -> [总次数, 成功次数]
        val stat = LinkedHashMap<String, IntArray>()
        for (e in list) {
            if (e.time < cutoff) continue
            var arr = stat.get(e.chain)
            if (arr == null) {
                arr = intArrayOf(0, 0)
                stat.put(e.chain, arr)
            }
            arr[0] = arr[0] + 1
            if (e.failCount == 0) arr[1] = arr[1] + 1
        }
        if (stat.isEmpty()) return "近 " + days + " 天没有记录"
        val sb = StringBuilder()
        val it = stat.entries.iterator()
        var first = true
        while (it.hasNext()) {
            val kv = it.next()
            if (!first) sb.append(" · ")
            first = false
            sb.append(kv.key).append(" ").append(kv.value[1]).append("/").append(kv.value[0])
        }
        return sb.toString()
    }

    /** 清空（面板里给个入口，便于排除历史干扰） */
    @JvmStatic
    fun clear(c: Context?) {
        if (c == null) return
        try {
            val f = file(c)
            if (f.exists()) f.delete()
        } catch (ignored: Throwable) {
        }
    }
}
