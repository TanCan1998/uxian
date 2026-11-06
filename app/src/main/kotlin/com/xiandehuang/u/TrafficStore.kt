package com.xiandehuang.u

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 完整抓包存储：SQLite + 逐条 gzip 无损压缩。
 *
 * 为什么换掉 JSONL 纯文本：
 *   1. 单条响应经常几百 KB（nextfresh 实测 614KB），纯文本 4MB 一个文件
 *      几秒就写满轮转，回头查历史全是空的。
 *   2. 纯文本没法按 api / 时间检索，只能整段 grep。
 *   3. gzip 对 JSON 文本典型能压 5~10 倍，且完全无损（解压即原样）。
 *
 * 设计要点（踩过的坑）：
 *   - **写盘必须在后台线程 + 批量事务**。record() 是从
 *     MtopResponse.getDataJsonObject() 的钩子里调的，那是主线程；
 *     直接同步 insert 会把列表滑动拖到掉帧。
 *   - **队列满了丢新不丢旧**，并计数 dropped，绝不阻塞调用方。
 *   - **容量上限按正文总字节算**，超了删最旧的 30% 再增量 VACUUM，
 *     不然数据库只涨不缩。
 *   - auto_vacuum 必须在建表前设成 INCREMENTAL，事后改无效。
 */
object TrafficStore {

    private const val DB_NAME = "traffic.db"

    /**
     * 正文总字节上限（压缩后）。超了自动裁旧。
     *
     * 32MB 的由来：上限算的是 `SUM(LENGTH(body))`，即**压缩后**字节，
     * 而磁盘实际占用还要加上索引（idx_fp/idx_api/idx_ts 三个）、
     * 每行的列开销和未压缩的小正文，实测正文 2.4MB 时文件是 3.8MB（~1.6x）。
     * 加上 SQLite 的页碎片，磁盘峰值约 1.8x。96MB 的话峰值接近 180MB，
     * 对一个注入模块来说太贪；32MB 对应峰值 ~58MB，已经能存上千条大响应。
     */
    private const val MAX_BODY_BYTES = 32L * 1024 * 1024

    /** 单个接口保留的最近记录条数：高频埋点接口一次会话就能刷几千条 */
    private const val MAX_PER_API = 120

    /**
     * 清理的最小间隔：10 分钟。
     *
     * 之前只在「累计写入 256 条」时检查一次容量，问题是：
     *   - 大响应场景（nextfresh 单条 750KB）写 30 条就能顶到上限，
     *     但攒不到 256 条就一直不检查；
     *   - 反过来高频小请求（device.report）几秒钟就 256 条，prune 空跑一次扫全表。
     * 改成「按时间 + 按条数，谁先到算谁」，既不会漏也不会空跑。
     */
    private const val PRUNE_MIN_INTERVAL_MS = 10L * 60 * 1000

    private const val PRUNE_ROW_HINT = 256

    /** 队列容量：写不过来时丢弃，绝不拖慢宿主 */
    private const val QUEUE_CAP = 8192

    /** 小于这个长度的正文不压缩（gzip 头尾开销不划算） */
    private const val MIN_COMPRESS = 256

    @Volatile
    private var db: SQLiteDatabase? = null

    /**
     * 是否记录。默认开（Prefs.TRAFFIC 默认 true）。
     * 注意：不能只依赖这个静态值 —— 它在进程重启后回到默认，
     * 而面板里关掉是写进 SP 的，所以判断要 combined() 一下。
     */
    @Volatile
    private var enabled = true

    /** 面板里显式设过值后，以面板为准 */
    @Volatile
    private var userSet = false

    @Volatile
    private var workerStarted = false

    @Volatile
    private var dropped = 0L

    private val queue = LinkedBlockingQueue<Row>(QUEUE_CAP)

    private class Row {
        var ts: Long = 0
        var fp: String = ""      // 内容指纹（dir|api|ver|method|body 的摘要）
        var dir: String = ""
        var api: String = ""
        var ver: String = ""
        var method: String? = null
        var headers: String? = null
        var body: String = ""
        /** 调用层证据：层|类名|方法名，见 CallerTrace */
        var caller: String? = null
        /**
         * 谁发起的这次请求：host = 宿主 App 自己，ours = 我方调试通道/功能脚本。
         * 不加这个字段的话，我方探测记录会和宿主行为混在一起，
         * 拿库里的 ver/method 当基准就会被自己的脏数据带偏（实测踩过三轮）。
         */
        var origin: String = "host"
        var temporarySession: String? = null
        var temporaryOnly = false
        var requestId: String? = null
        var metadata: String? = null
        var rawBytes: ByteArray? = null
    }

    @JvmStatic
    fun enabled(on: Boolean) {
        enabled = on
        userSet = true
    }

    /**
     * 当前是否记录。
     * 优先用面板显式设置（userSet），否则读 SP 文件（跨进程实时，
     * 且能拿到 Prefs.defOf 的默认值），最后才用静态默认。
     */
    @JvmStatic
    fun enabled(): Boolean {
        // 抓包常驻（2026-10-31）：业务链依赖抓包库取参数，不能让它被关掉。
        // enabled(on) 保留给「临时静默」用，但不会再由面板开关或调试模式触发。
        return true
    }

    // ------------------------------------------------------------ 写入

    /**
     * 记一条。**永不阻塞、永不抛异常** —— 调用点在宿主主线程的钩子里。
     *
     * @param dir  "REQ" / "RESP"
     * @param body 原文（未压缩）
     */
    @JvmStatic
    fun record(
        dir: String?,
        api: String?,
        ver: String?,
        method: String?,
        headers: String?,
        body: String?,
    ) {
        record(dir, api, ver, method, headers, body, null)
    }

