package com.xiandehuang.u

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 任务黑名单（照抄芝麻粒-M 1.5.3 的 `AutoBlackList` 机制）。
 *
 * ## 为什么要它
 *
 * 任务列表每轮都会混进一批**注定做不成**的任务：
 * 要真实交易的、要第三方 App 回调的、要好友互动的。
 * 我们现在的做法是「每轮都试一次，失败就跳过」—— 结果是
 *  - 每轮白花十几秒（每次 mtop ~1.2s）
 *  - 执行记录被同一批失败刷满，真正的问题看不出来
 *  - 服务端看到同一个 deliveryId 反复被 trigger，是明显的机器人特征
 *
 * 芝麻粒的处理是：**同一个任务失败 3 次就永久拉黑，以后连请求都不发**。
 * 实测它自己的 AutoBlackList.json 攒了 60+ 条，说明这条路走对了。
 *
 * ## 三次才拉黑，不是一次
 *
 * 一次失败分不清「任务本身做不了」还是「这次运气不好」（网络抖动、
 * 服务端瞬时 500、asac 刚好过期）。所以：
 *  第 1、2 次 → 只记次数，日志写「/3次命中，暂不拉黑」
 *  第 3 次     → 正式拉黑
 * 隔了 3 天以上没再命中，计数自动清零 —— 任务池会换，老任务可能下架了。
 *
 * ## 值的格式（四段，和芝麻粒一致）
 *
 * ```
 * count;day;unlockDay;type
 * ```
 * | 段 | 含义 |
 * |---|---|
 * | count | 累计命中次数（0-3） |
 * | day | 最近一次命中的天索引（`millis / 86400000`） |
 * | unlockDay | `-1` 永不自动解禁；`0` 待定；其他 = 解禁天索引 |
 * | type | 解禁后允许重试的次数上限 |
 *
 * 例：`"0;20747;-1;3"` = 永不自动解禁（交易类）；
 *     `"2;20750;0;1"` = 命中 2 次，待定。
 *
 * ## R8 雷区（本项目通用，别改）
 * 不用 `listOf` / `arrayListOf` 字面量（`Collection.toArray` 会变抽象方法）；
 * 不用 `kotlin.text.Regex`；不用 `?.let{}`；不用 `forEachLine`。
 */
object TaskBlacklist {

    private const val FILE = "task_blacklist.json"

    /** 条目上限。超了按最后命中时间淘汰最旧的，防止文件无限长。 */
    private const val MAX_ENTRIES = 400

    /** 命中几次才正式拉黑 */
    const val HIT_LIMIT = 3

    /** 隔多少天没命中就清零计数（任务池会换） */
    private const val RESET_DAYS = 3L

    /** 天索引的毫秒基数 */
    private const val DAY_MS = 86400000L

    /** 永不自动解禁的标记 */
    const val NEVER = -1L

    // ------------------------------------------------------------ 四段值

    /** 解析后的黑名单值 */
    class Entry(
        @JvmField var count: Int,
        @JvmField var day: Long,
        @JvmField var unlockDay: Long,
        @JvmField var type: Int,
        /** 任务标题，仅用于界面显示（存在 key 里不方便读） */
        @JvmField var title: String,
    ) {
        fun encode(): String = "$count;$day;$unlockDay;$type"

        fun isPermanent(): Boolean = unlockDay == NEVER
    }

    private fun parse(raw: String?, title: String): Entry? {
        if (raw == null || raw.isEmpty()) return null
        val p = raw.split(";")
        if (p.size < 4) return null
        try {
            return Entry(
                p[0].trim().toInt(),
                p[1].trim().toLong(),
                p[2].trim().toLong(),
                p[3].trim().toInt(),
                title,
            )
        } catch (t: Throwable) {
            return null
        }
    }

    private fun today(): Long = System.currentTimeMillis() / DAY_MS

    // ------------------------------------------------------------ 存取

    private fun file(c: Context): File = File(c.filesDir, FILE)

