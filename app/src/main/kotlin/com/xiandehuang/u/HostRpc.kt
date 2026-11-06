package com.xiandehuang.u

import android.content.Context
import android.os.Looper
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 闲鱼 H5 / Weex 共用的宿主 RPC 入口。
 *
 * WVIdleFishApiPlugin.callMtop -> WeexMtopUtil.request -> PApiContext.send。
 * 三参数重载明确将 WVCallBackContext 置 null，不创建页面或 WebView。
 * 只复用宿主网络服务；业务 asac 必须由真实业务初始化响应提供。
 */
object HostRpc {
    private const val BRIDGE = "com.taobao.idlefish.card.weexcard.module.WeexMtopUtil"
    private const val CALLBACK = BRIDGE + "\$Callback"

    @JvmStatic
    fun request(ctx: Context, api: String, version: String, data: String): String {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException("宿主 RPC 必须在后台线程等待回调")
        }
        if (api.isEmpty() || version.isEmpty()) throw IllegalArgumentException("api/version 不能为空")
        if (!Protocol.State.hostRpcReady) {
            throw IllegalStateException("ret=HOST_RPC_UNAVAILABLE msg=宿主无界面调用边界未就绪")
        }
        val loader = Protocol.State.classLoader ?: throw IllegalStateException("classloader not ready")
        val bridge = Class.forName(BRIDGE, false, loader)
        val callbackType = Class.forName(CALLBACK, false, loader)
        val jsonType = Class.forName("com.alibaba.fastjson.JSONObject", false, loader)
        val json = Class.forName("com.alibaba.fastjson.JSON", false, loader)
        val envelope = JSONObject().put("api", api).put("v", version)
            .put("accountSite", "xianyu").put("needLogin", false).put("data", JSONObject(data))
        // true 是 accountSite -> MtopInstanceId 映射开关，originJson 由 parseParams 独立开启。
        // needLogin 仅影响协议鉴权，禁止登录 UI 由精确 MtopBusiness hook 保证。
        val request = json.getMethod("parseObject", String::class.java)
            .invoke(null, envelope.toString())
        val done = CountDownLatch(1)
        val response = AtomicReference<Reply?>(null)
        fun complete(reply: Reply) {
            // 完整结果一次 CAS 发布；超时与回调只能一个获胜，不能先 completed 后填 data。
            if (response.compareAndSet(null, reply)) done.countDown()
        }
        val call = Protocol.State.HostCall { complete(Reply(false, "", it)) }
        val callback = Proxy.newProxyInstance(loader, arrayOf(callbackType)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "U闲宿主RPC回调"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "onSuccess", "onFail" -> {
                    try {
                        complete(Reply(method.name == "onSuccess", args?.getOrNull(0)?.toString() ?: ""))
                    } catch (_: Throwable) {
                        complete(Reply(false, "", "HOST_RPC_BAD_RESPONSE"))
                    }
                    null
                }
                else -> null
            }
        }
        Protocol.State.markHostCall(request, call)
        try {
            bridge.getMethod("request", jsonType, callbackType, Boolean::class.javaPrimitiveType)
                .invoke(null, request, callback, true)
            if (!done.await(25, TimeUnit.SECONDS)) {
                complete(Reply(false, "", "HOST_RPC_TIMEOUT"))
            }
        } catch (_: InvocationTargetException) {
            complete(Reply(false, "", "HOST_RPC_DISPATCH_FAILED"))
        } catch (_: InterruptedException) {
            complete(Reply(false, "", "HOST_RPC_INTERRUPTED"))
            Thread.currentThread().interrupt()
        } finally {
            // 不删除 identity：已排队的对象须仍可识别为已关闭，弱引用随宿主对象回收。
            call.closed = true
        }
        val reply = response.get() ?: Reply(false, "", "HOST_RPC_EMPTY")
        if (reply.error.isNotEmpty()) throw failure(reply.error)
        return decode(reply.success, reply.raw)
    }

    private class Reply(val success: Boolean, val raw: String, val error: String = "")

    private fun failure(code: String): IllegalStateException =
        IllegalStateException("ret=$code msg=" + when (code) {
            "HOST_RPC_TIMEOUT" -> "宿主RPC回调超时，执行结果未知，请先查询状态"
            "HOST_RPC_INTERRUPTED" -> "宿主RPC等待已取消，执行结果未知"
            "HOST_RPC_EMPTY" -> "宿主返回空响应"
            "HOST_RPC_BAD_RESPONSE" -> "宿主返回无效响应"
            else -> "宿主RPC调用失败"
        })

    private fun text(value: Any?): String =
        if (value == null || value == JSONObject.NULL) "" else value.toString().trim()

    /** 仅返回机器错误码，不把 result/data 中的 token、会话或服务端调试信息带进异常。 */
    private fun safeCode(value: String): String {
        val code = value.substringBefore("::").trim()
        if (code.isEmpty() || code.length > 96) return "HOST_RPC_FAILED"
        for (ch in code) {
            if (ch !in 'A'..'Z' && ch !in '0'..'9' && ch != '_') return "HOST_RPC_FAILED"
        }
        return code
    }

    private fun successCode(value: String): Boolean =
        value == "SUCCESS" || value.startsWith("SUCCESS::") || value == "0" || value == "200"

    private fun decode(success: Boolean, raw: String): String {
        if (raw.isBlank()) throw failure("HOST_RPC_EMPTY")
        val result = try { JSONObject(raw) } catch (_: Throwable) { throw failure("HOST_RPC_BAD_RESPONSE") }
        val ret = result.opt("ret")
        var failed = ""
        if (ret is org.json.JSONArray) {
            for (i in 0 until ret.length()) {
                val item = text(ret.opt(i))
                if (item.isNotEmpty() && !successCode(item)) { failed = item; break }
            }
        } else {
            val item = text(ret)
            if (item.isNotEmpty() && !successCode(item)) failed = item
        }
        val code = text(result.opt("code"))
        if (failed.isEmpty() && code.isNotEmpty() && !successCode(code)) failed = code
        // onFail 的 result 是说明文字；不将它当业务 data 返回，也不泄露到异常。
        val message = text(result.opt("result"))
        if (!success || failed.isNotEmpty() || result.opt("result") == false) {
            throw failure(safeCode(if (failed.isNotEmpty()) failed else if (code.isNotEmpty()) code else message))
        }
        val body = result.opt("data")
        if (body == null || body == JSONObject.NULL || (body is String && body.isBlank())) {
            throw failure("HOST_RPC_EMPTY")
        }
        // {} / [] 是合法业务结果，不伪造缺失 data 为 {}。
        return body.toString()
    }
}
