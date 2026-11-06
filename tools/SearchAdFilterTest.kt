package io.github.tancan1998.uxian

import java.io.File

/**
 * 不依赖 fastjson / Android 的离线测试：使用最小 JSON 解析器读取真实响应。
 * 样本来自宿主搜索响应（advertisement + 普通商品混排），不发网络请求。
 */
fun main(args: Array<String>) {
    val samples = parse(File(args[0]).readText()) as List<*>
    var ads = 0
    var goods = 0
    for ((i, raw) in samples.withIndex()) {
        val sample = raw as Map<*, *>
        val entries = sample["resultList"] as? List<*> ?: continue
        val originals = ArrayList<Any?>(entries)
        val root = HashMap<Any?, Any?>()
        root["resultList"] = originals
        var expectAds = 0
        for (e in originals) {
            val d = (e as Map<*, *>)["data"] as? Map<*, *>
            val main = (d?.get("item") as? Map<*, *>)?.get("main") as? Map<*, *>
            val args = ((main?.get("clickParam") as? Map<*, *>)?.get("args")) as? Map<*, *>
            val track = args?.get("idle_ad_track")
            val cid = args?.get("idle_ad_cid")
            if (args?.get("item_type") == "ad" ||
                (track is String && track.isNotEmpty() && cid is String && cid.isNotEmpty())) expectAds++
        }
        val r = SearchAdFilter.filter(root)
        check(r.recognized && r.before == entries.size)
        check(r.removed == expectAds) { "sample $i removed ${r.removed} expected $expectAds" }
        val kept = (r.value as Map<*, *>)["resultList"] as List<*>
        check(kept.size + expectAds == entries.size)
        for (e in kept) check(!SearchAdFilter.isAd(e))
        ads += r.removed
        goods += kept.size
        check(originals.size == entries.size) { "source list mutated" }
        if (r.removed > 0) {
            check((r.value as Map<*, *>)["resultList"] !== originals) { "returned original list" }
        }
    }
    check(SearchAdFilter.filter(null).removed == 0)
    check(!SearchAdFilter.filter(HashMap<Any?, Any?>()).recognized)
    check(SearchAdFilter.filter(mapOf("data" to mapOf("resultList" to listOf<Any?>()))).recognized)
    println("PASS: samples=${samples.size} removedAds=$ads keptGoods=$goods; source untouched; odd shapes safe")
}

/** 小型 JSON 解析器，支持对象/数组/字符串/数字/true/false/null 与转义。 */
private fun parse(s: String): Any? {
    var i = 0
    fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    fun str(): String {
        i++
        val b = StringBuilder()
        while (s[i] != '"') {
            if (s[i] == '\\') {
                i++
                when (s[i]) {
                    'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t')
                    'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                    'u' -> { b.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                    else -> b.append(s[i])
                }
            } else b.append(s[i])
            i++
        }
        i++
        return b.toString()
    }
    fun value(): Any? {
        ws()
        return when (s[i]) {
            '{' -> {
                i++; ws(); val m = LinkedHashMap<Any?, Any?>()
                if (s[i] == '}') { i++; return m }
                while (true) {
                    ws(); val k = str(); ws(); i++ // ':'
                    m[k] = value(); ws()
                    if (s[i] == ',') { i++; continue }
                    i++; break
                }
                m
            }
            '[' -> {
                i++; ws(); val a = ArrayList<Any?>()
                if (s[i] == ']') { i++; return a }
                while (true) {
                    a.add(value()); ws()
                    if (s[i] == ',') { i++; continue }
                    i++; break
                }
                a
            }
            '"' -> str()
            't' -> { i += 4; true }
            'f' -> { i += 5; false }
            'n' -> { i += 4; null }
            else -> {
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
                s.substring(start, i).toDouble()
            }
        }
    }
    return value()
}
