package io.github.tancan1998.uxian

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import org.json.JSONArray
import org.json.JSONObject
import java.util.WeakHashMap

/**
 * 首页顶部 tab（首页 / 集市 / 圈子 / 回收）的顺序与显隐。
 *
 * ⚠️ 协议层返回的是 **Bean**（`Fy26HomeCircleListResp`，headList 字段），
 * 不是 Map —— 和搜索结果一样，改 org.json 那层驱动不了页面。
 */
object HomeTabOrder {
    const val API = "mtop.taobao.idlehome.home.circle.list"
    private const val ORDER = "hometab_order"
    private const val CATALOG = "hometab_catalog"

    data class Item(val id: String, val title: String, val hidden: Boolean)

    private val originals = WeakHashMap<Any, ArrayList<Any?>>()
    @Volatile private var lastApplied = ""
    @Volatile private var hits = 0
    @Volatile private var lastShape = ""

    private fun field(o: Any?, name: String): Any? {
        if (o == null) return null
        if (o is JSONObject) {
            // android 的 JSONObject 不实现 Map，必须单独判
            val v = o.opt(name)
            return if (v == null || v == JSONObject.NULL) null else v
        }
        if (o is Map<*, *>) return o[name]
        return try { Reflect.field(o, name) } catch (_: Throwable) { null }
    }

    private fun str(o: Any?, name: String): String {
        if (o is JSONObject) {
            val v = o.optString(name, "")
            return if (v == "null") "" else v
        }
        val v = field(o, name) ?: return ""
        if (v is String) return if (v == "null") "" else v
        return ""
    }

    /** bizCode 稳定；缺失时退 circleId / 标题。 */
    fun identity(o: Any?): String {
        val biz = str(o, "bizCode")
        if (biz.isNotEmpty()) return "biz:" + biz
        val cid = str(o, "circleId")
        if (cid.isNotEmpty()) return "cid:" + cid
        val t = title(o)
        return if (t.isEmpty()) "" else "title:" + t
    }

    fun title(o: Any?): String {
        val info = field(o, "showInfo")
        val t = str(info, "title")
        return if (t.isEmpty()) str(o, "bizCode") else t
    }

    private fun read(ctx: Context, key: String): JSONArray = try {
        JSONArray(Prefs.getString(ctx, key, "[]") ?: "[]")
    } catch (_: Throwable) { JSONArray() }

    /** 原地重排 + 隐藏：headList 是宿主直接消费的列表，不能换成新对象。 */
    @Synchronized
    fun apply(ctx: Context, root: Any?) {
        val list = headListOf(root)
        if (list == null || list.isEmpty()) {
            lastShape = (root?.javaClass?.simpleName ?: "null")
            return
        }
        var source = originals[list]
        if (source == null) {
            source = ArrayList<Any?>()
            for (i in 0 until list.size) source.add(list[i])
            originals[list] = source
        }
        val catalog = JSONArray()
        for (o in source) {
            val id = identity(o)
            if (id.isNotEmpty()) catalog.put(JSONObject().put("id", id).put("title", title(o)))
        }
        val text = catalog.toString()
        if (catalog.length() > 0 && Prefs.getString(ctx, CATALOG, "") != text) {
            Prefs.setString(ctx, CATALOG, text)
        }
        val pref = read(ctx, ORDER)
        if (pref.length() == 0) return

        var hiddenCount = 0
        for (i in 0 until pref.length()) {
            if (pref.optJSONObject(i)?.optBoolean("hidden", false) == true) hiddenCount++
        }
        // 全部隐藏的话首页没有内容入口，直接不应用
        if (hiddenCount >= source.size) return

        val out = arrange(source, pref)
        if (out.isEmpty()) return

        val before = list.size
        while (list.size > 0) list.removeAt(list.size - 1)
        for (o in out) (list as MutableList<Any?>).add(o)

        val titles = StringBuilder()
        for (o in out) {
            if (titles.length > 0) titles.append('/')
            titles.append(title(o))
        }
        lastApplied = titles.toString()
        if (before != list.size || out.size == before) {
            hits++
            Protocol.State.logLine("首页 tab 已应用：$lastApplied")
        }
    }

