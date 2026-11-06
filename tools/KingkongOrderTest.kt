package io.github.tancan1998.uxian

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 使用真实首页 fixture 检查对象完整性及边界，不发网络请求。 */
fun main(args: Array<String>) {
    val root = JSONObject(File(args[0]).readText())
    val arrays = KingkongOrder.arrays(root)
    check(arrays.size == 1)
    val source = arrays[0]
    check(source.length() == 20)
    val before = source.toString()
    val last = source.getJSONObject(19)
    val first = source.getJSONObject(0)
    val order = JSONArray().put(KingkongOrder.identity(last)).put(KingkongOrder.identity(first))
    val sorted = KingkongOrder.arranged(source, order)
    check(sorted.get(0) === last)
    check(sorted.get(1) === first)
    check(sorted.length() == source.length())
    check(source.toString() == before)
    for (i in 0 until source.length()) {
        var count = 0
        for (j in 0 until sorted.length()) if (source.get(i) === sorted.get(j)) count++
        check(count == 1)
    }
    check(KingkongOrder.arranged(sorted, order).toString() == sorted.toString())
    check(KingkongOrder.arranged(source, JSONArray()).toString() == before)
    val stale = JSONArray().put("entry:offline").put(KingkongOrder.identity(last)).put(KingkongOrder.identity(last))
    check(KingkongOrder.arranged(source, stale).length() == 20)
    val extra = JSONObject().put("title", "新入口").put("targetUrl", "https://example.com/new?q=1")
    val mixed = JSONArray().put(first).put(JSONObject.NULL).put("unknown").put(first).put(extra)
    val result = KingkongOrder.arranged(mixed, JSONArray().put(KingkongOrder.identity(first)))
    check(result.length() == 5)
    check(result.get(0) === first && result.get(1) === first)
    check(result.isNull(2) && result.getString(3) == "unknown" && result.get(4) === extra)
    check(KingkongOrder.identity(extra) == "url:https://example.com/new")
    check(KingkongOrder.arrays(JSONObject()).isEmpty())
    check(KingkongOrder.arrays(JSONObject().put("homeTopList", "changed-schema")).isEmpty())
    check(KingkongOrder.arrays(JSONObject().put("homeTopList", JSONArray().put(JSONObject().put("widgets", JSONArray().put(JSONObject().put("widgetDO", JSONObject().put("channelDOList", JSONArray()))))))).isEmpty())
    println("PASS: 20 real entries; full object preservation; idempotence; reset; missing/new/duplicate IDs; malformed/empty structures")
}
