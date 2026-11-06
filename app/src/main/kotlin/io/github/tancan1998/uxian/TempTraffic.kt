package io.github.tancan1998.uxian

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** Independent, non-deduplicated capture; marker and file lock coordinate host processes. */
object TempTraffic {
    private const val NAME = "traffic_temp.db"
    private fun context() = Protocol.State.app
    private fun marker(): File? = context()?.getFileStreamPath("traffic_temp.active")
    private fun dbFile(): File? = context()?.getDatabasePath(NAME)
    private fun <T> locked(block: () -> T): T {
        val c = context() ?: throw IllegalStateException("应用尚未就绪")
        val file = c.getFileStreamPath("traffic_temp.lock")
        synchronized(TempTraffic::class.java) {
            RandomAccessFile(file, "rw").use { raf ->
                val lock = raf.channel.lock()
                try { return block() } finally { lock.release() }
            }
        }
    }
    @JvmStatic fun session(): String? = try {
        val f = marker() ?: return null
        if (!f.exists()) null else f.readText().trim().ifEmpty { null }
    } catch (_: Throwable) { null }
    private fun schema(d: SQLiteDatabase) {
        d.execSQL("CREATE TABLE IF NOT EXISTS traffic(id INTEGER PRIMARY KEY AUTOINCREMENT,ts INTEGER NOT NULL,dir TEXT,api TEXT,ver TEXT,method TEXT,headers TEXT,body TEXT,caller TEXT,origin TEXT,process INTEGER)")
        val columns = HashSet<String>()
        val cursor = d.rawQuery("PRAGMA table_info(traffic)", null)
        try { while (cursor.moveToNext()) columns.add(cursor.getString(1)) } finally { cursor.close() }
        if (!columns.contains("request_id")) d.execSQL("ALTER TABLE traffic ADD COLUMN request_id TEXT")
        if (!columns.contains("metadata")) d.execSQL("ALTER TABLE traffic ADD COLUMN metadata TEXT")
        if (!columns.contains("raw_body")) d.execSQL("ALTER TABLE traffic ADD COLUMN raw_body BLOB")
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_temp_ts ON traffic(ts)")
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_temp_request ON traffic(request_id)")
    }
    @JvmStatic fun start(): String = locked {
        val f = dbFile() ?: throw IllegalStateException("应用尚未就绪")
        val d = SQLiteDatabase.openOrCreateDatabase(f, null)
        try {
            schema(d)
        } finally { d.close() }
        val current = session()
        if (current == null) marker()!!.writeText(UUID.randomUUID().toString())
        "已开启临时抓包"
    }
    @JvmStatic fun stop(): String = locked {
        val m = marker() ?: throw IllegalStateException("应用尚未就绪")
        if (m.exists() && !m.delete()) throw IllegalStateException("无法停止临时抓包")
        schemaReady = false
        val f = dbFile() ?: throw IllegalStateException("应用尚未就绪")
        val files = arrayOf(f, File(f.path + "-wal"), File(f.path + "-shm"), File(f.path + "-journal"))
        for (item in files) if (item.exists() && !item.delete()) throw IllegalStateException("无法删除临时抓包文件: " + item.name)
        "已关闭临时抓包并删除临时数据"
    }
    @Volatile private var writeErrors = 0L
    @Volatile private var schemaReady = false
    @JvmStatic fun write(session: String, ts: Long, dir: String, api: String, ver: String, method: String?, headers: String?, body: String, caller: String?, origin: String,
        requestId: String? = null, metadata: String? = null, rawBody: ByteArray? = null) {
        try {
            locked {
                if (session != session()) return@locked
                val f = dbFile() ?: return@locked
                if (!f.exists()) return@locked
                val d = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READWRITE)
                try {
                    if (!schemaReady) { schema(d); schemaReady = true }
                    d.execSQL("INSERT INTO traffic(ts,dir,api,ver,method,headers,body,caller,origin,process,request_id,metadata,raw_body) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(ts, dir, api, ver, method, headers, body, caller, origin, android.os.Process.myPid(), requestId, metadata, rawBody))
                } finally { d.close() }
            }
        } catch (_: Throwable) { writeErrors++ }
    }
    @JvmStatic fun status(): String = if (session() == null) "已关闭" else
        "记录中 · " + (if (TempCapture.ready) "完成回调" else "读取挂点（覆盖有限）") +
            " · 队列丢弃=" + TrafficStore.dropped() + " · 写入失败=" + writeErrors
}
