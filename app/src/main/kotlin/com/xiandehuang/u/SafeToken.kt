package com.xiandehuang.u

import android.content.Context

/**
 * 主动生成 `asac`（safeToken）—— **不依赖用户打开任务页**。
 *
 * ## 完整链路（mt 逐行读源码得到，每步都有出处）
 *
 * ### 1. asac 就是 safeToken，由本地计算得出
 *
 * ```
 * H5 / 浮层要 asac 时 → JSBridgeService.getSign(callback, json)
 *   json = {"data": "<要签的内容>"}（长度必须 < 128）
 *
 * getSign 内部：
 *   uid      = Login.getUserId()
 *   tokenKey = <账号历史里的 tokenKey>
 *   signed   = AlibabaSecurityTokenService.sign(tokenKey, data)
 *   返回 {result:"HY_SUCCESS", signedData: signed, tokenKey: tokenKey}
 *
 * 签名落地：
 *   AlibabaSecurityTokenService.sign(key, data)
 *     → SecurityGuardManager.getSafeTokenComp()              → ISafeTokenComponent
 *     → signWithToken(key, data.getBytes("UTF-8"), 1)        → safeToken
 * ```
 *
 * ### 2. tokenKey 从哪来（**这里之前查错了包**）
 *
 * 有两套同名的 HistoryAccount，**必须用 `ali.user.open` 那套**：
 *
 * | 包 | tokenKey | 说明 |
 * |---|---|---|
 * | `com.ali.user.mobile.rpc.HistoryAccount` | 字段有，但**实测为空** | 旧实现，闲鱼不用 |
 * | `com.ali.user.open.history.HistoryAccount` | **有值** | ✅ 正确的那套 |
 *
 * 正确链路：
 * ```
 * AccountHistoryManager.getInstance()          ← 静态，直接拿
 *   .getHistoryAccounts() : List<ali.user.open.history.HistoryAccount>
 *   每条有 userId / tokenKey / nick / mobile / email
 * ```
 *
 * ### 3. tokenKey 什么时候被写进去
 *
 * `SessionManager.refreshWhenLogin(site, LoginReturnData)`，只对 `site == "taobao"` 写：
 * ```
 * deviceToken = LoginReturnData.deviceToken        (DeviceTokenRO)
 * session.deviceTokenSalt = deviceToken.salt
 * session.deviceTokenKey  = deviceToken.key
 * new HistoryAccount(userId, deviceTokenKey, nick, phone, email)
 * AccountHistoryManager.getInstance().putLoginHistory(account, deviceTokenSalt)
 * ```
 * 所以 `tokenKey` 是**登录时服务端下发、存进 AccountHistoryManager** 的，
 * 登录过一次就有，不需要任何页面。
 *
 * ## 结论
 *
 * 只要用户登录过，`tokenKey` 就在本地，`signWithToken` 也是本地计算 ——
 * **整条链不需要用户打开任务页**。
 *
 * ## 待验证
 *
 * 本地 sign 出的 safeToken 与 H5 塞进请求头的 asac 是否是同一个东西。
 */
object SafeToken {

    @Volatile
    private var lastError = ""

    @Volatile
    private var stage = ""

    @JvmStatic
    fun lastError(): String = lastError

    @JvmStatic
    fun stage(): String = stage

