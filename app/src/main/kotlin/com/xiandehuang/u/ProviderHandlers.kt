package com.xiandehuang.u

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.util.Log
import org.json.JSONObject
import java.lang.reflect.Modifier

/**
 * provider 通道的具体实现：把各种 selection 转成 Cursor。
 *
 * 从 NewEntry 拆出来（原 1100 行）。这块只做"选择器 → 数据"的翻译，
 * 不涉及任何 hook 安装，改通道不用碰注入逻辑。
 *
 * 支持的通道：
 *   xysidecar:cache[:<api>]             读宿主缓存的响应
 *   xysidecar:api:<类名>[:<过滤>]       反射探查任意类
 *   xysidecar:prefs[:get|set:<key>]     开关读写（跨进程实时）
 *   xysidecar:traffic[:k=v;...]         抓包查询
 *   xysidecar:tree[:选项]               View 树导出
 *   xysidecar:bodies                    最近捕获的宿主请求体
 *   xysidecar:mtop[:base64] + 3 args    发起 mtop 调用
 */
object ProviderHandlers {

    private const val TAG = "xysidecar"
    private const val LAUNCHER = "com.taobao.android.remoteobject.easy.MtopLauncher"

    /** 读宿主缓存的响应：xysidecar:cache[:<api>] */
    fun cacheCursor(selection: String): android.database.Cursor {
        val c = MatrixCursor(arrayOf(Protocol.COL_KEY, Protocol.COL_VALUE))
        try {
            var rest = selection.substring(Protocol.CACHE.length)
            if (rest.startsWith(":")) rest = rest.substring(1)
            if (rest.isEmpty()) {
                for (k in Protocol.State.captureKeys()) {
                    val cap = Protocol.State.captureOf(k) ?: continue
                    c.addRow(
                        arrayOf<Any>(
                            k,
                            cap.json.length.toString() + " chars, " +
                                (System.currentTimeMillis() - cap.at) / 1000 + "s ago",
                        ),
                    )
                }
                return c
            }
            val cap = Protocol.State.captureOf(rest)
            if (cap == null) {
                c.addRow(arrayOf<Any>("error", "not cached: $rest"))
                return c
            }
            c.addRow(arrayOf<Any>("ok", cap.json))
        } catch (t: Throwable) {
            c.addRow(arrayOf<Any>("error", t.toString()))
        }
        return c
    }

    /** 反射探查任意类：xysidecar:api:<全类名>[:<名字过滤>] */
    fun apiCursor(selection: String): android.database.Cursor {
        val c = MatrixCursor(arrayOf(Protocol.COL_KEY, Protocol.COL_VALUE))
        try {
            var rest = selection.substring(Protocol.API.length)
            if (rest.startsWith(":")) rest = rest.substring(1)
            val sep = rest.indexOf(':')
            val clsName = if (sep < 0) rest else rest.substring(0, sep)
            val filter = if (sep < 0) "" else rest.substring(sep + 1)
            if (clsName.isEmpty()) {
                c.addRow(arrayOf<Any>("error", "expect xysidecar:api:<class>[:<filter>]"))
                return c
            }
            val cl = Protocol.State.classLoader
            val cls = Class.forName(clsName, false, cl)
            c.addRow(arrayOf<Any>("class", cls.name))
            var i = 0
            for (m in cls.declaredMethods) {
                val n = m.name
                if (filter.isNotEmpty() && !n.lowercase().contains(filter.lowercase())) continue
                val sb = StringBuilder(n).append('(')
                val ps = m.parameterTypes
                for (k in ps.indices) {
                    if (k > 0) sb.append(", ")
                    sb.append(ps[k].simpleName)
                }
                sb.append(") : ").append(m.returnType.simpleName)
                c.addRow(arrayOf<Any>("m" + i++, sb.toString()))
            }
            for (f in cls.declaredFields) {
                if (filter.isNotEmpty() && !f.name.lowercase().contains(filter.lowercase())) continue
                c.addRow(arrayOf<Any>("f" + i++, f.type.simpleName + " " + f.name))
            }
            c.addRow(arrayOf<Any>("count", i.toString()))
        } catch (t: Throwable) {
            c.addRow(arrayOf<Any>("error", t.toString()))
        }
        return c
    }

