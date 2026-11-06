package com.xiandehuang.u

import android.content.Context
import java.util.HashMap
import org.json.JSONObject

/**
 * mtop 签名器 —— 用宿主自己的 [MtopConfig] 算 `m-sign` 等安全头。
 *
 * ## 为什么需要它
 *
 * 淘宝网关（`https://<biz>-acs.m.taobao.com/gw/<api>/<ver>`）可以直接 HTTP 调，
 * 但要求几个协议头，逐个补齐的实测顺序是：
 *
 * ```
 * 不加          → FAIL_SYS_PROTOVER_MISSED    缺少 m-pv
 * +m-pv:1.0    → FAIL_SYS_PROTOPARAM_MISSED   缺少 m-appkey
 * +m-appkey    → FAIL_SYS_PROTOPARAM_MISSED   缺少 m-sign   ← 卡在这
 * ```
 *
 * 所以只要能把 `m-sign` 算出来，就能**完全绕开 mtop SDK、绕开 hook、绕开抓包库**。
 *
 * ## 签名入口（mt 反编译确认）
 *
 * ```
 * mtopsdk.security.InnerSignImpl        (super: AbstractSignImpl)
 *   init(MtopConfig)                                     ← 绑定宿主配置
 *   getUnifiedSign(HashMap, HashMap, String, String, boolean, String) → HashMap
 *   getSign(HashMap, String) → String
 *   getAppKey(ISign$SignCtx) / getAppKeyByIndex(int, String) → String
 *   getMtopApiSign(HashMap, String, String) → String
 *   getWua(HashMap, String) → String          （x-umt）
 *   getMiniWua(HashMap, HashMap) → String     （x-mini-wua）
 * ```
 *
 * `MtopConfig` 从宿主现成实例拿：
 * ```
 * Protocol.State.mtop → mtopsdk.mtop.intf.Mtop.getMtopConfig()
 * ```
 * （`getMtopConfig()Lmtopsdk/mtop/global/MtopConfig;` 是 public 方法）
 *
 * ## 状态
 *
 * **实验性**。签名要的 `baseStr` 具体放哪些字段还没确认 ——
 * 这一步必须靠「抓一次宿主真实请求的 x-sign 反推」或「直接试」来确定，
 * 不能猜。猜错的话签名对不上，服务端只会回一个笼统的错。
 *
 * 本类只负责「把 InnerSignImpl 拿到手 + 调用」，字段构成由调用方给。
 */
object MtopSigner {

    @Volatile
    private var lastError = ""

    @Volatile
    private var stage = ""

    /** 独立的诊断串（不被 unifiedSign 的成功覆盖） */
    @Volatile
    private var diag = ""

    @JvmStatic
    fun diag(): String = diag

    @Volatile
    private var signer: Any? = null

    @Volatile
    private var appKeyCache: String = ""

    @JvmStatic
    fun lastError(): String = lastError

    @JvmStatic
    fun stage(): String = stage

    /** 宿主的 MtopConfig（从已经存在的 Mtop 实例取） */
    @JvmStatic
    fun mtopConfig(): Any? {
        val mtop = Protocol.State.mtop ?: return null
        return try {
            mtop.javaClass.getMethod("getMtopConfig").invoke(mtop)
        } catch (t: Throwable) {
            lastError = "getMtopConfig: " + t.javaClass.simpleName
            null
        }
    }

