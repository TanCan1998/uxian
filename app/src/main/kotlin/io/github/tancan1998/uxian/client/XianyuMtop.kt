package io.github.tancan1998.uxian.client

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import org.json.JSONObject

/**
 * 本机调用 U闲 的封装。
 *
 * ## 走哪条路
 *
 * **优先 HTTP**（`127.0.0.1:18787`），失败才回退 provider。
 *
 * 实测对比：
 * ```
 * provider 单次 1126ms，N=8 共 9197ms（串行）
 * HTTP     单次   26ms，N=8 共  117ms（~7x 并行）
 * ```
 *
 * HTTP 不可用的情况：闲鱼进程没起、或跨 uid 拿不到 token。
 * 此时回退到 provider（`LocalizationProvider`），代价是 1.1 秒。
 *
 * ## 关于 ContentResolver 参数
 *
 * 保留这个参数是为了 API 兼容（调用方已经传了），HTTP 路径下不实际使用。
 */
object XianyuMtop {

    @JvmField
    val URI: Uri = Uri.parse("content://com.taobao.idlefish.localization.provider")

    private const val SELECTION = "xysidecar:mtop"

    /** 是否强制走 provider（调试用；默认 false） */
    @Volatile
    /**
     * 强制走 provider，不走 HTTP。
     *
     * 默认 true：实测 HTTP 通道在本机（ColorOS）上只能支撑前几次请求，
     * 之后 ServerSocket.accept() 不再返回（MiniHttp-Conn 线程数归零、
     * 连接堆积在 Recv-Q）。原因未明，且同设备支付宝的 8080 也会失效，
     * 判断为设备级网络栈限制，非代码问题。
     * 所以默认用稳定的 provider 路径（~1.1s），HTTP 只在显式开启时尝试。
     */
    @JvmField
    var forceProvider: Boolean = true

    /** 最近一次调用走的路（http / provider），供诊断 */
    @Volatile
    var lastRoute: String = ""
        private set

    /** 原始调用：成功返回 JSON 文本，失败抛 XianyuException */
    @JvmStatic
    @Throws(XianyuException::class)
    fun call(
        resolver: ContentResolver,
        api: String,
        version: String?,
        data: String?,
    ): String {
        // ---- 1) 优先 HTTP ----
        if (!forceProvider) {
            try {
                val r = XianyuHttp.call(api, version, data)
                lastRoute = "http"
                return r
            } catch (t: XianyuException) {
                // HTTP 不可用（进程没起 / 无 token）→ 回退 provider
                lastRoute = "provider(" + (t.message ?: "unknown") + ")"
            } catch (t: Throwable) {
                lastRoute = "provider(" + t.javaClass.simpleName + ")"
            }
        }

        // ---- 2) 回退 provider ----
        return callViaProvider(resolver, api, version, data)
    }

