package io.github.tancan1998.uxian

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileReader
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * provider 通道协议：selection 的解析 + 宿主进程内共享状态。
 *
 * 全部走闲鱼自己的 ContentProvider，免安装、免重启、免界面操作。
 */
object Protocol {

    const val SELECTION = "xysidecar:mtop"
    const val INLINE = "xysidecar:mtop:"

    /** 诊断：selection = "xysidecar:diag" */
    const val DIAG = "xysidecar:diag"

    /**
     * channel socket 通道（跨进程，主进程死了也能用）：
     *   xysidecar:ch:ping             探活
     *   xysidecar:ch:status           主进程是否存活 / 压制开关 / socket 名
     *   xysidecar:ch:reap             回收渲染进程（仅主进程已死时生效）
     *   xysidecar:ch:api:<b64>        转发一次 RPC（base64 的 {"api","version","data"}）
     */
    const val CH = "xysidecar:ch"

    /**
     * 开关读写（无头，供脚本验证）：
     *   xysidecar:prefs                       → 列出所有开关当前值
     *   xysidecar:prefs:get:<key>             → 读单个
     *   xysidecar:prefs:set:<key>:<0|1>       → 写单个
     * 值为 0/1；键名见 Prefs 的常量。
     */
    const val PREFS = "xysidecar:prefs"

    /** 最近捕获的宿主请求体（api + data），用于对齐真实参数 */
    const val BODIES = "xysidecar:bodies"

    /** 完整抓包读取：xysidecar:traffic[:<fileIndex>[:<offset>]] */
    const val TRAFFIC = "xysidecar:traffic"

    /**
     * View 树导出（诊断界面用，免安装）：
     *   xysidecar:tree                  整棵树
     *   xysidecar:tree:bottom           只看屏幕下方 400px
     *   xysidecar:tree:find=卖闲置      只导出含该文字/desc 的子树（含祖先链）
     *   xysidecar:tree:find=xxx;depth=3 限制深度
     */
    const val TREE = "xysidecar:tree"

    /** 反射探查：xysidecar:api:<类名>[:<方法名过滤>] → 列出方法签名与字段 */
    const val API = "xysidecar:api"

    /**
     * 读宿主缓存的响应（主搜索只能走这条）：
     *   xysidecar:cache            → 列出已缓存的 api 与大小
     *   xysidecar:cache:<api>      → 取该 api 最近一次的完整响应
     */
    const val CACHE = "xysidecar:cache"

    /**
     * 导出抓包报告：xysidecar:export[:api1,api2]
     * 生成单文件 HTML 到 /sdcard/Download/U闲抓包/，返回路径。
     */
    const val EXPORT = "xysidecar:export"

    /** 接口词典查询：xysidecar:doc[:<api>] */
    const val DOC = "xysidecar:doc"

    /** 跑关联分析：xysidecar:analyze */
    const val ANALYZE = "xysidecar:analyze"

    /** 关联查询：xysidecar:rel[:<api>] */
    const val REL = "xysidecar:rel"

    /** 导入报告：xysidecar:import:<文件路径> */
    const val IMPORT = "xysidecar:import"

    /** 回放参数探查：xysidecar:replay[:<api>] */
    const val REPLAY = "xysidecar:replay"

    /**
     * 业务链：`xysidecar:chain[:<名字>]`。
     *
     * 面板「自动」里每个按钮背后就是这里的同名链。加这个通道是为了
     * **不用点界面就能验证**（也是唯一能脚本化跑链的方式）：
     *
     *   xysidecar:chain              → 列出可用链
     *   xysidecar:chain:signin       → 每日签到
     *   xysidecar:chain:forest       → 森林（领能量/喂养/答题）
     *   xysidecar:chain:cash         → 现金红包
     *   xysidecar:chain:circle       → 圈子签到
     *   xysidecar:chain:treasure      → 寻宝地图掷骰
     *   xysidecar:chain:all          → 全部按顺序跑
     *
     * 注意：这些是**写操作**（真签到、真掷骰），不是查询。
     */
    const val CHAIN = "xysidecar:chain"

    /**
     * 自动任务（定时推进器）的状态与手动触发。
     *
     *   xysidecar:auto            → 状态（间隔 / 上次 / 下次 / 暂停）
     *   xysidecar:auto:run        → 立刻跑一轮（按日期判断全链还是任务推进）
     *   xysidecar:auto:run:light  → 只跑任务推进（领奖 + 触发）
     *   xysidecar:auto:run:full   → 强制跑全链
     *   xysidecar:auto:set:30     → 改间隔（分钟，0 = 关闭）
     *
     * 和 [CHAIN] 的区别：那边是一次性执行，这边是**调度器自身**的状态
     * —— 无头验证「定时到底有没有跑」只能靠它。
     */
    const val AUTO = "xysidecar:auto"