    /**
     * 组装签名必需的 baseMap —— **逐行照抄 `InnerProtocolParamBuilderImpl`**。
     *
     * ## 反编译得到的真实构造规则（每条都有出处）
     *
     * ```
     * data        = 参数 JSON 原文
     * t           = SDKUtils.getCorrectionTime()
     *               = System.currentTimeMillis() / 1000 + timeOffset     ← 秒！
     * api         = apiName.toLowerCase(Locale.US)
     * v           = version.toLowerCase(Locale.US)
     * sid         = Mtop.getMultiAccountSid(prop.userInfo)
     * ttid        = MtopNetworkProp.ttid
     * deviceId    = Mtop.getDeviceId()
     * lat / lng   = XState.getValue(null,"lat"/"lng")，**两者都非空才一起写**
     * reqbiz-ext = MtopNetworkProp.reqBizExt
     * appKey      = MtopConfig.appKey
     * routerId    = MtopConfig.routerId（非空才写）
     * ```
     *
     * ## ⚠️ 之前错在哪（就是这两个）
     *
     * 1. **`t` 用了毫秒** —— 宿主用 `currentTimeMillis()/1000`，即**秒**。
     *    毫秒值拿去签名，服务端按秒校验，必然 `ILEGEL_SIGN`。
     * 2. **`api` / `v` 没转小写** —— 宿主明确 `toLowerCase(Locale.US)`。
     *
     * 这两个错任何一个都会让签名对不上，而报错是同一个笼统的
     * `FAIL_SYS_ILEGEL_SIGN`，看不出方向。
     */
    @JvmStatic
    fun buildBaseMap(api: String, version: String, data: String): HashMap<String, String> {
        val m = HashMap<String, String>()
        m["data"] = data
        // ① t：秒级（currentTimeMillis/1000 + offset），不是毫秒
        m["t"] = (System.currentTimeMillis() / 1000L + timeOffset()).toString()
        // ② api / v 必须小写
        m["api"] = api.lowercase()
        m["v"] = version.lowercase()

        // ③ sid：宿主的 Mtop.getMultiAccountSid(null)；取不到再退 getSid()
        val mtop = Protocol.State.mtop
        if (mtop != null) {
            var sid = ""
            try {
                val v = mtop.javaClass
                    .getMethod("getMultiAccountSid", String::class.java)
                    .invoke(mtop, null as String?) as? String
                if (!v.isNullOrEmpty()) sid = v
            } catch (ignored: Throwable) {
            }
            if (sid.isEmpty()) {
                try {
                    val v = mtop.javaClass.getMethod("getSid").invoke(mtop) as? String
                    if (!v.isNullOrEmpty()) sid = v
                } catch (ignored: Throwable) {
                }
            }
            if (sid.isNotEmpty()) m["sid"] = sid
            // ④ deviceId：Mtop.getDeviceId()
            try {
                val v = mtop.javaClass.getMethod("getDeviceId").invoke(mtop) as? String
                if (!v.isNullOrEmpty()) m["deviceId"] = v
            } catch (ignored: Throwable) {
            }
        }

        // ⑤ 其余从 MtopConfig / XState 补（含 ttid / utdid / lat / lng / appKey）
        val cfg = mtopConfig()
        if (cfg != null) {
            for (name in CONFIG_FIELDS) {
                try {
                    val f = cfg.javaClass.getDeclaredField(name)
                    f.isAccessible = true
                    val v = f.get(cfg)
                    // 空串不写：写进去会让后面的 XState 兜底判断失效
                    if (v != null && v.toString().isNotEmpty()) m[name] = v.toString()
                } catch (ignored: Throwable) {
                }
            }
        }
        fillFromXState(m)
        // ⑥ lat/lng 成对：宿主是两个都非空才一起写
        val lat = m["lat"]
        val lng = m["lng"]
        if (lat.isNullOrEmpty() || lng.isNullOrEmpty()) {
            m.remove("lat")
            m.remove("lng")
        }
        return m
    }

    /**
     * 时钟偏移（`SDKUtils.getTimeOffset()`）。
     *
     * 宿主用它与本地时间相加来纠正设备时钟偏差 ——
     * 设备时间不准时，只有带上它服务端才认。
     * 我们从 `SDKUtils` 反射取，取不到当 0。
     */
    private fun timeOffset(): Long {
        return try {
            val cl = Protocol.State.classLoader ?: return 0L
            val cls = Class.forName("mtopsdk.mtop.global.SDKUtils", false, cl)
            val v = cls.getMethod("getTimeOffset").invoke(null) as? Long
            v ?: 0L
        } catch (t: Throwable) {
            0L
        }
    }

