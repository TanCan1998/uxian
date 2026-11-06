package com.xiandehuang.u

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import org.json.JSONArray
import org.json.JSONObject
import java.util.WeakHashMap

/** 只重排首页金刚位中的完整入口对象，不改图标、链接或埋点。 */
object KingkongOrder {
    const val API = "mtop.taobao.idlehome.home.nextfresh"
    private const val ORDER = "kingkong_order"
    private const val CATALOG = "kingkong_catalog"
    data class Item(val id: String, val title: String)
    private val originals = WeakHashMap<JSONArray, JSONArray>()
    @Volatile private var lastApplied = ""
    @Volatile private var hits = 0

    /** ID 取运营入口标识（不是广告判据）；缺失时退到无查询参数的目标地址。 */
    fun identity(o: JSONObject): String {
        val args = o.optJSONObject("clickParam")?.optJSONObject("args")
        val id = args?.optString("idleAdsTaskId", "") ?: ""
        if (id.isNotEmpty() && id != "null") return "entry:" + id
        var url = o.optString("targetUrl", "")
        val q = url.indexOf('?')
        if (q >= 0) url = url.substring(0, q)
        val h = url.indexOf('#')
        if (h >= 0) url = url.substring(0, h)
        if (url.isNotEmpty() && url != "null") return "url:" + url
        val title = o.optString("title", "")
        return if (title.isEmpty() || title == "null") "" else "title:" + title
    }

    fun arrays(root: JSONObject): ArrayList<JSONArray> {
        val out = ArrayList<JSONArray>()
        val top = root.optJSONArray("homeTopList") ?: return out
        for (i in 0 until top.length()) {
            val widgets = top.optJSONObject(i)?.optJSONArray("widgets") ?: continue
            for (j in 0 until widgets.length()) {
                val widget = widgets.optJSONObject(j) ?: continue
                if (widget.optString("widgetType") != "channelWidget") continue
                val data = widget.optJSONObject("widgetDO") ?: continue
                val a = data.optJSONArray("channelDOList") ?: continue
                if (a.length() > 0) out.add(a)
            }
        }
        return out
    }

    /** 稳定重排：未知项按原顺序接在末尾，重复 ID、非对象项均不丢失。 */
    fun arranged(source: JSONArray, order: JSONArray): JSONArray {
        val out = JSONArray()
        val used = BooleanArray(source.length())
        for (j in 0 until order.length()) {
            val key = order.optString(j, "")
            if (key.isEmpty()) continue
            for (i in 0 until source.length()) {
                val item = source.optJSONObject(i) ?: continue
                if (!used[i] && identity(item) == key) {
                    used[i] = true
                    out.put(item)
                }
            }
        }
        for (i in 0 until source.length()) if (!used[i]) out.put(source.opt(i))
        return out
    }

