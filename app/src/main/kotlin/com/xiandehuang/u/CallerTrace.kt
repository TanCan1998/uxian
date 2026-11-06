package com.xiandehuang.u

/**
 * 判定一次 mtop 调用是从哪一层发起的 —— **用调用栈做证据，不靠猜**。
 *
 * 为什么不能只看 header：
 *   `h5-path` / `referer` 这两个 header 会**粘住**。实测同一次启动里，
 *   `idlemessage.session.sync` 有 2 条记录一条带 h5-path 一条不带；
 *   `idle.ab.config.get` 也是。原因是宿主把 H5 容器的 header 写进了
 *   共享的 MtopNetworkProp，后续原生请求会跟着带上。
 *   拿它判调用层会把原生调用误判成 H5。
 *
 * 所以真正的判据是 stackTrace 里第一个「不属于 mtop 自身」的帧：
 *   - 类名命中 H5 容器（WindVane / WebView / H5Page）→ H5
 *   - 命中 Weex 运行时 → Weex
 *   - 命中 Flutter → Flutter
 *   - 命中 com.taobao.idlefish 的 Kotlin 类 → Kotlin
 *   - 命中 com.taobao.idlefish 的 Java 类 → Java
 *   - 什么都没有（纯 mtopsdk 内部线程）→ unknown
 *
 * 只保留**稳定的证据**：类名、包名、是否 Kotlin。行号在混淆包里是噪声，
 * 而且不同版本会变，所以不存。
 */
object CallerTrace {

    /** 抓包库里 caller 字段的格式：层|类名|方法名 */
    const val SEP = "|"

    private val SKIP_PREFIX = arrayOf(
        "java.", "javax.", "android.", "dalvik.", "kotlin.",
        "mtopsdk.", "com.taobao.mtop", "anetwork.", "com.taobao.anet",
        "com.alibaba.mtl.", "org.android.", "androidx.",
        // 混淆后的 mtop 内部类：这些是请求派发链，不是调用方
        "mtopsdk", "com.taobao.tao.remotebusiness", "com.taobao.android.remoteobject",
        // Vector 框架生成的桥接类（h2 / j3 之类），一律不算业务
        "Vector_", "io.github.libxposed.", "HookBridge",
    )

    /** 已知的 mtopsdk / Vector 桥接类短名（混淆后只剩短名，只能按名字列） */
    private val FRAME_SHORT = arrayOf(
        "MtopBuilder", "MtopBusiness", "MtopRequest", "MtopResponse", "MtopStatistics",
        "h2", "j3", "i3", "k3", "l3", "Vector_", "HookBridge",
    )

    /** H5 / 容器的类名特征 */
    private val H5_KEYS = arrayOf(
        "com.taobao.android.webview", "WindVane", "com.uc.webview", "H5Page",
        "com.alibaba.aliweex", "WXBridge", "com.taobao.android.hybrid",
        "com.taobao.android.web", "JsBridge", "com.alipay.mobile.h5container",
        // flybird = 阿里 H5/DX 渲染引擎，实测混淆后类名仍保留这个前缀
        "com.flybird", "flybird",
    )
    private val WEEX_KEYS = arrayOf("com.taobao.weex", "com.alibaba.weex", "WXSDKInstance")
    private val FLUTTER_KEYS = arrayOf("io.flutter", "flutter.embedding")

    /**
     * @param chain 调用链，从内到外，最多 5 帧，形如
     *              `com.x.A.a → com.x.B.b → com.x.C.c`。
     *              只存一个类名信息量不够 —— 实测有 47/54 条请求整条栈都在
     *              远端对象 SDK 里，只有链才能看出「谁经谁发的」。
     */
    class Info(
        val layer: String,
        val cls: String,
        val method: String,
        val chain: String,
    ) {
        fun encode(): String = layer + SEP + cls + SEP + method + SEP + chain
    }

    /**
     * 从当前线程栈里找出调用方。
     *
     * @param limit 只看前多少帧。mtop 的调用栈可以很深（AsyncTask 池 +
     *              拦截器链），但真正的调用方通常在 60 帧内。
     */
    @JvmStatic
    fun capture(limit: Int = 80): Info? {
        val st = try {
            Thread.currentThread().stackTrace
        } catch (t: Throwable) {
            return null
        }
        return pick(st, limit)
    }