    /**
     * 从 `mtopsdk.xstate.XStateDelegate.stateIDs` 直接取会话变量。
     *
     * 反查链：
     * ```
     * Mtop.getUtdid() → XState.getValue(null,"utdid")
     *                    → XStateDelegate.getValue("utdid")
     *                        → stateIDs.get("utdid")      ← 就是这个静态 Map
     * ```
     *
     * ## ⚠️ 键名带前缀（实测 dump 出来的，不是猜的）
     *
     * XState 里 17 个键，与签名相关的是：
     * ```
     * utdid                       <device-id>                  ← 无前缀
     * INNER$DEFAULT$sid           <session-id>
     * MTOP_ID_TAOBAO$DEFAULT$sid  <app-session-id>
     * INNER$DEFAULT$uid           <user-id>
     * INNER$ttid / MTOP_ID_TAOBAO$ttid  <client-ttid>
     * lat / lng                   <latitude> / <longitude>
     * ```
     * 早先按 `sid` / `uid` / `ttid` 直接查全是 null —— 这就是签名算不对的原因。
     *
     * 两个 sid 是不同的：`INNER$` 是当前进程的、`MTOP_ID_TAOBAO$` 是按 appKey 分的。
     * 取 `INNER$` 优先（也就是 `Mtop.getSid()` 返回的那个）。
     */
    private fun fillFromXState(m: HashMap<String, String>) {
        try {
            val cl = Protocol.State.classLoader
            // classloader 可能还没就绪，但 XState 类肯定已经加载过（Mtop 在用）
            // 所以优先用 Mtop 实例的 classloader 兜底
            val ld = cl ?: (Protocol.State.mtop?.javaClass?.classLoader)
            if (ld == null) {
                diag = "无 classloader"
                return
            }
            val cls = Class.forName("mtopsdk.xstate.XStateDelegate", false, ld)
            val f = cls.getDeclaredField("stateIDs")
            f.isAccessible = true
            val map = f.get(null) as? Map<*, *> ?: run {
                diag = "stateIDs 为 null"
                return
            }
            val sb = StringBuilder("size=" + map.size)
            for ((baseMapKey, candidates) in xstateKeys()) {
                val cur = m[baseMapKey]
                if (cur != null && cur.isNotEmpty()) continue
                for (c in candidates) {
                    val v = map[c] as? String
                    if (!v.isNullOrEmpty()) {
                        m[baseMapKey] = v
                        break
                    }
                }
                sb.append(" ").append(baseMapKey).append("=")
                    .append(if (m[baseMapKey].isNullOrEmpty()) "-" else "ok")
            }
            diag = sb.toString()
        } catch (t: Throwable) {
            diag = "err: " + t.javaClass.simpleName + " " + (t.message ?: "")
        }
    }

    /**
     * baseMap 键 → XState 里的候选键（按优先级）。
     *
     * 都是 dump 出真实键名后填的，不是猜的。
     *
     * ⚠️ 用 `by lazy` 而不是直接 `val`：`fillFromXState` 在对象初始化早期
     * 就可能被调（`buildBaseMap` 由业务链触发），那时后面的 `val` 还没赋上值，
     * 直接读会拿到 null。实测就是因此 uid/lat/lng 没补进去。
     */
    private fun xstateKeys(): Array<Pair<String, Array<String>>> = arrayOf(
        Pair("utdid", arrayOf("utdid")),
        Pair("sid", arrayOf("INNER\$DEFAULT\$sid", "MTOP_ID_TAOBAO\$DEFAULT\$sid")),
        Pair("uid", arrayOf("INNER\$DEFAULT\$uid", "MTOP_ID_TAOBAO\$DEFAULT\$uid")),
        Pair("ttid", arrayOf("INNER\$ttid", "MTOP_ID_TAOBAO\$ttid", "SSR\$ttid")),
        Pair("lat", arrayOf("lat")),
        Pair("lng", arrayOf("lng")),
        Pair("deviceId", arrayOf("deviceId")),
    )

