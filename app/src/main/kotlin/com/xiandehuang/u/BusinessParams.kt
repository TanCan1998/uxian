package com.xiandehuang.u

import android.content.Context
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream

/** 只读取公开业务配置；不会执行 JS、创建 H5 会话或发送业务写请求。 */
object BusinessParams {
    class Scope(val api: String, val field: String = "", val scene: String = "") {
        val key: String = "business.asac.v1|" + api.lowercase(Locale.ROOT) + "|" + field + "=" + scene
    }

    @JvmField val TASK_TRIGGER = Scope("mtop.taobao.idle.task.uploadOliverTaskTrigger", "sceneId", "496")
    @JvmField val TASK_BENEFIT = Scope("mtop.taobao.idle.task.getolivertaskbenefit", "sceneId", "496")
    @JvmField val CASH_MAIN = Scope("mtop.idle.play.signin.cash.main")
    @JvmField val CASH_OPEN = Scope("mtop.idle.play.signin.cash.open")
    @JvmField val CHALLENGE_AWARD = Scope("mtop.taobao.idle.joy.community.activity.task.award", "business", "multi.day.challenge")
    @JvmField val PROP_USE = Scope("mtop.taobao.idle.play.prop.use")
    @JvmField val PROP_COMPOSITE = Scope("mtop.taobao.idle.interact.prop.composite")
    private val SCOPES = arrayOf(TASK_TRIGGER, TASK_BENEFIT, CASH_MAIN, CASH_OPEN, PROP_USE, PROP_COMPOSITE)
    private const val HOME = "https://h5.m.goofish.com/wow/moyu/moyu-project/xianyu-coin-new/pages/home"
    private const val SCRIPT_PREFIX = "/code/npm/@ali/moyu-project-xianyu-coin-new/"
    private const val SCRIPT_SUFFIX = "/pages/home/index.js"
    /** 宿主通用令牌：公开配置里查不到该场景时（现金、挑战在别的页面）的兜底。 */
    private const val GENERIC = "asac"
    private const val CHECK_KEY = "business.public.home.checked.v1"
    private const val CHECK_INTERVAL = 30L * 60L * 1000L
    private const val MAX_HTML = 1024 * 1024
    private const val MAX_JS = 3 * 1024 * 1024
    private const val FETCH_BUDGET = 22000L
    private val executor = Executors.newSingleThreadExecutor { work ->
        Thread(work, "ux-business-params").apply { isDaemon = true }
    }
    private val lock = Any()
    private val refreshLock = Any()
    @Volatile private var queued = false
    @Volatile private var lastAttempt = 0L

    private fun mainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    /** 启动/面板主线程只排队；实际抓取始终在后台，有检查间隔和单飞锁。 */
    @JvmStatic fun warmup(c: Context) {
        synchronized(lock) {
            if (queued || ApiInit.paused(c)) return
            queued = true
        }
        try {
            executor.execute {
                try { refreshPublic(c.applicationContext ?: c) } finally {
                    synchronized(lock) { queued = false }
                }
            }
        } catch (ignored: Throwable) {
            synchronized(lock) { queued = false }
        }
    }

    @JvmStatic fun cached(c: Context, scope: Scope): String = ParamStore.value(c, scope.key)

    /**
     * 抓包库里宿主最近一次请求带的 asac（只采 `origin='host'`）。
     *
     * 我方探测写进去的值不能用 —— 那是我方「想怎么发」，拿它当基准会自我循环
     * （抓包库的方法字段踩过这个坑，误判三轮，见 §7.4）。
     */
    /** 宿主令牌在参数库里的槽位（不随抓包库清理而丢失） */
    @JvmField val KEY_HOST_ASAC = "host::asac"

    private fun hostAsac(c: Context, api: String): String {
        // ① 参数库：MtopHooks 在宿主自己发请求时顺手存下来的
        val saved = ParamStore.value(c, KEY_HOST_ASAC)
        if (saved.isNotEmpty() && valid(saved)) return saved
        // ② 兜底才看抓包库（用户随时可以清空它）
        return trafficAsac(api)
    }