    /**
     * 回放参数探查：xysidecar:replay[:<api>]
     *
     * 把「我们准备怎么发」和「宿主实际怎么发」并排列出来，用于判断某个接口重放
     * 失败到底是 method / header / query 没还原，还是真的依赖服务端上下文。
     * 没有这一步就只能靠猜 —— 前面已经因为「prop.method 是默认 GET」猜错一轮。
     */
    fun replayCursor(selection: String): android.database.Cursor {
        val c = MatrixCursor(arrayOf(Protocol.COL_KEY, Protocol.COL_VALUE))
        try {
            var rest = selection.substring(Protocol.REPLAY.length)
            if (rest.startsWith(":")) rest = rest.substring(1)
            val api = rest.trim()
            if (api.isEmpty()) {
                c.addRow(arrayOf<Any>("hint", "expect xysidecar:replay:<api>[:set=method:POST]"))
                return c
            }
            // 写形式：xysidecar:replay:<api>:set=method:POST|GET
            // 用来做对照实验 —— 只改一个变量，确认某接口失败到底是 method 不对，
            // 还是真的依赖服务端上下文。没有这个就只能靠猜。
            val setIdx = api.indexOf(":set=")
            val realApi = if (setIdx > 0) api.substring(0, setIdx) else api
            if (setIdx > 0) {
                val kv = api.substring(setIdx + 5)
                val eq = kv.indexOf(':')
                val k = if (eq > 0) kv.substring(0, eq) else kv
                val v = if (eq > 0) kv.substring(eq + 1) else ""
                when (k) {
                    "method" -> {
                        // 两处都写：methodOf 优先读 guesses，只改一处会看起来"没生效"
                        Protocol.State.methodGuess(realApi, v.uppercase())
                        Protocol.State.method(realApi, v.uppercase())
                    }
                    "session" -> Protocol.State.needSession(realApi, v == "1" || v.equals("true", true))
                    else -> c.addRow(arrayOf<Any>("error", "unknown key: $k"))
                }
            }
            val api0 = realApi
            c.addRow(arrayOf<Any>("api", api0))
            c.addRow(arrayOf<Any>("finalMethod", Protocol.State.methodOf(api0) ?: "-"))
            c.addRow(arrayOf<Any>("oursMethod", Protocol.State.methodOursOf(api0) ?: "-"))
            c.addRow(arrayOf<Any>("isFinal", Protocol.State.hasFinalMethod(api0).toString()))
            c.addRow(arrayOf<Any>("switchToPost", Protocol.State.switchPostOf(api0)?.toString() ?: "-"))
            c.addRow(arrayOf<Any>("needSession", Protocol.State.needSessionOf(api0)?.toString() ?: "-"))
            c.addRow(arrayOf<Any>("needEcode", Protocol.State.needEcodeOf(api0)?.toString() ?: "-"))
            val h = Protocol.State.headersOf(api0)
            c.addRow(arrayOf<Any>("headers", if (h == null) "-" else h.size.toString()))
            val q = Protocol.State.queryParamsOf(api0)
            c.addRow(arrayOf<Any>("queryParams", if (q == null) "-" else q.keys.joinToString(",")))
            // 抓包库里这个接口的记录来源分布 —— 排查时先确认基准是干净的：
            // 如果 host=0，说明库里只有我方的探测记录，不能拿它当"宿主怎么发"的证据。
            try {
                val db = TrafficStore.openForRead()
                var cur: android.database.Cursor? = null
                try {
                    cur = db?.rawQuery(
                        "SELECT IFNULL(origin,'host'), COUNT(*) FROM traffic" +
                            " WHERE api=? AND dir='REQ' GROUP BY 1",
                        arrayOf(api0),
                    )
                    val sb = StringBuilder()
                    while (cur != null && cur.moveToNext()) {
                        if (sb.isNotEmpty()) sb.append(", ")
                        sb.append(cur.getString(0)).append('=').append(cur.getInt(1))
                    }
                    c.addRow(arrayOf<Any>("dbTraffic", sb.ifEmpty { "无记录" }.toString()))
                } finally {
                    try {
                        cur?.close()
                    } catch (ignored: Throwable) {
                    }
                }
            } catch (ignored: Throwable) {
            }
        } catch (t: Throwable) {
            c.addRow(arrayOf<Any>("error", t.toString()))
        }
        return c
    }