    /**
     * 磁盘格式：
     * ```json
     * { "496:79496": {"v":"3;20757;-1;3","t":"完成1笔旧衣回收"}, ... }
     * ```
     * 标题单独存在对象里而不是拼进四段值 —— 四段值的格式要和芝麻粒
     * 保持一致（便于互相对照排查），但标题是中文，混进去解析容易出错。
     *
     * 老版本（本模块第一次装机的版本）只存字符串 `"3;20757;-1;3"`，
     * 读取时兼容，读到字符串就当没有标题。
     */
    private class Stored(
        @JvmField val v: String,
        @JvmField val t: String,
    )

    private fun storedOf(all: JSONObject, key: String): Stored? {
        val raw = all.opt(key)
        if (raw == null) return null
        if (raw is JSONObject) {
            return Stored(raw.optString("v", ""), raw.optString("t", ""))
        }
        val s = raw.toString()
        if (s.isEmpty()) return null
        return Stored(s, "")
    }

    private fun putStored(all: JSONObject, key: String, v: String, t: String) {
        val o = JSONObject()
        o.put("v", v)
        if (t.isNotEmpty()) o.put("t", t)
        all.put(key, o)
    }

    /** 全量读。文件坏了就当空表，绝不抛异常。 */
    private fun load(c: Context): JSONObject {
        val f = file(c)
        if (!f.exists()) return JSONObject()
        return try {
            JSONObject(f.readText())
        } catch (t: Throwable) {
            JSONObject()
        }
    }

