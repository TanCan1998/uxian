package com.xiandehuang.u

import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.json.JSONArray
import org.json.JSONObject

/**
 * 金刚位：排序 + 整块隐藏。
 *
 * - 排序：重排 widgets 里的 channelDOList，交给宿主自己的解析器（只改顺序）。
 * - 隐藏：不动数据，只把控件设成 GONE。逐项隐藏会触发宿主分页重复铺图标，
 *   所以这里只做整块隐藏。
 */
object KingkongHook {
    private const val NAME = "com.taobao.idlefish.home.power.widget.DXFishHomeKingkongViewPagerWidgetNode"
    private const val ATTR_WIDGETS = 0x3d0ef583b40ffc3eL // mt 实测：widgets

    /** 节点 -> onCreateView 创建的控件实例（整块隐藏要用） */
    private val views = java.util.WeakHashMap<Any, View>()

    /** 隐藏前的高度，恢复时用 */
    private val heights = java.util.WeakHashMap<View, Int>()

    /** 外层包装的原始高度 */
    private val parents = java.util.WeakHashMap<View, Int>()

    fun install(module: XposedModule, cl: ClassLoader) {
        installData(module, cl)
        installView(module, cl)
    }

    private fun installData(module: XposedModule, cl: ClassLoader) {
        try {
            val cls = Class.forName(NAME, false, cl)
            val arrayClass = Class.forName("com.alibaba.fastjson.JSONArray", false, cl)
            val jsonClass = Class.forName("com.alibaba.fastjson.JSON", false, cl)
            val parse = jsonClass.getMethod("parseArray", String::class.java)
            val method = cls.getDeclaredMethod("onSetListAttribute", java.lang.Long.TYPE, arrayClass)
            HookKit.install(module, "kingkong", "onSetListAttribute", method, object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    var replacement: Any? = null
                    try {
                        if (chain.getArg(0) == ATTR_WIDGETS) {
                            HookState.entry("kingkong")
                            val ctx = Protocol.State.app
                            val raw = chain.getArg(1)
                            if (ctx != null && raw != null) {
                                val widgets = JSONArray(raw.toString())
                                val root = JSONObject().put(
                                    "homeTopList",
                                    JSONArray().put(JSONObject().put("widgets", widgets)),
                                )
                                val before = widgets.toString()
                                KingkongOrder.apply(ctx, root)
                                val after = widgets.toString()
                                if (before != after) {
                                    replacement = parse.invoke(null, after)
                                    HookState.hit("kingkong")
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        Protocol.State.logLine("金刚位排序未应用：" + (t.message ?: t.javaClass.simpleName))
                    }
                    if (replacement == null) return chain.proceed()
                    return chain.proceed(arrayOf<Any?>(chain.getArg(0), replacement))
                }
            })
        } catch (t: Throwable) {
            HookState.failed("kingkong", "onSetListAttribute", t.message ?: "数据绑定点不可用")
        }
    }

    /** 缓存控件实例 + 按开关整块隐藏 */
    private fun installView(module: XposedModule, cl: ClassLoader) {
        try {
            val cls = Class.forName(NAME, false, cl)
            val ctxCls = Class.forName("android.content.Context", false, cl)
            val m = Reflect.methodExact(cls, "onCreateView", ctxCls)
            HookKit.install(module, "kingkong", "onCreateView", m, object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    try {
                        val node = chain.getThisObject()
                        if (node != null && r is View) {
                            synchronized(views) { views[node] = r }
                            applyVisibility(r)
                        }
                    } catch (_: Throwable) {
                    }
                    return r
                }
            })
        } catch (t: Throwable) {
            HookState.failed("kingkong", "onCreateView", t.message ?: "创建视图不可用")
        }
        // 高度是 onMeasure 用 itemCount 算出来的：隐藏时把它置 0，测量自然归零，
        // 外层列表项跟着收起，不会留下空白（改布局参数会被宿主重新布局覆盖）。
        try {
            val cls = Class.forName(NAME, false, cl)
            val m = Reflect.methodExact(cls, "onMeasure", java.lang.Integer.TYPE, java.lang.Integer.TYPE)
            HookKit.install(module, "kingkong", "onMeasure", m, object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    try {
                        val node = chain.getThisObject() ?: return chain.proceed()
                        val ctx = Protocol.State.app
                        if (ctx != null) {
                            val hide = Prefs.get(ctx, Prefs.KINGKONG_HIDE, false)
                            val count = if (hide) 0 else KingkongOrder.items(ctx).size
                            val f = node.javaClass.getDeclaredField("itemCount")
                            f.isAccessible = true
                            val old = f.getInt(node)
                            if (old != count) {
                                f.setInt(node, count)
                                if (hide && old > 0) HookState.hit("kingkong")
                            }
                        }
                    } catch (_: Throwable) {
                    }
                    return chain.proceed()
                }
            })
        } catch (t: Throwable) {
            HookState.failed("kingkong", "onMeasure", t.message ?: "测量点不可用")
        }
        // 宿主渲染后会把可见性/高度重设一遍，这里再收敛一次，保证隐藏后不留空白
        try {
            val cls = Class.forName(NAME, false, cl)
            val ctxCls = Class.forName("android.content.Context", false, cl)
            val viewCls = Class.forName("android.view.View", false, cl)
            val m = Reflect.methodExact(cls, "onRenderView", ctxCls, viewCls)
            HookKit.install(module, "kingkong", "onRenderView", m, object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    try {
                        val node = chain.getThisObject()
                        var v: View? = null
                        synchronized(views) { v = views[node] }
                        if (v == null && chain.getArg(1) is View) v = chain.getArg(1) as View
                        if (v != null) applyVisibility(v)
                    } catch (_: Throwable) {
                    }
                    return r
                }
            })
        } catch (t: Throwable) {
            HookState.failed("kingkong", "onRenderView", t.message ?: "渲染点不可用")
        }
    }

    /**
     * 整块隐藏：控件本身隐藏，并把它所在的**列表项**（RecyclerView 的 item）收起。
     * 只收控件会在原处留一块空白 —— 那块空白是列表项的高度。
     */
    private fun applyVisibility(v: View) {
        try {
            val ctx = Protocol.State.app ?: return
            val hide = Prefs.get(ctx, Prefs.KINGKONG_HIDE, false)
            val item = itemHost(v)
            if (hide) {
                if (v.visibility != View.GONE) v.visibility = View.GONE
                if (item != null) {
                    if (!parents.containsKey(item)) parents[item] = item.layoutParams?.height ?: -2
                    val lp = item.layoutParams
                    if (lp != null && lp.height != 0) {
                        lp.height = 0
                        item.layoutParams = lp
                    }
                    if (item.visibility != View.GONE) item.visibility = View.GONE
                    item.requestLayout()
                }
                HookState.hit("kingkong")
            } else {
                if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
                if (item != null) {
                    val saved = parents.remove(item)
                    val lp = item.layoutParams
                    if (lp != null && lp.height == 0) {
                        lp.height = saved ?: android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        item.layoutParams = lp
                    }
                    if (item.visibility != View.VISIBLE) item.visibility = View.VISIBLE
                    item.requestLayout()
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** 找到承载金刚位的列表项（父容器是 RecyclerView / Pager 的那一层） */
    private fun itemHost(v: View): View? {
        var cur: View? = v
        var depth = 0
        while (cur != null && depth < 8) {
            val p = cur.parent as? View ?: return null
            val name = p.javaClass.name
            if (name.indexOf("RecyclerView") >= 0 || name.indexOf("ViewPager") >= 0) return cur
            cur = p
            depth++
        }
        return null
    }
}