    /** 无头开关读写：xysidecar:prefs[:get:<key>|:set:<key>:<0|1>] */
    fun prefsCursor(ctx: Context?, selection: String): android.database.Cursor {
        val c = MatrixCursor(arrayOf(Protocol.COL_KEY, Protocol.COL_VALUE))
        val app = ctx ?: Protocol.State.app
        if (app == null) {
            c.addRow(arrayOf<Any>("error", "no context"))
            return c
        }
        try {
            val rest = selection.substring(Protocol.PREFS.length)
            if (rest.isEmpty()) {
                for (k in Prefs.ALL) {
                    // 用 liveBool 而不是 Prefs.get：后者是进程内缓存，
                    // provider 报出去的值会和 SP 文件里的真实值不一致（踩过）
                    c.addRow(
                        arrayOf<Any>(
                            k,
                            if (Protocol.State.liveBool(app, k, Prefs.defOf(k))) "1" else "0",
                        ),
                    )
                }
                return c
            }
            val body = if (rest.startsWith(":")) rest.substring(1) else rest
            if (body.startsWith("get:")) {
                val k = body.substring(4)
                // 同上：必须读文件，不能读进程内缓存
                c.addRow(
                    arrayOf<Any>(
                        k,
                        if (Protocol.State.liveBool(app, k, Prefs.defOf(k))) "1" else "0",
                    ),
                )
                return c
            }
            if (body.startsWith("set:")) {
                val kv = body.substring(4)
                val sep = kv.lastIndexOf(':')
                if (sep <= 0) {
                    c.addRow(arrayOf<Any>("error", "expect set:<key>:<0|1>"))
                    return c
                }
                val k = kv.substring(0, sep)
                val vs = kv.substring(sep + 1).trim()
                val v = "1" == vs
                Prefs.set(app, k, v)
                // 面板切换时缓存的静态值也要同步，否则要等重启才生效
                if (Prefs.SKIP_SPLASH == k) Protocol.State.skipSplash = v
                c.addRow(arrayOf<Any>(k, if (v) "1" else "0"))
                return c
            }
            c.addRow(arrayOf<Any>("error", "bad prefs selector: $selection"))
        } catch (t: Throwable) {
            c.addRow(arrayOf<Any>("error", t.toString()))
        }
        return c
    }

    /**
     * 完整抓包查询：xysidecar:traffic[:<key=value;...>]
     *
     * 支持的键（都可省略，用 ; 分隔）：
     *   api=<接口名>        精确匹配；以 * 结尾则前缀匹配
     *   dir=REQ|RESP        只看请求/响应
     *   since=<毫秒时间戳>   起始时间
     *   limit=<n>           条数，默认 50，最大 500
     *   after=<id>          分页游标：从该 id 之后取
     *   body=1              带正文（默认不带，列表更轻）
     *   origin=host|ours    来源过滤：host=宿主 App 自己发的，ours=我方探测。
     *                       判断接口真实行为必须只看 host。
     */
    fun trafficCursor(selection: String): android.database.Cursor {
        val c = MatrixCursor(arrayOf(Protocol.COL_KEY, Protocol.COL_VALUE))
        try {
            var rest = selection.substring(Protocol.TRAFFIC.length)
            if (rest.startsWith(":")) rest = rest.substring(1)
            if (rest == "temp:start" || rest == "temp:stop" || rest == "temp:status") {
                val result = when (rest) {
                    "temp:start" -> TempTraffic.start()
                    "temp:stop" -> TempTraffic.stop()
                    else -> TempTraffic.status()
                }
                c.addRow(arrayOf<Any>("temp", result))
                return c
            }
            // 无参数 = 库体积概览（文件/正文/空闲页/行数）。
            // 排查「库怎么这么大」时先看这个，不用把库拉到电脑上分析。
            if (rest.trim().isEmpty()) {
                val d = TrafficStore.openForRead()
                c.addRow(
                    arrayOf<Any>(
                        "stat",
                        if (d == null) "数据库未就绪" else TrafficStore.fileStat(d),
                    ),
                )
                return c
            }
            // 手动回收空闲页：xysidecar:traffic:vacuum
            if ("vacuum" == rest.trim()) {
                c.addRow(arrayOf<Any>("vacuum", TrafficStore.vacuumNow()))
                return c
            }
            var api: String? = null
            var dir: String? = null
            var since = 0L
            var after = 0L
            var limit = 50
            var withBody = false
            var origin: String? = null
            if (rest.trim().isNotEmpty()) {
                for (kv in rest.split(";")) {
                    val eq = kv.indexOf('=')
                    if (eq <= 0) continue
                    val k = kv.substring(0, eq).trim()
                    val v = kv.substring(eq + 1).trim()
                    when (k) {
                        "api" -> api = v
                        "dir" -> dir = v
                        "since" -> since = v.toLong()
                        "after" -> after = v.toLong()
                        "limit" -> limit = v.toInt()
                        "body" -> withBody = "1" == v || v.equals("true", true)
                        // 默认全都要；只判宿主行为时传 origin=host
                        "origin" -> origin = v
                    }
                }
            }
            c.addRow(
                arrayOf<Any>(
                    "traffic",
                    TrafficStore.query(api, dir, since, limit, after, withBody, origin),
                ),
            )
        } catch (t: Throwable) {
            c.addRow(arrayOf<Any>("error", t.toString()))
        }
        return c
    }

