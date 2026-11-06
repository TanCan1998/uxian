package io.github.tancan1998.uxian

/** 只处理已确认属于搜索接口的 Flutter 回调 Map，按路径复制，保留其他字段及对象。 */
object SearchAdFilter {
    const val API = "mtop.taobao.idlemtopsearch.search"
    data class Result(val value: Any?, val before: Int, val removed: Int, val recognized: Boolean)

    private fun map(v: Any?): Map<*, *>? = v as? Map<*, *>
    private fun text(v: Any?): String = if (v == null) "" else v.toString()
    private fun nonempty(v: Any?): Boolean {
        val s = text(v)
        return s.isNotEmpty() && s != "null" && s != "false" && s != "0"
    }

    fun isAd(entry: Any?): Boolean {
        val e = map(entry) ?: return false
        val data = map(e["data"]) ?: return false
        val item = map(data["item"]) ?: return false
        val main = map(item["main"]) ?: return false
        val click = map(main["clickParam"]) ?: return false
        val args = map(click["args"]) ?: return false
        if (text(args["item_type"]).equals("ad", true) ||
            text(args["biz_type"]).equals("ad", true) ||
            text(args["tag"]).equals("ad", true)) return true
        // 普通商品也有 idle_search_adx=-1、可能带空广告字段，不能按字段存在就删。
        // 实测付费推广：item_type=goods，但有非空广告追踪串及投放 ID。
        return nonempty(args["idle_ad_track"]) &&
            (nonempty(args["idle_ad_cid"]) || nonempty(args["ad_group_id"]))
    }

    /**
     * 原地过滤：fastjson 的 JSONObject/JSONArray 实现了 Map/List，直接删元素
     * 仍是合法 JSON。用于 ResponseParameter.getData() 返回给多方的场景，
     * 不能替换成 LinkedHashMap（toString 会变成 Java Map 格式）。
     */
    /** 没有 JSON 解析器时对 fastjson 文本判定（紧凑格式，无空格）。 */
    fun isAdText(s: String?): Boolean {
        if (s == null || s.length < 20) return false
        if (s.indexOf("\"item_type\":\"ad\"") >= 0) return true
        if (s.indexOf("\"biz_type\":\"ad\"") >= 0) return true
        if (s.indexOf("\"tag\":\"ad\"") >= 0) return true
        val t = s.indexOf("\"idle_ad_track\":\"")
        val c = s.indexOf("\"idle_ad_cid\":\"")
        return t >= 0 && c >= 0 &&
            s[t + 17] != '"' && s[c + 15] != '"'
    }

    /** Bean 元素：优先看 data 字段，其次看自身文本。 */
    fun isAdElement(e: Any?): Boolean {
        if (e == null) return false
        if (e is Map<*, *>) return isAd(e)
        val data = try { Reflect.field(e, "data") } catch (_: Throwable) { null }
        if (data is Map<*, *>) {
            val wrapper = HashMap<Any?, Any?>()
            wrapper["data"] = data
            if (isAd(wrapper)) return true
        }
        return try { isAdText(e.toString()) } catch (_: Throwable) { false }
    }

    fun filter(value: Any?): Result = walk(value, 0)

    fun filterInPlace(value: Any?): Result = inPlace(value, 0)

    private fun inPlace(value: Any?, depth: Int): Result {
        val root = map(value) ?: return Result(value, 0, 0, false)
        val list = root["resultList"]
        if (list is MutableList<*>) {
            var removed = 0
            var i = 0
            while (i < list.size) {
                if (isAd(list[i])) {
                    try {
                        list.removeAt(i)
                        removed++
                        continue
                    } catch (_: Throwable) {
                        i++
                        continue
                    }
                }
                i++
            }
            return Result(value, list.size + removed, removed, true)
        }
        if (depth < 2) {
            val nested = root["data"]
            if (nested is Map<*, *>) {
                val r = inPlace(nested, depth + 1)
                if (r.recognized) return r
            }
        }
        return Result(value, 0, 0, false)
    }

    private fun copy(source: Map<*, *>): LinkedHashMap<Any?, Any?> {
        val out = LinkedHashMap<Any?, Any?>()
        for ((key, value) in source) out[key] = value
        return out
    }

    private fun walk(value: Any?, depth: Int): Result {
        val root = map(value) ?: return Result(value, 0, 0, false)
        val list = root["resultList"] as? List<*>
        if (list != null) {
            val kept = ArrayList<Any?>()
            var removed = 0
            for (entry in list) {
                if (isAd(entry)) removed++ else kept.add(entry)
            }
            if (removed == 0) return Result(value, list.size, 0, true)
            val changed = copy(root)
            changed["resultList"] = kept
            return Result(changed, list.size, removed, true)
        }
        // 宿主包装为 {data:业务对象}，兼容再套一层网关 data；不递归扫描商品内部。
        if (depth < 2 && root["data"] is Map<*, *>) {
            val nested = walk(root["data"], depth + 1)
            if (nested.recognized) {
                if (nested.removed == 0) return Result(value, nested.before, 0, true)
                val changed = copy(root)
                changed["data"] = nested.value
                return Result(changed, nested.before, nested.removed, true)
            }
        }
        return Result(value, 0, 0, false)
    }
}
