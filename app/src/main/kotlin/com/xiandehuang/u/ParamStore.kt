package com.xiandehuang.u

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 业务参数库 —— 独立 SQLite 文件，**和抓包库（traffic.db）完全无关**。
 *
 * ## 为什么要独立一份
 *
 * 抓包库是**调试素材**：4MB / 7000 条、随时被用户清空、关掉抓包开关就停更。
 * 业务链却一直从它拿参数，后果是：
 *  - 用户一清空抓包库 → 签到/领奖/任务链全瘫（要重新打开任务页攒数据）
 *  - 抓包开关关着时 → 业务链拿不到任何新参数
 *  - 抓包库按 api 只留最近若干条 → 老参数被挤掉就没了
 *
 * 这份库只存业务真正需要的几十条记录、几 KB，**用户操作抓包库影响不到它**。
 *
 * ## 为什么用 SQLite 而不是 JSON
 *
 * 上一版是 `api_profile.json`（整份读写），问题：
 *  - 每次改一个值要重写整个文件，写放大
 *  - 并发写要额外加锁，否则互相覆盖
 *  - 关联查询（「这个参数哪些接口在用」）只能全量扫内存
 *
 * SQLite 一行一个参数，`UPDATE ... WHERE key=?` 只动一行；
 * 写入是事务级的，进程被杀不会留半个文件（这正好也是芝麻粒用
 * 临时文件 + 原子重命名要解决的问题，SQLite 天然给了）。
 *
 * ## 为什么还要压缩
 *
 * `asac` / `fromToken` 这类值很短（20-30 字节），压缩没意义，
 * 但**响应体快照**可能有几 KB（`oliver.show` 的完整响应 2KB+）。
 * 所以 value 列存 BLOB，超过 [COMPRESS_MIN] 字节才 gzip，
 * 和抓包库 `traffic.body` 的做法保持一致（`compressed` 列标记）。
 *
 * ## 三类参数
 *
 * | 类别 | 存不存 | 例子 | 判据 |
 * |---|---|---|---|
 * | A 固定常量 | **不存** | fishAdCode / gps / sceneId / playId | 代码写死，永不变化 |
 * | B 可刷新 | **存** | asac | 实测隔十几小时仍有效，且有接口能主动刷 |
 * | C 一次性 | **不存** | fromToken / idempotentKey / bubbleId | 实测同一任务出现过 26 个不同值 |
 *
 * 判断一个参数该不该存，看两条：
 *  1. 它的值会不会在**同一天内**变化？（变 → C 类，别存）
 *  2. 有没有接口能在**不依赖用户操作**的前提下重新拿到它？（有 → B 类，可存可刷）
 *
 * ## R8 雷区
 * 不用 `listOf` / `arrayListOf` 字面量、不用 `kotlin.text.Regex`、不用 `?.let{}`。
 */
object ParamStore {

    /** 独立库文件，和 traffic.db 并列但互不相干 */
    private const val DB_NAME = "params.db"

    /** 超过这个字节数才 gzip（短值压缩反而变大） */
    private const val COMPRESS_MIN = 64

    /** 默认 TTL：12 小时。实测 asac 从 02:56 用到 12:59 仍有效，取保守值 */
    const val DEFAULT_TTL_MS = 12L * 3600L * 1000L

    const val KIND_REFRESHABLE = 1
    const val KIND_CONSTANT = 2

    // ------------------------------------------------------------ 数据

    class Param(
        @JvmField var key: String,
        @JvmField var value: String,
        @JvmField var kind: Int,
        @JvmField var ttlMs: Long,
        @JvmField var fetchedAt: Long,
        @JvmField var sourceApi: String,
        @JvmField var sourceField: String,
        @JvmField var note: String,
        /** 取到这个值的第几轮（用于「改了没有」的判断） */
        @JvmField var gen: Int,
        /** 最近检查时间；fetchedAt 为参数来源时间。 */
        @JvmField var checkedAt: Long = 0L,
    ) {
        fun expired(now: Long): Boolean = ttlMs > 0 && fetchedAt > 0 && now - fetchedAt > ttlMs

        fun remainingMs(now: Long): Long =
            if (ttlMs <= 0 || fetchedAt <= 0) -1 else maxOf(0L, ttlMs - (now - fetchedAt))
    }

