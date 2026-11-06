package io.github.tancan1998.uxian

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * 把抓包库渲染成**单文件 HTML 报告**，存到 /sdcard/Download/U闲抓包/。
 *
 * 为什么在设备上生成而不是导库出来离线做：
 *   1. 用户点一下就能看，不用把 sqlite + gzip 搬运到电脑；
 *   2. HTML 完全自包含（样式/脚本/数据内联），微信/QQ 发出去也能直接打开；
 *   3. 正文超过 2MB 的接口不做 JSON 解析，只给原文预览 —— 防止手机打开就卡死。
 *
 * 模板和脚本放在 assets/traffic.html + traffic.js，编译期由 tools/embed_assets.py
 * 转成 Kotlin 常量（APK 里没有 resources.arsc 的 assets 表，读不到真 assets）。
 *
 * 数据一律脱敏后才写进报告：token / session / oaid / umid / 手机号等。
 */
object TrafficExport {

    private const val TAG = "xysidecar"

    /** 单条正文上限：超过就不放进报告，避免生成几十 MB 的 HTML */
    private const val MAX_BODY_CHARS = 1_200_000

    /** 整个报告的数据上限（字符）。超了就只保留最近的接口。 */
    private const val MAX_TOTAL_CHARS = 12_000_000

    /**
     * 实例正文的**全局**预算（字符）。
     *
     * 每个接口最多 6 条实例、单条最多 200KB，258 个接口能堆到 31MB ——
     * 实测把报告撑到 31MB，手机打开很慢。这里给一个总预算，
     * 超了就只给前面的接口带实例，后面的只保留主正文。
     */
    private const val INSTANCE_BUDGET = 3_000_000

    class Result(val path: String, val apis: Int, val bytes: Long, val uri: String?)

    // ------------------------------------------------------------ 脱敏

    private val SENSITIVE = Regex(
        "(^|_)(token|accessToken|refreshToken|session|sid|sessId|cookie|password|" +
            "passwd|pwd|secret|appkey|appKey|apiKey|sign|signature|umid|oaid|aliOaid|" +
            "imei|androidId|idfa|deviceId|userId|sellerId|uid|phone|mobile|email)(_|$)",
        RegexOption.IGNORE_CASE,
    )

    private fun mask(v: String): String = when {
        v.length <= 6 -> "***"
        v.length <= 12 -> v.substring(0, 2) + "***" + v.substring(v.length - 2)
        else -> v.substring(0, 4) + "***" + v.substring(v.length - 4)
    }