    /**
     * dump `XStateDelegate.stateIDs` 全表。
     *
     * 用途：一次看清宿主的会话变量有哪些键、值是什么形态
     * （哪几个是登录态才有、哪些是设备级常量）。
     * 比逐个猜 getter 名字快得多。
     */
    @JvmStatic
    fun dumpXState(): String {
        return try {
            val cl = Protocol.State.classLoader ?: return """{"ok":false,"error":"no cl"}"""
            val cls = Class.forName("mtopsdk.xstate.XStateDelegate", false, cl)
            val f = cls.getDeclaredField("stateIDs")
            f.isAccessible = true
            val map = f.get(null) as? Map<*, *>
                ?: return """{"ok":false,"error":"stateIDs 为 null（XState 未初始化）"}"""
            val o = JSONObject()
            o.put("ok", true)
            o.put("size", map.size)
            // 报告各 mtop 实例（默认 / INNER / MTOP_ID_XIANYU），看会话是否不同
            try {
                val cl = Protocol.State.classLoader
                val ctx = Protocol.State.app
                if (cl != null && ctx != null) {
                    val mi = Class.forName("mtopsdk.mtop.intf.Mtop", false, cl)
                    val instM = mi.getMethod("instance", android.content.Context::class.java)
                    val arr = org.json.JSONArray()
                    for (id in arrayOf("", "INNER", "MTOP_ID_XIANYU")) {
                        try {
                            val ins = if (id.isEmpty()) instM.invoke(null, ctx) else
                                mi.getMethod("instance", android.content.Context::class.java, String::class.java)
                                    .invoke(null, ctx, id)
                            if (ins == null) continue
                            val o2 = JSONObject()
                            o2.put("instance", if (id.isEmpty()) "(default)" else id)
                            o2.put("class", ins.javaClass.name)
                            try {
                                o2.put("sid", ins.javaClass.getMethod("getSid").invoke(ins)?.toString() ?: "")
                            } catch (ignored: Throwable) {
                            }
                            try {
                                o2.put("utdid", ins.javaClass.getMethod("getUtdid").invoke(ins)?.toString() ?: "")
                            } catch (ignored: Throwable) {
                            }
                            arr.put(o2)
                        } catch (t: Throwable) {
                            val o2 = JSONObject()
                            o2.put("instance", id)
                            o2.put("err", t.javaClass.simpleName)
                            arr.put(o2)
                        }
                    }
                    o.put("instances", arr)
                }
            } catch (ignored: Throwable) {
            }
            // 顺带报告宿主的 ISign 实现类与 sign 字段 —— 决定用哪套签名算法
            try {
                val cfg = mtopConfig()
                if (cfg != null) {
                    val sf = cfg.javaClass.getDeclaredField("sign")
                    sf.isAccessible = true
                    val sv = sf.get(cfg)
                    o.put("signImpl", if (sv == null) "null" else sv.javaClass.name)
                }
            } catch (t: Throwable) {
                o.put("signImpl", "err:" + t.javaClass.simpleName)
            }
            for ((k, v) in map) {
                if (k == null) continue
                val s = if (v == null) "" else v.toString()
                // 值太长只留前缀，避免刷屏
                o.put(k.toString(), if (s.length > 80) s.substring(0, 80) + "…" else s)
            }
            o.toString()
        } catch (t: Throwable) {
            """{"ok":false,"error":"${t.javaClass.simpleName}: ${t.message}"}"""
        }
    }

    /** `MtopConfig` 里键名与 baseMap 同名的字段 */
    private val CONFIG_FIELDS = arrayOf("utdid", "ttid", "deviceId", "appKey")

