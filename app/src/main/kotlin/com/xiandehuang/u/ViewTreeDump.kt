package com.xiandehuang.u

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.reflect.Field

/**
 * View 树诊断：把宿主界面结构完整导出成文本，供外部按需查询。
 *
 * 为什么要有这个：
 *   排查「某个按钮/文字在哪一层」这类问题，靠猜坐标、猜类名、猜文字，
 *   每次都要改代码 + 打包 + 装机 + 重启，一轮几分钟还可能猜错。
 *   有了它就能一条命令导出真实结构（类名、坐标、可见性、背景色、文字、
 *   desc、padding、点击态），看完直接改代码。
 *
 * 通道（走闲鱼自己的 provider，免安装）：
 *   xysidecar:tree                     整棵树
 *   xysidecar:tree:bottom              只看屏幕下方（默认 400px）
 *   xysidecar:tree:find=卖闲置         只输出命中子树 + 祖先链
 *   xysidecar:tree:find=xxx;depth=3    限制深度
 *   xysidecar:tree:all=1               包含 GONE 的节点
 *   xysidecar:tree:clicks=1            只看可点击节点
 *   xysidecar:tree:bottom=800          自定义底部高度
 */
object ViewTreeDump {

    /** 导出参数 */
    class Opt {
        @JvmField var find: String? = null      // 按文字/desc 过滤
        @JvmField var depth: Int = 60           // 最大深度
        @JvmField var bottom: Int = -1          // 只看底部 N px；-1 表示全屏
        @JvmField var includeGone: Boolean = false
        @JvmField var clicksOnly: Boolean = false
        @JvmField var withPadding: Boolean = false
        @JvmField var maxLines: Int = 4000      // 防止一次吐几十 MB
    }

    @JvmStatic
    fun parse(rest: String?): Opt {
        val o = Opt()
        if (rest == null) return o
        val s = if (rest.startsWith(":")) rest.substring(1) else rest
        if (s.isEmpty()) return o
        for (raw in s.split(";")) {
            val kv = raw.trim()
            if (kv.isEmpty()) continue
            when (kv) {
                "bottom" -> { o.bottom = 400; continue }
                "all" -> { o.includeGone = true; continue }
                "clicks" -> { o.clicksOnly = true; continue }
                "padding" -> { o.withPadding = true; continue }
            }
            val eq = kv.indexOf('=')
            if (eq <= 0) {
                // 不带等号的裸词当作 find
                o.find = kv
                continue
            }
            val k = kv.substring(0, eq).trim()
            val v = kv.substring(eq + 1).trim()
            when (k) {
                "find" -> o.find = v
                "depth" -> o.depth = safeInt(v, o.depth)
                "bottom" -> o.bottom = safeInt(v, 400)
                "lines" -> o.maxLines = safeInt(v, o.maxLines)
                "all" -> o.includeGone = v == "1" || v.equals("true", true)
                "clicks" -> o.clicksOnly = v == "1" || v.equals("true", true)
            }
        }
        return o
    }

    private fun safeInt(v: String, def: Int): Int =
        try {
            v.toInt()
        } catch (t: Throwable) {
            def
        }

    /** 拿当前可用的窗口 decor（按最近使用排序，跳过不可见的） */
    @JvmStatic
    fun currentDecor(): View? {
        // 1) 先试 DarkForce 跟踪到的窗口（最准，且知道哪个最近用过）
        try {
            val list = DarkForce.decorsNewestFirst()
            list.firstOrNull { v ->
                v.isAttachedToWindow && v.visibility == View.VISIBLE &&
                    v.width > 0 && v.height > 0
            }?.let { return it }
            list.firstOrNull()?.let { return it }
        } catch (ignored: Throwable) {
        }
        // 2) 兜底：直接从 ActivityThread 里捞当前 resumed 的 Activity。
        // 为什么需要：DarkForce 只在 attach 过的窗口里记弱引用，
        // 而 attach 由 onResume 钩子触发 —— 页面切走后弱引用就失效了，
        // 此时查询会拿不到任何窗口（实测 RecoveryActivity 销毁后就查不到）。
        try {
            val at = Class.forName("android.app.ActivityThread")
            val cur = at.getMethod("currentActivityThread").invoke(null) ?: return null
            val acts = at.getDeclaredField("mActivities").apply { isAccessible = true }
                .get(cur) as? Map<*, *> ?: return null
            for (rec in acts.values) {
                try {
                    val act = rec!!.javaClass.getDeclaredField("activity")
                        .apply { isAccessible = true }.get(rec) as? android.app.Activity
                        ?: continue
                    if (act.isFinishing || act.isDestroyed) continue
                    val d = act.window?.decorView ?: continue
                    if (d.isAttachedToWindow && d.width > 0 && d.height > 0) return d
                } catch (ignored: Throwable) {
                }
            }
        } catch (ignored: Throwable) {
        }
        return null
    }