    /** 读内存日志：xysidecar:logs */
    const val LOGS = "xysidecar:logs"

    const val COL_KEY = "k"
    const val COL_VALUE = "v"

    /** @return {api, version, data}；不归我们管返回 null */
    @JvmStatic
    fun parse(selection: String?, args: Array<Any?>?): Array<String>? {
        if (selection == null) return null

        if (selection.startsWith(INLINE)) {
            return try {
                val json = String(
                    Base64.decode(selection.substring(INLINE.length).trim(), Base64.DEFAULT),
                    Charsets.UTF_8,
                )
                val o = JSONObject(json)
                val api = o.optString("api")
                if (api.isEmpty()) return null
                arrayOf(
                    api,
                    o.optString("version", "1.0"),
                    if (o.opt("data") == null) "{}" else o.opt("data").toString(),
                )
            } catch (t: Throwable) {
                null
            }
        }

        if (selection.startsWith(SELECTION)) {
            if (args == null || args.size < 3) return null
            val a0 = args[0] as? String ?: return null
            val version = args[1] as? String ?: "1.0"
            val data = args[2] as? String ?: "{}"
            return arrayOf(a0, version, data)
        }
        return null
    }

    /** 供脚本/命令行拼 selection */
    @JvmStatic
    fun inline(api: String, version: String, data: String): String =
        try {
            val o = JSONObject()
            o.put("api", api)
            o.put("version", version)
            o.put("data", data)
            val b64 = Base64.encodeToString(
                o.toString().toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP,
            )
            INLINE + b64
        } catch (t: Throwable) {
            INLINE
        }

    @JvmStatic
    fun ok(v: String): Cursor = row("ok", v)

    @JvmStatic
    fun err(msg: String): Cursor = row("error", msg)

    private fun row(k: String, v: String): Cursor {
        val c = MatrixCursor(arrayOf(COL_KEY, COL_VALUE))
        c.addRow(arrayOf<Any>(k, v))
        return c
    }

    /** 进程内共享状态（宿主进程） */
    object State {

        @Volatile @JvmField var classLoader: ClassLoader? = null
        @Volatile @JvmField var app: Context? = null

        /** 宿主自己初始化好的 Mtop 实例（由 hook Mtop.instance 捕获） */
        @Volatile @JvmField var mtop: Any? = null

        // —— 诊断用计数 ——
        @Volatile @JvmField var providerHooked = false
        @Volatile @JvmField var instanceHookCount = 0
        @Volatile @JvmField var initFired = false
        @Volatile @JvmField var captures = 0
        @Volatile @JvmField var tapHooks = 0
        /** 最终请求 hook 抓到的条数（diag 里能看到，确认钩子真的在跑） */
        @Volatile @JvmField var finalTapCount = 0L

        // 各项 hook 是否安装成功；失败则对应功能禁用
        @Volatile @JvmField var capSplash = false
        @Volatile @JvmField var capFilter = false
        @Volatile @JvmField var capTap = false
        @Volatile @JvmField var capUi = false
        @Volatile @JvmField var capMtop = false

        /** 搜索结果去广告钩子是否挂上 */
        @Volatile @JvmField var capSearchAd = false
        @Volatile @JvmField var skipSplash = false

        /** 内存日志（中文），面板里展示最近 200 条 */
        @JvmField
        val LOGS: MutableList<String> =
            Collections.synchronizedList(ArrayList<String>())

        @JvmStatic
        fun logLine(text: String) {
            try {
                val t = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
                if (LOGS.size > 200) LOGS.removeAt(0)
                LOGS.add("$t  $text")
                // 模块日志开关打开时，同时输出到 logcat，方便外部抓
                if (moduleLogOn()) {
                    Log.i("xysidecar", text)
                }
            } catch (ignored: Throwable) {
            }
        }

        @Volatile private var modLogMtime = -1L
        @Volatile private var modLogLen = -1L
        @Volatile private var modLogChecked = 0L
        @Volatile private var modLogValue = false

