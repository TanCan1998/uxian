package com.xiandehuang.u

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Temporary capture at the SDK completion boundary; never parses or changes a response. */
object TempCapture {
    @Volatile var ready = false
        private set
    private val serial = AtomicLong()
    private val tickets = WeakHashMap<Any, Ticket>()
    private class Ticket(val session: String, val id: String, val origin: String) {
        var requestSeen = false
        var responseSeen = false
    }
    private fun field(value: Any?, name: String): Any? = try {
        if (value == null) null else Reflect.field(value, name)
    } catch (_: Throwable) { null }
    private fun ticket(ctx: Any): Ticket? = synchronized(tickets) {
        val session = TempTraffic.session() ?: return@synchronized null
        val old = tickets[ctx]
        // A request belonging to a closed session must never enter a newly opened capture.
        if (old != null) return@synchronized if (old.session == session) old else null
        val request = field(ctx, "mtopRequest")
        val seq = field(ctx, "seqNo")?.toString().orEmpty()
        val id = android.os.Process.myPid().toString() + ":" +
            (if (seq.isNotEmpty()) seq + ":" else "local:") + serial.incrementAndGet()
        val origin = if (Protocol.State.hostCall(request) != null) "ours" else "host"
        val created = Ticket(session, id, origin)
        tickets[ctx] = created
        created
    }
    fun install(module: XposedModule, cl: ClassLoader) {
        try {
            val context = Class.forName("mtopsdk.framework.domain.MtopContext", false, cl)
            val callback = Class.forName("mtopsdk.framework.filter.after.ExecuteCallbackAfterFilter", false, cl)
                .getDeclaredMethod("doAfter", context)
            val convert = Class.forName("mtopsdk.framework.filter.before.NetworkConvertBeforeFilter", false, cl)
                .getDeclaredMethod("doBefore", context)
            module.hook(convert).setId("xysidecar-temp-request")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val result = chain.proceed()
                        try { request(chain.getArg(0)) } catch (_: Throwable) { }
                        return result
                    }
                })
            module.hook(callback).setId("xysidecar-temp-complete")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        // Snapshot before the host callback can consume or modify bytes/data.
                        try { complete(chain.getArg(0)) } catch (_: Throwable) { }
                        return chain.proceed()
                    }
                })
            ready = true
            Protocol.State.logLine("临时抓包：请求与完成回调已挂载")
        } catch (t: Throwable) {
            ready = false
            Protocol.State.logLine("临时抓包：完成回调不可用，回退数据读取挂点（" + t.javaClass.simpleName + "）")
        }
    }
    private fun request(ctx: Any) {
        if (!ready) return
        val ticket = ticket(ctx) ?: return
        synchronized(ticket) {
            if (ticket.requestSeen) return
            ticket.requestSeen = true
        }
        enqueue(ctx, ticket, false)
    }
    private fun complete(ctx: Any) {
        if (!ready) return
        val ticket = ticket(ctx) ?: return
        request(ctx) // also cover SDK failures before network conversion
        synchronized(ticket) {
            if (ticket.responseSeen) return
            ticket.responseSeen = true
        }
        enqueue(ctx, ticket, true)
    }
    private fun enqueue(ctx: Any, ticket: Ticket, response: Boolean) {
        val req = field(ctx, "mtopRequest")
        val resp = if (response) field(ctx, "mtopResponse") else null
        val net = field(ctx, "networkRequest")
        val api = (field(req, "apiName") ?: field(resp, "api"))?.toString().orEmpty()
        if (api.isEmpty()) return
        val meta = JSONObject().put("requestId", ticket.id)
            .put("event", if (response) "complete" else "request")
        val bytes = if (response) field(resp, "bytedata") as? ByteArray else null
        val fallback = if (response && bytes == null) {
            // Direct fields only: invoking getters can parse, filter or mutate host objects.
            val data = field(resp, "data") as? ByteArray
            if (data != null) {
                meta.put("bodySource", "data")
                data
            } else null
        } else null
        if (response) {
            meta.put("bodySource", if (bytes != null) "bytedata" else if (fallback != null) "data" else "none")
            meta.put("responseCode", field(resp, "responseCode") ?: JSONObject.NULL)
            meta.put("retCode", field(resp, "retCode") ?: JSONObject.NULL)
            meta.put("retMsg", field(resp, "retMsg") ?: JSONObject.NULL)
            val ret = field(resp, "ret")
            if (ret != null) meta.put("ret", JSONObject.wrap(ret))
            meta.put("responsePresent", resp != null)
            meta.put("streamMode", field(resp, "supportStreamJson") == true)
        }
        val headers = if (response) field(resp, "headerFields") else field(net, "headers")
        TrafficStore.recordTemporary(ticket.session, ticket.id, ticket.origin,
            if (response) "RESP" else "REQ", api,
            (field(req, "version") ?: field(resp, "v"))?.toString().orEmpty(),
            field(net, "method")?.toString(),
            if (headers is Map<*, *>) JSONObject.wrap(headers).toString() else null,
            if (response) null else field(req, "data")?.toString(),
            (bytes ?: fallback)?.clone(), meta.toString())
    }
}
