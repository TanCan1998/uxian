package io.github.tancan1998.uxian

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 在宿主界面里找东西：锚点行、同列的兄弟、带指定文字的子控件。
 *
 * 这一组方法都是纯查询（不改界面），可以单独调、单独验，不用碰注入流程。
 *
 * 关键背景：「我的」页顶部那排图标是宿主自己动态生成的，
 * 没有 id、没有稳定的类名，只能靠"找文字 → 往上找行 → 按同列样式仿造"。
 */
internal object ViewFinder {

    @JvmStatic
    fun dpf(c: Context, v: Float): Int =
        Math.round(v * c.resources.displayMetrics.density)

    @JvmStatic
    fun findTagged(root: View, tag: Any?): View? {
        if (root.tag === tag) return root
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) {
            val r = findTagged(root.getChildAt(i), tag)
            if (r != null) return r
        }
        return null
    }

    @JvmStatic
    fun hasBigChild(row: ViewGroup, threshold: Int): Boolean {
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i)
            if (c.width >= threshold && c.height >= threshold) return true
        }
        return false
    }

    @JvmStatic
    fun lpWidth(v: View?, def: Int): Int {
        val lp = v?.layoutParams ?: return def
        return if (lp.width > 0) lp.width else def
    }

    @JvmStatic
    fun lpHeight(v: View?, def: Int): Int {
        val lp = v?.layoutParams ?: return def
        return if (lp.height > 0) lp.height else def
    }

    @JvmStatic
    fun lpTopMargin(v: View?, def: Int): Int {
        val lp = v?.layoutParams
        return if (lp is LinearLayout.LayoutParams) lp.topMargin else def
    }

    /** 找「和某个文字同一列」的容器：宿主那排图标的每一格 */
    @JvmStatic
    fun findColumnWithText(row: ViewGroup, text: String): View? {
        for (i in 0 until row.childCount) {
            val col = row.getChildAt(i)
            if (col !is ViewGroup) continue
            for (j in 0 until col.childCount) {
                val c = col.getChildAt(j)
                if (c is TextView && text.contentEquals(c.text)) return col
                val cd = c.contentDescription
                if (cd != null && text.contentEquals(cd)) return col
            }
        }
        return null
    }

    /** 按文字或无障碍描述找锚点 */
    @JvmStatic
    fun findAnchor(root: View, texts: Array<String>): View? {
        if (root is TextView) {
            val cs = root.text
            if (cs != null) {
                val t = cs.toString()
                for (a in texts) if (t.contains(a)) return root
            }
        }
        val cd = root.contentDescription
        if (cd != null) {
            val t = cd.toString()
            for (a in texts) if (t.contains(a)) return root
        }
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) {
            val f = findAnchor(root.getChildAt(i), texts)
            if (f != null) return f
        }
        return null
    }

    @JvmStatic
    fun findExactText(root: View, text: String): View? {
        if (root is TextView) {
            val cs = root.text
            if (cs != null && text.contentEquals(cs)) return root
        }
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) {
            val f = findExactText(root.getChildAt(i), text)
            if (f != null) return f
        }
        return null
    }
}