    /**
     * 带调用方信息的版本。
     *
     * @param caller 由 CallerTrace.capture() 编码，格式「层|类名|方法名」。
     *               只有请求（REQ）需要，响应由同一个 api 关联，不用重复存。
     */
    @JvmStatic
    fun record(
        dir: String?,
        api: String?,
        ver: String?,
        method: String?,
        headers: String?,
        body: String?,
        caller: String?,
    ) {
        if (!enabled) return
        try {
            val r = Row()
            r.ts = System.currentTimeMillis()
            r.dir = dir ?: ""
            r.api = api ?: ""
            r.ver = ver ?: ""
            r.method = method
            r.headers = headers
            r.body = body ?: ""
            r.caller = caller
            // 来源在**入库这一刻**取：hook 跑在发请求的线程上，ThreadLocal 还在。
            // 放到写库线程去取就永远是 host 了（写库是异步的）。
            r.origin = Protocol.State.origin()
            // Dedicated callback capture owns REQ/RESP when installed; retain NOTE evidence.
            r.temporarySession = if (TempCapture.ready && (r.dir == "REQ" || r.dir == "RESP")) null else TempTraffic.session()
            // 指纹 = dir + api + ver + method + body 的 SHA-256。
            // 用完整 body 参与，避免"同接口不同参数"被误判成重复。
            r.fp = fingerprint(r.dir, r.api, r.ver, r.method, r.body, r.origin)
            if (!queue.offer(r)) {
                dropped++
            }
            startWorker()
        } catch (ignored: Throwable) {
        }
    }

    /** Uses the existing bounded worker; byte decoding and disk writes stay off host callbacks. */
    fun recordTemporary(session: String, requestId: String, origin: String, dir: String,
        api: String, ver: String, method: String?, headers: String?, body: String?,
        rawBytes: ByteArray?, metadata: String) {
        try {
            val row = Row()
            row.ts = System.currentTimeMillis()
            row.dir = dir
            row.api = api
            row.ver = ver
            row.method = method
            row.headers = headers
            row.body = body ?: ""
            row.origin = origin
            row.temporarySession = session
            row.temporaryOnly = true
            row.requestId = requestId
            row.metadata = metadata
            row.rawBytes = rawBytes
            if (!queue.offer(row)) dropped++
            startWorker()
        } catch (_: Throwable) { }
    }

    @JvmStatic
    fun dropped(): Long = dropped

    /** 内容指纹：同一请求/响应反复出现时用它合并 */
    /**
     * 计算指纹前先抹掉「每次都变但没意义」的字段。
     *
     * 实测依据（导出 traffic.db 对比同 api 的多行）：
     *   mtop.taobao.idle.rule.config 的 8 行 body 长度完全相同（2317），
     *   唯一差别是结尾的 serverTime：
     *     ..."serverTime":"2026-10-29 09:49:03"
     *     ..."serverTime":"2026-10-29 09:49:59"
     *   99.7% 相同的内容存成了两行 —— 这就是「每条只有 hits=2」的原因。
     *
     * 归一化的都是服务端回显的易变值，去掉不影响「同一份数据」的判断。
     */
    private fun normalize(body: String): String {
        if (body.isEmpty()) return body
        var s = body
        // 服务端时间戳：ISO / 带空格 / 纯毫秒
        s = s.replace(SERVER_TIME, "\"serverTime\":\"*\"")
        s = s.replace(TIMESTAMP_FIELD, "\"timestamp\":*")
        s = s.replace(TS_FIELD, "\"ts\":*")
        s = s.replace(REQ_TIME, "\"reqTime\":*")
        // 请求/响应链路 id、序列号
        s = s.replace(TRACE_ID, "\"traceId\":\"*\"")
        s = s.replace(REQ_ID, "\"reqId\":\"*\"")
        s = s.replace(SEQ_FIELD, "\"seq\":\"*\"")
        // 响应里的动态计数（广告曝光、埋点批次号等）
        s = s.replace(EXPECT_TIME, "\"expectTime\":*")

        // ---- 下面是 NOTE 摘要里的易变内容（实测占 258/416 条）----
        // builder: 行形如
        //   ...listener=...DefaultCallBackForRB@e296c73 |
        //   reqStartTime=1793251482626 | seqNo=MB1.MTOP1 | ...
        // 这三个每次都不一样，不抹掉的话每条 NOTE 都成了独立行。
        s = s.replace(OBJ_HASH, "@*")
        s = s.replace(TIME_KV, "$1*")
        s = s.replace(SEQ_NO, "seqNo=*")
        return s
    }

    private val SERVER_TIME = Regex("\"serverTime\"\\s*:\\s*\"[^\"]*\"")
    private val TIMESTAMP_FIELD = Regex("\"timestamp\"\\s*:\\s*\\d+")
    private val TS_FIELD = Regex("\"ts\"\\s*:\\s*\\d+")
    private val REQ_TIME = Regex("\"reqTime\"\\s*:\\s*\\d+")
    private val TRACE_ID = Regex("\"traceId\"\\s*:\\s*\"[^\"]*\"")
    private val REQ_ID = Regex("\"reqId\"\\s*:\\s*\"[^\"]*\"")
    private val SEQ_FIELD = Regex("\"seq\"\\s*:\\s*\"[^\"]*\"")
    private val EXPECT_TIME = Regex("\"expectTime\"\\s*:\\s*\\d+")

    /** 对象哈希：`...DefaultCallBackForRB@e296c73` */
    private val OBJ_HASH = Regex("@[0-9a-f]{6,}")
    /** `reqStartTime=1793251482626` / `sendStartTime=` / `onBgFinishTime=` 这类毫秒值 */
    private val TIME_KV = Regex("(\\w*(?:StartTime|FinishTime|ReadyTime|EndTime)=\\d+)")
    /** `seqNo=MB1.MTOP1` */
    private val SEQ_NO = Regex("seqNo=[^\\s|]+")

