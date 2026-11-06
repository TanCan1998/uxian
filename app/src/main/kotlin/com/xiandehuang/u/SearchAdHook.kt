package com.xiandehuang.u

import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/** 拦 MtopPlugin.sendMtop 交给 Flutter 的搜索结果，缓存与普通响应不因此多解析。 */
object SearchAdHook {
    private const val PLUGIN = "com.taobao.idlefish.mtop.mtopplugin.MtopPlugin"
    private const val RESULT = "io.flutter.plugin.common.MethodChannel\$Result"

    fun install(module: XposedModule, cl: ClassLoader) {
        try {
            val cls = Class.forName(PLUGIN, false, cl)
            val result = Class.forName(RESULT, false, cl)
            val map = Class.forName("java.util.Map", false, cl)
            val method = cls.getMethod("sendMtop", result, String::class.java, String::class.java, map,
                java.lang.Boolean.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE,
                String::class.java, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE,
                java.lang.Integer.TYPE, java.lang.Integer.TYPE)
            HookKit.install(module, "searchad", "sendMtop", method, object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    HookState.entry("searchad")
                    val original = chain.getArg(0)
                    val api = chain.getArg(1)
                    val enabled = try {
                        val ctx: Context? = Protocol.State.app
                        ctx != null && Prefs.get(ctx, Prefs.FILTER_SEARCH_AD, false)
                    } catch (_: Throwable) { false }
                    note(api as? String, enabled, chain.getArg(3))
                    if (!enabled || api != SearchAdFilter.API || original == null) return chain.proceed()
                    val apiName = api as String
                    val proxy = java.lang.reflect.Proxy.newProxyInstance(
                        result.classLoader, arrayOf(result),
                        Invocation(original, apiName),
                    )
                    return chain.proceed(arrayOf<Any?>(proxy, chain.getArg(1), chain.getArg(2),
                        chain.getArg(3), chain.getArg(4), chain.getArg(5), chain.getArg(6),
                        chain.getArg(7), chain.getArg(8), chain.getArg(9), chain.getArg(10),
                        chain.getArg(11)))
                }
            })
        } catch (t: Throwable) {
            HookState.failed("searchad", "sendMtop", t.message ?: "桥接点不可用")
        }
        // 另一条 Flutter 通道：send(ApiProtocol, Result)。搜索若走这条，
        // sendMtop 会一直 entry>0 而 hit=0（本轮就遇到的症状）。
        try {
            val cls = Class.forName(PLUGIN, false, cl)
            val result = Class.forName(RESULT, false, cl)
            val protocol = Class.forName("com.taobao.idlefish.protocol.net.api.ApiProtocol", false, cl)
            val method = cls.getMethod("send", protocol, result)
            HookKit.install(module, "searchad", "send", method, object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    HookState.entry("searchad")
                    val api = try {
                        Reflect.call(chain.getArg(0)!!, "getApiName") as? String
                    } catch (_: Throwable) { null }
                    val ctx: Context? = Protocol.State.app
                    val enabled = ctx != null && Prefs.get(ctx, Prefs.FILTER_SEARCH_AD, false)
                    note(api, enabled, null)
                    if (!enabled || api != SearchAdFilter.API) return chain.proceed()
                    val proxy = java.lang.reflect.Proxy.newProxyInstance(
                        result.classLoader, arrayOf(result), Invocation(chain.getArg(1)!!, api),
                    )
                    return chain.proceed(arrayOf<Any?>(chain.getArg(0), proxy))
                }
            })
        } catch (t: Throwable) {
            HookState.failed("searchad", "send", t.message ?: "协议通道不可用")
        }
        // 最底层：ResponseParameter.getData()。无论 Flutter 走哪个插件，只要
        // 结果经协议层回来都会读这里；原地过滤 fastjson 对象，格式不变。
        try {
            val cls = Class.forName("com.taobao.idlefish.protocol.net.ResponseParameter", false, cl)
            val method = cls.getMethod("getData")
            HookKit.install(module, "searchad", "getData", method, object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val value = chain.proceed() ?: return null
                    val api = try {
                        (Reflect.call(chain.getThisObject()!!, "getApi") as? String) ?: return value
                    } catch (_: Throwable) { return value }
                    if (api != SearchAdFilter.API && api.indexOf("idlemtopsearch.search") < 0) return value
                    HookState.entry("searchad")
                    val ctx: Context? = Protocol.State.app
                    val enabled = ctx != null && Prefs.get(ctx, Prefs.FILTER_SEARCH_AD, false)
                    try {
                        val r = if (!enabled) shapeOnly(value)
                            else if (value is Map<*, *>) SearchAdFilter.filterInPlace(value)
                            else filterBean(value)
                        noteResult(api, r.before, r.removed, value)
                        if (r.removed > 0) {
                            HookState.hit("searchad")
                            Protocol.State.logLine("搜索结果广告 $api 移除 ${r.removed}/${r.before}")
                        }
                    } catch (t: Throwable) {
                        Protocol.State.logLine("搜索过滤跳过：" + (t.message ?: t.javaClass.simpleName))
                    }
                    return value
                }
            })
        } catch (t: Throwable) {
            HookState.failed("searchad", "getData", t.message ?: "协议数据不可用")
        }
    }

    /** 只记录结构，不改数据（开关关闭时）。 */
    private fun shapeOnly(v: Any?): SearchAdFilter.Result {
        val list = resultListOf(v) ?: return SearchAdFilter.Result(v, 0, 0, false)
        return SearchAdFilter.Result(v, list.size, 0, true)
    }

    /** SearchResultResp 等 Bean：resultList 字段是可变 List，按广告标记原地删除。 */
    private fun filterBean(v: Any?): SearchAdFilter.Result {
        val list = resultListOf(v) ?: return SearchAdFilter.Result(v, 0, 0, false)
        val before = list.size
        if (list !is MutableList<*>) return SearchAdFilter.Result(v, before, 0, true)
        var i = 0
        var removed = 0
        while (i < list.size) {
            val e = list[i]
            val ad = try { SearchAdFilter.isAdElement(e) } catch (_: Throwable) { false }
            if (ad) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    (list as MutableList<Any?>).removeAt(i)
                    removed++
                    continue
                } catch (_: Throwable) { }
            }
            i++
        }
        return SearchAdFilter.Result(v, before, removed, true)
    }

    private fun resultListOf(v: Any?): List<*>? {
        if (v is Map<*, *>) {
            val l = v["resultList"] ?: (v["data"] as? Map<*, *>)?.get("resultList")
            if (l is List<*>) return l
        }
        val l = try { Reflect.field(v!!, "resultList") } catch (_: Throwable) { null }
        return l as? List<*>
    }

    // ---------------------------------------------------------------- 诊断

    private fun short(api: String?): String =
        if (api == null) "-" else api.replace("mtop.taobao.", "").replace("idlemtopsearch.", "is.")

    private val calls = ArrayList<String>()
    private val apis = LinkedHashSet<String>()
    private var matched = 0

    private fun note(api: String?, enabled: Boolean, params: Any?) {
        try {
            synchronized(calls) {
                if (apis.size < 12 && api != null) apis.add(api)
                var keys = ""
                if (params is Map<*, *>) keys = params.keys.joinToString(",").take(180)
                calls.add((api ?: "-") + " on=" + (if (enabled) 1 else 0) + " keys=" + keys)
                if (calls.size > 12) calls.removeAt(0)
            }
        } catch (_: Throwable) {
        }
    }

    private fun noteResult(api: String?, before: Int, removed: Int, payload: Any?) {
        try {
            synchronized(calls) {
                var shape = payload?.javaClass?.simpleName ?: "null"
                if (payload is Map<*, *>) shape = "Map" + payload.keys.toList().toString().take(150)
                calls.add("result " + short(api) + " " + shape + " list=" + before + " removed=" + removed)
                if (calls.size > 12) calls.removeAt(0)
            }
        } catch (_: Throwable) {
        }
    }

    fun cursor(): android.database.Cursor {
        val c = android.database.MatrixCursor(arrayOf("key", "value"))
        c.addRow(arrayOf<Any>("apis", apis.joinToString(" | ")))
        c.addRow(arrayOf<Any>("matched", matched.toString()))
        synchronized(calls) {
            for (i in calls.indices) c.addRow(arrayOf<Any>("log" + (i + 1), calls[i]))
        }
        return c
    }

    private class Invocation(val target: Any, val api: String) : java.lang.reflect.InvocationHandler {
        override fun invoke(proxy: Any?, method: java.lang.reflect.Method, args: Array<Any?>?): Any? {
            if (method.name == "success" && args != null && args.size == 1) {
                try {
                    val r = SearchAdFilter.filter(args[0])
                    noteResult(api, r.before, r.removed, args[0])
                    if (r.recognized) matched++
                    if (r.removed > 0) {
                        HookState.hit("searchad")
                        Protocol.State.logLine("搜索结果广告 $api 移除 ${r.removed}/${r.before}")
                        return method.invoke(target, *arrayOf<Any?>(r.value))
                    }
                } catch (t: Throwable) {
                    Protocol.State.logLine("搜索过滤跳过：" + (t.message ?: t.javaClass.simpleName))
                }
            }
            return method.invoke(target, *(args ?: emptyArray<Any?>()))
        }
    }
}