    /** 导出。返回逐行文本（不带前缀，调用方自己拼）。 */
    @JvmStatic
    fun dump(root: View?, o: Opt): List<String> {
        val out = ArrayList<String>()
        if (root == null) {
            out.add("error: 拿不到当前窗口（闲鱼可能不在前台，或 Activity 还没 attach）")
            return out
        }
        try {
            val loc = IntArray(2)
            root.getLocationOnScreen(loc)
            val rootBottom = loc[1] + root.height
            val dm = root.resources.displayMetrics
            out.add(
                "root=${root.javaClass.name} [${loc[0]},${loc[1]} " +
                    "${root.width}x${root.height}] screen=${dm.widthPixels}x${dm.heightPixels}"
            )
            val find = o.find
            if (find != null) {
                // 过滤模式：先找命中节点，再输出它的祖先链 + 子树
                val hit = findByText(root, find, 0)
                if (hit == null) {
                    out.add("未找到含「$find」的节点")
                    return out
                }
                val chain = ArrayList<View>()
                var cur: View? = hit
                while (cur != null) {
                    chain.add(0, cur)
                    val p = cur.parent
                    cur = p as? View
                }
                out.add("命中: ${hit.javaClass.simpleName} —— 以下为祖先链与子树")
                for (i in chain.indices) {
                    emit(chain[i], i, rootBottom, o, out)
                }
                emitSubtree(hit, chain.size, rootBottom, o, out)
                return out
            }
            walk(root, 0, rootBottom, o, out)
        } catch (t: Throwable) {
            out.add("dump 失败: $t")
        }
        return out
    }

    private fun walk(v: View, depth: Int, rootBottom: Int, o: Opt, out: MutableList<String>) {
        if (out.size >= o.maxLines) return
        if (depth > o.depth) return
        try {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val top = loc[1]
            val bottom = top + v.height

            var inScope = true
            if (o.bottom > 0) {
                // 只保留与底部区域有交集的
                inScope = bottom >= rootBottom - o.bottom
            }
            if (o.clicksOnly && !v.isClickable) inScope = false
            if (!o.includeGone && v.visibility == View.GONE) inScope = false

            if (inScope) emit(v, depth, rootBottom, o, out)

            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    walk(v.getChildAt(i), depth + 1, rootBottom, o, out)
                    if (out.size >= o.maxLines) return
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun emitSubtree(
        v: View,
        depth: Int,
        rootBottom: Int,
        o: Opt,
        out: MutableList<String>,
    ) {
        if (out.size >= o.maxLines) return
        if (depth > o.depth) return
        try {
            emit(v, depth, rootBottom, o, out)
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    emitSubtree(v.getChildAt(i), depth + 1, rootBottom, o, out)
                    if (out.size >= o.maxLines) return
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    /** 输出一行：类名 [x,y w x h] 可见性 背景色 文字 desc 点击态 padding */
    private fun emit(v: View, depth: Int, rootBottom: Int, o: Opt, out: MutableList<String>) {
        try {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val sb = StringBuilder()
            repeat(depth) { sb.append("  ") }
            sb.append(v.javaClass.simpleName)
            sb.append(" [").append(loc[0]).append(',').append(loc[1]).append(' ')
                .append(v.width).append('x').append(v.height).append(']')
            val vis = v.visibility
            sb.append(if (vis == 0) " VISIBLE" else if (vis == 4) " INVISIBLE" else " GONE")
            if (v.isClickable) sb.append(" CLICK")

            val bg: Drawable? = v.background
            if (bg != null) {
                sb.append(" bg=").append(bg.javaClass.simpleName)
                sb.append(colorOf(bg))
            }
            // ImageView 的图片内容常常画在 drawable(src) 里而不是 background ——
            // 底部「卖闲置」就是这种情况：两个同尺寸 ImageView 叠着，
            // 黄底画在**没有 background** 的那个上。不输出 src 就永远看不出这层。
            if (v is android.widget.ImageView) {
                sb.append(" src=").append(v.drawable?.javaClass?.simpleName ?: "null")
                if (v.scaleType != null) sb.append(" scale=").append(v.scaleType)
            }
            if (v is TextView) {
                val cs = v.text
                if (cs != null && cs.isNotEmpty()) sb.append(" text='").append(cs).append('\'')
            }
            val cd = v.contentDescription
            if (cd != null && cd.isNotEmpty()) sb.append(" desc='").append(cd).append('\'')
            if (o.withPadding) {
                sb.append(" pad=").append(v.paddingLeft).append(',')
                    .append(v.paddingTop).append(',')
                    .append(v.paddingRight).append(',')
                    .append(v.paddingBottom)
            }
            if (v.alpha < 1f) sb.append(" alpha=").append(v.alpha)
            // getRenderEffect 是 API31+，编译目标低于它，用反射探测
            try {
                if (View::class.java.getMethod("getRenderEffect").invoke(v) != null) {
                    sb.append(" HAS_EFFECT")
                }
            } catch (ignored: Throwable) {
            }
            out.add(sb.toString())
        } catch (ignored: Throwable) {
        }
    }

    /** 尽量把背景色值取出来（ColorDrawable / GradientDrawable 的填充色） */
    private fun colorOf(bg: Drawable): String =
        try {
            when (bg) {
                is ColorDrawable -> String.format("(#%08X)", bg.color)
                is GradientDrawable -> {
                    val f: Field = GradientDrawable::class.java.getDeclaredField("mFillColor")
                    f.isAccessible = true
                    when (val cv = f.get(bg)) {
                        is android.content.res.ColorStateList ->
                            String.format("(#%08X)", cv.defaultColor)
                        is Int -> String.format("(#%08X)", cv)
                        else -> ""
                    }
                }
                else -> ""
            }
        } catch (ignored: Throwable) {
            ""
        }

    /** 按文字或 desc 找第一个命中节点 */
    @JvmStatic
    fun findByText(v: View, needle: String, depth: Int): View? {
        if (depth > 80) return null
        try {
            if (v is TextView) {
                val cs = v.text
                if (cs != null && cs.toString().contains(needle)) return v
            }
            val cd = v.contentDescription
            if (cd != null && cd.toString().contains(needle)) return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val hit = findByText(v.getChildAt(i), needle, depth + 1)
                    if (hit != null) return hit
                }
            }
        } catch (ignored: Throwable) {
        }
        return null
    }
}
