package com.xiandehuang.u

import android.content.Context
import android.graphics.Color

/**
 * 主题常量。单独放顶层 object：Java 侧访问就是普通静态字段，
 * 不受 companion 的 @JvmField 生成规则影响（踩过）。
 */
object ThemeColors {
    /** 品牌黄，两套共用（正常模式的入口图标底色） */
    const val ACCENT = 0xFFFEE512.toInt()

    /** 落在黄底上的文字/图标 */
    const val ON_ACCENT = 0xFF1A1A1A.toInt()

    /**
     * 调试模式的入口图标底色（蓝）与光晕。
     * 面板里的开关开启色也用它 —— 调试态下整个面板换成蓝色强调，
     * 一眼能看出"现在是调试模式"，不会和正常态混淆。
     */
    const val DEBUG_ACCENT = 0xFF5C9DFF.toInt()
    const val DEBUG_GLOW = 0xFF7FB3FF.toInt()

    /** 落在蓝色上的文字/图标 */
    const val ON_DEBUG_ACCENT = 0xFFFFFFFF.toInt()
}

/**
 * 面板配色：浅色 / 深色两套。
 *
 * **不再有独立的主题开关**，两套配色跟着「闲鱼深色（反色）」这一个开关走：
 *   dark_force 开 → 面板用深色配色（和闲鱼屏幕的深色观感一致）
 *   dark_force 关 → 面板用浅色配色
 * 这样面板不会出现「自己是深色、背后闲鱼是浅色」的割裂状态。
 *
 * 注意：面板是独立 Dialog 窗口，宿主的反色着色器**管不到它**，
 * 所以这里必须给出一套实实在在的深色配色，不能指望被一起反色。
 */