    /**
     * 取当前登录账号的 `tokenKey`。
     *
     * 走 `com.ali.user.open.history.AccountHistoryManager`（**open 包，不是 mobile 包**）。
     * 按 `Login.getUserId()` 匹配，取不到就退第一条。
     */
    @JvmStatic
    fun tokenKey(ctx: Context?): String {
        val cl = Protocol.State.classLoader ?: run {
            lastError = "classloader 未就绪"
            return ""
        }
        try {
            stage = "uid"
            var uid = ""
            try {
                val lc = Class.forName("com.taobao.login4android.Login", false, cl)
                uid = (lc.getMethod("getUserId").invoke(null) as? String) ?: ""
            } catch (ignored: Throwable) {
            }

            stage = "AccountHistoryManager"
            val ahmCls = Class.forName(
                "com.ali.user.open.history.AccountHistoryManager", false, cl,
            )
            val mgr = ahmCls.getMethod("getInstance").invoke(null) ?: run {
                lastError = "AccountHistoryManager 实例为空"
                return ""
            }
            stage = "getHistoryAccounts"
            val list = ahmCls.getMethod("getHistoryAccounts").invoke(mgr) as? List<*>
                ?: run {
                lastError = "getHistoryAccounts 返回 null"
                return ""
            }
            if (list.isEmpty()) {
                lastError = "历史账号列表为空（本机从未登录过？）"
                return ""
            }
            stage = "match"
            var fallback = ""
            for (item in list) {
                if (item == null) continue
                val tk = readField(item, "tokenKey")
                val u = readField(item, "userId")
                if (tk.isEmpty()) continue
                if (fallback.isEmpty()) fallback = tk
                if (uid.isNotEmpty() && u == uid) {
                    stage = "done"
                    return tk
                }
            }
            if (fallback.isNotEmpty()) {
                // 没匹配上 uid 但有 tokenKey：用第一条（单账号场景就是它）
                stage = "done(fallback)"
                return fallback
            }
            lastError = "列表里有 " + list.size + " 条，但 tokenKey 全为空"
            return ""
        } catch (t: Throwable) {
            val c = if (t is java.lang.reflect.InvocationTargetException) t.cause ?: t else t
            lastError = "[$stage] " + c.javaClass.simpleName + ": " + (c.message ?: "")
            return ""
        }
    }