    /**
     * 导出报告：xysidecar:export[:api1,api2]
     *
     * 在 provider 线程里同步生成（不是主线程，几百毫秒可以接受），
     * 这样外部脚本一条命令就能拿到 HTML，不用点面板。
     */
    fun exportCursor(ctx: Context?, selection: String): android.database.Cursor {
        val c = MatrixCursor(arrayOf(Protocol.COL_KEY, Protocol.COL_VALUE))
        val app = ctx ?: Protocol.State.app
        if (app == null) {
            c.addRow(arrayOf<Any>("error", "no context"))
            return c
        }
        try {
            var rest = selection.substring(Protocol.EXPORT.length)
            if (rest.startsWith(":")) rest = rest.substring(1)
            val only = if (rest.isBlank()) null else rest.split(",").toTypedArray()
            ApiDoc.load(app)
            val r = TrafficExport.build(app, only)
            c.addRow(arrayOf<Any>("path", r.path))
            c.addRow(arrayOf<Any>("apis", r.apis.toString()))
            c.addRow(arrayOf<Any>("bytes", r.bytes.toString()))
            if (r.uri != null) c.addRow(arrayOf<Any>("uri", r.uri!!))
        } catch (t: Throwable) {
            c.addRow(arrayOf<Any>("error", t.toString()))
        }
        return c
    }

    /** 词典查询：xysidecar:doc[:<api>] */
    /**
     * 业务链：`xysidecar:chain[:<名字>]`。
     *
     * 和面板「自动」里的按钮一一对应。存在的意义是**无头验证**：
     * 改完链的逻辑不用点界面就能确认它到底调通了没有。
     *
     * 注意这些是写操作（真签到、真掷骰），provider 线程里同步跑；
     * all 会跑五条链，可能十几秒，别连发（provider 线程会被占住）。
     */
    fun chainCursor(ctx: Context?, selection: String): android.database.Cursor {
        val c = MatrixCursor(arrayOf(Protocol.COL_KEY, Protocol.COL_VALUE))
        val app = ctx ?: Protocol.State.app
        if (app == null) {
            c.addRow(arrayOf<Any>("error", "no context"))
            return c
        }
        val rest = selection.substring(Protocol.CHAIN.length)
        val name = if (rest.startsWith(":")) rest.substring(1).trim() else ""
        if (name.isEmpty()) {
            for (entry in ChainPlan.entries) {
                c.addRow(arrayOf<Any>(entry.id, entry.title + "：" + entry.desc))
            }
            c.addRow(arrayOf<Any>("all", "全部按顺序跑"))
            c.addRow(arrayOf<Any>("pending", "推进任务及衍生奖励"))
            return c
        }
        try {
            if (ChainPlan.select(name).isEmpty()) {
                c.addRow(arrayOf<Any>("error", "未知链：$name"))
                return c
            }
            val results = Chains.manual(app, name)
            for (r in results) {
                for (s in r.steps) {
                    c.addRow(arrayOf<Any>(r.title + " · " + s.name, s.detail))
                }
                if (r.steps.isEmpty()) c.addRow(arrayOf<Any>(r.title, "无步骤"))
            }
        } catch (t: Throwable) {
            c.addRow(arrayOf<Any>("error", t.toString()))
        }
        return c
    }