    /** 偏好项：新格式是对象，旧格式是纯 id 字符串（两种都能读）。 */
    private fun readItems(ctx: Context): ArrayList<Item> {
        val arr = read(ctx, ORDER)
        val out = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            if (v is String) {
                if (v.isNotEmpty()) out.add(Item(v, ""))
            } else if (v is JSONObject) {
                val id = v.optString("id", "")
                if (id.isNotEmpty()) out.add(Item(id, v.optString("title", "")))
            }
        }
        return out
    }

    private fun idsOf(items: List<Item>): JSONArray {
        val a = JSONArray()
        for (it in items) if (it.id.isNotEmpty()) a.put(it.id)
        return a
    }


    private fun read(ctx: Context, key: String): JSONArray = try {
        JSONArray(Prefs.getString(ctx, key, "[]") ?: "[]")
    } catch (_: Throwable) { JSONArray() }

    @Synchronized
    fun apply(ctx: Context, root: JSONObject) {
        val lists = arrays(root)
        if (lists.isEmpty()) return // 分页空响应不清掉首页目录
        val catalog = JSONArray()
        val seen = HashSet<String>()
        val prefs = readItems(ctx)
        val order = idsOf(prefs)
        val applied = JSONArray()
        var changed = false
        for (list in lists) {
            var source = originals[list]
            if (source == null) {
                source = JSONArray()
                for (i in 0 until list.length()) source.put(list.opt(i))
                originals[list] = source
            }
            for (i in 0 until source.length()) {
                val o = source.optJSONObject(i) ?: continue
                val id = identity(o)
                if (id.isNotEmpty() && seen.add(id)) {
                    catalog.put(JSONObject().put("id", id).put("title", o.optString("title", "入口")))
                }
            }
            val sorted = arranged(source, order)
            for (i in 0 until sorted.length()) {
                if (list.opt(i) !== sorted.opt(i)) changed = true
                list.put(i, sorted.opt(i))
                val o = sorted.optJSONObject(i)
                if (o != null) applied.put(o.optString("title", ""))
            }
        }
        val text = catalog.toString()
        if (catalog.length() > 0 && Prefs.getString(ctx, CATALOG, "") != text) {
            Prefs.setString(ctx, CATALOG, text)
        }
        lastApplied = applied.toString()
        if (changed) {
            hits++
            Protocol.State.logLine("金刚位顺序已应用（${catalog.length()} 个入口）")
        }
    }



    fun items(ctx: Context): ArrayList<Item> {
        val catalog = read(ctx, CATALOG)
        val out = ArrayList<Item>()
        val seen = HashSet<String>()
        for (it in readItems(ctx)) {
            if (!seen.add(it.id)) continue
            var title = it.title
            if (title.isEmpty()) {
                for (j in 0 until catalog.length()) {
                    val o = catalog.optJSONObject(j) ?: continue
                    if (o.optString("id") == it.id) { title = o.optString("title"); break }
                }
            }
            out.add(Item(it.id, title))
        }
        for (j in 0 until catalog.length()) {
            val o = catalog.optJSONObject(j) ?: continue
            val id = o.optString("id")
            if (id.isNotEmpty() && seen.add(id)) out.add(Item(id, o.optString("title")))
        }
        return out
    }

    fun save(ctx: Context, items: List<Item>): Boolean {
        val order = JSONArray()
        val seen = HashSet<String>()
        for (item in items) {
            if (item.id.isEmpty() || !seen.add(item.id)) continue
            order.put(JSONObject().put("id", item.id).put("title", item.title))
        }
        // 临时下线的入口保留偏好，重新出现时仍能找到它
        for (it in readItems(ctx)) {
            if (!seen.add(it.id)) continue
            order.put(JSONObject().put("id", it.id).put("title", it.title))
        }
        return write(ctx, order.toString())
    }

    private fun write(ctx: Context, value: String): Boolean {
        Prefs.setString(ctx, ORDER, value)
        return Prefs.getString(ctx, ORDER, null) == value
    }
    fun reset(ctx: Context): Boolean = write(ctx, "[]")
    fun summary(ctx: Context): String {
        val arr = read(ctx, ORDER)
        if (arr.length() == 0) return "默认顺序"
        return "自定义"
    }

    /** 无头验证入口，只操作本地排序设置。 */
    fun cursor(ctx: Context?, selection: String): Cursor {
        val c = MatrixCursor(arrayOf("key", "value"))
        if (ctx == null) { c.addRow(arrayOf("error", "宿主尚未就绪")); return c }
        val prefix = "xysidecar:kingkong:set:"
        if (selection.startsWith(prefix)) {
            try {
                val order = JSONArray(selection.substring(prefix.length))
                val current = items(ctx)
                val chosen = ArrayList<Item>()
                for (i in 0 until order.length()) {
                    val v = order.opt(i)
                    val id = if (v is JSONObject) v.optString("id") else order.getString(i)
                    var found = false
                    for (item in current) if (item.id == id) {
                        chosen.add(Item(item.id, item.title)); found = true; break
                    }
                    require(found) { "入口不存在：$id" }
                }
                c.addRow(arrayOf("saved", save(ctx, chosen).toString()))
            } catch (t: Throwable) { c.addRow(arrayOf("error", t.message ?: "参数错误")) }
        } else if (selection == "xysidecar:kingkong:reset") {
            c.addRow(arrayOf("reset", reset(ctx).toString()))
        }
        c.addRow(arrayOf("mode", summary(ctx)))
        c.addRow(arrayOf("catalog", read(ctx, CATALOG).toString()))
        c.addRow(arrayOf("order", read(ctx, ORDER).toString()))
        c.addRow(arrayOf("applied", lastApplied))
        c.addRow(arrayOf("hits", hits.toString()))
        return c
    }
}
