package io.github.tancan1998.uxian

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.InputStreamReader

/**
 * 从导出的 HTML 报告里**回灌**数据：接口元信息、词典、关联关系。
 *
 * 用途：换机 / 重装 / 清库之后，把之前分析过的成果恢复回来，
 * 不用重新抓一遍、重新分析一遍。
 *
 * 只恢复「接口层」的数据（apis / relations / 词典），**不恢复抓包正文** ——
 * 正文体积大且时效性强，报告里已经是脱敏 + 截断过的，回灌进库反而污染。
 *
 * 报告的 `<script id="D" type="application/json">` 里就是完整数据集，
 * 直接解析它比另外设计一个导入格式简单得多，也不会出现两套格式不一致。
 */
object TrafficImport {

    private const val TAG = "xysidecar"

    class Result(
        val apis: Int,
        val relations: Int,
        val fieldDoc: Int,
        val error: String?,
    )

    /** 从 content:// uri 读（文件选择器返回的就是这个） */
    @JvmStatic
    fun fromUri(ctx: Context, uri: Uri): Result {
        return try {
            val text = ctx.contentResolver.openInputStream(uri)?.use { ins ->
                InputStreamReader(ins, Charsets.UTF_8).readText()
            } ?: return Result(0, 0, 0, "打不开文件")
            fromText(text)
        } catch (t: Throwable) {
            Log.w(TAG, "import failed: $t")
            Result(0, 0, 0, t.toString())
        }
    }

    @JvmStatic
    fun fromFile(path: String): Result {
        return try {
            fromText(java.io.File(path).readText(Charsets.UTF_8))
        } catch (t: Throwable) {
            Result(0, 0, 0, t.toString())
        }
    }

    /** 解析报告文本并写库 */
    @JvmStatic
    fun fromText(html: String): Result {
        val marker = "id=\"D\" type=\"application/json\">"
        val i = html.indexOf(marker)
        if (i < 0) return Result(0, 0, 0, "不是 U 闲抓包报告（找不到数据块）")
        val start = i + marker.length
        val end = html.indexOf("</script>", start)
        if (end < 0) return Result(0, 0, 0, "数据块不完整")
        val raw = html.substring(start, end).replace("<\\/", "</")
        val data = try {
            JSONObject(raw)
        } catch (t: Throwable) {
            return Result(0, 0, 0, "数据解析失败：${t.message}")
        }

        val d = TrafficStore.openForRead()
            ?: return Result(0, 0, 0, "抓包库未就绪")

        val rows = data.optJSONArray("rows")
            ?: return Result(0, 0, 0, "报告里没有接口数据")

        var apiN = 0
        var relN = 0
        val now = System.currentTimeMillis()
        try {
            d.beginTransaction()

            // ---- 接口元信息 ----
            for (i2 in 0 until rows.length()) {
                val o = rows.optJSONObject(i2) ?: continue
                val api = o.optString("api", "")
                if (api.isEmpty()) continue
                // 已存在的行不覆盖本地值 —— 本地抓到的比报告里的新。
                // 只补本地空着的字段。
                val exist = Relation.apiMeta(api)
                val cv = android.content.ContentValues()
                cv.put("api", api)
                putIfMissing(cv, "intent", o.optString("intent", ""), exist?.optString("intent"))
                putIfMissing(cv, "cat", o.optString("cat", ""), exist?.optString("cat"))
                putIfMissing(cv, "layer", o.optString("layer", ""), exist?.optString("layer"))
                putIfMissing(cv, "replay", o.optString("replay", ""), exist?.optString("replay"))
                putIfMissing(
                    cv, "replay_why", o.optString("replayWhy", ""), exist?.optString("replayWhy"),
                )
                val note = o.optString("note", "")
                if (note.isNotEmpty()) cv.put("note", note)
                val chain = o.optString("chain", "")
                if (chain.isNotEmpty()) cv.put("chain", chain)
                if (exist == null) {
                    cv.put("req_n", 0)
                    cv.put("resp_n", 0)
                    cv.put("resp_len", 0L)
                    cv.put("first_ts", now)
                    cv.put("last_ts", now)
                }
                d.insertWithOnConflict("apis", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                // IGNORE 之后补 UPDATE，把非空字段写进去
                val set = StringBuilder()
                val args = ArrayList<Any>()
                for (k in arrayOf(
                    "intent", "cat", "layer", "replay", "replay_why", "note", "chain",
                )) {
                    val v = cv.get(k)
                    if (v != null && v.toString().isNotEmpty()) {
                        if (set.isNotEmpty()) set.append(", ")
                        set.append(k).append("=?")
                        args.add(v)
                    }
                }
                if (set.isNotEmpty()) {
                    args.add(api)
                    d.execSQL("UPDATE apis SET $set WHERE api=?", args.toTypedArray())
                }
                apiN++
            }

            // ---- 关联关系 ----
            for (i2 in 0 until rows.length()) {
                val o = rows.optJSONObject(i2) ?: continue
                val rels = o.optJSONArray("rels") ?: continue
                for (j in 0 until rels.length()) {
                    val r = rels.optJSONObject(j) ?: continue
                    // 只导 from 方向，避免正反两条重复
                    if (r.optString("dir") == "in") continue
                    val cv = android.content.ContentValues()
                    cv.put("from_api", r.optString("from"))
                    cv.put("to_api", r.optString("to"))
                    cv.put("field", r.optString("field"))
                    cv.put("kind", r.optString("kind"))
                    cv.put("score", r.optInt("score"))
                    cv.put("samples", r.optInt("samples"))
                    cv.put("evidence", r.optString("evidence"))
                    cv.put("ts", now)
                    d.insertWithOnConflict(
                        "relations", null, cv, SQLiteDatabase.CONFLICT_REPLACE,
                    )
                    relN++
                }
            }

            d.setTransactionSuccessful()
        } catch (t: Throwable) {
            Log.w(TAG, "import write failed: $t")
            return Result(0, 0, 0, t.toString())
        } finally {
            try { if (d.inTransaction()) d.endTransaction() } catch (ignored: Throwable) {}
        }

        // ---- 词典合并 ----
        var fN = 0
        try {
            val apiDoc = data.optJSONObject("apiDoc")
            val fieldDoc = data.optJSONObject("fieldDoc")
            if (apiDoc != null || fieldDoc != null) fN = ApiDoc.mergeImport(apiDoc, fieldDoc)
        } catch (t: Throwable) {
            Log.w(TAG, "import doc failed: $t")
        }

        Log.i(TAG, "import done: $apiN apis, $relN rels, $fN fields")
        return Result(apiN, relN, fN, null)
    }

    /** 本地为空才写，非空说明本地数据更新，不覆盖 */
    private fun putIfMissing(
        cv: android.content.ContentValues,
        key: String,
        incoming: String,
        local: String?,
    ) {
        if (incoming.isEmpty()) return
        if (local != null && local.isNotEmpty()) return
        cv.put(key, incoming)
    }
}