    private fun fingerprint(
        dir: String,
        api: String,
        ver: String,
        method: String?,
        body: String,
        origin: String = "host",
    ): String =
        try {
            val md = MessageDigest.getInstance("SHA-256")
            val sb = StringBuilder()
            // origin 必须参与指纹：否则宿主的请求和我方的探测请求（同样的
            // api/ver/body）会被合并成一条 hits=2，两个来源彻底分不开。
            sb.append(dir).append('\u0001').append(api).append('\u0001')
                .append(ver).append('\u0001').append(method).append('\u0001')
                .append(origin).append('\u0001')
                .append(normalize(body))
            val d = md.digest(sb.toString().toByteArray(Charsets.UTF_8))
            val hex = StringBuilder(d.size * 2)
            for (b in d) hex.append(String.format("%02x", b))
            hex.toString()
        } catch (t: Throwable) {
            // 摘要不可用时退回长度+hashCode，仍然能挡住绝大多数重复
            "h$dir$api$ver$method$origin${body.length}${body.hashCode()}"
        }

    private fun startWorker() {
        if (workerStarted) return
        synchronized(TrafficStore::class.java) {
            if (workerStarted) return
            workerStarted = true
            val t = Thread(Runnable { loop() }, "ux-traffic")
            t.isDaemon = true
            t.priority = Thread.MIN_PRIORITY
            t.start()
        }
    }

    private fun loop() {
        val batch = ArrayList<Row>(128)
        var sincePrune = 0
        var lastPrune = 0L
        while (true) {
            try {
                // 空闲时每 2 分钟醒一次检查清理周期，保证「一段时间没写入，
                // 到期后也能把空闲页收回来」；有数据时 poll 立刻返回。
                val first = queue.poll(2, TimeUnit.MINUTES) ?: run {
                    maybePrune(0, lastPrune)?.let { lastPrune = it }
                    continue
                }
                batch.clear()
                batch.add(first)
                queue.drainTo(batch, 199)
                for (row in batch) {
                    val session = row.temporarySession ?: continue
                    val body = row.rawBytes?.toString(Charsets.UTF_8) ?: row.body
                    TempTraffic.write(session, row.ts, row.dir, row.api, row.ver, row.method,
                        row.headers, body, row.caller, row.origin, row.requestId, row.metadata, row.rawBytes)
                }
                // Raw completion envelopes must not change the established regular DB format.
                batch.removeAll { it.temporaryOnly }
                if (batch.isNotEmpty() && writeBatch(batch)) {
                    sincePrune += batch.size
                    if (sincePrune >= PRUNE_ROW_HINT) {
                        sincePrune = 0
                        lastPrune = maybePrune(PRUNE_ROW_HINT, lastPrune) ?: lastPrune
                    }
                }
            } catch (t: Throwable) {
                try {
                    Thread.sleep(500)
                } catch (ie: InterruptedException) {
                    return
                }
            }
        }
    }

    /**
     * 定期容量检查 + 空闲页回收。
     *
     * 两条触发线（谁先到算谁）：
     *   - 写入条数达标（rows >= PRUNE_ROW_HINT）
     *   - 距离上次检查超过 PRUNE_MIN_INTERVAL_MS（**空闲期也会触发**，
     *     否则删完数据后一旦没有新写入，空闲页就永远留在文件里）
     *
     * @return 本次实际执行的时间戳；被间隔挡住时返回 null
     */
    private fun maybePrune(rows: Int, lastPrune: Long): Long? {
        val now = System.currentTimeMillis()
        val dueByTime = now - lastPrune >= PRUNE_MIN_INTERVAL_MS
        if (!dueByTime && rows < PRUNE_ROW_HINT) return null
        pruneIfNeeded()
        return now
    }