    /**
     * 拿到配好的 `InnerSignImpl`（幂等）。
     *
     * 必须 `init(MtopConfig)` 过才能用 —— 没 init 的话内部安全组件全是空，
     * 调签名会 NPE 或返回空串。
     */
    @JvmStatic
    fun prepare(): Any? {
        val hit = signer
        if (hit != null) return hit
        synchronized(MtopSigner::class.java) {
            if (signer != null) return signer
            val cl = Protocol.State.classLoader ?: run {
                lastError = "classloader 未就绪"
                return null
            }
            try {
                stage = "config"
                val cfg = mtopConfig() ?: run {
                    lastError = "拿不到 MtopConfig（Mtop 实例未就绪）"
                    return null
                }
                // ① 优先**直接复用宿主已有的 sign 实现**。
                //    理由：mtopConfig.sign 可能是 LocalInnerSignImpl，
                //    也可能是 InnerSignImpl —— 两者签名算法不同
                //    （LocalInnerSignImpl.convertInnerBaseStrMap 返回 String，
                //      InnerSignImpl 返回 HashMap）。自己 new InnerSignImpl
                //    等于换了算法，服务端必然 ILEGEL_SIGN（实测就是这个问题）。
                stage = "reuseHostSign"
                try {
                    val sf = cfg.javaClass.getDeclaredField("sign")
                    sf.isAccessible = true
                    val host = sf.get(cfg)
                    if (host != null) {
                        signer = host
                        stage = "ready(reuse:" + host.javaClass.simpleName + ")"
                        return host
                    }
                } catch (ignored: Throwable) {
                }
                // ② 宿主没有时才自己建（并 init）
                stage = "new"
                val cls = Class.forName("mtopsdk.security.InnerSignImpl", false, cl)
                val inst = cls.getDeclaredConstructor().newInstance()
                stage = "init"
                val initM = cls.getMethod(
                    "init", Class.forName("mtopsdk.mtop.global.MtopConfig", false, cl),
                )
                initM.invoke(inst, cfg)
                signer = inst
                stage = "ready(self)"
                return inst
            } catch (t: Throwable) {
                val c = if (t is java.lang.reflect.InvocationTargetException) t.cause ?: t else t
                lastError = "[" + stage + "] " + c.javaClass.simpleName + ": " +
                    (c.message ?: "")
                return null
            }
        }
    }