    private fun trafficAsac(api: String): String {
        return try {
            val h = TrafficStore.headersFromDb(api)
            h["asac"] ?: h["x-asac"] ?: ""
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * 宿主自己发请求时把 asac 存进参数库。
     *
     * 为什么不能只靠抓包库：抓包数据是**可清理的研究数据**，
     * 而 asac 是业务链的必需参数，清一次库就断链是不行的。
     */
    @JvmStatic
    fun rememberHostAsac(c: Context?, value: String) {
        if (c == null) return
        val v = value.trim()
        if (v.isEmpty() || v.length < 8) return
        try {
            if (ParamStore.value(c, KEY_HOST_ASAC) == v) return
            ParamStore.record(c, KEY_HOST_ASAC, v, "宿主请求", "asac", "宿主发请求时带的令牌")
        } catch (t: Throwable) {
        }
    }

    /** 主线程绝不等待网络。失败保留同 api/scene 的旧值，不读取旧全局 asac。 */
    @JvmStatic fun resolve(c: Context, scope: Scope): String {
        if (ApiInit.paused(c)) return ""
        // ① 宿主自己发请求时带的 asac 最可信：它是宿主 H5 容器按会话下发的。
        //
        // 实测（2026-10-31 夜）：同一批任务，用公开配置里的令牌 trigger 回
        // FAIL_BIZ_COMMON_PARAM_ILLEGAL，换成抓包库里宿主最近一次请求带的
        // 令牌就 SUCCESS（「点闪购商品领叠加红包」直接 COMPLETE）。
        // 所以**先看抓包库的 host 值**，没有再退回参数库。
        val live = hostAsac(c, scope.api)
        if (live.isNotEmpty() && valid(live)) {
            if (ParamStore.value(c, scope.key) != live) {
                ParamStore.record(c, scope.key, live, "宿主请求", "asac", "抓包库最新一条 host 请求")
            }
            return live
        }
        val fresh = ParamStore.fresh(c, scope.key)
        if (fresh.isNotEmpty()) return fresh
        if (mainThread()) warmup(c) else refreshPublic(c)
        val cached = cached(c, scope)
        if (cached.isNotEmpty()) return cached
        return adoptGeneric(c, scope)
    }

    /**
     * 公开配置里没有这个场景时，退回宿主当前会话的通用令牌。
     *
     * 现金红包、多日挑战是各自的页面，任务页脚本里没有它们的 asac；
     * 实测同一会话的令牌跨场景通用（用任务场景的令牌调 `cash.main` 正常返回）。
     * 落库时标明来源，和公开配置取到的值区分开 —— 面板能看出是兜底值。
     */
    private fun adoptGeneric(c: Context, scope: Scope): String {
        // 任务场景的令牌每次公开配置检查都会刷新，优先用它；再退到通用槽位。
        var value = ParamStore.fresh(c, TASK_TRIGGER.key)
        if (value.isEmpty()) value = ParamStore.fresh(c, GENERIC)
        if (value.isEmpty()) value = ParamStore.value(c, TASK_TRIGGER.key)
        if (value.isEmpty() || !valid(value)) return ""
        ParamStore.record(c, scope.key, value, "宿主通用令牌", "asac", "公开配置无该场景，退回通用令牌")
        Protocol.State.logLine("业务参数：" + scope.api + " 公开配置无值，改用宿主通用令牌")
        return value
    }

    /** 显式按 activityId 存储，绝不能给任务作用域填值。调用方提供只读活动响应。 */
    @JvmStatic fun rememberActivity(c: Context, activityId: String, response: JSONObject): String {
        if (!activityId.matchesSafeId()) return ""
        val data = response.optJSONObject("data") ?: response
        val activity = data.optJSONObject("activityDO") ?: return ""
        val actualId = activity.optString("activityId", activity.optString("id", ""))
        if (actualId.isNotEmpty() && actualId != activityId) return ""
        val value = activity.optString("asac", "")
        if (!valid(value)) return ""
        val key = "business.asac.v1|activityId=" + activityId
        ParamStore.record(c, key, value, "activity.response", "activityDO.asac", "activityId=" + activityId)
        return value
    }

    private fun String.matchesSafeId(): Boolean {
        if (isEmpty() || length > 32) return false
        for (ch in this) if (ch !in '0'..'9') return false
        return true
    }

    private fun valid(value: String): Boolean {
        if (value.length < 12 || value.length > 128) return false
        for (ch in value) {
            if (ch !in 'a'..'z' && ch !in 'A'..'Z' && ch !in '0'..'9' && ch != '_' && ch != '-') return false
        }
        return true
    }

    /** 成功和失败的检查时间都落盘，避免解析失败后每条任务重复抓取。 */
    private fun refreshPublic(c: Context) {
        if (mainThread() || ApiInit.paused(c)) return
        synchronized(refreshLock) {
            val now = System.currentTimeMillis()
            val persisted = ParamStore.get(c, CHECK_KEY)?.fetchedAt ?: 0L
            val checked = maxOf(lastAttempt, persisted)
            if (checked > 0 && now >= checked && now - checked < CHECK_INTERVAL) return
            lastAttempt = now
            ParamStore.record(c, CHECK_KEY, "checking", HOME, "script.src", "公开资源检查")
            val deadline = android.os.SystemClock.elapsedRealtime() + FETCH_BUDGET
            try {
                val html = fetch(URL(HOME), false, MAX_HTML, deadline)
                val script = scriptUrl(html) ?: throw IllegalArgumentException("script")
                val js = fetch(script, true, MAX_JS, deadline)
                var loaded = 0
                for (scope in SCOPES) {
                    val value = extract(js, scope)
                    if (value.isEmpty()) continue
                    ParamStore.record(c, scope.key, value, script.toExternalForm(),
                        "request.data.asac/ext_headers.asac", scope.field + "=" + scope.scene)
                    loaded++
                }
                ParamStore.record(c, CHECK_KEY, "ok:" + loaded, script.toExternalForm(), "script.src", "已检查")
                // 任务场景的令牌就是这次会话的通用令牌；现金/挑战页没有公开配置可读，
                // 靠它兜底。值没变时 record 不写，不会把「取到时间」刷成假新鲜。
                val generic = ParamStore.value(c, TASK_TRIGGER.key)
                if (valid(generic)) {
                    ParamStore.record(c, GENERIC, generic, script.toExternalForm(),
                        "request.data.asac", "任务场景令牌，跨场景兜底")
                }
                Protocol.State.logLine("业务参数：公开配置已检查，更新 " + loaded + " 个作用域")
            } catch (t: Throwable) {
                // 只记录异常类型，URL/响应/参数值都不进入日志。
                ParamStore.record(c, CHECK_KEY, "unavailable", HOME, "script.src", t.javaClass.simpleName)
                Protocol.State.logLine("业务参数：公开配置暂不可用，保留原作用域缓存")
            }
        }
    }

    private fun trusted(url: URL, script: Boolean): Boolean {
        if (url.protocol != "https" || url.userInfo != null || (url.port != -1 && url.port != 443)) return false
        if (url.query != null || url.ref != null) return false
        if (!script) return (url.host == "h5.m.goofish.com" || url.host == "ssr.m.goofish.com") &&
            url.path == "/wow/moyu/moyu-project/xianyu-coin-new/pages/home"
        if (url.host != "g.alicdn.com") return false
        val path = url.path
        if (!path.startsWith(SCRIPT_PREFIX) || !path.endsWith(SCRIPT_SUFFIX)) return false
        val version = path.substring(SCRIPT_PREFIX.length, path.length - SCRIPT_SUFFIX.length)
        if (version.isEmpty() || version.length > 48) return false
        for (ch in version) if (ch !in '0'..'9' && ch != '.' && ch != '-') return false
        return true
    }

    internal fun scriptUrl(html: String): URL? {
        val tags = Pattern.compile("<script\\b[^>]*\\bsrc\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE).matcher(html)
        val candidates = LinkedHashMap<String, URL>()
        while (tags.find()) {
            val raw = tags.group(2) ?: continue
            val url = try { URL(URL(HOME), raw.replace("&amp;", "&")) } catch (ignored: Throwable) { continue }
            if (trusted(url, true)) candidates[url.toExternalForm()] = url
        }
        return if (candidates.size == 1) candidates.values.iterator().next() else null
    }

    private fun fetch(initial: URL, script: Boolean, limit: Int, deadline: Long): String {
        var url = initial
        for (hop in 0..2) {
            if (!trusted(url, script)) throw IllegalArgumentException("domain")
            val left = deadline - android.os.SystemClock.elapsedRealtime()
            if (left <= 0) throw java.net.SocketTimeoutException()
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = minOf(6000L, left).toInt()
                connection.readTimeout = minOf(6000L, left).toInt()
                connection.setRequestProperty("Accept-Encoding", "gzip")
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location") ?: throw IllegalArgumentException("redirect")
                    url = URL(url, location)
                    continue
                }
                if (status != 200 || connection.contentLengthLong > limit) throw IllegalArgumentException("response")
                val input = if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(connection.inputStream) else connection.inputStream
                input.use {
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        if (android.os.SystemClock.elapsedRealtime() >= deadline) throw java.net.SocketTimeoutException()
                        val n = it.read(buf)
                        if (n < 0) break
                        if (out.size() + n > limit) throw IllegalArgumentException("size")
                        out.write(buf, 0, n)
                    }
                    return String(out.toByteArray(), Charsets.UTF_8)
                }
            } finally { connection.disconnect() }
        }
        throw IllegalArgumentException("redirects")
    }

