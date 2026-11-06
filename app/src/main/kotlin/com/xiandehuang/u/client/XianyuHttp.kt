package com.xiandehuang.u.client

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 通过 HTTP 调 U闲（推荐路径）。
 *
 * ## 为什么不用 provider
 *
 * `content query` 命令有约 1.1 秒固定开销（acquireProvider + 建 binder + 取 cursor），
 * 且**这条路径在 shell 侧串行**。实测对比：
 *
 * ```
 * provider 路径   单次 1126ms   N=8 共 9197ms（加速比 1.0x，完全串行）
 * HTTP 路径       单次   26ms   N=8 共  117ms（加速比 ~7x）
 * ```
 *
 * ## 前提
 *
 * U闲 的 HTTP 服务跑在闲鱼主进程的 `127.0.0.1:18787`，不设鉴权
 * （只绑回环，用途是调试）。跨 uid 时可能连不上，需回退 provider。
 *
 * 端口约定见 docs/http-channel.md。
 */
object XianyuHttp {

    const val PORT = 18787
    private const val BASE = "http://127.0.0.1:18787"

    /** 服务是否可达 */
    fun available(): Boolean = try {
        val c = URL("$BASE/ping").openConnection() as HttpURLConnection
        c.connectTimeout = 800
        c.readTimeout = 800
        c.requestMethod = "GET"
        val ok = c.responseCode == 200
        c.disconnect()
        ok
    } catch (t: Throwable) {
        false
    }

    /**
     * 单次调用。
     * @return mtop 的原始响应 JSON 文本
     * @throws XianyuException 通道不可用或服务端报错
     */
    @Throws(XianyuException::class)
    fun call(api: String, version: String?, data: String?): String {
        val body = JSONObject()
            .put("api", api)
            .put("version", version ?: "1.0")
            .put("data", data ?: "{}")
            .toString()
        val resp = post("/rpc", body)
        val o = JSONObject(resp)
        if (!o.optBoolean("ok", false)) {
            val err = o.optString("error", "unknown")
            throw XianyuException.fromRet(null, err)
        }
        // 服务端返回 {"ok":true,"ret":"SUCCESS","body":"<原始JSON>"}
        return o.optString("body", "{}")
    }

    @Throws(Exception::class)
    fun callJson(api: String, version: String?, data: JSONObject?): JSONObject =
        JSONObject(call(api, version, data?.toString() ?: "{}"))

    /**
     * 批量并发调用 —— HTTP 通道最大的价值。
     *
     * @param calls 每项是 Triple(api, version, dataJson)
     * @return 与入参一一对应的响应列表
     */
    @Throws(Exception::class)
    fun batch(calls: List<Triple<String, String, String>>): List<JSONObject> {
        val arr = org.json.JSONArray()
        for ((api, ver, d) in calls) {
            arr.put(JSONObject().put("api", api).put("version", ver).put("data", d))
        }
        val body = JSONObject().put("calls", arr).toString()
        val resp = JSONObject(post("/batch", body))
        if (!resp.optBoolean("ok", false)) {
            throw XianyuException.fromRet(null, resp.optString("error", "unknown"))
        }
        val out = ArrayList<JSONObject>()
        val rs = resp.optJSONArray("results") ?: return out
        var i = 0
        while (i < rs.length()) {
            out.add(rs.optJSONObject(i) ?: JSONObject())
            i++
        }
        return out
    }

    /** 数据查询（rel / traffic / bodies / logs / prefs / doc） */
    @Throws(Exception::class)
    fun query(path: String, body: String = ""): JSONObject {
        val resp = if (body.isBlank()) {
            get(path)
        } else {
            post(path, body)
        }
        val o = JSONObject(resp)
        if (!o.optBoolean("ok", false)) {
            throw XianyuException.fromRet(null, o.optString("error", "unknown"))
        }
        return o.optJSONObject("data") ?: JSONObject()
    }

    /**
     * 异步任务：analyze（7 秒）/ export（3 秒）。
     * 立刻返回 taskId，用 [taskStatus] 轮询。
     */
    @Throws(Exception::class)
    fun startTask(kind: String): String {
        val o = JSONObject(post("/$kind", ""))
        if (!o.optBoolean("ok", false)) {
            throw XianyuException.fromRet(null, o.optString("error", "unknown"))
        }
        return o.optString("task")
    }

    /** 查任务结果；id 为空时返回全部 */
    @Throws(Exception::class)
    fun taskStatus(id: String? = null): JSONObject {
        val path = if (id.isNullOrEmpty()) "/task" else "/task?id=$id"
        return JSONObject(get(path))
    }

    // ---------------------------------------------------------------- 底层

    @Throws(XianyuException::class)
    private fun post(path: String, body: String): String {
        try {
            val c = URL(BASE + path).openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 120_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val bytes = body.toByteArray(Charsets.UTF_8)
            c.setRequestProperty("Content-Length", bytes.size.toString())
            c.outputStream.use { it.write(bytes) }
            return readAll(c)
        } catch (t: Throwable) {
            throw XianyuException(
                XianyuException.Kind.CHANNEL_UNAVAILABLE, null,
                "HTTP 不可达: " + (t.javaClass.simpleName + ": " + t.message),
            )
        }
    }

    @Throws(XianyuException::class)
    private fun get(path: String): String {
        try {
            val c = URL(BASE + path).openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 120_000
            c.requestMethod = "GET"
            return readAll(c)
        } catch (t: Throwable) {
            throw XianyuException(
                XianyuException.Kind.CHANNEL_UNAVAILABLE, null,
                "HTTP 不可达: " + (t.javaClass.simpleName + ": " + t.message),
            )
        }
    }

    private fun readAll(c: HttpURLConnection): String {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        c.disconnect()
        if (code !in 200..299) {
            throw XianyuException(
                XianyuException.Kind.CHANNEL_UNAVAILABLE, null,
                "HTTP $code: " + text.take(200),
            )
        }
        return text
    }
}