    private fun readField(o: Any, name: String): String {
        return try {
            val f = o.javaClass.getDeclaredField(name)
            f.isAccessible = true
            val v = f.get(o)
            if (v == null) "" else v.toString()
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * 生成 safeToken。
     *
     * 优先直连 `ISafeTokenComponent.signWithToken`，失败退回官方封装
     * `AlibabaSecurityTokenService.sign`。
     *
     * @param data 要签的内容（长度必须 < 128，见 getSign 的校验）
     */
    @JvmStatic
    fun sign(ctx: Context?, key: String, data: String): String {
        if (key.isEmpty() || data.isEmpty()) {
            lastError = "key 或 data 为空"
            return ""
        }
        val cl = Protocol.State.classLoader ?: return ""
        try {
            stage = "safeTokenComp"
            val sgmCls = Class.forName(
                "com.alibaba.wireless.security.open.SecurityGuardManager", false, cl,
            )
            val sgm = sgmCls.getMethod("getInstance", android.content.Context::class.java)
                .invoke(null, ctx)
            val comp = sgmCls.getMethod("getSafeTokenComp").invoke(sgm)
            if (comp != null) {
                stage = "signWithToken"
                val bytes = data.toByteArray(Charsets.UTF_8)
                val v = comp.javaClass.getMethod(
                    "signWithToken", String::class.java, ByteArray::class.java,
                    java.lang.Integer.TYPE,
                ).invoke(comp, key, bytes, 1) as? String
                if (!v.isNullOrEmpty()) {
                    stage = "done(direct)"
                    return v
                }
            }
        } catch (t: Throwable) {
            val c = if (t is java.lang.reflect.InvocationTargetException) t.cause ?: t else t
            lastError = "[$stage] " + c.javaClass.simpleName + ": " + (c.message ?: "")
        }
        try {
            stage = "service.sign"
            val svc = Class.forName(
                "com.ali.user.mobile.security.AlibabaSecurityTokenService", false, cl,
            )
            val v = svc.getMethod("sign", String::class.java, String::class.java)
                .invoke(null, key, data) as? String
            if (!v.isNullOrEmpty()) {
                stage = "done(service)"
                return v
            }
        } catch (t: Throwable) {
            val c = if (t is java.lang.reflect.InvocationTargetException) t.cause ?: t else t
            lastError = "[service.sign] " + c.javaClass.simpleName + ": " + (c.message ?: "")
        }
        return ""
    }

    /**
     * 诊断：一次看清整条链。
     *
     * 输出 uid / 历史账号条数与字段 / tokenKey 长度 / 一次签名结果。
     * **只输出长度和前缀，不打印 token 明文。**
     */
    @JvmStatic
    fun probe(ctx: Context?): String {
        val o = org.json.JSONObject()
        try {
            val cl = Protocol.State.classLoader
            if (cl == null) {
                o.put("ok", false)
                o.put("error", "no classloader")
                return o.toString()
            }
            var uid = ""
            try {
                val lc = Class.forName("com.taobao.login4android.Login", false, cl)
                uid = (lc.getMethod("getUserId").invoke(null) as? String) ?: ""
            } catch (ignored: Throwable) {
            }
            o.put("uid", uid)

            // 直连存储：com.ali.user.open.core.service.StorageService.getDDpExValue("tb_history_acounts")
            // （读自 AccountHistoryManager.getHistoryAccounts —— 这才是 open 包用的键）
            try {
                val sCls = Class.forName(
                    "com.ali.user.open.core.AliMemberSDK", false, cl,
                )
                val stIface = Class.forName(
                    "com.ali.user.open.core.service.StorageService", false, cl,
                )
                val svc = sCls.getMethod("getService", Class::class.java)
                    .invoke(null, stIface)
                if (svc != null) {
                    val raw = stIface.getMethod("getDDpExValue", String::class.java)
                        .invoke(svc, "tb_history_acounts") as? String
                    o.put("tbRawLen", if (raw == null) -1 else raw.length)
                    if (!raw.isNullOrEmpty()) {
                        o.put("tbRaw", if (raw.length > 700) raw.substring(0, 700) + "…" else raw)
                    }
                } else {
                    o.put("tbSvc", "null")
                }
            } catch (t: Throwable) {
                o.put("tbErr", t.javaClass.simpleName + ": " + (t.message ?: ""))
            }
            // 历史账号列表
            try {
                val ahmCls = Class.forName(
                    "com.ali.user.open.history.AccountHistoryManager", false, cl,
                )
                val mgr = ahmCls.getMethod("getInstance").invoke(null)
                val list = ahmCls.getMethod("getHistoryAccounts").invoke(mgr) as? List<*>
                if (list == null) {
                    o.put("accounts", "null")
                } else {
                    o.put("accountCount", list.size)
                    val brief = ArrayList<String>()
                    for (item in list) {
                        if (item == null) continue
                        val tk = readField(item, "tokenKey")
                        val u = readField(item, "userId")
                        val nick = readField(item, "nick")
                        brief.add(
                            "u=$u tk=" + (if (tk.isEmpty()) "空" else tk.length.toString() + "位") +
                                " nick=$nick",
                        )
                    }
                    o.put("accountBrief", brief.toString())
                    if (list.isNotEmpty() && list[0] != null) {
                        val fs = ArrayList<String>()
                        for (f in list[0]!!.javaClass.declaredFields) {
                            try {
                                f.isAccessible = true
                                val v = f.get(list[0])
                                val s2 = if (v == null) "" else v.toString()
                                if (s2.isNotEmpty()) {
                                    fs.add(f.name + "=" + (if (s2.length > 16) s2.substring(0, 16) + "…" else s2))
                                }
                            } catch (ignored: Throwable) {
                            }
                        }
                        o.put("account0Fields", fs.toString())
                    }
                }
            } catch (t: Throwable) {
                o.put("accountErr", t.javaClass.simpleName + ": " + (t.message ?: ""))
            }

            // tokenKey
            val tk = tokenKey(ctx)
            o.put("tokenKeyLen", tk.length)
            o.put("tokenErr", lastError)

            // 签一次
            if (tk.isNotEmpty()) {
                val signed = sign(ctx, tk, "asac-probe")
                o.put("signedLen", signed.length)
                o.put("signedHead", if (signed.length > 16) signed.substring(0, 16) + "…" else signed)
                o.put("signErr", lastError)
            }
            o.put("stage", stage)
            o.put("ok", tk.isNotEmpty())
        } catch (t: Throwable) {
            try {
                o.put("ok", false)
                o.put("error", t.javaClass.simpleName + ": " + (t.message ?: ""))
            } catch (ignored: Throwable) {
            }
        }
        return o.toString()
    }
}