    /** 纯函数：按偏好排序、剔除隐藏项；未知项按原顺序接在末尾。 */
    fun arrange(source: List<Any?>, pref: JSONArray): ArrayList<Any?> {
        val order = ArrayList<String>()
        val hidden = HashSet<String>()
        for (i in 0 until pref.length()) {
            val o = pref.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty()) continue
            order.add(id)
            if (o.optBoolean("hidden", false)) hidden.add(id)
        }
        val out = ArrayList<Any?>()
        val used = BooleanArray(source.size)
        for (id in order) {
            for (i in source.indices) {
                val jo = source[i] ?: continue
                if (!used[i] && identity(jo) == id) {
                    used[i] = true
                    if (!hidden.contains(id)) out.add(source[i])
                }
            }
        }
        for (i in source.indices) {
            val jo = source[i] ?: continue
            if (used[i] || hidden.contains(identity(jo))) continue
            out.add(source[i])
        }
        return out
    }

    /** 兼容 Map 与 Bean 两种形态。 */
    private fun headListOf(root: Any?): MutableList<*>? {
        if (root is Map<*, *>) {
            val direct = root["headList"]
            if (direct is MutableList<*>) return direct
            val inner = root["data"] as? Map<*, *>
            val nested = inner?.get("headList")
            if (nested is MutableList<*>) return nested
        }
        val l = field(root, "headList")
        return l as? MutableList<*>
    }

    fun items(ctx: Context): ArrayList<Item> {
        val catalog = read(ctx, CATALOG)
        val pref = read(ctx, ORDER)
        val out = ArrayList<Item>()
        val seen = HashSet<String>()
        for (i in 0 until pref.length()) {
            val o = pref.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty() || !seen.add(id)) continue
            out.add(Item(id, o.optString("title", id), o.optBoolean("hidden", false)))
        }
        for (i in 0 until catalog.length()) {
            val o = catalog.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty() || !seen.add(id)) continue
            out.add(Item(id, o.optString("title", id), false))
        }
        return out
    }

    fun save(ctx: Context, items: List<Item>): Boolean {
        val arr = JSONArray()
        val seen = HashSet<String>()
        for (it in items) {
            if (it.id.isEmpty() || !seen.add(it.id)) continue
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("hidden", it.hidden))
        }
        Prefs.setString(ctx, ORDER, arr.toString())
        return Prefs.getString(ctx, ORDER, null) == arr.toString()
    }

    fun reset(ctx: Context): Boolean {
        Prefs.setString(ctx, ORDER, "[]")
        return read(ctx, ORDER).length() == 0
    }

    fun summary(ctx: Context): String {
        val arr = read(ctx, ORDER)
        if (arr.length() == 0) return "默认顺序"
        var hidden = 0
        for (i in 0 until arr.length()) {
            if (arr.optJSONObject(i)?.optBoolean("hidden", false) == true) hidden++
        }
        return if (hidden > 0) "自定义 · 隐藏 $hidden" else "自定义"
    }

    /** 无头验证：只改本地配置，不发请求。 */
    fun cursor(ctx: Context?, selection: String): Cursor {
        val c = MatrixCursor(arrayOf("key", "value"))
        if (ctx == null) { c.addRow(arrayOf("error", "宿主尚未就绪")); return c }
        val prefix = "xysidecar:hometab:set:"
        if (selection.startsWith(prefix)) {
            try {
                val arr = JSONArray(selection.substring(prefix.length))
                val cur = items(ctx)
                val chosen = ArrayList<Item>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id")
                    for (it in cur) if (it.id == id) {
                        chosen.add(Item(it.id, it.title, o.optBoolean("hidden", false)))
                        break
                    }
                }
                c.addRow(arrayOf("saved", save(ctx, chosen).toString()))
            } catch (t: Throwable) { c.addRow(arrayOf("error", t.message ?: "参数错误")) }
        } else if (selection == "xysidecar:hometab:reset") {
            c.addRow(arrayOf("reset", reset(ctx).toString()))
        }
        c.addRow(arrayOf("mode", summary(ctx)))
        c.addRow(arrayOf("catalog", read(ctx, CATALOG).toString()))
        c.addRow(arrayOf("order", read(ctx, ORDER).toString()))
        c.addRow(arrayOf("applied", lastApplied))
        c.addRow(arrayOf("shape", lastShape))
        c.addRow(arrayOf("hits", hits.toString()))
        return c
    }
}