    /** 执行会话诊断：`xysidecar:run` —— 只读，验证步骤是否真的在更新。 */
    fun runCursor(ctx: Context?): android.database.Cursor {
        val c = MatrixCursor(arrayOf(Protocol.COL_KEY, Protocol.COL_VALUE))
        val s = RunProgress.latest
        if (s == null) {
            c.addRow(arrayOf<Any>("session", "无"))
            return c
        }
        c.addRow(arrayOf<Any>("running", s.running.toString()))
        c.addRow(arrayOf<Any>("current", s.current))
        c.addRow(arrayOf<Any>("lines", s.snapshot().size.toString()))
        c.addRow(arrayOf<Any>("age", ((System.currentTimeMillis() - s.startedAt) / 1000L).toString() + "s"))
        c.addRow(arrayOf<Any>("revision", s.revision.toString()))
        return c
    }

    /**
     * 自动任务调度器：`xysidecar:auto[:run[:light|full]] [:set:<分钟>]`。
     *
     * 存在的理由和 [chainCursor] 一样 —— 无头验证。定时器跑在后台线程里，
     * 界面上只能看到「上次执行」那一行字；要确认它到底有没有跑、间隔改了
     * 有没有生效、暂停是否真的顺延，只能从这里查。
     *
     * `run` 是写操作（真领奖、真触发），provider 线程里同步跑，
     * 任务推进一轮约 10-30 秒，别连发。
     */
    fun autoCursor(ctx: Context?, selection: String): android.database.Cursor {
        val c = MatrixCursor(arrayOf(Protocol.COL_KEY, Protocol.COL_VALUE))
        val app = ctx ?: Protocol.State.app
        if (app == null) {
            c.addRow(arrayOf<Any>("error", "no context"))
            return c
        }
        var rest = selection.substring(Protocol.AUTO.length)
        if (rest.startsWith(":")) rest = rest.substring(1)
        val arg = rest.trim()
        try {
            if (arg.isEmpty() || arg == "status") {
                val m = AutoTask.minutes(app)
                c.addRow(arrayOf<Any>("interval", if (m <= 0) "关闭" else "每 $m 分钟"))
                val last = AutoTask.lastAt(app)
                c.addRow(
                    arrayOf<Any>(
                        "last",
                        if (last <= 0L) "无" else hm(last) + " " + AutoTask.lastText(app),
                    ),
                )
                c.addRow(arrayOf<Any>("next", orDash(AutoTask.nextText(app))))
                c.addRow(arrayOf<Any>("paused", if (ApiInit.paused(app)) "是" else "否"))
                c.addRow(arrayOf<Any>("state", ApiInit.stateText()))
                return c
            }
            if (arg == "set" || arg.startsWith("set:")) {
                val v = arg.substringAfter(':', "").toIntOrNull()
                if (v == null) {
                    c.addRow(arrayOf<Any>("error", "用法 xysidecar:auto:set:<分钟>"))
                    return c
                }
                AutoTask.setMinutes(app, v)
                c.addRow(arrayOf<Any>("interval", if (v <= 0) "关闭" else "每 $v 分钟"))
                c.addRow(arrayOf<Any>("next", orDash(AutoTask.nextText(app))))
                return c
            }
            if (arg == "run" || arg.startsWith("run:")) {
                val force = when (arg.substringAfter(':', "")) {
                    "light" -> 1
                    "full" -> 2
                    else -> 0
                }
                val s = AutoTask.runRound(app, force)
                c.addRow(arrayOf<Any>("result", s))
                c.addRow(arrayOf<Any>("next", orDash(AutoTask.nextText(app))))
                return c
            }
            c.addRow(arrayOf<Any>("error", "未知参数：$arg"))
        } catch (t: Throwable) {
            c.addRow(arrayOf<Any>("error", t.toString()))
        }
        return c
    }

    /** 空串换成破折号；不用 String.ifEmpty —— R8 收缩后 kotlin 扩展会缺实现 */
    private fun orDash(s: String): String = if (s.length == 0) "—" else s

    private fun hm(ms: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = ms
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val mi = cal.get(java.util.Calendar.MINUTE)
        return (if (h < 10) "0$h" else "" + h) + ":" + (if (mi < 10) "0$mi" else "" + mi)
    }

