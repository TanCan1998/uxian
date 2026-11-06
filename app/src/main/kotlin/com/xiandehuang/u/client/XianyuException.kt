package com.xiandehuang.u.client

/**
 * 归一后的调用错误。
 *
 * 闲鱼的失败有两种形态，调用方不该各自解析字符串：
 *   1. mtop 业务失败  → ret 形如 "FAIL_SYS_SESSION_EXPIRED::Session过期"
 *   2. 通道本身失败    → 模块未启用 / 进程拉不起来 / 超时
 */
class XianyuException(
    val kind: Kind,
    /** 原始 ret（可能为空） */
    val ret: String?,
    val detail: String?,
) : Exception(buildMessage(kind, ret, detail)) {

    enum class Kind {
        /** 登录态失效，需要用户重新登录闲鱼 */
        SESSION_EXPIRED,

        /** 接口名或版本不对（服务端没有这个 api） */
        API_NOT_FOUND,

        /** 触发风控 / 需要验证 */
        RISK_CONTROL,

        /** 参数错误 */
        BAD_PARAMS,

        /** 通道不可用：模块未启用、进程拉不起来 */
        CHANNEL_UNAVAILABLE,

        /** 请求超时 */
        TIMEOUT,

        /** 其它业务失败 */
        FAILED,

        /** 无法归类 */
        UNKNOWN,
    }

    /** 是否值得让用户重新登录后再试 */
    fun needsLogin(): Boolean = kind == Kind.SESSION_EXPIRED || kind == Kind.RISK_CONTROL

    /** 是否值得原样重试（通道抖动） */
    fun retryable(): Boolean = kind == Kind.TIMEOUT || kind == Kind.CHANNEL_UNAVAILABLE

    companion object {
        private fun buildMessage(k: Kind, ret: String?, detail: String?): String {
            val head = when (k) {
                Kind.SESSION_EXPIRED -> "登录态已失效，请在闲鱼中重新登录"
                Kind.API_NOT_FOUND -> "接口不存在（api 名或版本不对）"
                Kind.RISK_CONTROL -> "触发风控校验，请先在闲鱼里正常操作一次"
                Kind.BAD_PARAMS -> "请求参数不正确"
                Kind.CHANNEL_UNAVAILABLE -> "通道不可用（模块未启用或闲鱼进程拉不起来）"
                Kind.TIMEOUT -> "请求超时"
                Kind.FAILED -> "请求失败"
                Kind.UNKNOWN -> "未知错误"
            }
            val sb = StringBuilder(head)
            if (!ret.isNullOrEmpty()) sb.append("｜").append(ret)
            if (!detail.isNullOrEmpty()) sb.append("｜").append(detail)
            return sb.toString()
        }

        /** 把 mtop 的 ret / 异常文本归类 */
        @JvmStatic
        fun fromRet(ret: String?, detail: String?): XianyuException {
            val r = ret ?: ""
            val upper = r.uppercase()
            val k = when {
                upper.contains("SESSION_EXPIRED") || upper.contains("SESSIONTIMEOUT") ||
                    upper.contains("TOKEN_EXOIRED") || upper.contains("TOKEN_EXPIRED") ||
                    upper.contains("NEED_LOGIN") || upper.contains("NOT_LOGIN") ->
                    Kind.SESSION_EXPIRED

                upper.contains("API_NOT_FOUND") -> Kind.API_NOT_FOUND

                upper.contains("RGV587") || upper.contains("PUNISH") ||
                    upper.contains("RISK") || upper.contains("ILLEGAL_ACCESS") ||
                    upper.contains("FAIL_SYS_USER_VALIDATE") -> Kind.RISK_CONTROL

                upper.contains("PARAM") || upper.contains("ILLEGAL_ARGUMENT") -> Kind.BAD_PARAMS

                upper.contains("TIMEOUT") || upper.contains("NETWORK") -> Kind.TIMEOUT

                r.isNotEmpty() -> Kind.FAILED

                else -> Kind.UNKNOWN
            }
            return XianyuException(k, r, detail)
        }
    }
}