    /**
     * 保守读取请求对象：api 必须为对象首字段；平衡括号定位整个请求；只认直接 data/
     * ext_headers 中的字面量。动态 scene、拼接、变量、模板字符串和冲突值均拒绝。
     * 不依赖压缩后的包装函数名，也不会从下一个接口借到 asac。
     */
    internal fun extract(js: String, scope: Scope): String {
        val start = Pattern.compile("\\{\\s*(?:api|\"api\"|'api')\\s*:\\s*([\"'])" +
            Pattern.quote(scope.api) + "\\1", Pattern.CASE_INSENSITIVE).matcher(js)
        var found = ""
        while (start.find()) {
            val end = objectEnd(js, start.start(), minOf(js.length, start.start() + 6000))
            if (end < 0) continue
            val request = properties(js.substring(start.start(), end + 1)) ?: continue
            val bodyText = request["data"] ?: continue
            val body = properties(bodyText) ?: continue
            if (scope.field.isNotEmpty() && scalar(body[scope.field] ?: "") != scope.scene) continue
            // sceneId 不在请求对象直接 data 中时，绝不猜函数参数属于哪个场景。
            val bodyAsac = body["asac"]
            val headersText = request["ext_headers"]
            val headers = if (headersText == null) null else properties(headersText) ?: continue
            val headerAsac = headers?.get("asac")
            if (bodyAsac == null && headerAsac == null) continue
            val b = if (bodyAsac == null) "" else scalar(bodyAsac)
            val h = if (headerAsac == null) "" else scalar(headerAsac)
            if (bodyAsac != null && !valid(b)) continue
            if (headerAsac != null && !valid(h)) continue
            if (b.isNotEmpty() && h.isNotEmpty() && b != h) return ""
            val value = if (b.isNotEmpty()) b else h
            if (found.isNotEmpty() && found != value) return ""
            found = value
        }
        return found
    }