        /**
         * 模块日志开关（实时、跨进程）。
         *
         * 为什么不能走 getSharedPreferences()：
         * 它是**进程内单例缓存**。闲鱼是多进程架构，Xposed 会给每个进程都加载
         * 一份模块。provider 的 prefs:set 写的是主进程，别的进程的 SP 实例
         * 看不到新值 —— 实测 SP 文件里是 true，模块读到 false。
         *
         * 所以直接读 XML 文件。为了不每次都做完整 I/O，用文件 mtime 做缓存：
         * 每次只 stat 一次（微秒级），文件变了才重新解析。
         */
        @JvmStatic
        fun moduleLogOn(): Boolean {
            try {
                var c = app
                if (c == null) {
                    // 早期钩子里 app 还没设上，兜底取一次
                    try {
                        val at = Class.forName("android.app.ActivityThread")
                        val a = at.getMethod("currentApplication").invoke(null)
                        if (a is Context) {
                            app = a
                            c = a
                        }
                    } catch (ignored: Throwable) {
                    }
                }
                if (c == null) return false
                // 普通模式一律不打 logcat（模块日志只在调试态有意义）
                if (!liveBool(c, Prefs.DEBUG, false)) return false
                val f = spFile(c) ?: return false
                // 用 mtime + 文件长度 组合做指纹；再叠一个 1 秒的强制刷新窗口，
                // 防止 apply() 异步写入时 mtime 精度不够（文件系统可能只到秒）
                val mt = f.lastModified()
                val len = f.length()
                val now = System.currentTimeMillis()
                if (mt == modLogMtime && len == modLogLen && now - modLogChecked < 1000) {
                    return modLogValue
                }

                val v = readBoolFromSpFile(f, "module_log")
                modLogMtime = mt
                modLogLen = len
                modLogChecked = now
                modLogValue = v
                return v
            } catch (t: Throwable) {
                return false
            }
        }

        /**
         * 每个 key 一份缓存。
         *
         * 踩过的坑：原来只有一个缓存槽（swKey/swValue），而 Theme.of()
         * 会连续读 dark_force 和 debug_mode 两个 key —— 第二个把第一个
         * 冲掉，之后读第一个就命中了第二个的值。表现是切换调试态时
         * 面板强调色不变（读到的 debug 值其实是 dark_force 的）。
         */
        private class SwCache {
            @Volatile var mtime = -1L
            @Volatile var len = -1L
            @Volatile var checked = 0L
            @Volatile var value = false
        }

        private val swCacheMap = java.util.concurrent.ConcurrentHashMap<String, SwCache>()

        /** 刚写完 SP 时调用：丢掉缓存，下一次读强制回源 */
        @JvmStatic
        fun invalidateSwCache() {
            swCacheMap.clear()
        }

        @JvmStatic
        fun liveBool(c: Context?, key: String): Boolean = liveBool(c, key, false)

        /**
         * 跨进程实时读布尔开关，**带默认值**。
         *
         * 关键：SP 文件里没有这个键时（用户从没在面板里拨过），
         * readBoolFromSpFile 返回 false，会把"默认开"的开关误报成关。
         * 所以缺键时必须回落到调用方给的 def。
         */
        @JvmStatic
        fun liveBool(c: Context?, key: String, def: Boolean): Boolean {
            try {
                if (c == null) return def
                // 读不到 SP 文件也要回落到 def，不能返回 false ——
                // 否则任何一次 IO 异常都会让「默认开」的开关静默变关，
                // 表现是「日志说已拦截、广告照样出现」（本项目踩过的同源坑）
                val f = spFile(c) ?: return def
                val mt = f.lastModified()
                val len = f.length()
                val now = System.currentTimeMillis()
                val cache = swCacheMap.getOrPut(key) { SwCache() }
                if (mt == cache.mtime && len == cache.len && now - cache.checked < 700) {
                    return cache.value
                }
                var v = readBoolFromSpFile(f, key)
                if (!hasKeyInSpFile(f, key)) v = def
                cache.mtime = mt
                cache.len = len
                cache.checked = now
                cache.value = v
                return v
            } catch (t: Throwable) {
                // 关键：异常时必须回落到 def，不能返回 false。
                // 返回 false 会让「默认开」的开关在异常时静默变关 ——
                // 这正是「日志说已拦截、广告照样出现」的同源故障。
                Log.w("xysidecar", "liveBool($key) 异常，回落默认值 $def: $t")
                return def
            }
        }