    /**
     * 批量写入，**同一指纹只留一行**。
     *
     * 去重策略（实测依据：4314 条里 4128 条落在重复组，1106 条连时间戳都相同）：
     *   - 指纹 = dir|api|ver|method|body 的 SHA-256
     *   - 命中已有指纹 → 只更新 hits+1、last_ts，不再插新行
     *   - 不设时间窗：轮询类接口（如 device.report 82 次）合并后仍然只有一行，
     *     看 hits 就知道它被调了多少次
     *   - 保留首次 ts 和 last_ts，能看出"这个请求从什么时候开始、持续到什么时候"
     *
     * 为什么不在内存里做 LRU 缓存：进程随时可能被杀，缓存丢了就会重复插入。
     * 直接查库（有 idx_fp 索引）虽然多一次读，但语义准确、跨进程一致。
     */
    private fun writeBatch(rows: List<Row>): Boolean {
        val d = open() ?: return false
        var ins: SQLiteStatement? = null
        var upd: SQLiteStatement? = null
        var cur: Cursor? = null
        try {
            ins = d.compileStatement(
                "INSERT INTO traffic(ts,last_ts,hits,fp,dir,api,ver,method,headers," +
                    "body,raw_len,compressed,caller,origin) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            )
            upd = d.compileStatement("UPDATE traffic SET hits=hits+1, last_ts=? WHERE id=?")
            d.beginTransaction()
            for (r in rows) {
                // 先找同指纹的最近一条
                var existId = 0L
                cur = d.rawQuery(
                    "SELECT id FROM traffic WHERE fp=? ORDER BY id DESC LIMIT 1",
                    arrayOf(r.fp),
                )
                if (cur != null) {
                    if (cur.moveToFirst()) existId = cur.getLong(0)
                    cur.close()
                    cur = null
                }
                if (existId > 0) {
                    upd.bindLong(1, r.ts)
                    upd.bindLong(2, existId)
                    upd.executeUpdateDelete()
                    continue
                }
                val raw = r.body.toByteArray(Charsets.UTF_8)
                var compressed = 0
                var payload = raw
                if (raw.size >= MIN_COMPRESS) {
                    val gz = gzip(raw)
                    if (gz != null && gz.size < raw.size - 16) {
                        payload = gz
                        compressed = 1
                    }
                }
                ins.clearBindings()
                ins.bindLong(1, r.ts)
                ins.bindLong(2, r.ts)
                ins.bindLong(3, 1)
                ins.bindString(4, r.fp)
                ins.bindString(5, r.dir)
                ins.bindString(6, r.api)
                ins.bindString(7, r.ver)
                if (r.method == null) ins.bindNull(8) else ins.bindString(8, r.method!!)
                if (r.headers == null) ins.bindNull(9) else ins.bindString(9, r.headers!!)
                ins.bindBlob(10, payload)
                ins.bindLong(11, raw.size.toLong())
                ins.bindLong(12, compressed.toLong())
                if (r.caller == null) ins.bindNull(13) else ins.bindString(13, r.caller!!)
                ins.bindString(14, r.origin)
                ins.executeInsert()
            }
            d.setTransactionSuccessful()
            return true
        } catch (t: Throwable) {
            return false
        } finally {
            try { cur?.close() } catch (ignored: Throwable) {}
            try { ins?.close() } catch (ignored: Throwable) {}
            try { upd?.close() } catch (ignored: Throwable) {}
            try { if (d.inTransaction()) d.endTransaction() } catch (ignored: Throwable) {}
        }
    }

    private fun open(): SQLiteDatabase? {
        val cur = db
        if (cur != null && cur.isOpen) return cur
        synchronized(TrafficStore::class.java) {
            db?.let { if (it.isOpen) return it }
            try {
                val c: Context = Protocol.State.app ?: return null
                val f = c.getDatabasePath(DB_NAME)
                val fresh = !f.exists()
                val d = SQLiteDatabase.openOrCreateDatabase(f, null)
                if (fresh) {
                    // 必须在建表前设置，建表后再改无效
                    d.execSQL("PRAGMA auto_vacuum = INCREMENTAL")
                }
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS traffic(" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "ts INTEGER NOT NULL," +          // 首次出现时间
                        "last_ts INTEGER NOT NULL," +     // 最近一次出现时间
                        "hits INTEGER NOT NULL DEFAULT 1," +  // 累计出现次数
                        "fp TEXT," +                      // 内容指纹，去重用
                        "dir TEXT," +
                        "api TEXT," +
                        "ver TEXT," +
                        "method TEXT," +
                        "headers TEXT," +
                        "body BLOB," +
                        "raw_len INTEGER," +
                        "compressed INTEGER," +
                        "caller TEXT," +
                        "origin TEXT)",
                )
                // ---- 接口元数据表 ----
                // 一个接口一行（不是一次请求一行）。存意图/分类/层/重放结论，
                // 以及「分析」阶段算出来的关联关系。
                // 单独成表的原因：traffic 是按请求存的、会被裁剪轮转，
                // 而接口的元信息是稳定的、需要长期保留、还要能被导入覆盖。
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS apis(" +
                        "api TEXT PRIMARY KEY," +
                        "intent TEXT," +        // 中文意图
                        "cat TEXT," +           // 分类
                        "layer TEXT," +         // 调用层
                        "replay TEXT," +        // yes / no / unk
                        "replay_why TEXT," +    // 不可重放的原因
                        "note TEXT," +          // 补充说明
                        "chain TEXT," +         // 最近一次的调用链
                        "req_n INTEGER," +      // 抓到的请求条数
                        "resp_n INTEGER," +     // 抓到的响应条数
                        "resp_len INTEGER," +   // 响应字节数
                        "first_ts INTEGER," +
                        "last_ts INTEGER," +
                        "analyzed_ts INTEGER)", // 上次跑关联分析的时间
                )
                // ---- 接口关联表 ----
                // from_api 的**请求参数** 与 to_api 的**响应字段** 存在对应关系。
                // 依据是「同一个值同时出现在两边」，见 Relation.kt 的判定。
                d.execSQL(
                    "CREATE TABLE IF NOT EXISTS relations(" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "from_api TEXT NOT NULL," +   // 谁的需求
                        "to_api TEXT NOT NULL," +     // 谁能提供
                        "field TEXT," +               // 关联的字段/值名
                        "kind TEXT," +                // param→resp / resp→resp / shared
                        "score INTEGER," +            // 0-100，越大越可信
                        "samples INTEGER," +          // 支撑样本数
                        "evidence TEXT," +            // 具体值样例（脱敏后）
                        "ts INTEGER)",
                )
                d.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_rel ON relations" +
                        "(from_api,to_api,field,kind)",
                )
                d.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_rel_from ON relations(from_api)",
                )

                // 旧版本（无 fp/last_ts/hits 列）直接重建：
                // 抓包数据是可再生的一次性数据，不值得为它写迁移逻辑，
                // 但必须先检测，否则 INSERT 列数不匹配会一直失败。
                migrateIfNeeded(d)
                d.execSQL("CREATE INDEX IF NOT EXISTS idx_api ON traffic(api)")
                d.execSQL("CREATE INDEX IF NOT EXISTS idx_ts ON traffic(ts)")
                // 指纹索引：去重查询走它，唯一性由应用层保证（不用 UNIQUE，
                // 因为轮转删除后要允许同指纹重新插入）
                d.execSQL("CREATE INDEX IF NOT EXISTS idx_fp ON traffic(fp)")
                d.execSQL("PRAGMA synchronous = NORMAL")
                db = d
                return d
            } catch (t: Throwable) {
                return null
            }
        }
    }

    // ------------------------------------------------------------ 压缩

    /** 老表结构（缺 fp/last_ts/hits）→ 重建。抓包数据可再生，不做逐行迁移。 */
    private fun migrateIfNeeded(d: SQLiteDatabase) {
        var needRebuild = false
        var c: Cursor? = null
        try {
            c = d.rawQuery("PRAGMA table_info(traffic)", null)
            val cols = HashSet<String>()
            while (c != null && c.moveToNext()) cols.add(c.getString(1))
            // 缺 fp（最老的版本）/ caller / origin 都重建。
            // 抓包是可再生数据，不做 ALTER TABLE 迁移，直接丢掉重来。
            if (cols.isNotEmpty() &&
                (!cols.contains("fp") || !cols.contains("caller") || !cols.contains("origin"))
            ) {
                needRebuild = true
            }
        } catch (ignored: Throwable) {
        } finally {
            try { c?.close() } catch (ignored: Throwable) {}
        }
        if (!needRebuild) return
        try {
            d.execSQL("DROP TABLE IF EXISTS traffic")
            d.execSQL(
                "CREATE TABLE traffic(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "ts INTEGER NOT NULL," +
                    "last_ts INTEGER NOT NULL," +
                    "hits INTEGER NOT NULL DEFAULT 1," +
                    "fp TEXT," +
                    "dir TEXT," +
                    "api TEXT," +
                    "ver TEXT," +
                    "method TEXT," +
                    "headers TEXT," +
                    "body BLOB," +
                    "raw_len INTEGER," +
                    "compressed INTEGER," +
                    "caller TEXT," +
                        "origin TEXT)",
            )
            Protocol.State.logLine("抓包库结构升级：已按去重机制重建")
            // DROP TABLE 把整张表的数据页丢进 freelist，不回收的话文件
            // 会一直停在旧大小上（实测留下过 11.9MB 空闲页 / 75% 页占比）。
            reclaimSpace(d)
        } catch (ignored: Throwable) {
        }
    }

    private fun gzip(raw: ByteArray): ByteArray? {
        var gz: GZIPOutputStream? = null
        return try {
            val bos = ByteArrayOutputStream(maxOf(64, raw.size / 4))
            gz = GZIPOutputStream(bos, 8192)
            gz.write(raw)
            gz.finish()
            gz.flush()
            bos.toByteArray()
        } catch (t: Throwable) {
            null
        } finally {
            try { gz?.close() } catch (ignored: Throwable) {}
        }
    }

    private fun gunzip(data: ByteArray, compressed: Boolean): String {
        if (!compressed) {
            return try {
                String(data, Charsets.UTF_8)
            } catch (t: Throwable) {
                String(data)
            }
        }
        var input: GZIPInputStream? = null
        return try {
            input = GZIPInputStream(ByteArrayInputStream(data), 8192)
            val bos = ByteArrayOutputStream(maxOf(256, data.size * 4))
            val buf = ByteArray(8192)
            var n: Int
            while (true) {
                n = input.read(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
            }
            String(bos.toByteArray(), Charsets.UTF_8)
        } catch (t: Throwable) {
            ""
        } finally {
            try { input?.close() } catch (ignored: Throwable) {}
        }
    }

    // ------------------------------------------------------------ 查询

    /**
     * 查询抓包，返回 JSON 文本。
     *
     * @param api      非空则按 api 精确过滤（支持 "xxx" 前缀用 LIKE）
     * @param dir      "REQ"/"RESP"，空则不限
     * @param sinceMs  起始时间戳，0 不限
     * @param limit    条数上限（<=0 用默认 50，最大 500）
     * @param offsetId 从哪个 id 之后开始（分页游标），0 表示从头
     * @param withBody 是否带正文（列表场景不带，省流量）
     */
    @JvmStatic
    fun query(
        api: String?,
        dir: String?,
        sinceMs: Long,
        limit: Int,
        offsetId: Long,
        withBody: Boolean,
        origin: String? = null,
    ): String {
        val d = open() ?: return "{\"error\":\"db not ready\"}"
        val lim = if (limit <= 0) 50 else minOf(limit, 500)
        val where = StringBuilder(" WHERE 1=1")
        val args = ArrayList<String>()
        if (!api.isNullOrEmpty()) {
            if (api.endsWith("*")) {
                where.append(" AND api LIKE ?")
                args.add(api.substring(0, api.length - 1) + "%")
            } else {
                where.append(" AND api = ?")
                args.add(api)
            }
        }
        if (!dir.isNullOrEmpty()) {
            where.append(" AND dir = ?")
            args.add(dir)
        }
        // 来源过滤：host = 宿主 App 自己发的，ours = 我方调试通道发的。
        // 判断接口真实行为必须只看 host，否则会被自己的探测记录带偏。
        if (!origin.isNullOrEmpty()) {
            where.append(" AND (origin IS NULL OR origin = ?)")
            args.add(origin)
        }
        if (sinceMs > 0) {
            where.append(" AND ts >= ?")
            args.add(sinceMs.toString())
        }
        if (offsetId > 0) {
            where.append(" AND id > ?")
            args.add(offsetId.toString())
        }
        val out = StringBuilder()
        var c: Cursor? = null
        try {
            out.append("{\"rows\":[")
            c = d.rawQuery(
                "SELECT id,ts,dir,api,ver,method,headers,raw_len,compressed," +
                    "last_ts,hits,caller,origin" + (if (withBody) ",body" else "") + " FROM traffic" + where +
                    " ORDER BY id ASC LIMIT " + lim,
                args.toTypedArray(),
            )
            var first = true
            var lastId = 0L
            while (c != null && c.moveToNext()) {
                if (!first) out.append(',')
                first = false
                lastId = c.getLong(0)
                out.append("{\"id\":").append(lastId)
                    .append(",\"t\":\"").append(fmtTime(c.getLong(1))).append('"')
                    .append(",\"ts\":").append(c.getLong(1))
                    .append(",\"dir\":\"").append(esc(c.getString(2))).append('"')
                    .append(",\"api\":\"").append(esc(c.getString(3))).append('"')
                    .append(",\"ver\":\"").append(esc(c.getString(4))).append('"')
                val m = c.getString(5)
                if (m != null) out.append(",\"method\":\"").append(esc(m)).append('"')
                val h = c.getString(6)
                if (h != null) out.append(",\"headers\":").append(h)
                out.append(",\"rawLen\":").append(c.getLong(7))
                out.append(",\"lastTs\":").append(c.getLong(9))
                out.append(",\"lastT\":\"").append(fmtTime(c.getLong(9))).append('"')
                out.append(",\"hits\":").append(c.getLong(10))
                val cal = c.getString(11)
                if (cal != null) out.append(",\"caller\":\"").append(esc(cal)).append('"')
                val org = c.getString(12)
                out.append(",\"origin\":\"").append(esc(org ?: "host")).append('"')
                if (withBody) {
                    val blob = c.getBlob(13)
                    val text = gunzip(blob ?: ByteArray(0), c.getInt(8) == 1)
                    out.append(",\"body\":\"").append(esc(text)).append('"')
                }
                out.append('}')
            }
            out.append("],\"lastId\":").append(lastId)
                .append(",\"dropped\":").append(dropped)
                .append(",\"count\":").append(statCount(d))
                .append(",\"bytes\":").append(statBytes(d)).append('}')
        } catch (t: Throwable) {
            return "{\"error\":\"" + esc(t.toString()) + "\"}"
        } finally {
            try { c?.close() } catch (ignored: Throwable) {}
        }
        return out.toString()
    }

    @JvmStatic
    fun statCount(d: SQLiteDatabase): Long {
        var c: Cursor? = null
        try {
            c = d.rawQuery("SELECT COUNT(*) FROM traffic", null)
            if (c != null && c.moveToFirst()) return c.getLong(0)
        } catch (ignored: Throwable) {
        } finally {
            try { c?.close() } catch (ignored: Throwable) {}
        }
        return 0
    }

    @JvmStatic
    fun statBytes(d: SQLiteDatabase): Long {
        var c: Cursor? = null
        try {
            c = d.rawQuery("SELECT IFNULL(SUM(LENGTH(body)),0) FROM traffic", null)
            if (c != null && c.moveToFirst()) return c.getLong(0)
        } catch (ignored: Throwable) {
        } finally {
            try { c?.close() } catch (ignored: Throwable) {}
        }
        return 0
    }

    /**
     * 文件体积统计：文件 / 数据 / 空闲页 / 页占比。
     *
     * 只看 SUM(LENGTH(body)) 会严重低估磁盘占用 —— 空闲页不在这项里，
     * 却实实在在占着文件大小。所以要拿 page_count - freelist_count 才是
     * 真实数据页，两者一比就能看出「虚胖」。
     */
    @JvmStatic
    fun fileStat(d: SQLiteDatabase): String {
        var pageSize = 4096L
        var pageCount = 0L
        var freePages = 0L
        var cur: Cursor? = null
        try {
            cur = d.rawQuery("PRAGMA page_size", null)
            if (cur.moveToFirst()) pageSize = cur.getLong(0)
            cur.close()
            cur = d.rawQuery("PRAGMA page_count", null)
            if (cur.moveToFirst()) pageCount = cur.getLong(0)
            cur.close()
            cur = d.rawQuery("PRAGMA freelist_count", null)
            if (cur.moveToFirst()) freePages = cur.getLong(0)
        } catch (ignored: Throwable) {
        } finally {
            try { cur?.close() } catch (ignored: Throwable) {}
        }
        val fileKb = pageCount * pageSize / 1024
        val freeKb = freePages * pageSize / 1024
        val pct = if (pageCount > 0) freePages * 100 / pageCount else 0L
        return "文件 ${fileKb}KB / 正文 ${statBytes(d) / 1024}KB / " +
            "空闲 ${freeKb}KB(${pct}%) / ${statCount(d)} 行"
    }

    /**
     * 手动回收：`xysidecar:traffic:vacuum`。
     *
     * 自动清理是「10 分钟 + 256 条」双触发，但排查问题时会想立刻把
     * 一个刚测完的库压回去（导出前、对比体积时），这里给个手动口。
     */
    @JvmStatic
    fun vacuumNow(): String {
        val d = open() ?: return "数据库未就绪"
        return try {
            val before = fileStat(d)
            d.execSQL("VACUUM")
            "回收前：$before\n回收后：" + fileStat(d)
        } catch (t: Throwable) {
            "回收失败：$t"
        }
    }

    private fun fmtTime(ms: Long): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(ms))

    private fun esc(s: String?): String {
        if (s == null) return ""
        val b = StringBuilder(s.length + 16)
        for (ch in s) {
            when (ch) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else ->
                    if (ch.code < 0x20) b.append(String.format("\\u%04x", ch.code))
                    else b.append(ch)
            }
        }
        return b.toString()
    }

    // ------------------------------------------------------------ 面板辅助

    /** 总条数 */
    /** 给导出器用的只读句柄 */
    @JvmStatic
    fun openForRead(): SQLiteDatabase? = open()

    /**
     * 从抓包库取「每个接口最近一次真实使用的 HTTP 方法」。
     *
     * 用途：进程刚启动、hook 还没抓到任何请求时，回放需要知道该发 GET 还是 POST。
     * 没有这个回填，只能退回 mtop 默认的 GET —— 写接口发成 GET 一律被服务端拒
     * （实测森林 energy.issue / animal.open 抓包都是 POST）。
     */
    @JvmStatic
    fun methodsFromDb(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val d = open() ?: return out
        var cur: android.database.Cursor? = null
        try {
            // 只取 origin='host' 的记录：我方探测写进去的 ver/method 是"我想怎么发"，
            // 不是"宿主怎么发"，拿它当基准会自我循环（实测因此误判三轮）。
            cur = d.rawQuery(
                "SELECT api, method FROM traffic t WHERE dir='REQ'" +
                    " AND (origin IS NULL OR origin='host')" +
                    " AND method IS NOT NULL AND method != ''" +
                    " AND ts = (SELECT MAX(ts) FROM traffic WHERE api=t.api AND dir='REQ'" +
                    "           AND (origin IS NULL OR origin='host'))",
                null,
            )
            while (cur.moveToNext()) {
                val api = cur.getString(0) ?: continue
                val m = cur.getString(1) ?: continue
                if (api.isNotEmpty() && m.isNotEmpty()) out[api] = m.uppercase()
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

    /** 解压单条正文（compressed=1 时是 gzip） */
    @JvmStatic
    fun decompress(blob: ByteArray?, compressed: Boolean): String =
        if (blob == null) "" else gunzip(blob, compressed)

    /**
     * 从抓包库回填某个 api 的**请求头**（宿主真发过的那份）。
     *
     * ## 为什么需要
     *
     * `Protocol.State.headersOf(api)` 只读内存缓存，而内存缓存靠
     * `hookFinalRequest` 现抓 —— 一个接口只要宿主这次进程里还没发过，
     * 就永远拿不到 header。调试通道（`xysidecar:mtop`）因此对
     * 「宿主从不调用、只有我们想试」的接口必然缺 header：
     *  - `uploadOliverTaskTrigger`（闲鱼客户端根本不调它，只有 H5 会调）
     *  - `getolivertaskbenefit` 带 `referer: ssr.m.goofish.com/...`，
     *    缺了它服务端按 H5 容器会话态判定，回 `FAIL_BIZ_COMMON_PARAM_ILLEGAL`
     *
     * 结果就是「调一次试一次都要装机」，而装机一轮 2 分钟。
     * 从抓包库兜底之后，同一台设备上试参数不再需要编译。
     *
     * ## 合并而不是取最新一条
     *
     * 宿主对不同接口会带不同的头子集：有的只有 `umid`，有的多一个 `referer`。
     * 只取最新一条会丢掉更早那条里的关键字段（实测 `asac` 就只出现在
     * 部分记录里）。所以按时间**从旧到新**合并，新的覆盖旧的。
     *
     * 只采 `origin='host'`：我方探测写进去的头是「我想怎么发」，不是宿主真值，
     * 拿它当基准会自我循环（method 那边踩过，误判三轮）。
     */
    @JvmStatic
    fun headersFromDb(api: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (api.isEmpty()) return out
        val d = open() ?: return out
        var cur: android.database.Cursor? = null
        try {
            // 从旧到新遍历，后来的 putAll 覆盖先前的
            cur = d.rawQuery(
                "SELECT headers FROM traffic" +
                    " WHERE api = ? AND dir='REQ'" +
                    " AND (origin IS NULL OR origin='host')" +
                    " AND headers IS NOT NULL AND headers != ''" +
                    " ORDER BY ts ASC LIMIT 60",
                arrayOf(api),
            )
            while (cur.moveToNext()) {
                val raw = cur.getString(0) ?: continue
                if (raw.isEmpty()) continue
                try {
                    val o = JSONObject(raw)
                    val keys = o.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = o.optString(k, "")
                        if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
                    }
                } catch (ignored: Throwable) {
                }
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

    @JvmStatic
    fun count(): Long = open()?.let { statCount(it) } ?: 0

    /** 正文总字节（压缩后） */
    @JvmStatic
    fun bodyBytes(): Long = open()?.let { statBytes(it) } ?: 0

    /** 清空全部抓包数据 */
    @JvmStatic
    fun clear() {
        val d = open() ?: return
        try {
            d.execSQL("DELETE FROM traffic")
            d.execSQL("PRAGMA incremental_vacuum")
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 面板用的纯文本摘要：按 id 倒序取最近 N 条，
     * 只带 api/版本/大小，不带正文（正文太大，弹窗放不下）。
     *
     * **结论在前**：调用方把它放在列表上方当标题行用 ——
     * 原来这段统计在末尾，200 条列表要滚到底才看得到，
     * 而它恰恰是「这个库现在什么状况」最该一眼看到的东西。
     */
    @JvmStatic
    fun recentSummary(): String {
        val d = open() ?: return "数据库未就绪"
        return try {
            val n = statCount(d)
            val b = statBytes(d)
            "共 " + n + " 条（已按内容去重），压缩后 " +
                String.format(Locale.US, "%.2fMB", b / 1048576.0) +
                "，丢弃 " + dropped + " 条"
        } catch (t: Throwable) {
            "读取失败：" + t
        }
    }

    /** 只出列表，不带统计（统计走 [recentSummary]） */
    @JvmStatic
    fun recentText(limit: Int): String {
        val d = open() ?: return "数据库未就绪"
        val sb = StringBuilder()
        var c: Cursor? = null
        try {
            c = d.rawQuery(
                "SELECT id,ts,dir,api,ver,raw_len,hits,last_ts FROM traffic" +
                    " ORDER BY id DESC LIMIT " + maxOf(1, limit),
                null,
            )
            while (c != null && c.moveToNext()) {
                sb.append('#').append(c.getLong(0)).append(' ')
                    .append(fmtTime(c.getLong(1))).append(' ')
                    .append(pad(c.getString(2), 4)).append(' ')
                    .append(c.getString(3))
                val v = c.getString(4)
                if (!v.isNullOrEmpty()) sb.append(" v").append(v)
                val len = c.getLong(5)
                if (len > 0) {
                    sb.append("  ").append(
                        if (len < 1024) "${len}B"
                        else String.format(Locale.US, "%.1fKB", len / 1024.0),
                    )
                }
                val hits = c.getLong(6)
                if (hits > 1) {
                    // 合并过的记录：显示次数和最近一次时间，一眼看出是不是轮询
                    sb.append("  x").append(hits)
                        .append(" 末次").append(fmtTime(c.getLong(7)))
                }
                sb.append('\n')
            }
            if (sb.isEmpty()) sb.append("暂无数据")
        } catch (t: Throwable) {
            sb.append("读取失败：").append(t)
        } finally {
            try { c?.close() } catch (ignored: Throwable) {}
        }
        return sb.toString()
    }

    private fun pad(s: String?, w: Int): String {
        val b = StringBuilder(s ?: "")
        while (b.length < w) b.append(' ')
        return b.toString()
    }

    // ------------------------------------------------------------ 容量控制

    private fun pruneIfNeeded() {
        val d = open() ?: return
        try {
            var bytes = 0L
            var count = 0L
            val c = d.rawQuery(
                "SELECT COUNT(*), IFNULL(SUM(LENGTH(body)),0) FROM traffic",
                null,
            )
            if (c != null) {
                if (c.moveToFirst()) {
                    count = c.getLong(0)
                    bytes = c.getLong(1)
                }
                c.close()
            }
            // 先裁超量数据
            if (bytes > MAX_BODY_BYTES && count > 0L) {
                // 删最旧的 30%，按 id 而不是 ts（同毫秒多条时 ts 不唯一）
                val cut = count * 3 / 10
                d.execSQL(
                    "DELETE FROM traffic WHERE id IN " +
                        "(SELECT id FROM traffic ORDER BY id ASC LIMIT " + maxOf(1L, cut) + ")",
                )
                Protocol.State.logLine(
                    "抓包库超限，已裁掉最旧 $cut 条（原 ${bytes / 1024 / 1024}MB）",
                )
            }
            // 单接口限流：某些埋点接口（search.highway.upload 之类）一次会话能刷几千条，
            // 把整库挤满。按 api 保留最近 MAX_PER_API 条，超出部分按 id 删最旧。
            try {
                val cur2 = d.rawQuery(
                    "SELECT api, COUNT(*) c FROM traffic GROUP BY api HAVING c > " + (MAX_PER_API * 2),
                    null,
                )
                val heavy = ArrayList<String>()
                if (cur2 != null) {
                    while (cur2.moveToNext()) heavy.add(cur2.getString(0))
                    cur2.close()
                }
                for (api in heavy) {
                    d.execSQL(
                        "DELETE FROM traffic WHERE api = ? AND id NOT IN " +
                            "(SELECT id FROM traffic WHERE api = ? ORDER BY id DESC LIMIT " +
                            MAX_PER_API + ")",
                        arrayOf<Any?>(api, api),
                    )
                }
                if (heavy.isNotEmpty()) {
                    Protocol.State.logLine("抓包库按接口保留最近 $MAX_PER_API 条（清理 ${heavy.size} 个高频接口）")
                }
            } catch (ignored: Throwable) {
            }
            // 再回收空闲页（无论是否裁剪都要做：DROP 表重建、迁移、
            // 以及外部工具删数据都会留下空闲页，不回收文件只涨不缩）
            reclaimSpace(d)
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 回收空闲页。
     *
     * 增量 vacuum 每次只收尾部连续的空闲页，碎片化严重（空闲页散在中间）时
     * 收不干净 —— 实测遇到过一个库：11.9MB 文件里 9.4MB 是中间的空闲页，
     * `PRAGMA incremental_vacuum` 一点没缩。所以：
     *   - 空闲页占比 < 25%：incremental_vacuum 就够，快且不锁
     *   - 占比 >= 25%：直接全量 VACUUM（重写整个文件，耗时但彻底）
     */
    private fun reclaimSpace(d: SQLiteDatabase) {
        var freePages = 0L
        var pageSize = 4096L
        var pageCount = 0L
        var cur: Cursor? = null
        try {
            cur = d.rawQuery("PRAGMA freelist_count", null)
            if (cur.moveToFirst()) freePages = cur.getLong(0)
            cur.close()
            cur = d.rawQuery("PRAGMA page_size", null)
            if (cur.moveToFirst()) pageSize = cur.getLong(0)
            cur.close()
            cur = d.rawQuery("PRAGMA page_count", null)
            if (cur.moveToFirst()) pageCount = cur.getLong(0)
        } catch (ignored: Throwable) {
        } finally {
            try { cur?.close() } catch (ignored: Throwable) {}
        }
        if (freePages <= 8 || pageCount <= 0L) return
        val ratio = freePages.toDouble() / pageCount
        try {
            if (ratio < 0.25) {
                d.execSQL("PRAGMA incremental_vacuum")
            } else {
                d.execSQL("VACUUM")
            }
            Protocol.State.logLine(
                "抓包库回收空闲页 ${freePages * pageSize / 1024}KB" +
                    if (ratio < 0.25) "（增量）" else "（全量）",
            )
        } catch (t: Throwable) {
            // VACUUM 需要独占，失败就退回增量，别让异常冒到写库线程
            try { d.execSQL("PRAGMA incremental_vacuum") } catch (ignored: Throwable) {}
        }
    }
}