    fun docCursor(ctx: Context?, selection: String): android.database.Cursor {        val c = MatrixCursor(arrayOf(Protocol.COL_KEY, Protocol.COL_VALUE))
        try {
            ApiDoc.load(ctx)
            var rest = selection.substring(Protocol.DOC.length)
            if (rest.startsWith(":")) rest = rest.substring(1)
            if (rest.isBlank()) {
                val d = ApiDoc.docJson()
                val it = d.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val info = ApiDoc.intentOf(k)
                    c.addRow(arrayOf<Any>(k, info.first + "  [" + info.second + "]"))
                }
                return c
            }
            val info = ApiDoc.intentOf(rest)
            c.addRow(arrayOf<Any>("intent", info.first))
            c.addRow(arrayOf<Any>("cat", info.second))
            c.addRow(arrayOf<Any>("layer", ApiDoc.layerOf(rest)))
            val note = ApiDoc.noteOf(rest)
            if (note.isNotEmpty()) c.addRow(arrayOf<Any>("note", note))
            val pr = ApiDoc.loadProbe(ctx)[rest]
            c.addRow(arrayOf<Any>("replay", pr?.first ?: "unk"))
            if (pr?.second != null) c.addRow(arrayOf<Any>("replayWhy", pr!!.second!!))
        } catch (t: Throwable) {
            c.addRow(arrayOf<Any>("error", t.toString()))
        }
        return c
    }

    /** 跑关联分析：xysidecar:analyze */
    fun analyzeCursor(ctx: Context?): android.database.Cursor {
        val c = MatrixCursor(arrayOf(Protocol.COL_KEY, Protocol.COL_VALUE))
        try {
            ApiDoc.load(ctx)
            val r = Relation.analyze(ctx)
            c.addRow(arrayOf<Any>("apis", r.apis.toString()))
            c.addRow(arrayOf<Any>("relations", r.relations.toString()))
            c.addRow(arrayOf<Any>("ms", r.ms.toString()))
        } catch (t: Throwable) {
            c.addRow(arrayOf<Any>("error", t.toString()))
        }
        return c
    }

    /** 关联查询：xysidecar:rel[:<api>] */
    fun relCursor(selection: String): android.database.Cursor {
        val c = MatrixCursor(arrayOf(Protocol.COL_KEY, Protocol.COL_VALUE))
        try {
            var rest = selection.substring(Protocol.REL.length)
            if (rest.startsWith(":")) rest = rest.substring(1)
            val list = if (rest.isBlank()) Relation.allRelations()
            else Relation.relationsOf(rest)
            var i = 0
            for (o in list) {
                c.addRow(
                    arrayOf<Any>(
                        "r" + i++,
                        o.optString("from") + " → " + o.optString("to") +
                            "  " + o.optString("field") + "=" + o.optString("evidence") +
                            "  [" + o.optInt("score") + "]",
                    ),
                )
            }
            c.addRow(arrayOf<Any>("count", list.size.toString()))
        } catch (t: Throwable) {
            c.addRow(arrayOf<Any>("error", t.toString()))
        }
        return c
    }

    /** 导入报告：xysidecar:import:<路径> */
    fun importCursor(ctx: Context?, selection: String): android.database.Cursor {
        val c = MatrixCursor(arrayOf(Protocol.COL_KEY, Protocol.COL_VALUE))
        try {
            var rest = selection.substring(Protocol.IMPORT.length)
            if (rest.startsWith(":")) rest = rest.substring(1)
            if (rest.isBlank()) {
                c.addRow(arrayOf<Any>("error", "expect xysidecar:import:<path>"))
                return c
            }
            ApiDoc.load(ctx)
            val r = TrafficImport.fromFile(rest.trim())
            if (r.error != null) {
                c.addRow(arrayOf<Any>("error", r.error!!))
            } else {
                c.addRow(arrayOf<Any>("apis", r.apis.toString()))
                c.addRow(arrayOf<Any>("relations", r.relations.toString()))
                c.addRow(arrayOf<Any>("fieldDoc", r.fieldDoc.toString()))
            }
        } catch (t: Throwable) {
            c.addRow(arrayOf<Any>("error", t.toString()))
        }
        return c
    }

    /** View 树导出：xysidecar:tree[:选项] */
    fun treeCursor(selection: String): android.database.Cursor {
        val c = MatrixCursor(arrayOf(Protocol.COL_KEY, Protocol.COL_VALUE))
        try {
            val rest = selection.substring(Protocol.TREE.length)
            val o = ViewTreeDump.parse(rest)
            val root = ViewTreeDump.currentDecor()
            val lines = ViewTreeDump.dump(root, o)
            var i = 0
            for (line in lines) {
                c.addRow(arrayOf<Any>("l" + i++, line))
            }
            c.addRow(arrayOf<Any>("count", lines.size.toString()))
        } catch (t: Throwable) {
            c.addRow(arrayOf<Any>("error", t.toString()))
        }
        return c
    }

    /** 最近捕获的宿主请求体：xysidecar:bodies */
    fun bodiesCursor(): android.database.Cursor {
        val c = MatrixCursor(arrayOf(Protocol.COL_KEY, Protocol.COL_VALUE))
        synchronized(Protocol.State.bodies) {
            var i = 0
            for (b in Protocol.State.bodies) {
                c.addRow(arrayOf<Any>("body" + i++, b))
            }
        }
        return c
    }

    /**
     * channel socket 通道。provider 在主进程，但**真正的服务端在 :channel**，
     * 所以这里只是个转发器：主进程死了 provider 也没了，此时要用
     * `run-as` + LocalSocket 直连（见 tools/chcall.sh）。
     */
    fun channelCursor(selection: String, args: Array<Any?>?): android.database.Cursor {
        val c = MatrixCursor(arrayOf(Protocol.COL_KEY, Protocol.COL_VALUE))
        var rest = selection.substring(Protocol.CH.length)
        if (rest.startsWith(":")) rest = rest.substring(1)

        val parts = rest.split(":", limit = 2)
        val cmd = parts[0]
        val payload = parts.getOrNull(1)

        val resp = when (cmd) {
            "ping", "status", "reap" -> ChannelClient.command(cmd)
            "task" -> {
                // xysidecar:ch:task:<b64 的 {cmd:"task.add"/"task.list"/...}>
                if (payload.isNullOrEmpty()) {
                    return Protocol.err("xysidecar:ch:task 需要 base64 参数")
                }
                try {
                    val json = String(
                        android.util.Base64.decode(payload, android.util.Base64.DEFAULT),
                        Charsets.UTF_8,
                    )
                    ChannelClient.sendRaw(json)
                } catch (t: Throwable) {
                    return Protocol.err("bad payload: $t")
                }
            }
            "api" -> {
                if (payload.isNullOrEmpty()) {
                    return Protocol.err("xysidecar:ch:api 需要 base64 参数")
                }
                try {
                    val json = String(
                        android.util.Base64.decode(payload, android.util.Base64.DEFAULT),
                        Charsets.UTF_8,
                    )
                    val o = org.json.JSONObject(json)
                    ChannelClient.request(
                        o.optString("api"),
                        o.optString("version", "1.0"),
                        o.opt("data")?.toString() ?: "{}",
                    )
                } catch (t: Throwable) {
                    return Protocol.err("bad payload: $t")
                }
            }
            else -> return Protocol.err("unknown ch cmd: $cmd")
        }

        if (resp == null) {
            c.addRow(arrayOf<Any>("error", "channel socket 不可达（:channel 未启动或未注入）"))
            return c
        }
        c.addRow(arrayOf<Any>("ok", resp.optString("ok")))
        for (k in resp.keys()) {
            if (k == "ok") continue
            c.addRow(arrayOf<Any>(k, resp.opt(k).toString()))
        }
        return c
    }

    fun dispatch(ctx: Context?, selection: String, args: Array<Any?>?): android.database.Cursor {
        val context = ctx ?: Protocol.State.app ?: return Protocol.err("no context")

        val req = Protocol.parse(selection, args)
            ?: return Protocol.err("bad request: expect xysidecar:mtop[:base64] + 3 args")

        return try {
            Protocol.ok(HostRpc.request(context.applicationContext ?: context, req[0], req[1], req[2]))
        } catch (t: Throwable) {
            Protocol.err(t.javaClass.simpleName + ": " + t.message)
        }
    }

    /** 历史低层调试实现，保留研究入口；业务任务不进入此路径。 */
    private fun legacyDispatch(context: Context, req: Array<String>): android.database.Cursor {
        // 优先走主进程的 MtopBridge：它拿的是宿主自己初始化好的 Mtop 实例，
        // 并且能回放 hook 捕获到的 header / HTTP method / query 参数。
        //
        // 之前这里是「channel socket 优先」，实测代价很大：channel 里的 ChannelMtop
        // 是自建实例，没有 header、没有 method，mtop 默认 GET →
        //   要求 POST 的接口 FAIL_SYS_INVALID_HTTP_METHOD
        //   参数 10KB+ 的接口被服务端 414，客户端再改写成 ANDROID_SYS_PARAM_TOO_LONG
        // 27 个「不可重放」接口里绝大部分是这么来的，与接口本身无关。
        // channel 只作为「主进程不在」时的兜底保留。
        if (Protocol.State.mtop == null) {
            val deadline = System.currentTimeMillis() + 12000
            while (Protocol.State.mtop == null && System.currentTimeMillis() < deadline) {
                Protocol.State.awaitReady(200)
                if (Protocol.State.mtop == null) {
                    try {
                        Thread.sleep(150)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
        if (Protocol.State.mtop != null) {
            try {
                return Protocol.ok(
                    MtopBridge.request(
                        context.applicationContext ?: context,
                        req[0], req[1], req[2],
                    ),
                )
            } catch (t: Throwable) {
                // 主进程有实例就不要再回退 channel：channel 是自建 Mtop，拿不到
                // 登录态上下文，会把失败伪装成"成功但空 body"，探测时全是假阳性。
                Log.w(TAG, "主进程 MtopBridge 失败: ${t.message}")
                return Protocol.err(t.javaClass.simpleName + ": " + t.message)
            }
        }

        ChannelClient.request(req[0], req[1], req[2])?.let { r ->
            if (r.optBoolean("ok")) {
                val body = r.optString("body")
                return Protocol.ok(if (body.isEmpty()) "{}" else body)
            }
            val err = r.optString("error")
            if (err.isNotEmpty()) return Protocol.err(err)
        }

        val ac = context.applicationContext
        val app: Context = ac ?: context

        // provider 冷启动拉起的进程不会跑 App 自己的初始化，这里替它跑一次
        if (!Protocol.State.initFired && Protocol.State.mtop == null) {
            try {
                val launcher = Class.forName(
                    LAUNCHER, false, Protocol.State.classLoader,
                )
                if (app is Application) {
                    Reflect.callStatic(launcher, "initMtopSdk", app)
                    Log.i(TAG, "invoked initMtopSdk manually")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "manual initMtopSdk failed: $t")
            }
        }

        // ---- channel / 非主进程兜底 ----
        // 主进程里 Protocol.State.mtop 由 MtopHooks 捕获宿主实例后填入；
        // 但 provider 若被声明到 :channel（或主进程已死、被 provider 拉起的冷进程），
        // 宿主自己不会初始化 Mtop，这里用 Mtop.instance(Context) 自建一套。
        // 实测（7.28.80）：channel 内 mtopsdk 类全部可达，instance(ctx) 可成功，
        // 公共接口与真实业务接口均可调通。
        if (Protocol.State.mtop == null && app is Application) {
            try {
                val cl = Protocol.State.classLoader ?: app.classLoader
                val mtopCls = Class.forName("mtopsdk.mtop.intf.Mtop", false, cl)
                val m = mtopCls.getMethod("instance", Context::class.java)
                val inst = m.invoke(null, app)
                if (inst != null) {
                    Protocol.State.mtop = inst
                    Log.i(TAG, "dispatch: 自建 Mtop 实例成功（非主进程路径）")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "dispatch: 自建 Mtop 失败: ${t.cause ?: t}")
            }
        }
        var last: Throwable? = null
        for (i in 0 until 20) {
            try {
                return Protocol.ok(MtopBridge.request(app, req[0], req[1], req[2]))
            } catch (t: Throwable) {
                last = t
                val m = t.message ?: ""
                if (m.contains("INIT_ERROR") || m.contains("empty body")) {
                    Log.w(TAG, "attempt $i failed ($m), retrying")
                    try {
                        Thread.sleep(500)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    continue
                }
                break
            }
        }
        Log.e(TAG, "mtop call failed: $last")
        return Protocol.err(last!!.javaClass.simpleName + ": " + last.message)
    }

}