internal class Theme private constructor(
    private val dark: Boolean,
    private val debug: Boolean,
) {

    companion object {
        // ---- 浅色 ----
        private const val L_BG = 0xFFF5F5F6.toInt()
        private const val L_CARD = 0xFFFFFFFF.toInt()
        private const val L_DIV = 0xFFF0F0F0.toInt()
        private const val L_TEXT = 0xFF1A1A1A.toInt()
        private const val L_SUB = 0xFF999999.toInt()
        // 说明文字：比 sub 深一档。#999 配白底对比度只有 2.8:1，
        // 用户会以为整行是禁用态（实测被明确提过）。#666 是 5.7:1。
        private const val L_HINT = 0xFF666666.toInt()
        private const val L_OFF = 0xFFB0B0B0.toInt()
        private const val L_ARROW = 0xFFC8C8C8.toInt()
        private const val L_INSET = 0xFF333333.toInt()
        private const val L_TRACK_ON = 0xFFFFE9A8.toInt()
        private const val L_TRACK_OFF = 0xFFDDDDDD.toInt()
        private const val L_THUMB_OFF = 0xFFFFFFFF.toInt()
        private const val L_WARN_BG = 0xFFFFF7E0.toInt()
        private const val L_WARN_TX = 0xFFA66A00.toInt()
        // 可用 / 失效：绿红各一套（浅色底上用 600 档，白底够对比、不刺眼）
        private const val L_OK_TX = 0xFF15A34A.toInt()
        private const val L_OK_BG = 0xFFE7F7EE.toInt()
        private const val L_BAD_TX = 0xFFDC2626.toInt()
        private const val L_BAD_BG = 0xFFFDECEC.toInt()

        // ---- 深色 ----
        private const val D_BG = 0xFF1D1E22.toInt()
        private const val D_CARD = 0xFF27292E.toInt()
        private const val D_DIV = 0xFF34373D.toInt()
        private const val D_TEXT = 0xFFECEDF0.toInt()
        private const val D_SUB = 0xFF8E9199.toInt()
        private const val D_HINT = 0xFFB4B8C0.toInt()
        private const val D_OFF = 0xFF5C6069.toInt()
        private const val D_ARROW = 0xFF4E525B.toInt()
        private const val D_INSET = 0xFFC6C9D0.toInt()
        private const val D_TRACK_ON = 0xFF6A5A12.toInt()
        private const val D_TRACK_OFF = 0xFF3A3D44.toInt()
        private const val D_THUMB_OFF = 0xFF8E9199.toInt()
        private const val D_WARN_BG = 0xFF322912.toInt()
        private const val D_WARN_TX = 0xFFE3B54C.toInt()
        // 深色底用 400 档，避免暗背景上发闷
        private const val D_OK_TX = 0xFF4ADE80.toInt()
        private const val D_OK_BG = 0xFF16301F.toInt()
        private const val D_BAD_TX = 0xFFF87171.toInt()
        private const val D_BAD_TX_BG = 0xFF3A1D1D.toInt()

        // ---- 调试态轨道（比图标色深一档，避免整块太扎眼）----
        private const val DEBUG_TRACK_ON_L = 0xFFD6E6FF.toInt()
        private const val DEBUG_TRACK_ON_D = 0xFF1E3A66.toInt()

        private val LIGHT = Theme(false, false)
        private val DARK = Theme(true, false)
        private val LIGHT_DEBUG = Theme(false, true)
        private val DARK_DEBUG = Theme(true, true)

        /**
         * 面板用哪套配色：直接跟「闲鱼深色（反色）」开关走。
         * 走 liveBool 读文件，保证和宿主进程看到的开关一致（免重启）。
         */
        @JvmStatic
        fun of(c: Context?): Theme {
            var dark = false
            try {
                dark = c != null && Protocol.State.liveBool(c, Prefs.DARK_FORCE)
            } catch (ignored: Throwable) {
            }
            var dbg = false
            try {
                dbg = c != null &&
                    Protocol.State.liveBool(c, Prefs.DEBUG, Prefs.defOf(Prefs.DEBUG))
            } catch (ignored: Throwable) {
            }
            return if (dbg) {
                if (dark) DARK_DEBUG else LIGHT_DEBUG
            } else {
                if (dark) DARK else LIGHT
            }
        }

        @JvmStatic
        fun color(argb: Int): Int = argb

        @JvmStatic
        fun parse(hex: String): Int = Color.parseColor(hex)
    }

    fun dark(): Boolean = dark

    fun bg(): Int = if (dark) D_BG else L_BG

    fun card(): Int = if (dark) D_CARD else L_CARD

    fun divider(): Int = if (dark) D_DIV else L_DIV

    fun text(): Int = if (dark) D_TEXT else L_TEXT

    fun sub(): Int = if (dark) D_SUB else L_SUB

    /** 说明文字（行内副标题）。比 sub 深，避免看起来像禁用 */
    fun hint(): Int = if (dark) D_HINT else L_HINT

    /** 可用（绿）：文字 / 浅底胶囊 */
    fun okText(): Int = if (dark) D_OK_TX else L_OK_TX

    fun okBg(): Int = if (dark) D_OK_BG else L_OK_BG

    /** 失效（红）：文字 / 浅底胶囊 */
    fun badText(): Int = if (dark) D_BAD_TX else L_BAD_TX

    fun badBg(): Int = if (dark) D_BAD_TX_BG else L_BAD_BG

    fun off(): Int = if (dark) D_OFF else L_OFF

    fun arrow(): Int = if (dark) D_ARROW else L_ARROW

    fun inset(): Int = if (dark) D_INSET else L_INSET

    /** 开关打开时的轨道色：调试态用蓝，正常用黄调 */
    fun trackOn(): Int =
        if (debug) {
            if (dark) DEBUG_TRACK_ON_D else DEBUG_TRACK_ON_L
        } else {
            if (dark) D_TRACK_ON else L_TRACK_ON
        }

    /** 开关打开时的圆点色：与入口图标同色（调试蓝 / 品牌黄） */
    fun thumbOn(): Int = if (debug) ThemeColors.DEBUG_ACCENT else ThemeColors.ACCENT

    /** 强调色：调试态蓝、正常态黄。图标、开关、选中态统一用它 */
    fun accent(): Int = if (debug) ThemeColors.DEBUG_ACCENT else ThemeColors.ACCENT

    /** 落在强调色上的文字色 */
    fun onAccent(): Int = if (debug) ThemeColors.ON_DEBUG_ACCENT else ThemeColors.ON_ACCENT

    fun debug(): Boolean = debug

    fun trackOff(): Int = if (dark) D_TRACK_OFF else L_TRACK_OFF

    fun thumbOff(): Int = if (dark) D_THUMB_OFF else L_THUMB_OFF

    fun warnBg(): Int = if (dark) D_WARN_BG else L_WARN_BG

    fun warnText(): Int = if (dark) D_WARN_TX else L_WARN_TX

    /** 对话框系统 Theme：深色用 Material 深色底，否则会塞一张白纸进来 */
    fun dialogTheme(): Int =
        if (dark) {
            android.R.style.Theme_Material_Dialog_Alert
        } else {
            android.R.style.Theme_Material_Light_Dialog_Alert
        }
}
