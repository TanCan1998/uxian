package io.github.tancan1998.uxian

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 用户数据快照：闲鱼币 / 现金红包 / 游戏币 / 等级。
 *
 * ## 为什么是「快照」而不是现查
 *
 * 面板是弹窗，**绝不能在主线程发网络请求**（一次 300-1200ms，用户会以为卡死）。
 * 所以数据由业务链跑的时候顺手写进来（它们本来就有这些数字），面板只读缓存。
 * 缓存带时间戳，面板上标「03:46 更新」，用户知道这是什么时候的数。
 *
 * 存 Prefs 而不是单独数据库：一共 6 个整数 + 1 个时间戳，不值得一张表。
 */
object UserData {

    private const val K_COIN = "data_coin"          // 闲鱼币余额（寻宝地图 userInfo.coinNum）
    private const val K_CASH = "data_cash"          // 现金红包已攒（分）
    private const val K_CASH_MAX = "data_cash_max"  // 现金红包目标
    private const val K_RED = "data_red"            // 待拆红包个数
    private const val K_POINT = "data_point"        // 游戏币
    private const val K_LEVEL = "data_level"        // 等级
    private const val K_EXP = "data_exp"            // 经验
    private const val K_AT = "data_at"              // 更新时间

    private fun touch(c: Context) {
        Prefs.setLong(c, K_AT, System.currentTimeMillis())
    }

    @JvmStatic
    fun coin(c: Context?, v: Int) {
        if (c == null || v <= 0) return
        Prefs.setInt(c, K_COIN, v)
        touch(c)
    }

    @JvmStatic
    fun cash(c: Context?, cur: Int, max: Int, red: Int) {
        if (c == null) return
        if (cur > 0) Prefs.setInt(c, K_CASH, cur)
        if (max > 0) Prefs.setInt(c, K_CASH_MAX, max)
        Prefs.setInt(c, K_RED, red)
        touch(c)
    }

    @JvmStatic
    fun point(c: Context?, v: Int) {
        if (c == null || v <= 0) return
        Prefs.setInt(c, K_POINT, v)
        touch(c)
    }

    @JvmStatic
    fun level(c: Context?, lv: Int, exp: Int) {
        if (c == null) return
        if (lv > 0) Prefs.setInt(c, K_LEVEL, lv)
        if (exp > 0) Prefs.setInt(c, K_EXP, exp)
        touch(c)
    }

    @JvmStatic
    fun coin(c: Context?): Int = if (c == null) 0 else Prefs.getInt(c, K_COIN, 0)

    @JvmStatic
    fun point(c: Context?): Int = if (c == null) 0 else Prefs.getInt(c, K_POINT, 0)

    @JvmStatic
    fun level(c: Context?): Int = if (c == null) 0 else Prefs.getInt(c, K_LEVEL, 0)

    @JvmStatic
    fun exp(c: Context?): Int = if (c == null) 0 else Prefs.getInt(c, K_EXP, 0)

    @JvmStatic
    fun cashText(c: Context?): String {
        if (c == null) return "—"
        val cur = Prefs.getInt(c, K_CASH, 0)
        val max = Prefs.getInt(c, K_CASH_MAX, 0)
        if (max <= 0) return "—"
        val yuan = cur / 100.0
        val maxYuan = max / 100.0
        return String.format(Locale.CHINA, "%.2f/%.2f 元", yuan, maxYuan)
    }

    @JvmStatic
    fun red(c: Context?): Int = if (c == null) 0 else Prefs.getInt(c, K_RED, 0)

    /** 面板一行的副标题：`闲鱼币 346 · 红包 390/500 · 游戏币 450` */
    @JvmStatic
    fun summary(c: Context?): String {
        if (c == null) return ""
        val sb = StringBuilder()
        val coin = coin(c)
        if (coin > 0) sb.append("闲鱼币 ").append(coin)
        val point = point(c)
        if (point > 0) {
            if (sb.isNotEmpty()) sb.append(" · ")
            sb.append("游戏币 ").append(point)
        }
        val cash = cashText(c)
        if (cash != "—") {
            if (sb.isNotEmpty()) sb.append(" · ")
            sb.append("红包 ").append(cash)
        }
        return sb.toString()
    }

    /** 更新时间的人话：`03:46 更新` */
    @JvmStatic
    fun updated(c: Context?): String {
        if (c == null) return ""
        val at = Prefs.getLong(c, K_AT, 0L)
        if (at <= 0L) return "还没取到"
        val f = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        return f.format(Date(at)) + " 更新"
    }
}