        /** SP 文件里是否真的存在这个键（区分"没设过"和"设成 false"） */
        private fun hasKeyInSpFile(f: File, key: String): Boolean {
            var input: FileInputStream? = null
            try {
                input = FileInputStream(f)
                val p = android.util.Xml.newPullParser()
                p.setInput(input, "UTF-8")
                var ev = p.eventType
                while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (ev == org.xmlpull.v1.XmlPullParser.START_TAG &&
                        "boolean" == p.name &&
                        key == p.getAttributeValue(null, "name")
                    ) {
                        return true
                    }
                    ev = p.next()
                }
            } catch (ignored: Throwable) {
            } finally {
                try { input?.close() } catch (ignored: Throwable) {}
            }
            return false
        }

        /** 定位 SP 的 xml 文件；拿不到返回 null */
        private fun spFile(c: Context): File? {
            try {
                val dir = c.applicationInfo.dataDir
                if (!dir.isNullOrEmpty()) {
                    val f = File(dir, "shared_prefs/xysidecar_prefs.xml")
                    if (f.exists()) return f
                }
            } catch (ignored: Throwable) {
            }
            // 兜底：从 files 目录往上退两级
            try {
                val files = c.filesDir
                if (files != null) {
                    val f = File(files.parentFile, "shared_prefs/xysidecar_prefs.xml")
                    if (f.exists()) return f
                }
            } catch (ignored: Throwable) {
            }
            return null
        }

        /** 从 SP 的 xml 里读一个 boolean 键 */
        private fun readBoolFromSpFile(f: File, key: String): Boolean {
            var br: BufferedReader? = null
            try {
                br = BufferedReader(FileReader(f))
                val needle = "name=\"$key\""
                while (true) {
                    val line = br.readLine() ?: break
                    if (line.indexOf(needle) < 0) continue
                    return line.indexOf("value=\"true\"") >= 0
                }
            } catch (ignored: Throwable) {
            } finally {
                try { br?.close() } catch (ignored: Throwable) {}
            }
            return false
        }

        /** 记录 App 自己发的请求 api/version（用于对齐真实接口名） */
        @JvmField
        val tap: MutableList<String> =
            Collections.synchronizedList(ArrayList<String>())

        /**
         * 最近捕获的宿主请求体（api + 参数），用于对齐真实接口参数。
         * 只保留最近 20 条，且只在调试模式下写入。
         */
        @JvmField
        val bodies: MutableList<String> =
            Collections.synchronizedList(ArrayList<String>())

        /**
         * 宿主为每个 api 设置的请求头（api → header map）。
         * 有些接口（实测搜索）只带 data 会被服务端判为无效请求、返回空 data，
         * 必须连请求头一起回放。
         */
        @JvmField
        val headers: MutableMap<String, MutableMap<String, String>> =
            Collections.synchronizedMap(LinkedHashMap<String, MutableMap<String, String>>())

