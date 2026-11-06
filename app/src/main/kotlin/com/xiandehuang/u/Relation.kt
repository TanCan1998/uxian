package com.xiandehuang.u

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 接口关联分析：找出「A 的请求参数」和「B 的响应数据」之间的对应关系。
 *
 * ## 为什么要做这件事
 *
 * 有些接口从外部重发必定失败，不是参数写错了，而是**它依赖另一个接口先跑过**。
 * 例如 `idle.idleadv.scene.restore` 报 `FAIL_BIZ_RESULT_NULL`，
 * 因为它需要 `home.nextfresh` 先返回一个 sceneId；单独调它就是空。
 *
 * 把这种「谁依赖谁」挖出来，重放失败的接口就有解释了：
 * 先调被依赖的那个，拿到值再拼进参数里。
 *
 * ## 判定方法（不用猜，用同一性）
 *
 * 对每条抓包记录，把两边的值都抽出来：
 *   - **参数值**：请求体里所有字符串/数字叶子（含 JSON 字符串里套的 JSON）
 *   - **响应值**：响应体里所有字符串/数字叶子
 *
 * 然后找「同一个值同时出现在 A 的参数和 B 的响应里」。
 * 值必须是**有辨识度的**（见 [interesting]），否则 "0" / "true" / "1.0"
 * 这种会把所有接口连成一团。
 *
 * 打分规则：
 *   - 值越长、越像 ID（数字串 / 加密串 / uuid）→ 分越高
 *   - 同一个 (A,B,字段) 在多次抓包里重复出现 → 加分
 *   - 出现在太多接口里（比如 traceId 满世界都是）→ 扣分甚至丢弃
 *
 * ## 已知会误报的东西（实测后要加进黑名单）
 *
 * 每次请求都变、但和业务无关的：traceId / pvid / rn / reqId / seqNo /
 * serverTime / 各种时间戳。这些在 TrafficStore.normalize 里已经认识一批，
 * 这里复用同一份正则。
 */
object Relation {

    private const val TAG = "xysidecar"

    /** 一次分析最多处理多少条记录（防止大库把主线程拖死） */
    private const val MAX_ROWS = 4000

    /**
     * 一个值出现在超过这么多个接口的响应里，就判定它是**环境常量**
     * （地区码 / appVersion / 品牌），不是数据依赖。
     *
     * 实测：放宽到 6 时，`areaCode=330102`（杭州地区码）出现在几十个接口里，
     * 直接炸出 1841 条「关联」，全是噪声。真正的依赖很少超过 3 个接口共享。
     */
    private const val MAX_APIS_PER_VALUE = 3

    /** 太短的值不参与匹配 */
    private const val MIN_LEN = 4

    /** 一个接口最多记多少条关联，防止爆炸 */
    private const val MAX_PER_API = 40

    // ------------------------------------------------------------ 对外入口

    class Result(
        val apis: Int,
        val relations: Int,
        val ms: Long,
    )

