package com.xiandehuang.u

import java.io.File
import java.lang.reflect.Proxy

/** 验证 MethodChannel.Result 代理：只改 success(Object)，其余调用原样转发。 */
interface TestResult {
    fun success(o: Any?)
    fun error(code: String?, msg: String?, details: Any?)
}

fun main(args: Array<String>) {
    val text = File(args[0]).readText()
    val root = HashMap<Any?, Any?>()
    root["resultList"] = listOf(ad(), goods(), ad())
    var seen: Any? = null
    var errors = 0
    val target = object : TestResult {
        override fun success(o: Any?) { seen = o }
        override fun error(code: String?, msg: String?, details: Any?) { errors++ }
    }
    val proxy = Proxy.newProxyInstance(TestResult::class.java.classLoader, arrayOf(TestResult::class.java)) { _, m, a ->
        if (m.name == "success" && a != null && a.size == 1) {
            val r = SearchAdFilter.filter(a[0])
            if (r.removed > 0) return@newProxyInstance m.invoke(target, *arrayOf<Any?>(r.value))
        }
        m.invoke(target, *(a ?: emptyArray<Any?>()))
    }
    val typed = proxy as TestResult
    typed.success(root)
    val kept = (seen as Map<*, *>)["resultList"] as List<*>
    check(kept.size == 1 && !SearchAdFilter.isAd(kept[0])) { "proxy did not filter" }
    typed.error("E", "msg", null)
    check(errors == 1) { "error not forwarded" }
    check(text.isNotEmpty())
    println("PASS: proxy filters success payload only; other methods forwarded untouched")
}

private fun ad(): Map<*, *> = mapOf(
    "data" to mapOf("item" to mapOf("main" to mapOf("clickParam" to mapOf(
        "args" to mapOf("item_type" to "ad", "biz_type" to "ad", "idle_ad_track" to "x"))))),
)

private fun goods(): Map<*, *> = mapOf(
    "data" to mapOf("item" to mapOf("main" to mapOf("clickParam" to mapOf(
        "args" to mapOf("item_type" to "goods", "biz_type" to "item", "idle_search_adx" to "-1"))))),
)