    /** 对 JSON 正文按字段名脱敏；解析失败就按正则兜底 */
    private fun redact(text: String): String {
        if (text.isEmpty()) return text
        val t = text.trim()
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                val o = if (t.startsWith("{")) JSONObject(t) else JSONArray(t)
                redactIn(o, 0)
                return o.toString()
            } catch (ignored: Throwable) {
            }
        }
        return redactText(text)
    }

    private fun redactIn(o: Any?, depth: Int) {
        if (depth > 8 || o == null) return
        when (o) {
            is JSONObject -> {
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = o.opt(k)
                    if (v is JSONObject || v is JSONArray) {
                        redactIn(v, depth + 1)
                    } else if (v is String && SENSITIVE.containsMatchIn(k) &&
                        v.isNotEmpty() && v != "0" && v != "false" && v != "true" &&
                        v != "null"
                    ) {
                        o.put(k, mask(v))
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until minOf(o.length(), 200)) redactIn(o.opt(i), depth + 1)
            }
        }
    }

    private fun redactText(t: String): String {
        var s = t
        s = Regex("\"(umid|oaid|aliOaid|imei|androidId)\"\\s*:\\s*\"([^\"]{6,})\"")
            .replace(s) { m -> "\"${m.groupValues[1]}\":\"${mask(m.groupValues[2])}\"" }
        s = Regex("1[3-9]\\d{9}").replace(s) { m ->
            m.value.substring(0, 3) + "****" + m.value.substring(7)
        }
        return s
    }

    // ------------------------------------------------------------ 生成

    /**
     * 生成报告。**必须异步调用**（面板里走线程），大库会阻塞几百毫秒。
     *
     * @param onlyApis 只导出这些接口（null = 全部）
     */
    @JvmStatic
    fun build(ctx: Context, onlyApis: Array<String>?): Result {
        val rows = collect(onlyApis)
        ApiDoc.load(ctx)
        val probe = ApiDoc.loadProbe(ctx)

        // 关联关系：接口 → 关联列表
        val relMap = HashMap<String, JSONArray>()
        for (o in Relation.allRelations()) {
            val f = o.optString("from")
            val t = o.optString("to")
            relMap.getOrPut(f) { JSONArray() }.put(o)
            // 反向也挂一份，方便在 to 那边看到「谁依赖我」
            val back = JSONObject(o.toString())
            back.put("dir", "in")
            relMap.getOrPut(t) { JSONArray() }.put(back)
        }

        var instBudget = INSTANCE_BUDGET
        val arr = JSONArray()
        for (r in rows) {
            val o = JSONObject()
            o.put("api", r.api)
            val info = ApiDoc.intentOf(r.api)
            o.put("intent", info.first)
            o.put("cat", info.second)
            val caller = CallerTrace.decode(r.caller)
            o.put("layer", caller?.get(0) ?: ApiDoc.layerOf(r.api))
            if (caller != null) {
                o.put("callerCls", caller[1])
                o.put("callerMethod", caller[2])
                if (caller.size > 3 && caller[3].isNotEmpty()) {
                    o.put("chain", caller[3])
                    o.put("chainCn", cnChain(caller[3]))
                }
            }
            val pr = probe[r.api]
            o.put("replay", pr?.first ?: "unk")
            if (pr?.second != null) o.put("replayWhy", pr!!.second)
            o.put("ts", r.ts)
            o.put("hits", r.hits)
            o.put("ver", r.ver ?: "")
            o.put("respLen", r.respLen)
            val reqT = r.req
            if (reqT != null) o.put("req", JSONObject().put("text", clip(redact(reqT))))
            val hdrT = r.reqHeaders
            if (hdrT != null) o.put("reqHeaders", JSONObject().put("text", redact(hdrT)))
            val respT = r.resp
            if (respT != null) o.put("resp", JSONObject().put("text", clip(redact(respT))))
            // 关联接口（供详情页的「关联」浮窗用）
            relMap[r.api]?.let { o.put("rels", it) }
            // 该接口抓到的全部请求实例（供折叠展开用）。
            // 全局预算控制：实例正文是报告体积的大头（258 个接口 + 每个 6 条实例
            // 实测能到 31MB），超预算就不再往里塞，只保留主正文。
            if (instBudget > 0 && r.instCount > 2) {
                val insts = instancesOf(r.api, instBudget)
                if (insts.length() > 0) {
                    o.put("instances", insts)
                    instBudget -= insts.toString().length
                }
            }
            arr.put(o)
        }

        val data = JSONObject()
        data.put("rows", arr)
        data.put("apiDoc", ApiDoc.docJson())
        data.put("fieldDoc", ApiDoc.fieldJson())
        data.put("meta", JSONObject().put("sub", subtitle(rows.size, rows)))
        data.put("classDoc", ApiDoc.classDocJson())
        data.put("layerLabel", TrafficAssets.LABELS.optJSONObject("layers") ?: JSONObject())
        data.put("replayLabel", TrafficAssets.LABELS.optJSONObject("replay") ?: JSONObject())

        var blob = data.toString()
        // 正文里出现 </script> 会截断脚本块
        blob = blob.replace("</", "<\\/")

        val html = TrafficAssets.HTML
            .replace("__DATA__", blob)
            .replace("__JS__", TrafficAssets.JS)

        val saved = save(ctx, html)
        Log.i(TAG, "export: ${rows.size} apis, ${html.length} chars -> ${saved.first}")
        return Result(
            saved.first, rows.size, html.toByteArray(Charsets.UTF_8).size.toLong(),
            saved.second,
        )
    }

    /**
     * 把调用链里的类名翻成中文：`RulesManager.checkUpdate → FBDocument.run`
     * → `规则管理器.checkUpdate → Flybird H5 文档.run`
     *
     * 类名保留在后面，因为排查时还需要用英文类名去搜代码。
     * 没收录的类名原样保留 —— 宁可不翻，也不瞎编。
     */
    private fun cnChain(chain: String): String {
        val sb = StringBuilder()
        for (seg in chain.split(" → ")) {
            if (seg.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append(" → ")
            val dot = seg.lastIndexOf('.')
            val cls = if (dot > 0) seg.substring(0, dot) else seg
            val meth = if (dot > 0) seg.substring(dot + 1) else ""
            val cn = ApiDoc.classCn(cls)
            if (cn != null) {
                sb.append(cn).append('（').append(cls).append('）')
            } else {
                sb.append(cls)
            }
            if (meth.isNotEmpty()) sb.append('.').append(meth)
        }
        return sb.toString()
    }

    /**
     * 取某个接口的**全部**请求实例（不只是最新一条）。
     *
     * 用途：
     *   1. 导出里每个接口右侧的折叠按钮，展开后列出这几次请求；
     *   2. 对比功能要对比同一接口的不同请求，需要多条。
     *
     * 每条只保留精简信息（时间、大小、命中次数、正文），不含 headers —— 
     * headers 几百字节且每次都差不多，全带上报告会大好几倍。
     */
    private fun instancesOf(api: String, budget: Int): JSONArray {
        val out = JSONArray()
        val d = TrafficStore.openForRead() ?: return out
        var c: Cursor? = null
        try {
            c = d.rawQuery(
                "SELECT id,ts,dir,raw_len,hits,body,compressed FROM traffic" +
                    " WHERE api=? AND dir IN ('REQ','RESP')" +
                    // 排除我方探测：报告是"宿主行为样本"，掺进自己的调试请求会误导
                    " AND (origin IS NULL OR origin='host')" +
                    " ORDER BY id DESC LIMIT 12",
                arrayOf(api),
            )
            // 请求和响应按「成对」组织：先收集，再按时间配对
            val reqs = ArrayList<Triple<Long, Long, String>>()   // (ts, len, body)
            val resps = ArrayList<Triple<Long, Long, String>>()
            while (c.moveToNext()) {
                val ts = c.getLong(1)
                val dir = c.getString(2)
                val len = c.getLong(3)
                val hits = c.getInt(4)
                val text = TrafficStore.decompress(c.getBlob(5), c.getInt(6) == 1)
                val triple = Triple(ts, len, clip(redact(text)))
                if (dir == "REQ") reqs.add(triple) else resps.add(triple)
                if (hits > 1) {
                    // 命中多次只展开一次，注释放在下面
                }
            }
            // 用时间戳最近的做配对：第 i 个请求配第 i 个响应（倒序，所以从 0 开始）
            val n = maxOf(reqs.size, resps.size)
            for (i in 0 until minOf(n, 12)) {
                val o = JSONObject()
                val rq = if (i < reqs.size) reqs[i] else null
                val rp = if (i < resps.size) resps[i] else null
                o.put("i", i + 1)
                o.put("ts", rq?.first ?: rp?.first ?: 0L)
                // 实例正文单条上限：只是用来 diff「同一接口的两次请求」，
                // 不需要全量。200KB 足够覆盖绝大多数请求体。
                if (rq != null) {
                    o.put("reqLen", rq.second)
                    o.put("req", clip(rq.third, 200_000))
                }
                if (rp != null) {
                    o.put("respLen", rp.second)
                    o.put("resp", clip(rp.third, 200_000))
                }
                out.put(o)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "instancesOf($api) failed: $t")
        } finally {
            try { c?.close() } catch (ignored: Throwable) {}
        }
        return out
    }

    private fun clip(s: String): String = clip(s, MAX_BODY_CHARS)

    private fun clip(s: String, cap: Int): String =
        if (s.length > cap) s.substring(0, cap) + "\n…（已截断）" else s

    /**
     * 按路径找回 content uri。MediaStore 里的文件可以直接用 uri 打开，
     * 不需要 FileProvider（本工程没有 androidx，也没有自定义 provider）。
     */
    @JvmStatic
    fun uriOf(ctx: Context, path: String): android.net.Uri? {
        try {
            if (!path.startsWith("Download/")) {
                // 落在私有目录，只能给 file://
                return android.net.Uri.fromFile(File(path))
            }
            val name = path.substringAfterLast('/')
            val dir = path.substringBeforeLast('/')
            val cur = ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf(name, "%$dir%"),
                "${MediaStore.MediaColumns._ID} DESC",
            )
            if (cur != null) {
                try {
                    if (cur.moveToFirst()) {
                        val id = cur.getLong(0)
                        return android.content.ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, id,
                        )
                    }
                } finally {
                    cur.close()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "uriOf failed: $t")
        }
        return android.net.Uri.fromFile(File("/sdcard/$path"))
    }

    private fun subtitle(n: Int, rows: List<Row>): String {
        var total = 0L
        for (r in rows) total += r.respLen
        val mb = total / 1048576.0
        val sz = if (mb >= 1) String.format(Locale.US, "%.1fMB", mb)
        else (total / 1024).toString() + "KB"
        return "$n 个接口 · 响应合计 $sz · 已脱敏"
    }

    private class Row {
        var api: String = ""
        var ver: String? = null
        var ts: Long = 0
        var hits: Int = 1
        var respLen: Long = 0
        var req: String? = null
        var reqHeaders: String? = null
        var resp: String? = null
        var caller: String? = null
        /** 该接口抓到几条（请求+响应里多的那个），用于决定是否给实例 */
        var instCount: Int = 0
    }

    /**
     * 从库里收集数据：每个接口只留**最新一条**请求和响应。
     * 按时间升序遍历，后来的覆盖前面的。
     */
    private fun collect(onlyApis: Array<String>?): List<Row> {
        val map = LinkedHashMap<String, Row>()
        val order = ArrayList<String>()
        var c: Cursor? = null
        var total = 0L
        try {
            val d = TrafficStore.openForRead() ?: return emptyList()
            // 报告默认只收宿主自己的记录；我方探测的单独标记（origin=ours），
            // 免得"这个接口用什么版本"被自己的调试请求带偏。
            val conds = ArrayList<String>()
            if (onlyApis != null && onlyApis.isNotEmpty()) {
                conds.add("api IN (" + onlyApis.joinToString(",") { "?" } + ")")
            }
            conds.add("(origin IS NULL OR origin='host')")
            val filter = " WHERE " + conds.joinToString(" AND ")
            c = d.rawQuery(
                "SELECT api,ver,ts,dir,hits,raw_len,headers,body,compressed,caller" +
                    " FROM traffic" + filter + " ORDER BY id",
                onlyApis,
            )
            while (c.moveToNext()) {
                val api = c.getString(0) ?: continue
                // NOTE 行是日志摘要（api 为空），不是接口，不能进报告。
                // 之前漏了这个判断，报告里会多出一条 intent/api 全空的记录（踩过）。
                if (api.isEmpty()) continue
                val dr0 = c.getString(3)
                if (dr0 != "REQ" && dr0 != "RESP") continue
                var r = map[api]
                if (r == null) {
                    r = Row()
                    r.api = api
                    r.ts = c.getLong(2)
                    map[api] = r
                    order.add(api)
                }
                r.ts = minOf(r.ts, c.getLong(2))
                r.hits = maxOf(r.hits, c.getInt(4))
                val dir = c.getString(3)
                val text = TrafficStore.decompress(c.getBlob(7), c.getInt(8) == 1)
                r.instCount++
                if (dir == "REQ") {
                    r.req = text
                    r.reqHeaders = c.getString(6)
                    if (c.getString(1) != null) r.ver = c.getString(1)
                    val cal = c.getString(9)
                    if (cal != null) r.caller = cal
                } else if (dir == "RESP") {
                    // 总量控制：报告太大手机打不开，超了就丢掉后面的接口
                    if (total + text.length > MAX_TOTAL_CHARS) continue
                    total += text.length
                    r.resp = text
                    r.respLen = c.getLong(5)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "export collect failed: $t")
        } finally {
            try { c?.close() } catch (ignored: Throwable) {}
        }
        return order.mapNotNull { map[it] }
    }

    // ------------------------------------------------------------ 落盘

    /** 存到 /sdcard/Download/U闲抓包/，返回 (可读路径, content uri) */
    private fun save(ctx: Context, html: String): Pair<String, String?> {
        val stamp = SimpleDateFormat("MMdd-HHmm", Locale.US).format(Date())
        val name = "U闲抓包-$stamp.html"
        val dir = "U闲抓包"
        val bytes = html.toByteArray(Charsets.UTF_8)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues()
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/html")
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$dir")
                val uri = ctx.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv,
                )
                if (uri != null) {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    return Pair("Download/$dir/$name", uri.toString())
                }
            }
            // 29 以下直接写公共目录（需要 WRITE_EXTERNAL_STORAGE，模块已声明）
            val pub = File("/sdcard/Download/$dir")
            if (!pub.exists()) pub.mkdirs()
            val f = File(pub, name)
            FileOutputStream(f).use { it.write(bytes) }
            return Pair(f.absolutePath, null)
        } catch (t: Throwable) {
            Log.w(TAG, "export save failed: $t, fallback to app dir")
            val f = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, name)
            FileOutputStream(f).use { it.write(bytes) }
            return Pair(f.absolutePath, null)
        }
    }
}