    private fun pick(st: Array<StackTraceElement>, limit: Int): Info? {
        val n = minOf(st.size, limit)

        // 特征层优先：Flutter / Weex / H5 的类名属于框架自己，不会被混淆，
        // 栈里出现就说明这次调用来自那个容器。
        for (i in 0 until n) {
            val cn = st[i].className ?: continue
            if (FLUTTER_KEYS.any { cn.contains(it) }) {
                return Info("fl", cn, st[i].methodName, chainOf(st, n))
            }
            if (WEEX_KEYS.any { cn.contains(it) }) {
                return Info("weex", cn, st[i].methodName, chainOf(st, n))
            }
            if (H5_KEYS.any { cn.contains(it) }) {
                return Info("h5", cn, st[i].methodName, chainOf(st, n))
            }
        }

        // 业务帧 = 第一个既不是 JDK/Android 框架、也不是 mtop/Vector 桥的帧。
        //
        // 实测的栈（混淆后，且存在**嵌套钩子**）：
        //   4 MtopHooks$hookRequestTap$1.intercept   ← 我们
        //   5 h2.b  6 h2.proceed  7 j3.callback      ← Vector 的 Hooker 包装
        //   8 Vector_.asyncRequest                   ← Vector 入口
        //   9 MtopBuilder.asyncRequest               ← mtopsdk（原始方法，被 HookBridge 调起）
        //  10 HookBridge.invokeOriginalMethod        ← Vector
        //  11 j3.a 12 i3.a 13 h2.b ...               ← Vector 又一层
        //  14 MtopHooks...intercept                  ← 我们的钩子第二次出现（嵌套）
        //  15 h2.b ... 19 j3.callback
        //  20 ... 真正的业务调用方
        //
        // 结论：不能「遇到第一个非 java.* 帧就返回」，必须把整段框架桥全部跳过。
        // 判据是「类名短名命中 FRAME_SHORT」或「包名命中 isSkip」。
        // 业务帧的选择策略（两次实测修正后的结论）：
        //
        // 内层帧信息量最大 —— 它告诉你「哪个模块发起了这次请求」。
        // 例如 RulesManager.checkUpdate / HighwayMtopUtil.run / MRTConfigRequest.requestMtop。
        // 外层往往是线程池或 SDK 的入口（MtopLauncher$3.run / Thread.run），没信息量。
        //
        // 但内层也可能是中间层（MtopBusiness / MtopExtSDKHandler），这些要在
        // isSkip / FRAME_SHORT 里排掉，剩下的第一个就是真正的业务发起方。
        for (i in 0 until n) {
            val e = st[i]
            val cn = e.className ?: continue
            if (isSkip(cn)) continue
            if (isFrameShort(cn)) continue
            if (cn.startsWith("com.xiandehuang.u")) continue
            return Info(
                if (isKotlin(cn)) "kt" else "java", cn, e.methodName ?: "?",
                chainOf(st, n),
            )
        }

        // 兜底：栈里完全没有宿主业务类。
        // 实测这确实会发生 —— 有一批请求是远端对象 SDK 自己发的：
        //   MtopLauncher$3.run → Sync.start → Sync.syncDataByMtop
        //   → BaseHandler.request → MtopExtSDKHandler.process → originReq
        // 整条栈都在 com.taobao.android.remoteobject 里，没有页面/模块参与。
        // 这种情况标成 sdk 并给出 SDK 类名，比留空诚实、也有排查价值。
        for (i in 0 until n) {
            val e = st[i]
            val cn = e.className ?: continue
            if (cn.startsWith("java.") || cn.startsWith("android.") ||
                cn.startsWith("kotlin.") || cn.startsWith("dalvik.")
            ) {
                continue
            }
            if (cn.startsWith("com.xiandehuang.u")) continue
            if (isFrameShort(cn)) continue
            return Info("sdk", cn, e.methodName ?: "?", chainOf(st, n))
        }
        return null
    }

    /**
     * 把栈压成一条链：从内到外，跳过 JDK / Vector 桥 / 我们自己的钩子，
     * 最多 5 帧。**这一项是给人看的**，用来判断"谁经谁发的"。
     */
    private fun chainOf(st: Array<StackTraceElement>, n: Int): String {
        val sb = StringBuilder()
        var k = 0
        for (i in 0 until n) {
            val e = st[i]
            val cn = e.className ?: continue
            if (cn.startsWith("java.") || cn.startsWith("android.") ||
                cn.startsWith("kotlin.") || cn.startsWith("dalvik.")
            ) {
                continue
            }
            if (cn.startsWith("com.xiandehuang.u")) continue
            if (isFrameShort(cn)) continue
            val short = cn.substringAfterLast('.').substringBefore('$')
            if (sb.contains(short + ".")) continue
            if (k > 0) sb.append(" → ")
            sb.append(short).append('.').append(e.methodName ?: "?")
            if (++k >= 5) break
        }
        return sb.toString()
    }

    /** 混淆后的框架桥类：只按短名判断 */
    private fun isFrameShort(cn: String): Boolean {
        val short = cn.substringAfterLast('.')
        for (s in FRAME_SHORT) if (short == s) return true
        return false
    }

    private fun isSkip(cn: String): Boolean {
        for (p in SKIP_PREFIX) if (cn.startsWith(p)) return true
        return false
    }

    /**
     * 类是不是 Kotlin 写的。
     *
     * 判据：加载类上的 `kotlin.Metadata` 注解。**不能用类名启发式** ——
     * 闲鱼里 Kotlin 类的名字和 Java 类没有区别。
     * 注意必须用 `Class.forName(name, false, cl)`：初始化会触发静态块，
     * 在钩子里做这件事很危险。
     */
    private val KOTLIN_CACHE = HashMap<String, Boolean>()

    @JvmStatic
    fun isKotlin(className: String): Boolean {
        synchronized(KOTLIN_CACHE) {
            KOTLIN_CACHE[className]?.let { return it }
        }
        var r = false
        try {
            val cl = Protocol.State.classLoader
            if (cl != null) {
                val c = Class.forName(className, false, cl)
                for (a in c.annotations) {
                    if (a.annotationClass.java.name == "kotlin.Metadata") { r = true; break }
                }
            }
        } catch (ignored: Throwable) {
        }
        synchronized(KOTLIN_CACHE) {
            if (KOTLIN_CACHE.size > 512) KOTLIN_CACHE.clear()
            KOTLIN_CACHE[className] = r
        }
        return r
    }

    /** 把库里存的编码还原成 (层, 类, 方法)，解析失败返回 null */
    @JvmStatic
    fun decode(s: String?): Array<String>? {
        if (s.isNullOrEmpty()) return null
        val p = s.split(SEP)
        if (p.size < 3) return null
        return arrayOf(p[0], p[1], p[2], if (p.size > 3) p[3] else "")
    }
}