    /**
     * 跑一遍分析：刷新 apis 表，重建 relations 表。
     *
     * **必须异步调用**（面板里走线程），几千条记录要几百毫秒。
     */
    @JvmStatic
    fun analyze(ctx: android.content.Context?): Result {
        val t0 = System.currentTimeMillis()
        val d = TrafficStore.openForRead()
        if (d == null) return Result(0, 0, 0)

        val rows = loadRows(d)
        if (rows.isEmpty()) return Result(0, 0, System.currentTimeMillis() - t0)

        // 1) 每个接口的请求值 / 响应值
        val reqVals = HashMap<String, MutableSet<String>>()   // api -> 参数值
        val respVals = HashMap<String, MutableSet<String>>()  // api -> 响应值
        val reqFieldOf = HashMap<String, MutableMap<String, String>>()  // 值 -> 字段名
        val respFieldOf = HashMap<String, MutableMap<String, String>>()
        val reqN = HashMap<String, Int>()
        val respN = HashMap<String, Int>()
        val respLen = HashMap<String, Long>()
        val chainOf = HashMap<String, String>()
        val firstTs = HashMap<String, Long>()
        val lastTs = HashMap<String, Long>()

        for (r in rows) {
            val api = r.api
            firstTs[api] = minOf(firstTs[api] ?: Long.MAX_VALUE, r.ts)
            lastTs[api] = maxOf(lastTs[api] ?: 0L, r.ts)
            if (r.dir == "REQ") {
                reqN[api] = (reqN[api] ?: 0) + 1
                val cal = r.caller
                if (cal != null && cal.isNotEmpty()) chainOf[api] = cal
                val m = reqFieldOf.getOrPut(api) { HashMap() }
                val set = reqVals.getOrPut(api) { HashSet() }
                collect(r.body, set, m)
            } else {
                respN[api] = (respN[api] ?: 0) + 1
                respLen[api] = maxOf(respLen[api] ?: 0L, r.len)
                val m = respFieldOf.getOrPut(api) { HashMap() }
                val set = respVals.getOrPut(api) { HashSet() }
                collect(r.body, set, m)
            }
        }

        // 2) 值 → 出现在哪些接口的响应里
        //    这一步是核心索引：反查「谁提供这个值」。
        val provider = HashMap<String, MutableList<String>>()   // 值 -> [api]
        for ((api, set) in respVals) {
            for (v in set) {
                val l = provider.getOrPut(v) { ArrayList(2) }
                if (l.size < 64 && !l.contains(api)) l.add(api)
            }
        }

        // 3) 对每个接口的每个参数值，反查提供者
        val out = ArrayList<Rel>()
        for ((api, set) in reqVals) {
            var n = 0
            for (v in set) {
                if (n >= MAX_PER_API) break
                val provs = provider[v] ?: continue
                if (provs.size > MAX_APIS_PER_VALUE) continue   // 通用噪声
                val field = reqFieldOf[api]?.get(v) ?: "?"
                if (ENV_KEYS.containsMatchIn(field)) continue
                // apiName 回显：值是接口名，这是协议字段不是数据依赖
                if (v.startsWith("mtop.") || v.startsWith("com.taobao.")) continue
                for (to in provs) {
                    if (to == api) continue
                    n++
                    val score = score(v, provs.size, 1)
                    out.add(Rel(api, to, field, "param→resp", score, 1, mask(v)))
                    if (n >= MAX_PER_API) break
                }
            }
        }

        // 4) 写库
        val relCount = writeRelations(d, out)
        val apiCount = writeApis(
            d, reqN, respN, respLen, chainOf, firstTs, lastTs,
        )
        Log.i(TAG, "relation analyze: $apiCount apis, $relCount rels, ${out.size} raw")
        return Result(apiCount, relCount, System.currentTimeMillis() - t0)
    }

    // ------------------------------------------------------------ 取值

    private class Row {
        var api: String = ""
        var dir: String = ""
        var body: String = ""
        var len: Long = 0
        var ts: Long = 0
        var caller: String? = null
    }

    private fun loadRows(d: SQLiteDatabase): List<Row> {
        val out = ArrayList<Row>()
        var c: Cursor? = null
        try {
            // 只取最近的 MAX_ROWS 条，按 id 倒序再翻转，保证拿到的是最新的。
            // 必须排除 origin='ours'：那是我方探测写入的，不是宿主真实行为，
            // 拿它做关联分析会把"我发出去的参数"当成"宿主在用的参数"。
            c = d.rawQuery(
                "SELECT api,dir,body,raw_len,ts,compressed,caller FROM traffic" +
                    " WHERE dir IN ('REQ','RESP') AND api != ''" +
                    " AND (origin IS NULL OR origin='host')" +
                    " ORDER BY id DESC LIMIT $MAX_ROWS",
                null,
            )
            while (c.moveToNext()) {
                val r = Row()
                r.api = c.getString(0) ?: continue
                r.dir = c.getString(1) ?: continue
                r.body = TrafficStore.decompress(c.getBlob(2), c.getInt(5) == 1)
                r.len = c.getLong(3)
                r.ts = c.getLong(4)
                r.caller = c.getString(6)
                out.add(r)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "loadRows failed: $t")
        } finally {
            try { c?.close() } catch (ignored: Throwable) {}
        }
        out.reverse()
        return out
    }

