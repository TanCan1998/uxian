package io.github.tancan1998.uxian

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.WeakHashMap

/**
 * 响应过滤器：挂在 MtopResponse.getDataJsonObject() 上，
 * 在宿主解析数据之前把不要的东西摘掉。
 */
object ResponseFilter {

    private val TAG = MtopBridge.tag()

    /**
     * 统一入口：按返回值类型分派。
     *   JSONObject        → 原地改（宿主缓存的是同一个对象）
     *   byte[] / String   → 解析 → 过滤 → 重新序列化返回
     * 返回 null 表示不改，调用方沿用原值。
     */
    @JvmStatic
    fun process(response: Any?, raw: Any?): Any? {
        if (raw is JSONObject) {
            apply(response, raw)
            return null
        }
        var text: String? = null
        if (raw is ByteArray) {
            try {
                text = String(raw, Charsets.UTF_8)
            } catch (ignored: Throwable) {
            }
        } else if (raw is String) {
            text = raw
        }
        if (text.isNullOrEmpty()) return null
        if (!text.trim().startsWith("{")) return null
        return try {
            val o = JSONObject(text)
            apply(response, o)
            val out = o.toString()
            Log.i(TAG, "processed ${out.length} chars via bytes/string")
            if (raw is ByteArray) out.toByteArray(Charsets.UTF_8) else out
        } catch (t2: Throwable) {
            null
        }
    }

    @JvmStatic
    fun apply(response: Any?, json: Any?) {
        if (json !is JSONObject) return

        var api = ""
        try {
            val v = Reflect.field(response!!, "api")
            if (v is String) api = v
        } catch (ignored: Throwable) {
        }
        if (api.isEmpty()) return

        // 完整抓包：每个响应都落盘（含 api / ret / 完整 JSON），不做白名单、
        // 不截断。之前只存 6 个白名单接口 + 600KB 截断，查接口时经常"没有"，
        // 只能靠猜，现在全量存。
        try {
            // 响应也全量入库（gzip 压缩），不再只存 6 个白名单接口。
            // 只读一次 toString：大响应（nextfresh 600KB+）重复序列化很贵。
            TrafficStore.record("RESP", api, null, null, null, json.toString())
        } catch (ignored: Throwable) {
        }

        // 先把宿主拿到的结果存一份（必须在过滤之前，存的是原始数据）。
        // 这是主搜索唯一可行的取数路径：那个接口从外部重发会被服务端拒
        // （FAIL_BIZ_COMMON_PARAM_ILLEGAL），但宿主自己发是成功的。
        //
        // 注意：getDataJsonObject() 会被调用多次。第一次拿到的是原始数据，
        // 之后几次拿到的是**已被本类改过**的对象。如果不加去重，缓存会被
        // 过滤后的版本覆盖，导致"缓存里看不到广告"的自证循环（踩过）。
        if (markCapturedOnce(response)) {
            capture(api, json)
        }
        val ctx: Context = Protocol.State.app ?: return

        try {
            if (api.contains("idlemessage.session.sync") &&
                Prefs.get(ctx, Prefs.FILTER_SERVICE, true)
            ) {
                filterServiceSessions(json)
            }
            if (api.contains("idlehome.home") || api.contains("home.nextfresh")) {
                maybeDumpFeed(ctx, api, json)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "filter failed ($api): $t")
        }
    }

    @Volatile
    private var feedDumps = 0

    /**
     * 每个响应对象只允许捕获一次。
     * getDataJsonObject() 会被调用多次，第一次是原始数据，之后是已被改过的对象。
     * 用 WeakHashMap 按对象身份去重，不影响 GC。
     */
    private val CAPTURED: MutableMap<Any, Boolean> =
        java.util.Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    private fun markCapturedOnce(response: Any?): Boolean =
        try {
            synchronized(CAPTURED) {
                if (CAPTURED.containsKey(response)) {
                    false
                } else {
                    CAPTURED[response!!] = java.lang.Boolean.TRUE
                    if (CAPTURED.size > 64) CAPTURED.clear()
                    true
                }
            }
        } catch (t: Throwable) {
            false
        }

    /**
     * 缓存宿主自己拿到的响应，供外部按需读取。
     *
     * 背景：主搜索接口（mtop.taobao.idlemtopsearch.search）从外部重发必定被拒
     * （FAIL_BIZ_COMMON_PARAM_ILLEGAL，参数与宿主逐字段一致也没用），
     * 但宿主自己发是成功的，响应会经过这里。
     * 所以改成"宿主搜一次，我们把结果存下来"，外部再读缓存。
     *
     * 只存白名单里的 api，避免把整个 App 的响应都囤在内存里。
     */
    private val CAPTURE_APIS = arrayOf(
        "mtop.taobao.idlemtopsearch.search",
        "mtop.taobao.idlemtopsearch.search.discover",
        "mtop.taobao.idlemtopsearch.search.suggest",
        "mtop.taobao.idle.awesome.detail.unit",
        "mtop.taobao.idlemessage.session.sync",
        "mtop.taobao.idlehome.home.nextfresh",
    )

    /** 单个响应的最大留存字符数，超了截断（够用且防爆内存） */
    private const val MAX_CAPTURE = 600 * 1024

    private fun capture(api: String, json: JSONObject) {
        try {
            if (!CAPTURE_APIS.contains(api)) return
            var text = json.toString()
            if (text.length > MAX_CAPTURE) {
                text = text.substring(0, MAX_CAPTURE)
            }
            Protocol.State.capture(api, text)
        } catch (t: Throwable) {
            Log.w(TAG, "capture failed ($api): $t")
        }
    }