    // ------------------------------------------------------------ 连接

    @Volatile
    private var db: SQLiteDatabase? = null

    private fun open(): SQLiteDatabase? {
        val cur = db
        if (cur != null && cur.isOpen) return cur
        synchronized(ParamStore::class.java) {
            db?.let { if (it.isOpen) return it }
            try {
                val c: Context = Protocol.State.app ?: return null
                val f = c.getDatabasePath(DB_NAME)
                val d = SQLiteDatabase.openOrCreateDatabase(f, null)
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS params(" +
                        "key TEXT PRIMARY KEY," +
                        "value BLOB," +
                        "compressed INTEGER NOT NULL DEFAULT 0," +
                        "kind INTEGER NOT NULL DEFAULT 1," +
                        "ttl INTEGER NOT NULL DEFAULT 0," +
                        "fetched_at INTEGER NOT NULL DEFAULT 0," +
                        "source_api TEXT," +
                        "source_field TEXT," +
                        "note TEXT," +
                        "gen INTEGER NOT NULL DEFAULT 0," +
                        "updated_at INTEGER NOT NULL DEFAULT 0)",
                )
                db = d
                return d
            } catch (t: Throwable) {
                return null
            }
        }
    }

    /** 面板/调试：库文件信息 */
    @JvmStatic
    fun fileStat(): String {
        val c = Protocol.State.app ?: return "无上下文"
        return try {
            val f = c.getDatabasePath(DB_NAME)
            if (!f.exists()) "未创建" else
                "%.1f KB / %d 条".format(f.length() / 1024.0, count())
        } catch (t: Throwable) {
            "读取失败"
        }
    }