        /** 记录一条请求头集合：与已有合并而不是覆盖（宿主会分多次调用 headers(Map)） */
        @JvmStatic
        fun headers(api: String?, h: Map<String, String>?) {
            if (api.isNullOrEmpty() || h.isNullOrEmpty()) return
            synchronized(headers) {
                val cur = headers.getOrPut(api) { LinkedHashMap() }
                cur.putAll(h)
                while (headers.size > 40) {
                    val it = headers.keys.iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    }
                }
            }
        }

        /** 取某个 api 的请求头副本 */
        @JvmStatic
        fun headersOf(api: String?): MutableMap<String, String>? {
            synchronized(headers) {
                val h = headers[api] ?: return null
                return LinkedHashMap(h)
            }
        }

        /**
         * 宿主自己拿到的响应缓存（api → JSON 文本 + 时间戳）。
         * 主搜索接口只能这样取：外部重发会被服务端拒，宿主自己发是成功的。
         */
        class Capture(@JvmField val json: String, @JvmField val at: Long)

        private val CAPS: MutableMap<String, Capture> =
            Collections.synchronizedMap(LinkedHashMap<String, Capture>())

        @JvmStatic
        fun capture(api: String?, json: String?) {
            if (api == null || json == null) return
            synchronized(CAPS) {
                CAPS[api] = Capture(json, System.currentTimeMillis())
                while (CAPS.size > 12) {
                    val it = CAPS.keys.iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    }
                }
            }
        }

        @JvmStatic
        fun captureOf(api: String?): Capture? {
            synchronized(CAPS) {
                return CAPS[api]
            }
        }

        @JvmStatic
        fun captureKeys(): List<String> {
            synchronized(CAPS) {
                return ArrayList(CAPS.keys)
            }
        }

        /**
         * 宿主为每个 api 用的 HTTP 方法（GET / POST …）。
         * 实测搜索接口宿主用 GET，我们默认 POST 会拿到空 data。
         */
        @JvmField
        val methods: MutableMap<String, String> =
            Collections.synchronizedMap(LinkedHashMap<String, String>())

        /** 来自 tap 的初步值（prop.method，可能还是默认 GET） */
        @JvmField
        val methodGuesses: MutableMap<String, String> =
            Collections.synchronizedMap(LinkedHashMap<String, String>())

        @JvmStatic
        fun methodGuess(api: String?, m: String?) {
            if (api == null || m == null) return
            synchronized(methodGuesses) {
                methodGuesses[api] = m
                while (methodGuesses.size > 60) {
                    val it = methodGuesses.keys.iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    }
                }
            }
        }

        /** 来自最终请求（真正发出的 HTTP 动词），优先级高于猜测值 */
        @JvmStatic
        fun method(api: String?, m: String?) {
            if (api == null || m == null) return
            synchronized(methods) {
                methods[api] = m
                while (methods.size > 60) {
                    val it = methods.keys.iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    }
                }
            }
        }

        /**
         * 我方重放时发出的动词。**单独存**，绝不能写进 methods：
         * hook 对我方请求同样生效，写进 methods 就等于"我上次发了什么，
         * 下次就照着发"，宿主真实行为被自己的记录顶掉（实测踩过：
         * 宿主 energy.list 用 GET，探测发了 POST，之后一直被按 POST 回放）。
         */
        @JvmField
        val methodsOurs: MutableMap<String, String> =
            Collections.synchronizedMap(LinkedHashMap<String, String>())

        @JvmStatic
        fun methodOurs(api: String?, m: String?) {
            if (api == null || m == null) return
            synchronized(methodsOurs) {
                methodsOurs[api] = m
                while (methodsOurs.size > 60) {
                    val it = methodsOurs.keys.iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    }
                }
            }
        }

        @JvmStatic
        fun methodOursOf(api: String?): String? =
            synchronized(methodsOurs) { methodsOurs[api] }

        /**
         * 优先用真值；没有真值才退回猜测值（猜测值多半是 mtop 默认的 GET，
         * 用在长参数接口上会翻车，所以至少要尝试 switchToPost）。
         */
        @JvmStatic
        fun methodOf(api: String?): String? {
            // 优先级：真值(Request.method，只采宿主) > 抓包库回填(host) > prop 猜测
            //
            // prop.method 是 mtop 默认值（GET），业务代码很少调 reqMethod，
            // 真实动词由 converter 决定，所以它最不可靠，只能做最后兜底。
            // methods 里只会进**宿主自己**的请求（我方重放单独存 methodsOurs），
            // 这样就不会出现"我上次发了什么下次就照着发"的自我循环。
            synchronized(methods) {
                methods[api]
            }?.let { return it }
            synchronized(methodGuesses) {
                return methodGuesses[api]
            }
        }

        /** 从抓包库回填 method（进程刚起、hook 还没抓到真值时用） */
        @JvmStatic
        fun backfillMethods(pairs: Map<String, String>) {
            for ((k, v) in pairs) {
                if (k.isEmpty() || v.isEmpty()) continue
                synchronized(methods) {
                    // 只填不覆盖：hook 抓到的真值更可信
                    if (methods[k] == null) methods[k] = v
                }
                // 抓包库里的 method 是**真实发出过的动词**，比 prop 的默认 GET 可信得多。
                // prop 猜测值不可靠（mtop 默认 GET，业务代码很少调 reqMethod），
                // 留着它只会让 methodOf 在真值缺失时选错。直接清掉。
                synchronized(methodGuesses) {
                    methodGuesses.remove(k)
                }
            }
        }

        /** 有没有拿到任何来源的 method（不管哪个优先级） */
        @JvmStatic
        fun anyMethod(api: String?): Boolean =
            synchronized(methodGuesses) { methodGuesses[api] != null } ||
                synchronized(methods) { methods[api] != null }

        /** 是否拿到了实测真值（用于探测时区分「确定的」和「猜的」） */
        @JvmStatic
        fun hasFinalMethod(api: String?): Boolean =
            synchronized(methods) { methods[api] != null }

        /** 宿主为某个 api 用的 needSession / needEcode（MtopRequest 上的真实值） */
        @JvmField
        val sessions: MutableMap<String, Boolean> =
            Collections.synchronizedMap(LinkedHashMap<String, Boolean>())

        @JvmField
        val ecodes: MutableMap<String, Boolean> =
            Collections.synchronizedMap(LinkedHashMap<String, Boolean>())

        @JvmStatic
        fun needSession(api: String?, on: Boolean) {
            if (api == null) return
            synchronized(sessions) { sessions[api] = on }
        }

        @JvmStatic
        fun needSessionOf(api: String?): Boolean? =
            synchronized(sessions) { sessions[api] }

        @JvmStatic
        fun needEcode(api: String?, on: Boolean) {
            if (api == null) return
            synchronized(ecodes) { ecodes[api] = on }
        }

        @JvmStatic
        fun needEcodeOf(api: String?): Boolean? =
            synchronized(ecodes) { ecodes[api] }

        /**
         * 宿主为某个 api 设的 allowSwitchToPOST。
         * mtop 默认 false；为 true 时长参数 GET 会自动改走 POST
         * （AbstractNetworkConverter：url 长度 + body > switchToPostThreshold 0x2000）。
         */
        @JvmField
        val switchPosts: MutableMap<String, Boolean> =
            Collections.synchronizedMap(LinkedHashMap<String, Boolean>())

        @JvmStatic
        fun switchPost(api: String?, on: Boolean) {
            if (api == null) return
            synchronized(switchPosts) {
                switchPosts[api] = on
                while (switchPosts.size > 40) {
                    val it = switchPosts.keys.iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    }
                }
            }
        }

        @JvmStatic
        fun switchPostOf(api: String?): Boolean? {
            synchronized(switchPosts) {
                return switchPosts[api]
            }
        }

        /**
         * 走 URL query string 的参数（MtopNetworkProp.queryParameterMap）。
         * 这类接口的 data 常常是 "{}" —— 参数全在 query 里，
         * 不回放就变成「参数缺失」，服务端报 FAIL_BIZ_INVALID_QUERY_PARAM。
         */
        @JvmField
        val queryParamsMap: MutableMap<String, MutableMap<String, String>> =
            Collections.synchronizedMap(LinkedHashMap<String, MutableMap<String, String>>())

        @JvmStatic
        fun queryParams(api: String?, q: Map<*, *>?) {
            if (api == null || q.isNullOrEmpty()) return
            synchronized(queryParamsMap) {
                val cur = queryParamsMap.getOrPut(api) { LinkedHashMap() }
                for ((k, v) in q) {
                    if (k == null || v == null) continue
                    cur[k.toString()] = v.toString()
                }
                while (queryParamsMap.size > 40) {
                    val it = queryParamsMap.keys.iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    }
                }
            }
        }

        @JvmStatic
        fun queryParamsOf(api: String?): MutableMap<String, String>? {
            synchronized(queryParamsMap) {
                val q = queryParamsMap[api] ?: return null
                return LinkedHashMap(q)
            }
        }

        /** 记录一条请求体；超过上限丢最旧的 */
        @JvmStatic
        fun body(api: String?, data: String?) = body(api, null, data)

        /**
         * 记录一条请求体（带版本号）。
         * 版本号必须一起记：接口名对了版本不对，mtop 会静默返回
         * FAIL_SYS_API_NOT_FOUNDED（踩过很多次）。
         */
        @JvmStatic
        fun body(api: String?, version: String?, data: String?) {
            if (api.isNullOrEmpty()) return
            try {
                // 带时间戳：20 条滚动覆盖后，没有时间就分不清哪条是刚才那一下
                val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                var line = ts + " " + api +
                    " v=" + (version ?: "?") +
                    " " + (data?.let { redact(it) } ?: "{}")
                if (line.length > 8000) line = line.substring(0, 8000) + "..."
                synchronized(bodies) {
                    // 同一请求会走多个 asyncRequest 重载，只留一条，
                    // 否则 20 条窗口立刻被同一条冲干净
                    val key = api + " v=" + version + " " + (data?.let { redact(it) } ?: "{}")
                    if (bodies.isNotEmpty() && bodies[bodies.size - 1].endsWith(key)) return
                    bodies.add(line)
                    while (bodies.size > 40) bodies.removeAt(0)
                }
            } catch (ignored: Throwable) {
            }
        }

        // 只脱敏真正的凭据；itemId / uid 这类业务标识必须保留，
        // 否则无法对齐接口参数（之前的过度脱敏让详情接口一直调不通）
        private val PII: Pattern = Pattern.compile(
            "(?i)(\"(?:token|_m_h5_tk|sid|sid2|utdid|ttid|accessToken|refreshToken)\"\\s*:\\s*)\"[^\"]*\"",
        )

        @JvmStatic
        fun redact(s: String): String =
            try {
                PII.matcher(s).replaceAll("$1\"***\"")
            } catch (t: Throwable) {
                s
            }

        /**
         * 摘要日志（过滤命中、重放结果等）。
         * 现在统一受「记录网络请求」开关控制，并同时落进抓包库的 NOTE 行，
         * 这样面板/查询里能看到"什么时候发生了什么"，不再只存在于内存。
         */
        /** 去掉行首的 HH:mm:ss 时间戳（tapLog 拼进去的） */
        private fun stripTime(line: String): String {
            val t = line.trimStart()
            if (t.length > 8 && t[2] == ':' && t[5] == ':') {
                return t.substring(8).trimStart()
            }
            return t
        }

        @JvmStatic
        fun tapLog(line0: String) {
            // 抓包常驻（2026-10-31）：不再看开关，也没有调试模式限制
            try {
                // 摘要里带 HH:mm:ss，直接入库会让每条指纹都不同 ——
                // 实测 NOTE 占了 6426/9753 条（66%），全是这种噪声。
                // 把时间戳剥掉，同类事件才能合并成一行（看 hits 就知道次数）。
                val clean = stripTime(line0)
                // builder: 行是内部状态转储（listener/时间戳/seqNo 等），
                // 对排查没价值，却占了 NOTE 的大头（实测 210/344 条）。
                // 这类只在 logcat 里看，不进库。
                if (!clean.startsWith("builder:")) {
                    // NOTE 的前缀形式和 api 解析：
                    //   body:<api> {...}   → 请求体
                    //   req:<api>/<ver>    → 发起请求
                    //   resp:<api>/<ver>   → 收到响应
                    //   err:<api> ...      → 失败
                    // 以前统统记成 api=""，导致「按接口查」时这些全被漏掉
                    // （实测 729 行空 api，占 41% 的行）。解析出来存进 api 列，
                    // ver 也顺手填上，按接口过滤就能看到完整时间线。
                    var api = ""
                    var ver: String? = null
                    val sep = clean.indexOf(':')
                    if (sep in 1..12) {
                        val head = clean.substring(0, sep)
                        if (head == "body" || head == "req" || head == "resp" || head == "err") {
                            var tail = clean.substring(sep + 1).trimStart()
                            var end = 0
                            while (end < tail.length &&
                                !tail[end].isWhitespace() && tail[end] != '{'
                            ) end++
                            var tok = tail.substring(0, end)
                            val slash = tok.indexOf('/')
                            if (slash > 0) {
                                ver = tok.substring(slash + 1).take(24)
                                tok = tok.substring(0, slash)
                            }
                            // 只接受像接口名的 token（有点号，不是纯文本）
                            if (tok.length > 8 && tok.indexOf('.') > 0) api = tok
                        }
                    }
                    TrafficStore.record("NOTE", api, ver, null, null, clean)
                }
            } catch (ignored: Throwable) {
            }
            val line = redact(line0)
            try {
                Log.i("xysidecar", line)
            } catch (ignored: Throwable) {
            }
        }

        // ---------------------------------------------------------- 完整抓包
        /**
         * 完整抓包：**SQLite + 逐条 gzip 无损压缩**，见 TrafficStore。
         *
         * 之前是纯文本 JSONL，4MB 一个文件、几秒写满就轮转，历史全丢；
         * 而且没法按 api/时间检索。现在换库：
         *   - 正文 gzip 压缩（JSON 典型压 5~10 倍，完全无损）
         *   - 按 api / ts 建索引，可直接检索
         *   - 后台线程批量事务写，队列满就丢新并计数，绝不拖慢宿主
         *   - 正文总量超 96MB 自动裁最旧 30%
         */
        @JvmStatic
        fun record(
            dir: String?,
            api: String?,
            version: String?,
            method: String?,
            headersJson: String?,
            body: String?,
        ) {
            TrafficStore.record(dir, api, version, method, headersJson, body)
        }

        @JvmStatic
        fun trafficEnabled(on: Boolean) {
            TrafficStore.enabled(on)
        }

        @JvmStatic
        fun trafficEnabled(): Boolean = TrafficStore.enabled()

        /** 写到 /sdcard/Download（MediaStore，不占闲鱼自己的目录） */
        @JvmStatic
        fun dumpTo(fileName: String, line: String) {
            try {
                val c = app ?: return
                val cv = android.content.ContentValues()
                cv.put(
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                    "xy_" + fileName + "_" + System.currentTimeMillis() + ".txt",
                )
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    cv.put(
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        "Download/xysidecar",
                    )
                }
                val uri = c.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    cv,
                ) ?: return
                val os = c.contentResolver.openOutputStream(uri) ?: return
                os.write(line.toByteArray(Charsets.UTF_8))
                os.close()
            } catch (t: Throwable) {
                Log.w("xysidecar", "dump failed: $t")
            }
        }

        @JvmStatic
        fun tap(api: String, version: String) {
            val k = "$api/$version"
            if (!tap.contains(k)) {
                if (tap.size > 40) tap.removeAt(0)
                tap.add(k)
            }
        }

        /** 只保留对象 identity，不调用宿主 JSONObject 的 equals/hashCode，也不写入 wire data。 */
        class HostCall(private val onFailure: (String) -> Unit) {
            @Volatile var closed = false
            private val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
            fun active(): Boolean = !closed && System.nanoTime() < deadline
            fun fail(code: String) {
                closed = true
                onFailure(code)
            }
        }

        private class IdentityRef(
            value: Any,
            queue: java.lang.ref.ReferenceQueue<Any>? = null,
        ) : java.lang.ref.WeakReference<Any>(value, queue) {
            private val hash = System.identityHashCode(value)
            override fun hashCode(): Int = hash
            override fun equals(other: Any?): Boolean = this === other ||
                (other is IdentityRef && get() != null && get() === other.get())
        }

        private val identityQueue = java.lang.ref.ReferenceQueue<Any>()
        private val hostCalls = HashMap<IdentityRef, HostCall>()

        @Synchronized
        fun markHostCall(value: Any?, call: HostCall) {
            if (value == null) return
            drainHostCalls()
            hostCalls[IdentityRef(value, identityQueue)] = call
        }

        @Synchronized
        fun hostCall(value: Any?): HostCall? {
            if (value == null) return null
            drainHostCalls()
            return hostCalls[IdentityRef(value)]
        }

        private fun drainHostCalls() {
            while (true) {
                val ref = identityQueue.poll() ?: break
                hostCalls.remove(ref)
            }
        }

        @Volatile var hostRpcReady = false

        /** 同步旧入口仍用线程嵌套计数；异步 HostRpc 使用上面的精确对象关联。 */
        private val OURS = ThreadLocal<Int>()

        /**
         * 本次请求是否由我方发起（调试通道 / 功能脚本），而不是宿主 App 自己发的。
         *
         * 为什么必须区分：抓包库既是"宿主的真实行为样本"又是"我方探测的记录"，
         * 混在一起会直接污染判断 —— 实测拿库里记的 ver=3.0 / method=GET 当基准，
         * 结果那个值是我自己探测时写进去的，把排查带偏了整整三轮。
         */
        private val ORIGIN = ThreadLocal<String>()

        /** 来源值 */
        const val SRC_HOST = "host"
        const val SRC_OURS = "ours"

        @JvmStatic
        fun enterOurs() {
            OURS.set((OURS.get() ?: 0) + 1)
            ORIGIN.set(SRC_OURS)
        }

        @JvmStatic
        fun exitOurs() {
            val depth = (OURS.get() ?: 0) - 1
            if (depth > 0) {
                OURS.set(depth)
            } else {
                OURS.remove()
                ORIGIN.remove()
            }
        }

        @JvmStatic
        fun isOurs(): Boolean = (OURS.get() ?: 0) > 0

        /** 仅为抓包同步调用设置来源，结束后恢复，不把整段宿主调用视为我方。 */
        fun <T> withOrigin(ours: Boolean, block: () -> T): T {
            val oldDepth = OURS.get()
            val oldOrigin = ORIGIN.get()
            if (ours) { OURS.set(1); ORIGIN.set(SRC_OURS) }
            else { OURS.remove(); ORIGIN.set(SRC_HOST) }
            try { return block() } finally {
                if (oldDepth == null) OURS.remove() else OURS.set(oldDepth)
                if (oldOrigin == null) ORIGIN.remove() else ORIGIN.set(oldOrigin)
            }
        }

        /** 当前线程的请求来源：ours / host */
        @JvmStatic
        fun origin(): String = ORIGIN.get() ?: SRC_HOST

        private val READY = CountDownLatch(1)

        @JvmStatic
        fun markReady(ctx: Context?) {
            if (ctx != null) {
                val ac = ctx.applicationContext
                app = ac ?: ctx
            }
            READY.countDown()
        }

        @JvmStatic
        fun awaitReady(timeoutMs: Long): Boolean =
            try {
                READY.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
    }
}