    /** 当前 appKey（宿主配置里的那个） */
    @JvmStatic
    fun appKey(): String {
        if (appKeyCache.isNotEmpty()) return appKeyCache
        val s = prepare() ?: return ""
        // ① 正规路径：SecurityGuardManager.getStaticDataStoreComp()
        //                 .getAppKeyByIndex(appKeyIndex, authCode)
        //    这是 InnerSignImpl.getAppKeyByIndex 内部的真实做法 ——
        //    appKey 存在签名证书里，按 index 取，不能直接读字段。
        try {
            val cl = Protocol.State.classLoader ?: return ""
            val sgmCls = Class.forName(
                "com.alibaba.wireless.security.open.SecurityGuardManager", false, cl,
            )
            val ctx = Protocol.State.app
            val sgm = sgmCls.getMethod("getInstance", android.content.Context::class.java)
                .invoke(null, ctx)
            if (sgm != null) {
                val comp = sgmCls.getMethod("getStaticDataStoreComp").invoke(sgm)
                if (comp != null) {
                    val idx = appKeyIndex()
                    val auth = authCode()
                    val v = comp.javaClass.getMethod(
                        "getAppKeyByIndex", java.lang.Integer.TYPE, String::class.java,
                    ).invoke(comp, idx, auth) as? String
                    if (!v.isNullOrEmpty()) {
                        appKeyCache = v
                        return v
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
        // ② 退路：反射 sign 实现上的 private getAppKeyByIndex
        try {
            val m = s.javaClass.getDeclaredMethod(
                "getAppKeyByIndex", java.lang.Integer.TYPE, String::class.java,
            )
            m.isAccessible = true
            val v = m.invoke(s, appKeyIndex(), authCode()) as? String
            if (!v.isNullOrEmpty()) {
                appKeyCache = v
                return v
            }
        } catch (ignored: Throwable) {
        }
        // 退回从 MtopConfig 字段读
        try {
            val cfg = mtopConfig() ?: return ""
            for (name in arrayOf("appKey", "mAppKey", "appkey")) {
                try {
                    val f = cfg.javaClass.getDeclaredField(name)
                    f.isAccessible = true
                    val v = f.get(cfg) as? String
                    if (!v.isNullOrEmpty()) {
                        appKeyCache = v
                        return v
                    }
                } catch (ignored: Throwable) {
                }
            }
        } catch (ignored: Throwable) {
        }
        return ""
    }

    /**
     * 取 `MtopConfig.authCode` —— 签名时必传。
     *
     * 反查 `InnerSignImpl.init`：
     * ```
     * mUnifiedSign = SecurityGuardManager.getInstance(ctx)
     *                  .getInterface(IUnifiedSecurityComponent.class)
     * mUnifiedSign.init({ "authCode": mtopConfig.authCode })
     * ```
     * 以及 `initUmidToken(appKey, mtopConfig.authCode)`。
     * 也就是说**组件的会话态是用 config 里的 authCode 初始化的** ——
     * 签名时传的 authCode 必须和它一致，否则算出来的签名对不上
     * （表现就是 FAIL_SYS_ILEGEL_SIGN，实测就卡在这）。
     */
    /** `MtopConfig.appKeyIndex` —— appKey 存在证书里，按这个索引取 */
    @JvmStatic
    fun appKeyIndex(): Int {
        val cfg = mtopConfig() ?: return 0
        return try {
            val f = cfg.javaClass.getDeclaredField("appKeyIndex")
            f.isAccessible = true
            (f.get(cfg) as? Int) ?: 0
        } catch (t: Throwable) {
            0
        }
    }

    @JvmStatic
    fun authCode(): String {
        val cfg = mtopConfig() ?: return ""
        return try {
            val f = cfg.javaClass.getDeclaredField("authCode")
            f.isAccessible = true
            (f.get(cfg) as? String) ?: ""
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * 算一组安全头。
     *
     * @param base 待签字段（api / v / data / t / appKey ...）
     * @param ext  扩展字段
     * @param apiName 接口名
     * @return `x-sign` / `x-sgext` / `x-umt` / `x-mini-wua` 等；失败返回空 Map
     */
    @JvmStatic
    fun unifiedSign(
        base: HashMap<String, String>,
        ext: HashMap<String, String>,
        apiName: String,
        authCode: String,
        useWua: Boolean,
    ): Map<String, String> {
        // ⚠️ 第 6 个参数是 requestId，不是 apiName。
        // 反查 InnerProtocolParamBuilderImpl.buildParams 第 88 行：
        //   v6 = mtopBuilder.requestId
        //   getUnifiedSign(baseMap, ext, appKey, authCode, useWua, v6)
        // 早先传 apiName，等于把 requestId 填错 —— 签名必然对不上。
        val requestId = java.util.UUID.randomUUID().toString().replace("-", "")
        val out = HashMap<String, String>()
        val s = prepare() ?: return out
        try {
            stage = "unifiedSign"
            val key = appKey()
            if (key.isEmpty()) {
                lastError = "appKey 为空"
                return out
            }
            val m = s.javaClass.getMethod(
                "getUnifiedSign",
                HashMap::class.java,
                HashMap::class.java,
                String::class.java,
                String::class.java,
                java.lang.Boolean.TYPE,
                String::class.java,
            )
            val ret = m.invoke(s, base, ext, key, authCode, useWua, requestId)
            if (ret is Map<*, *>) {
                for ((k, v) in ret) {
                    if (k != null && v != null) out[k.toString()] = v.toString()
                }
            }
            stage = "done"
        } catch (t: Throwable) {
            val c = if (t is java.lang.reflect.InvocationTargetException) t.cause ?: t else t
            lastError = "[unifiedSign] " + c.javaClass.simpleName + ": " + (c.message ?: "")
        }
        return out
    }

    /** 只要一个签名字符串 */
    @JvmStatic
    fun sign(base: HashMap<String, String>, apiName: String): String {
        val s = prepare() ?: return ""
        return try {
            val m = s.javaClass.getMethod("getSign", HashMap::class.java, String::class.java)
            (m.invoke(s, base, apiName) as? String) ?: ""
        } catch (t: Throwable) {
            val c = if (t is java.lang.reflect.InvocationTargetException) t.cause ?: t else t
            lastError = "[getSign] " + c.javaClass.simpleName + ": " + (c.message ?: "")
            ""
        }
    }
}