    @JvmStatic
    fun count(): Int {
        val d = open() ?: return 0
        var cur: android.database.Cursor? = null
        return try {
            cur = d.rawQuery("SELECT COUNT(*) FROM params", null)
            if (cur.moveToFirst()) cur.getInt(0) else 0
        } catch (t: Throwable) {
            0
        } finally {
            try {
                cur?.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------ 压缩

    private fun pack(raw: String): Pair<ByteArray, Int> {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        if (bytes.size < COMPRESS_MIN) return Pair(bytes, 0)
        return try {
            val bos = ByteArrayOutputStream(bytes.size)
            val gz = GZIPOutputStream(bos)
            gz.write(bytes)
            gz.finish()
            gz.close()
            val z = bos.toByteArray()
            // 压不小就不压（短字符串常见）
            if (z.size < bytes.size) Pair(z, 1) else Pair(bytes, 0)
        } catch (t: Throwable) {
            Pair(bytes, 0)
        }
    }

    private fun unpack(blob: ByteArray?, compressed: Boolean): String {
        if (blob == null) return ""
        if (!compressed) return String(blob, Charsets.UTF_8)
        return try {
            val inS = GZIPInputStream(ByteArrayInputStream(blob))
            val out = ByteArrayOutputStream(blob.size * 3)
            val buf = ByteArray(4096)
            while (true) {
                val n = inS.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            inS.close()
            String(out.toByteArray(), Charsets.UTF_8)
        } catch (t: Throwable) {
            ""
        }
    }

    // ------------------------------------------------------------ 读

    @JvmStatic
    fun get(c: Context?, key: String): Param? {
        if (key.isEmpty()) return null
        val d = open() ?: return null
        var cur: android.database.Cursor? = null
        try {
            cur = d.rawQuery(
                "SELECT key,value,compressed,kind,ttl,fetched_at,source_api,source_field,note,gen,updated_at" +
                    " FROM params WHERE key=?",
                arrayOf(key),
            )
            if (!cur.moveToFirst()) return null
            return Param(
                cur.getString(0),
                unpack(cur.getBlob(1), cur.getInt(2) == 1),
                cur.getInt(3),
                cur.getLong(4),
                cur.getLong(5),
                cur.getString(6) ?: "",
                cur.getString(7) ?: "",
                cur.getString(8) ?: "",
                cur.getInt(9),
                cur.getLong(10),
            )
        } catch (t: Throwable) {
            return null
        } finally {
            try {
                cur?.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    /** 原始值（可能已过期） */
    @JvmStatic
    fun value(c: Context?, key: String): String {
        val p = get(c, key) ?: return ""
        return p.value
    }

    /**
     * **未过期**的值；过期或没有都返回空串。
     *
     * 业务链用这个而不是 [value]：拿过期值去调接口会被服务端拒
     * （asac 过期回 `FAIL_BIZ_COMMON_PARAM_ILLEGAL`，和「没带」一模一样），
     * 那样反而会污染任务黑名单计数。
     */
    @JvmStatic
    fun fresh(c: Context?, key: String): String {
        val p = get(c, key) ?: return ""
        if (p.value.isEmpty()) return ""
        if (p.expired(System.currentTimeMillis())) return ""
        return p.value
    }

    // ------------------------------------------------------------ 写

    /**
     * 写入一个值（重置 fetchedAt，gen + 1）。
     *
     * 值没变就不写 —— 每轮都写同一份 asac 会让 updated_at 一直变，
     * 面板上「什么时候取到的」就失去意义了。
     */
    @JvmStatic
    fun put(c: Context?, key: String, value: String) {
        if (key.isEmpty() || value.isEmpty()) return
        val d = open() ?: return
        try {
            val old = get(c, key)
            if (old != null && old.value == value) return
            writeRow(d, key, value, old)
        } catch (ignored: Throwable) {
        }
    }

    /** 强制写入（值相同也更新 fetchedAt，用于「刷新成功」确认） */
    @JvmStatic
    fun touch(c: Context?, key: String, value: String) {
        if (key.isEmpty() || value.isEmpty()) return
        val d = open() ?: return
        try {
            writeRow(d, key, value, get(c, key))
        } catch (ignored: Throwable) {
        }
    }

    private fun writeRow(d: SQLiteDatabase, key: String, value: String, old: Param?) {
        val (blob, comp) = pack(value)
        val now = System.currentTimeMillis()
        val st = d.compileStatement(
            "INSERT OR REPLACE INTO params" +
                "(key,value,compressed,kind,ttl,fetched_at,source_api,source_field,note,gen,updated_at)" +
                " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        )
        try {
            st.bindString(1, key)
            st.bindBlob(2, blob)
            st.bindLong(3, comp.toLong())
            st.bindLong(4, (old?.kind ?: KIND_REFRESHABLE).toLong())
            st.bindLong(5, old?.ttlMs ?: DEFAULT_TTL_MS)
            st.bindLong(6, now)
            st.bindString(7, old?.sourceApi ?: "")
            st.bindString(8, old?.sourceField ?: "")
            st.bindString(9, old?.note ?: "")
            st.bindLong(10, ((old?.gen ?: 0) + 1).toLong())
            st.bindLong(11, now)
            st.executeInsert()
        } finally {
            try {
                st.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    /** 原子保存业务作用域、来源和检查时间，沿用现有 SQLite/gzip。 */
    @JvmStatic
    fun record(
        c: Context?, key: String, value: String, source: String, field: String, note: String,
        fetchedAt: Long = System.currentTimeMillis(),
    ) {
        if (key.isEmpty() || value.isEmpty()) return
        val d = open() ?: return
        synchronized(ParamStore::class.java) {
            try {
                val old = get(c, key)
                val (blob, comp) = pack(value)
                val st = d.compileStatement(
                    "INSERT OR REPLACE INTO params" +
                        "(key,value,compressed,kind,ttl,fetched_at,source_api,source_field,note,gen,updated_at)" +
                        " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                )
                try {
                    st.bindString(1, key)
                    st.bindBlob(2, blob)
                    st.bindLong(3, comp.toLong())
                    st.bindLong(4, KIND_REFRESHABLE.toLong())
                    st.bindLong(5, DEFAULT_TTL_MS)
                    st.bindLong(6, fetchedAt)
                    st.bindString(7, source)
                    st.bindString(8, field)
                    st.bindString(9, note)
                    st.bindLong(10, ((old?.gen ?: 0) + if (old?.value == value) 0 else 1).toLong())
                    st.bindLong(11, System.currentTimeMillis())
                    st.executeInsert()
                } finally { st.close() }
            } catch (ignored: Throwable) {}
        }
    }

    /** 登记档案元信息（不动已有值） */
    @JvmStatic
    fun register(
        c: Context?,
        key: String,
        kind: Int,
        ttlMs: Long,
        sourceApi: String,
        sourceField: String,
        note: String,
    ) {
        if (key.isEmpty()) return
        val d = open() ?: return
        try {
            val old = get(c, key)
            val now = System.currentTimeMillis()
            val (blob, comp) = pack(old?.value ?: "")
            val st = d.compileStatement(
                "INSERT OR REPLACE INTO params" +
                    "(key,value,compressed,kind,ttl,fetched_at,source_api,source_field,note,gen,updated_at)" +
                    " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            )
            try {
                st.bindString(1, key)
                st.bindBlob(2, blob)
                st.bindLong(3, comp.toLong())
                st.bindLong(4, kind.toLong())
                st.bindLong(5, ttlMs)
                st.bindLong(6, old?.fetchedAt ?: 0L)
                st.bindString(7, sourceApi)
                st.bindString(8, sourceField)
                st.bindString(9, note)
                st.bindLong(10, (old?.gen ?: 0).toLong())
                st.bindLong(11, now)
                st.executeInsert()
            } finally {
                try {
                    st.close()
                } catch (ignored: Throwable) {
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    @JvmStatic
    fun remove(c: Context?, key: String) {
        val d = open() ?: return
        try {
            d.execSQL("DELETE FROM params WHERE key=?", arrayOf<Any>(key))
        } catch (ignored: Throwable) {
        }
    }

    /** 清空（面板「重置参数」；下次需要会重新取） */
    @JvmStatic
    fun clearAll() {
        val d = open() ?: return
        try {
            d.execSQL("DELETE FROM params")
            d.execSQL("PRAGMA incremental_vacuum")
        } catch (ignored: Throwable) {
        }
    }

    // ------------------------------------------------------------ 全量登记

    /**
     * 把已知参数档案写进库（幂等，只动元信息，不动值）。
     *
     * 每次启动调一次。[ApiInit] 会保证它在合适时机跑。
     */
    @JvmStatic
    fun ensureDefaults(c: Context?) {
        if (c == null) return
        // 历史全局 asac 不迁入业务作用域。BusinessParams 按 api/scene 独立登记。
        // ---- A 类：固定常量，不进库（列在这里只作说明） ----
        //   fishAdCode / gps：公开版不预置维护者位置，默认空值
        //   sceneId 496 / playId 101,110 / awardIndexList "1"
        // ---- C 类：一次性，绝不存 ----
        //   fromToken（同任务实测 26 个不同值）
        //   idempotentKey（毫秒时间戳）
        //   bubbleId / secondScene（成对，每次刷新都变）
        //   locationBefore（每掷一次就变）
        //   deliveryId / taskInstanceId（每轮从列表取）
    }

    // ------------------------------------------------------------ 面板

    @JvmStatic
    fun summary(c: Context?, key: String): String {
        val p = get(c, key) ?: return "未登记"
        if (p.value.isEmpty()) return "无值"
        if (p.fetchedAt <= 0) return "无值"
        if (p.ttlMs <= 0) return "已缓存（不过期）"
        val left = p.remainingMs(System.currentTimeMillis())
        if (left <= 0) return "已过期"
        val h = left / 3600000L
        val m = (left % 3600000L) / 60000L
        return if (h > 0) "${h}小时${m}分后过期" else "${m}分钟后过期"
    }

    @JvmStatic
    fun lines(c: Context?): ArrayList<String> {
        val out = ArrayList<String>()
        val d = open() ?: return out
        var cur: android.database.Cursor? = null
        try {
            cur = d.rawQuery(
                "SELECT key,value,compressed,fetched_at FROM params ORDER BY key", null,
            )
            while (cur.moveToNext()) {
                val k = cur.getString(0) ?: continue
                val v = unpack(cur.getBlob(1), cur.getInt(2) == 1)
                val shown = if (v.isEmpty()) "（空）" else
                    if (k.contains("asac", true)) "已保存（隐藏）" else
                        if (v.length > 14) v.substring(0, 14) + "…" else v
                out.add(k + "　" + shown + "　" + summary(c, k))
            }
        } catch (ignored: Throwable) {
        } finally {
            try {
                cur?.close()
            } catch (ignored: Throwable) {
            }
        }
        return out
    }
}