    /**
     * 从一段正文里抽出所有「有辨识度的值」，并记下每个值对应的字段名。
     *
     * @param out 值集合
     * @param fieldOf 值 → 字段名（同一个值可能出现在多个字段，取第一个）
     */
    private fun collect(
        text: String,
        out: MutableSet<String>,
        fieldOf: MutableMap<String, String>,
    ) {
        if (text.isEmpty()) return
        val t = text.trim()
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                walk(if (t.startsWith("{")) JSONObject(t) else JSONArray(t), "", out, fieldOf, 0)
                return
            } catch (ignored: Throwable) {
            }
        }
        // 非 JSON（或解析失败）：按正则捞一遍长串
        for (m in VALUE_RE.findAll(text)) {
            val v = m.groupValues[1]
            if (interesting(v)) {
                out.add(v)
                fieldOf.putIfAbsent(v, "?")
            }
        }
    }

    private val VALUE_RE = Regex("\"([A-Za-z0-9+/=_\\-.]{4,80})\"")

    private fun walk(
        o: Any?,
        path: String,
        out: MutableSet<String>,
        fieldOf: MutableMap<String, String>,
        depth: Int,
    ) {
        if (depth > 7 || o == null) return
        when (o) {
            is JSONObject -> {
                val it = o.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = o.opt(k) ?: continue
                    val p = if (path.isEmpty()) k else "$path.$k"
                    when (v) {
                        is JSONObject, is JSONArray -> walk(v, p, out, fieldOf, depth + 1)
                        is String -> {
                            val s = v.trim()
                            // 字符串里套 JSON 很常见（extraParam / args / extend），要再挖一层
                            if (s.length > 1 && (s.startsWith("{") || s.startsWith("["))) {
                                try {
                                    walk(
                                        if (s.startsWith("{")) JSONObject(s) else JSONArray(s),
                                        p, out, fieldOf, depth + 1,
                                    )
                                    continue
                                } catch (ignored: Throwable) {
                                }
                            }
                            if (interesting(s)) {
                                out.add(s)
                                fieldOf.putIfAbsent(s, k)
                            }
                        }
                        is Number -> {
                            val s = v.toString()
                            if (interesting(s)) {
                                out.add(s)
                                fieldOf.putIfAbsent(s, k)
                            }
                        }
                    }
                }
            }
            is JSONArray -> {
                val n = minOf(o.length(), 80)
                for (i in 0 until n) {
                    val v = o.opt(i) ?: continue
                    when (v) {
                        is JSONObject, is JSONArray -> walk(v, path, out, fieldOf, depth + 1)
                        is String -> if (interesting(v)) {
                            out.add(v)
                            fieldOf.putIfAbsent(v, path.substringAfterLast('.'))
                        }
                        is Number -> if (interesting(v.toString())) {
                            out.add(v.toString())
                            fieldOf.putIfAbsent(v.toString(), path.substringAfterLast('.'))
                        }
                    }
                }
            }
        }
    }

    /**
     * 设备/环境固定值：这些在任何接口里都一样，**不代表依赖**。
     *
     * 实测教训：不加这个黑名单，`appVersion=7.28.80` / `brand=OnePlus` /
     * `platform=android` / 经纬度 会把所有接口连成一团 ——
     * 58 条「关联」里 50 条是这种垃圾。
     */
    private val ENV_KEYS = Regex(
        "^(brand|model|platform|os|appversion|app_version|apiversion|socmodel|soc_model|" +
            "latitude|longitude|lat|lng|gps|city|province|country|region|" +
            "ua|useragent|user_agent|screensize|screen|density|dpi|width|height|" +
            "networktype|network_type|operator|carrier|language|locale|" +
            "channel|channel_2|channelid|utdid|umid|oaid|alioaid|imei|androidid|" +
            "deviceid|idfa|mac|serial|resolution|fontscale|timezone|" +
            "_fuzzyinfo_|fuzzyinfo|gzuoaid|" +
            // 身份字段：同一个用户的所有请求都带，不是「A 依赖 B」
            "userid|uid|sellerid|seller_id|nick|usernick|fishnick|snsnick|uniquenick|" +
            // 地区 / 类目 / 业务编码：几十个接口都带，是环境常量不是依赖
            "areacode|areacode_2|fishadcode|adcode|citycode|provincecode|regioncode|" +
            "countrycode|divisioncode|locationid|location_id|cateid|cate_id|categoryid|" +
            "category_id|bizcode|biz_code|sceneid|scene_id|channelid|tabid|tab_id|" +
            "bucketid|bucket_id|testid|test_id|abid|ab_id|spmprefix|spmb|spma|spmc|spmd|" +
            // 实测漏网的：坐标分片、加速度、昵称
            "lon|lat|acc|alt|speed|bearing|accuracy|" +
            "nk|nn|nickname|displayname|username|realname|" +
            "token|sid|utdid|trackid|sessionid|requestid|pageid|page_id)$",
        RegexOption.IGNORE_CASE,
    )

    /** 值本身就是环境常量 */
    private val ENV_VALUES = setOf(
        "android", "oneplus", "ios", "arm64-v8a", "armeabi-v7a",
        "true", "false", "null", "undefined", "zh-cn", "zh_cn", "cn",
        "online", "offline",
    )

    /** 每次都变、和业务无关的字段名（出现在字段名里就跳过） */
    private val NOISE_KEY = Regex(
        "(time|timestamp|ts|trace|pvid|rn|reqid|seqno|seq|nonce|sign|random|rand|" +
            "cache|expire|version|ver|count|index|idx|size|num|offset|page|spm|" +
            "latitude|longitude|lat|lng|gps|city|province|ua|useragent|screen|density|" +
            "dpi|width|height|fontsize|color|margin|padding|spacing|duration|elapsed)",
        RegexOption.IGNORE_CASE,
    )

    /** 纯数字但像时间戳/坐标/尺寸的，也别要 */
    private fun interesting(v: String): Boolean {
        if (v.length < MIN_LEN || v.length > 200) return false
        if (ENV_VALUES.contains(v.lowercase())) return false
        if (NOISE_KEY.containsMatchIn(v)) return false
        // 全数字：太短没辨识度，13 位多半是时间戳，坐标带小数点
        if (v.all { it.isDigit() }) {
            if (v.length < 6) return false
            if (v.length == 13 && (v.startsWith("17") || v.startsWith("18"))) return false
            // 10 位秒级时间戳（实测 triggerTime=1793258134 会误报）
            if (v.length == 10 && (v.startsWith("17") || v.startsWith("18"))) return false
            return true
        }
        // 纯小写字母单词（true/false/null 之类）
        if (v.length < 6 && v.all { it.isLetter() }) return false
        // 纯 hex 且只有 1-2 种字符（000000 / ffffff 这类颜色）
        if (v.all { it.isDigit() || it in "abcdefABCDEF" } && v.toSet().size <= 2) return false
        return true
    }

    /** 打分：值越像 ID、提供者越少，越可信 */
    private fun score(v: String, providers: Int, samples: Int): Int {
        var s = 40
        if (v.length >= 8) s += 15
        if (v.length >= 16) s += 10
        if (v.all { it.isDigit() }) s += 15            // 数字 ID
        if (v.contains("/") || v.contains("+") || v.contains("=")) s += 10  // 加密串/base64
        s += minOf(15, samples * 5)
        s -= (providers - 1) * 10                       // 提供者越多越像噪声
        return maxOf(5, minOf(100, s))
    }

    private fun mask(v: String): String =
        if (v.length <= 8) v else v.substring(0, 4) + "…" + v.substring(v.length - 4)

    // ------------------------------------------------------------ 写库

    private class Rel(
        val from: String,
        val to: String,
        val field: String,
        val kind: String,
        val score: Int,
        val samples: Int,
        val evidence: String,
    )

    private fun writeRelations(d: SQLiteDatabase, rels: List<Rel>): Int {
        // 同一 (from,to,field,kind) 只留分最高的
        val best = LinkedHashMap<String, Rel>()
        for (r in rels) {
            val k = r.from + "|" + r.to + "|" + r.field + "|" + r.kind
            val old = best[k]
            if (old == null || r.score > old.score) best[k] = r
        }
        try {
            d.beginTransaction()
            d.delete("relations", null, null)
            val now = System.currentTimeMillis()
            for (r in best.values) {
                val cv = ContentValues()
                cv.put("from_api", r.from)
                cv.put("to_api", r.to)
                cv.put("field", r.field)
                cv.put("kind", r.kind)
                cv.put("score", r.score)
                cv.put("samples", r.samples)
                cv.put("evidence", r.evidence)
                cv.put("ts", now)
                d.insertWithOnConflict(
                    "relations", null, cv, SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            d.setTransactionSuccessful()
            return best.size
        } catch (t: Throwable) {
            Log.w(TAG, "writeRelations failed: $t")
            return 0
        } finally {
            try { if (d.inTransaction()) d.endTransaction() } catch (ignored: Throwable) {}
        }
    }

    private fun writeApis(
        d: SQLiteDatabase,
        reqN: Map<String, Int>,
        respN: Map<String, Int>,
        respLen: Map<String, Long>,
        chainOf: Map<String, String>,
        firstTs: Map<String, Long>,
        lastTs: Map<String, Long>,
    ): Int {
        val all = LinkedHashSet<String>()
        all.addAll(reqN.keys)
        all.addAll(respN.keys)
        try {
            d.beginTransaction()
            val now = System.currentTimeMillis()
            for (api in all) {
                // 已有行就只更新统计字段，保留导入进来的 intent/note 等
                val cv = ContentValues()
                cv.put("api", api)
                cv.put("req_n", reqN[api] ?: 0)
                cv.put("resp_n", respN[api] ?: 0)
                cv.put("resp_len", respLen[api] ?: 0L)
                cv.put("first_ts", firstTs[api] ?: now)
                cv.put("last_ts", lastTs[api] ?: now)
                cv.put("analyzed_ts", now)
                val ch = chainOf[api]
                if (ch != null) cv.put("chain", ch)
                d.insertWithOnConflict("apis", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                // IGNORE 之后补一次 UPDATE，把统计刷新
                d.execSQL(
                    "UPDATE apis SET req_n=?, resp_n=?, resp_len=?, first_ts=?," +
                        " last_ts=?, analyzed_ts=?" + (if (ch != null) ", chain=?" else "") +
                        " WHERE api=?",
                    (listOf(
                        reqN[api] ?: 0, respN[api] ?: 0, respLen[api] ?: 0L,
                        firstTs[api] ?: now, lastTs[api] ?: now, now,
                    ) + (if (ch != null) listOf<Any>(ch) else emptyList()) + listOf<Any>(api))
                        .toTypedArray(),
                )
            }
            d.setTransactionSuccessful()
            return all.size
        } catch (t: Throwable) {
            Log.w(TAG, "writeApis failed: $t")
            return 0
        } finally {
            try { if (d.inTransaction()) d.endTransaction() } catch (ignored: Throwable) {}
        }
    }

    // ------------------------------------------------------------ 读

    /** 某个接口的关联列表（双向：谁依赖我 / 我依赖谁） */
    @JvmStatic
    fun relationsOf(api: String): List<JSONObject> {
        val d = TrafficStore.openForRead() ?: return emptyList()
        val out = ArrayList<JSONObject>()
        var c: Cursor? = null
        try {
            c = d.rawQuery(
                "SELECT from_api,to_api,field,kind,score,samples,evidence FROM relations" +
                    " WHERE from_api=? OR to_api=? ORDER BY score DESC LIMIT 60",
                arrayOf(api, api),
            )
            while (c.moveToNext()) {
                val o = JSONObject()
                o.put("from", c.getString(0))
                o.put("to", c.getString(1))
                o.put("field", c.getString(2))
                o.put("kind", c.getString(3))
                o.put("score", c.getInt(4))
                o.put("samples", c.getInt(5))
                o.put("evidence", c.getString(6))
                // 站在 api 的立场：out = 我依赖别人，in = 别人依赖我
                o.put("dir", if (api == c.getString(0)) "out" else "in")
                out.add(o)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "relationsOf failed: $t")
        } finally {
            try { c?.close() } catch (ignored: Throwable) {}
        }
        return out
    }

    /** 全部关联（导出用） */
    @JvmStatic
    fun allRelations(): List<JSONObject> {
        val d = TrafficStore.openForRead() ?: return emptyList()
        val out = ArrayList<JSONObject>()
        var c: Cursor? = null
        try {
            c = d.rawQuery(
                "SELECT from_api,to_api,field,kind,score,samples,evidence" +
                    " FROM relations ORDER BY from_api, score DESC",
                null,
            )
            while (c.moveToNext()) {
                val o = JSONObject()
                o.put("from", c.getString(0))
                o.put("to", c.getString(1))
                o.put("field", c.getString(2))
                o.put("kind", c.getString(3))
                o.put("score", c.getInt(4))
                o.put("samples", c.getInt(5))
                o.put("evidence", c.getString(6))
                out.add(o)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "allRelations failed: $t")
        } finally {
            try { c?.close() } catch (ignored: Throwable) {}
        }
        return out
    }

    /** 某接口存过的元信息（导入的词典优先） */
    @JvmStatic
    fun apiMeta(api: String): JSONObject? {
        val d = TrafficStore.openForRead() ?: return null
        var c: Cursor? = null
        try {
            c = d.rawQuery(
                "SELECT intent,cat,layer,replay,replay_why,note,chain,req_n,resp_n," +
                    "resp_len,analyzed_ts FROM apis WHERE api=?",
                arrayOf(api),
            )
            if (c.moveToNext()) {
                val o = JSONObject()
                o.put("intent", c.getString(0) ?: "")
                o.put("cat", c.getString(1) ?: "")
                o.put("layer", c.getString(2) ?: "")
                o.put("replay", c.getString(3) ?: "")
                o.put("replayWhy", c.getString(4) ?: "")
                o.put("note", c.getString(5) ?: "")
                o.put("chain", c.getString(6) ?: "")
                o.put("reqN", c.getInt(7))
                o.put("respN", c.getInt(8))
                o.put("respLen", c.getLong(9))
                o.put("analyzedTs", c.getLong(10))
                return o
            }
        } catch (t: Throwable) {
            Log.w(TAG, "apiMeta failed: $t")
        } finally {
            try { c?.close() } catch (ignored: Throwable) {}
        }
        return null
    }

    /** 全部接口元信息（导出用） */
    @JvmStatic
    fun allApiMeta(): List<JSONObject> {
        val d = TrafficStore.openForRead() ?: return emptyList()
        val out = ArrayList<JSONObject>()
        var c: Cursor? = null
        try {
            c = d.rawQuery(
                "SELECT api,intent,cat,layer,replay,replay_why,note,chain,req_n,resp_n," +
                    "resp_len,first_ts,last_ts FROM apis ORDER BY api",
                null,
            )
            while (c.moveToNext()) {
                val o = JSONObject()
                o.put("api", c.getString(0))
                o.put("intent", c.getString(1) ?: "")
                o.put("cat", c.getString(2) ?: "")
                o.put("layer", c.getString(3) ?: "")
                o.put("replay", c.getString(4) ?: "")
                o.put("replayWhy", c.getString(5) ?: "")
                o.put("note", c.getString(6) ?: "")
                o.put("chain", c.getString(7) ?: "")
                o.put("reqN", c.getInt(8))
                o.put("respN", c.getInt(9))
                o.put("respLen", c.getLong(10))
                o.put("firstTs", c.getLong(11))
                o.put("lastTs", c.getLong(12))
                out.add(o)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "allApiMeta failed: $t")
        } finally {
            try { c?.close() } catch (ignored: Throwable) {}
        }
        return out
    }
}