    private fun save(c: Context, o: JSONObject) {
        try {
            // 超上限时淘汰最久没命中的：day 最小、count 最小的先走
            if (o.length() > MAX_ENTRIES) {
                val names = o.keys()
                var worst = ""
                var worstScore = Long.MAX_VALUE
                while (names.hasNext()) {
                    val k = names.next()
                    val st = storedOf(o, k) ?: continue
                    val e = parse(st.v, st.t) ?: continue
                    // 分数 = day * 10 + count，越小越该淘汰
                    val score = e.day * 10 + e.count
                    if (score < worstScore) {
                        worstScore = score
                        worst = k
                    }
                }
                if (worst.isNotEmpty()) o.remove(worst)
            }
            // 临时文件 + 原子重命名（照抄芝麻粒 j60.u）：
            // 直接覆盖写到一半被杀，会留下解析不了的半个 JSON，
            // 下次读出来是空表 —— 等于黑名单全丢，所有任务重新白试三轮。
            val f = file(c)
            val tmp = File(
                f.parentFile,
                FILE + ".tmp" + android.os.Process.myPid() + "-" +
                    Thread.currentThread().id + "-" + System.nanoTime(),
            )
            var ok = false
            try {
                val out = java.io.FileOutputStream(tmp)
                try {
                    out.write(o.toString().toByteArray(Charsets.UTF_8))
                    out.flush()
                    out.fd.sync()
                } finally {
                    try {
                        out.close()
                    } catch (ignored: Throwable) {
                    }
                }
                ok = tmp.renameTo(f)
                if (!ok) f.writeText(o.toString())
            } finally {
                try {
                    if (tmp.exists()) tmp.delete()
                } catch (ignored: Throwable) {
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    /** 取一条（内部用，带标题） */
    private fun get(c: Context, key: String): Entry? {
        val st = storedOf(load(c), key) ?: return null
        return parse(st.v, st.t)
    }

    // ------------------------------------------------------------ 查询

    /**
     * 这个任务现在是否该跳过。
     *
     * 判据（对齐芝麻粒）：
     *  - 没记录 → 不拦
     *  - `unlockDay == -1` → 永久拦
     *  - `count < 3` → 还没到拉黑门槛，不拦
     *  - 距上次命中超过 [RESET_DAYS] 天 → 计数作废，不拦
     *  - `unlockDay == 0` → 已拉黑但没定解禁日，拦
     *  - `unlockDay > today` → 还没到解禁日，拦
     *  - `unlockDay <= today` → 解禁，不拦
     */
    @JvmStatic
    fun isBlocked(c: Context?, key: String): Boolean {
        if (c == null || key.isEmpty()) return false
        val e = get(c, key) ?: return false
        if (e.unlockDay == NEVER) return true
        if (e.count < HIT_LIMIT) return false
        val now = today()
        if (now - e.day > RESET_DAYS) return false
        if (e.unlockDay == 0L) return true
        return e.unlockDay > now
    }

    /** 拉黑原因的短文案，给执行记录用 */
    @JvmStatic
    fun reason(c: Context?, key: String): String {
        if (c == null || key.isEmpty()) return ""
        val e = get(c, key) ?: return ""
        if (e.unlockDay == NEVER) return "永久拉黑"
        if (e.unlockDay > today()) return "解禁日 ${e.unlockDay - today()} 天后"
        return "已拉黑"
    }

    /**
     * 当前命中次数文案：`2/3`。
     *
     * 执行记录里直接显示「第 2/3 次」，用户就知道这个任务还能再试一次 ——
     * 比只说「失败」有用得多。
     */
    @JvmStatic
    fun hitText(c: Context?, key: String): String {
        if (c == null || key.isEmpty()) return "1/$HIT_LIMIT"
        val e = get(c, key)
        val n = if (e == null) 1 else e.count
        return "$n/$HIT_LIMIT"
    }

    // ------------------------------------------------------------ 写入

    /**
     * 记一次失败。返回**是否已经正式拉黑**（true = 以后别再试）。
     *
     * 第 1、2 次只累加计数并返回 false；第 3 次写 `unlockDay=0`（待定解禁）
     * 并返回 true。交易类用 [blockForever] 直接 `-1`。
     */
    @JvmStatic
    fun note(c: Context?, key: String, title: String): Boolean {
        if (c == null || key.isEmpty()) return false
        try {
            val all = load(c)
            var e = get(c, key)
            val now = today()
            if (e == null || now - e.day > RESET_DAYS) {
                e = Entry(0, now, 0L, 0, title)
            }
            e.count = e.count + 1
            e.day = now
            e.title = title
            if (e.count >= HIT_LIMIT) {
                if (e.unlockDay != NEVER) e.unlockDay = 0L
                putStored(all, key, e.encode(), title)
                save(c, all)
                return true
            }
            putStored(all, key, e.encode(), title)
            save(c, all)
            return false
        } catch (t: Throwable) {
            return false
        }
    }

    /**
     * 直接永久拉黑，不计数。
     *
     * 用于**已知做不了**的类别（交易/履约类、指向外部 App 的）。
     * 这类试三次纯属浪费 —— 芝麻粒对它们也是直接写 `-1`。
     */
    @JvmStatic
    fun blockForever(c: Context?, key: String, title: String) {
        if (c == null || key.isEmpty()) return
        try {
            val all = load(c)
            val e = Entry(HIT_LIMIT, today(), NEVER, HIT_LIMIT, title)
            putStored(all, key, e.encode(), title)
            save(c, all)
        } catch (ignored: Throwable) {
        }
    }

    /** 做成功了就清掉记录（任务池会轮换，做成功说明以前误判了） */
    @JvmStatic
    fun clear(c: Context?, key: String) {
        if (c == null || key.isEmpty()) return
        try {
            val all = load(c)
            if (all.has(key)) {
                all.remove(key)
                save(c, all)
            }
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 只清「软性」记录（计数中 / 待定解禁），**永久拉黑保留**。
     *
     * 永久条目是交易/履约类，清掉也做不了，下次照样被判定回来 ——
     * 让用户以为「清空 = 都能做了」是误导。要动永久条目只有 [clearAll]。
     *
     * @return 清掉的条数
     */
    @JvmStatic
    fun clearSoft(c: Context?): Int {
        if (c == null) return 0
        return try {
            val all = load(c)
            val names = all.keys()
            val drop = ArrayList<String>()
            while (names.hasNext()) {
                val k = names.next()
                val st = storedOf(all, k) ?: continue
                val e = parse(st.v, st.t) ?: continue
                if (e.unlockDay != NEVER) drop.add(k)
            }
            for (k in drop) all.remove(k)
            if (drop.isNotEmpty()) save(c, all)
            drop.size
        } catch (t: Throwable) {
            0
        }
    }

    /** 永久条目的条数（清空时告诉用户「还剩几条」） */
    @JvmStatic
    fun permanentCount(c: Context?): Int {
        if (c == null) return 0
        return try {
            val all = load(c)
            var n = 0
            val names = all.keys()
            while (names.hasNext()) {
                val st = storedOf(all, names.next()) ?: continue
                val e = parse(st.v, st.t) ?: continue
                if (e.unlockDay == NEVER) n++
            }
            n
        } catch (t: Throwable) {
            0
        }
    }

    /** 手动清空全部（面板上的「清空黑名单」） */
    @JvmStatic
    fun clearAll(c: Context?) {
        if (c == null) return
        try {
            val f = file(c)
            if (f.exists()) f.delete()
        } catch (ignored: Throwable) {
        }
    }

    /** 面板行右侧的摘要文案：`12 条 · 8 永久` */
    @JvmStatic
    fun summaryLine(c: Context?): String {
        if (c == null) return ""
        val all = load(c)
        val total = all.length()
        if (total == 0) return "暂无"
        var perm = 0
        try {
            val names = all.keys()
            while (names.hasNext()) {
                val st = storedOf(all, names.next()) ?: continue
                val e = parse(st.v, st.t) ?: continue
                if (e.unlockDay == NEVER) perm++
            }
        } catch (ignored: Throwable) {
        }
        return if (perm > 0) "$total 条 · $perm 永久" else "$total 条"
    }

    // ------------------------------------------------------------ 界面

    /** 条目数，给面板行显示 */
    @JvmStatic
    fun count(c: Context?): Int {
        if (c == null) return 0
        return try {
            load(c).length()
        } catch (t: Throwable) {
            0
        }
    }

    /**
     * 取出来给界面看：`标题　原因`。
     * 按「永久 → 已拉黑 → 计数中」排序，用户最想看的在最上面。
     */
    @JvmStatic
    fun lines(c: Context?, max: Int): ArrayList<String> {
        val out = ArrayList<String>()
        if (c == null) return out
        try {
            val all = load(c)
            val names = all.keys()
            // 三档分开收，最后按档拼接（不用 sortWith，避免 R8 裁 Comparator）
            val perm = ArrayList<String>()
            val blocked = ArrayList<String>()
            val counting = ArrayList<String>()
            while (names.hasNext()) {
                val k = names.next()
                val st = storedOf(all, k) ?: continue
                val e = parse(st.v, st.t) ?: continue
                val t = titleOf(k, e)
                if (e.unlockDay == NEVER) {
                    perm.add("$t　永久拉黑")
                } else if (e.count >= HIT_LIMIT) {
                    blocked.add("$t　已拉黑（${e.count} 次）")
                } else {
                    counting.add("$t　${e.count}/$HIT_LIMIT 次")
                }
            }
            addUpTo(out, perm, max)
            addUpTo(out, blocked, max)
            addUpTo(out, counting, max)
        } catch (ignored: Throwable) {
        }
        return out
    }

    private fun addUpTo(out: ArrayList<String>, src: ArrayList<String>, max: Int) {
        for (s in src) {
            if (out.size >= max) return
            out.add(s)
        }
    }

    /**
     * 标题没存进值里（值只有四段数字），从 key 里取。
     *
     * key 的构造是 `<sceneId>:<deliveryId>`，取不到标题 ——
     * 所以 [Entry.title] 才是权威来源，这里只是兜底。
     */
    private fun titleOf(key: String, e: Entry): String {
        if (e.title.isNotEmpty()) return e.title
        return key
    }
}
