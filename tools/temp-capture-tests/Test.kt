package com.xiandehuang.u

class Ctx(@JvmField val mtopRequest: Req = Req(), @JvmField var mtopResponse: Resp? = null,
    @JvmField val networkRequest: Net? = Net(), @JvmField val seqNo: String = "seq")
fun main() {
    TempCapture.javaClass.getDeclaredField("ready").apply { isAccessible = true }.setBoolean(TempCapture, true)
    val req = TempCapture.javaClass.getDeclaredMethod("request", Any::class.java).apply { isAccessible = true }
    val done = TempCapture.javaClass.getDeclaredMethod("complete", Any::class.java).apply { isAccessible = true }
    fun request(c: Ctx) { req.invoke(TempCapture, c) }
    fun complete(c: Ctx) { done.invoke(TempCapture, c) }
    val bytes = "{\"ret\":[\"SUCCESS\"],\"data\":{}}".toByteArray()
    val c = Ctx(mtopResponse = Resp(bytes))
    Protocol.State.ours[c.mtopRequest] = Any()
    request(c); request(c); complete(c); complete(c)
    check(TrafficStore.rows.size == 2)
    val a = TrafficStore.rows[0]; val b = TrafficStore.rows[1]
    check(a.id == b.id && a.origin == "ours" && b.origin == "ours")
    bytes[0] = 0
    check(b.bytes!![0] == '{'.code.toByte()) // snapshot survives host mutation
    println("PASS callback capture without data getter, dedup, correlation, origin, immutable bytes")
    val old = Ctx(seqNo = "old"); request(old)
    val n = TrafficStore.rows.size
    TempTraffic.active = null; complete(old)
    TempTraffic.active = "session-b"; complete(old)
    check(TrafficStore.rows.size == n)
    println("PASS closed/reopened session rejects late completion")
    val error = Ctx(mtopResponse = Resp(null, responseCode = 0, retCode = "NETWORK_ERROR"), networkRequest = null, seqNo = "error")
    complete(error)
    check(TrafficStore.rows.takeLast(2).map { it.dir } == listOf("REQ", "RESP"))
    check(TrafficStore.rows.last().metadata.contains("NETWORK_ERROR"))
    println("PASS pre-network failure and empty raw body preserve error metadata")
    val fallback = Ctx(mtopResponse = Resp(null, "fallback".toByteArray()), seqNo = "fallback")
    complete(fallback)
    check(String(TrafficStore.rows.last().bytes!!) == "fallback")
    check(TrafficStore.rows.last().metadata.contains("data"))
    println("PASS direct byte-field fallback")
}
