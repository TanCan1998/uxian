package io.github.tancan1998.uxian

object Reflect {
    fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
}
object Protocol {
    object State {
        val ours = java.util.IdentityHashMap<Any, Any>()
        fun hostCall(req: Any?): Any? = ours[req]
        fun logLine(s: String) { }
    }
}
object TempTraffic {
    var active: String? = "session-a"
    fun session(): String? = active
}
object TrafficStore {
    data class Row(val session: String, val id: String, val origin: String, val dir: String,
        val api: String, val body: String?, val bytes: ByteArray?, val metadata: String)
    val rows = ArrayList<Row>()
    fun recordTemporary(session: String, requestId: String, origin: String, dir: String,
        api: String, ver: String, method: String?, headers: String?, body: String?,
        rawBytes: ByteArray?, metadata: String) {
        rows.add(Row(session, requestId, origin, dir, api, body, rawBytes, metadata))
    }
}
class Req(@JvmField val apiName: String = "test.api", @JvmField val version: String = "1.0",
    @JvmField val data: String = "{}")
class Net(@JvmField val method: String = "POST", @JvmField val headers: Map<String,String> = emptyMap())
class Resp(@JvmField val bytedata: ByteArray?, @JvmField val data: ByteArray? = null,
    @JvmField val responseCode: Int = 200, @JvmField val retCode: String = "SUCCESS",
    @JvmField val retMsg: String = "OK", @JvmField val ret: Array<String> = arrayOf(retCode),
    @JvmField val supportStreamJson: Boolean = false,
    @JvmField val headerFields: Map<String,String> = emptyMap()) {
    fun getDataJsonObject(): Any = error("MUST NOT PARSE RESPONSE")
}
