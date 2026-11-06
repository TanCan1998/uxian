package io.github.tancan1998.uxian

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 用真实 headList 验证排序/隐藏：不发网络、不写配置。 */
fun main(args: Array<String>) {
    val arr = JSONArray(File(args[0]).readText())
    val src = ArrayList<Any?>()
    for (i in 0 until arr.length()) src.add(arr.getJSONObject(i))
    check(src.size == 4)

    // 1) 空偏好：原样
    val same = HomeTabOrder.arrange(src, JSONArray())
    check(same.size == 4)
    for (i in 0 until 4) check(same[i] === src[i])

    // 2) 交换集市与回收
    val pref = JSONArray()
        .put(JSONObject().put("id", "biz:recycle").put("title", "回收").put("hidden", false))
        .put(JSONObject().put("id", "biz:main").put("title", "首页").put("hidden", false))
        .put(JSONObject().put("id", "biz:IDLE_CIRCLE").put("title", "圈子").put("hidden", false))
        .put(JSONObject().put("id", "biz:market").put("title", "集市").put("hidden", false))
    val moved = HomeTabOrder.arrange(src, pref)
    check(moved.size == 4)
    check(HomeTabOrder.identity(moved[0] as JSONObject) == "biz:recycle")
    check(HomeTabOrder.identity(moved[3] as JSONObject) == "biz:market")
    for (o in moved) check(src.contains(o))

    // 3) 隐藏集市
    val hide = JSONArray()
        .put(JSONObject().put("id", "biz:main").put("hidden", false))
        .put(JSONObject().put("id", "biz:market").put("hidden", true))
        .put(JSONObject().put("id", "biz:IDLE_CIRCLE").put("hidden", false))
        .put(JSONObject().put("id", "biz:recycle").put("hidden", false))
    val kept = HomeTabOrder.arrange(src, hide)
    check(kept.size == 3)
    for (o in kept) check(HomeTabOrder.identity(o as JSONObject) != "biz:market")

    // 4) 未知 id 不报错、缺失项按原顺序补在末尾
    val odd = JSONArray().put(JSONObject().put("id", "biz:gone").put("hidden", false))
    val oddOut = HomeTabOrder.arrange(src, odd)
    check(oddOut.size == 4)
    check(HomeTabOrder.identity(oddOut[0] as JSONObject) == "biz:main")

    // 5) 标题兜底
    val plain = JSONObject().put("bizCode", "x").put("showInfo", JSONObject().put("title", "测试"))
    check(HomeTabOrder.title(plain) == "测试")
    check(HomeTabOrder.identity(JSONObject().put("bizCode", "y")) == "biz:y")
    println("PASS: 4 真实 tab；顺序、隐藏、未知/缺失 id、标题兜底均正确")
}