    /** 仅接受无转义的字符串或十进制数字，不求值 JS 表达式。 */
    private fun scalar(text: String): String {
        val s = text.trim()
        if (s.length >= 2 && (s[0] == '\'' || s[0] == '"') && s.last() == s[0]) {
            val value = s.substring(1, s.length - 1)
            if (value.indexOf('\\') >= 0 || value.indexOf(s[0]) >= 0) return ""
            return value
        }
        if (s.isEmpty()) return ""
        for (ch in s) if (ch !in '0'..'9') return ""
        return s
    }

    private fun objectEnd(s: String, begin: Int, limit: Int): Int {
        if (begin >= limit || s[begin] != '{') return -1
        val stack = StringBuilder()
        var quote = '\u0000'
        var escape = false
        var i = begin
        while (i < limit) {
            val ch = s[i]
            if (quote != '\u0000') {
                if (escape) escape = false else if (ch == '\\') escape = true else if (ch == quote) quote = '\u0000'
            } else {
                when (ch) {
                    '\'', '"' -> quote = ch
                    '`', '/' -> return -1 // 模板/正则/注释不是此解析器支持的请求字面量
                    '{', '[', '(' -> stack.append(ch)
                    '}', ']', ')' -> {
                        if (stack.isEmpty()) return -1
                        val expected = when (ch) { '}' -> '{'; ']' -> '['; else -> '(' }
                        if (stack.last() != expected) return -1
                        stack.deleteCharAt(stack.length - 1)
                        if (stack.isEmpty()) return if (ch == '}') i else -1
                    }
                }
            }
            i++
        }
        return -1
    }

    private fun properties(text: String): HashMap<String, String>? {
        val s = text.trim()
        if (s.length < 2 || s[0] != '{' || objectEnd(s, 0, s.length) != s.length - 1) return null
        val out = HashMap<String, String>()
        var depth = 0
        var quote = '\u0000'
        var escape = false
        var begin = 1
        var i = 1
        while (i < s.length) {
            val ch = s[i]
            if (quote != '\u0000') {
                if (escape) escape = false else if (ch == '\\') escape = true else if (ch == quote) quote = '\u0000'
            } else {
                when (ch) {
                    '\'', '"' -> quote = ch
                    '{', '[', '(' -> depth++
                    '}', ']', ')' -> depth--
                }
                if ((ch == ',' && depth == 0) || i == s.length - 1) {
                    val field = s.substring(begin, i).trim()
                    if (field.isNotEmpty()) {
                        val colon = field.indexOf(':')
                        if (colon <= 0) return null
                        var key = field.substring(0, colon).trim()
                        if (key.startsWith("\"") || key.startsWith("'")) key = scalar(key)
                        if (key.isEmpty() || out.containsKey(key)) return null
                        for (k in key) if (!k.isLetterOrDigit() && k != '_' && k != '$') return null
                        out[key] = field.substring(colon + 1).trim()
                    }
                    begin = i + 1
                }
            }
            i++
        }
        return out
    }
}