    /**
     * 纯 provider 调用（HTTP 不可用时的回退路径）。
     * 一般不需要直接调用，[call] 会自动选路。
     */
    @JvmStatic
    @Throws(XianyuException::class)
    fun callViaProvider(
        resolver: ContentResolver,
        api: String,
        version: String?,
        data: String?,
    ): String {
        val c: Cursor = try {
            resolver.query(
                URI, null, SELECTION,
                arrayOf(api, version ?: "1.0", data ?: "{}"),
                null,
            )
        } catch (t: Throwable) {
            throw XianyuException(
                XianyuException.Kind.CHANNEL_UNAVAILABLE, null,
                t.javaClass.simpleName + ": " + t.message,
            )
        } ?: throw XianyuException(
            XianyuException.Kind.CHANNEL_UNAVAILABLE, null,
            "provider 返回 null（模块未启用？）",
        )

        try {
            if (!c.moveToFirst()) {
                throw XianyuException(
                    XianyuException.Kind.CHANNEL_UNAVAILABLE, null, "空结果",
                )
            }
            val k = c.getString(0)
            val v = if (c.columnCount > 1) c.getString(1) else null
            if (k == "ok") return v ?: ""
            throw XianyuException.fromRet(null, v ?: "unknown error")
        } finally {
            try {
                c.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun callJson(
        resolver: ContentResolver,
        api: String,
        version: String?,
        data: JSONObject?,
    ): JSONObject = JSONObject(call(resolver, api, version, data?.toString() ?: "{}"))

    /**
     * 带版本自适应的调用。
     *
     * 2026-10-28 实测：mtop 对版本号不敏感 —— 版本不对时**不报错**，直接返回
     * `{}`。首页 nextfresh 传 1.0 返回 {}、传 8.0 返回 690KB；搜索 shade 传
     * 1.0 返回 {}、传 2.0 有数据。所以这里在拿到空对象时按候选版本重试。
     *
     * @param versions 候选版本，按优先级排列
     */
    @JvmStatic
    @Throws(Exception::class)
    fun callAuto(
        resolver: ContentResolver,
        api: String,
        versions: Array<String>,
        data: JSONObject?,
    ): JSONObject {
        var last = JSONObject()
        var lastErr: Exception? = null
        for (v in versions) {
            try {
                val r = callJson(resolver, api, v, data)
                if (r.length() > 0) return r
                last = r
            } catch (e: Exception) {
                lastErr = e
            }
        }
        if (lastErr != null && last.length() == 0) throw lastErr
        return last
    }

    // ---------- 常用接口封装 ----------

    /** 会话列表（真实接口，版本 3.0） */
    @JvmStatic
    @Throws(Exception::class)
    fun sessionSync(r: ContentResolver, fetchNum: Int): JSONObject {
        val d = JSONObject()
        d.put("fetchNum", fetchNum)
        d.put("sessionTypes", "[]")
        return callJson(r, "mtop.taobao.idlemessage.session.sync", "3.0", d)
    }

    /** 会话消息同步 */
    @JvmStatic
    @Throws(Exception::class)
    fun messageSync(r: ContentResolver, sessionId: String?, fetchNum: Int): JSONObject {
        val d = JSONObject()
        d.put("sessionId", sessionId)
        d.put("fetchNum", fetchNum)
        return callJson(r, "mtop.taobao.idlemessage.message.sync", "1.0", d)
    }

    /**
     * 商品详情。
     *
     * 真实接口是 awesome.detail.unit（不是 idleitem.detail），
     * 参数从宿主真实请求抓取：缺 apiName / flowVersion / hitNativeDetail 等字段
     * 服务端会直接返回空对象。
     */
    @JvmStatic
    @Throws(Exception::class)
    fun itemDetail(r: ContentResolver, itemId: String?): JSONObject {
        val d = JSONObject()
        d.put("itemId", itemId)
        d.put(
            "apiName",
            "com.taobao.idlefish.detail.business.model.protocol.DetailRequest",
        )
        d.put("flowVersion", "8.0")
        d.put("hitNativeDetail", true)
        d.put("isBundleGroup", false)
        d.put("isOld", false)
        d.put("needSimpleDetail", false)
        d.put("originJson", false)
        d.put("extraParam", "{\"transmitSignal\":\"1\"}")
        return callJson(r, "mtop.taobao.idle.awesome.detail.unit", "1.0", d)
    }

    /**
     * 搜索。
     *
     * 2026-10-28 实测纠正：真实接口是 mtop.taobao.idlemtopsearch.search（1.0），
     * 不是旧代码里的 mtop.taobao.idlesearch.search —— 后者宿主确实也会发，
     * 但只返回 {}。搜索页的真实检索请求由宿主在进入搜索 tab 后发出，
     * 这里按宿主同款字段拼装；仍可能因缺少个性化字段返回空。
     */
    @JvmStatic
    @Throws(Exception::class)
    fun search(r: ContentResolver, keyword: String?, page: Int, size: Int): JSONObject {
        val d = JSONObject()
        d.put("keyword", keyword)
        d.put("pageNumber", page)
        d.put("pageSize", size.toString())
        d.put("bizCode", "main")
        d.put("searchReqFromPage", "xyHome")
        d.put("useFrequentViewLongTime", false)
        d.put("pinSouIndex", 0)
        return callAuto(
            r, "mtop.taobao.idlemtopsearch.search",
            arrayOf("1.0", "2.0"), d,
        )
    }

    /** 首页信息流（真实版本 8.0；传 1.0 会静默返回 {}，2026-10-28 实测） */
    @JvmStatic
    @Throws(Exception::class)
    fun homeFeed(r: ContentResolver, page: Int): JSONObject {
        val d = JSONObject()
        d.put("apiName", "mtop.taobao.idlehome.home.nextfresh")
        d.put("apiVersioin", "8.0")
        d.put("city", "杭州")
        d.put("pageNumber", page)
        d.put("originJson", false)
        d.put("trackName", "Item")
        d.put("lastResponseCount", 0)
        d.put("isWidePage", false)
        d.put("needCustomsUrlParams", true)
        d.put("luxuryCustomData", "{}")
        d.put("data", "{}")
        return callAuto(
            r, "mtop.taobao.idlehome.home.nextfresh",
            arrayOf("8.0", "2.0", "1.0"), d,
        )
    }
}