    /** 临时：把首页响应的结构挖出来，用于设计推广过滤规则 */
    private fun maybeDumpFeed(ctx: Context, api: String, root: JSONObject) {
        if (!Prefs.get(ctx, Prefs.DEBUG, false)) return
        if (feedDumps >= 40) return   // 上限只防爆量，调试时足够抓多页
        feedDumps++
        val sb = StringBuilder("=== $api #$feedDumps\n")
        try {
            val it = root.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = root.opt(k)
                if (v is JSONArray) {
                    sb.append("array ").append(k).append(" len=").append(v.length()).append("\n")
                    if (v.length() > 0) {
                        var fs = v.opt(0).toString()
                        if (fs.length > 900) fs = fs.substring(0, 900) + "..."
                        sb.append("  [0] ").append(fs).append("\n")
                    }
                    // 逐条给摘要：找推广标记
                    for (i in 0 until v.length()) {
                        val o = v.opt(i)
                        if (o !is JSONObject) continue
                        val data = o.optJSONObject("data")
                        val line = StringBuilder("  #").append(i)
                            .append(" cardType=").append(o.optString("cardType"))
                            .append(" sectionType=").append(o.optString("sectionType"))
                        if (data != null) {
                            line.append(" bizType=").append(data.optString("bizType"))
                            val tpl = data.optJSONObject("template")
                            if (tpl != null) line.append(" tpl=").append(tpl.optString("name"))
                            val cp = data.optJSONObject("clickParam")
                            if (cp != null) line.append(" clickKeys=").append(cp.keySet())
                        }
                        if (data != null) {
                            val cp2 = data.optJSONObject("clickParam")
                            val args2 = cp2?.optJSONObject("args")
                            if (args2 != null) {
                                line.append(" adsId=").append(args2.optString("idleAdsTaskId"))
                                    .append(" track=").append(args2.optString("trackName"))
                                    .append(" match=").append(args2.optString("matchType"))
                            }
                        }
                        line.append(" ads=").append(o.optString("idleAdsTaskId"))
                            .append(" mocked=").append(o.optString("mocked"))
                            .append(" topic=").append(o.optString("moreTopicId"))
                        sb.append(line).append("\n")
                    }
                } else if (v is JSONObject) {
                    var fs = v.toString()
                    if (fs.length > 400) fs = fs.substring(0, 400) + "..."
                    sb.append("object ").append(k).append(" ").append(fs).append("\n")
                } else {
                    sb.append("value ").append(k).append("=").append(v).append("\n")
                }
            }
        } catch (t: Throwable) {
            sb.append("dump err ").append(t)
        }
        Protocol.State.dumpTo("xy_feed.txt", sb.toString())
    }

    /**
     * 消息列表过滤服务号/系统号。
     *
     * 2026-10-28 修正：旧实现只保留 sessionType == "1"，但实测宿主请求的
     * sessionTypes 是 [3,6,10,63,25,26,62,23]，说明正常会话类型远不止 "1"。
     * 旧逻辑把 9 条真人会话全部清空（日志 filter:sessions 9->0），
     * 是严重的数据破坏 bug。
     *
     * 现在的判据：只剔除明确的服务/系统会话类型，其余一律保留。
     * 拿不准就保留 —— 宁可漏过滤，也不能清空用户的聊天列表。
     */
    @Throws(Exception::class)
    private fun filterServiceSessions(root: JSONObject) {
        val arr = root.optJSONArray("sessions") ?: return
        val keep = JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (isServiceSession(item)) continue
            keep.put(item)
        }
        if (keep.length() != arr.length()) {
            // 诊断：记录被删的昵称，确认没误删真人
            val removed = StringBuilder()
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i)
                if (it == null || !isServiceSession(it)) continue
                val se = it.optJSONObject("session")
                val u = se?.optJSONObject("userInfo")
                removed.append(if (se == null) "?" else se.optString("sessionType"))
                    .append(':')
                    .append(if (u == null) "?" else u.optString("nick", ""))
                    .append(' ')
            }
            root.put("sessions", keep)
            // 累计到「消息页过滤服务号」，适配性页显示历史拦截数
            HookState.hit("message")
            Protocol.State.tapLog("filter:sessions ${arr.length()}->${keep.length()}")
            Protocol.State.logLine(
                "消息过滤 ${arr.length()}->${keep.length()} 删: " + removed.toString().trim(),
            )
        }
    }

    /**
     * 服务号/系统号会话判定。
     *
     * 2026-10-28 用真机数据定标（9 条会话全是官方号）：
     *   type=25 闲鱼精选   type=10 关注上新   type=63 服务号
     *   type=3  系统消息   type=62 交易消息   type=23 闲鱼币/圈子/活动/卖家助手
     * 这些 type 全部是服务类。真人私聊是 type=1。
     *
     * 旧实现（只保留 type=="1"）会把 9 条全删光；现在反过来：
     * 只剔除明确的官方类型，其余一律保留 —— 宁可漏过滤，也不清空用户列表。
     */
    private val SERVICE_TYPES: Set<String> =
        HashSet(listOf("3", "10", "23", "25", "62", "63", "100", "999"))

    private fun isServiceSession(item: JSONObject): Boolean =
        try {
            val session = item.optJSONObject("session") ?: return false
            val t = session.optString("sessionType", "")
            if (SERVICE_TYPES.contains(t)) {
                true
            } else {
                // 兜底：昵称是明确的官方号
                val user = session.optJSONObject("userInfo")
                val nick = user?.optString("nick", "") ?: ""
                nick.contains("服务号") || nick.contains("系统消息") ||
                    nick.contains("闲鱼精选") || nick.contains("交易消息")
            }
        } catch (e: Throwable) {
            false
        }
}