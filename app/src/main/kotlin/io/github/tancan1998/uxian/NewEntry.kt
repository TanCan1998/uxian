package io.github.tancan1998.uxian

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * libxposed API 102 入口（Vector / 新版 LSPosed）。
 *
 * 与 legacy 版功能完全一致，只是换成了新 API 的 hook 形式：
 *   hook(Executable).intercept(chain -> 返回值即结果，不 proceed 就是替换)
 *
 * 入口在此：META-INF/xposed/java_init.list
 */
class NewEntry : XposedModule() {



    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (TARGET != param.packageName) return

        val cl = param.defaultClassLoader
        Protocol.State.classLoader = cl

        Log.i(TAG, "onPackageLoaded: begin build=" + BuildTag.STAMP)
        installAllHooks(cl)
    }

    /**
     * Vector / libxposed API 102 热重载：重装模块 App 时，框架会向正在运行的宿主
     * 进程提议换一代代码，不用杀宿主。返回 true 表示接受。
     *
     * 注意：能热重载的前提是 module.prop 里有 autoHotReload=true，
     * 且模块只声明一个 Java 入口类（我们是 NewEntry）。
     */
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        Log.i(TAG, "onHotReloading: build=" + BuildTag.STAMP + " -> 准备换到新一代代码")
        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        Log.i(TAG, "onHotReloaded: 新一代代码已生效 build=" + BuildTag.STAMP)
        // 关键：框架不会重放 package 生命周期回调（官方文档明确说明），
        // 默认实现只是把所有旧钩子卸掉。所以必须在这里自己重新装一遍，
        // 否则热重载后模块等于"活着但什么都没干"。
        try {
            val cl = Protocol.State.classLoader
            if (cl == null) {
                Log.w(TAG, "onHotReloaded: classLoader 为空，跳过重装钩子")
                return
            }
            installAllHooks(cl)
            Log.i(TAG, "onHotReloaded: 钩子已全部重装")
        } catch (t: Throwable) {
            Log.e(TAG, "onHotReloaded: 重装钩子失败", t)
        }
    }

    private fun installAllHooks(cl: ClassLoader) {
        Log.i(TAG, "installAllHooks begin")
        // 热重载会重装一遍，先清掉旧计数，避免 entry/hit 叠加成双倍
        HookState.reset()
        // 版本门禁只做提示，不阻断（fail-open）
        VersionGate.check(Protocol.State.app)
        // 进程探测必须最先跑：它决定后面哪些钩子该装（主进程 / :channel 需求完全不同）
        try {
            ChannelProbe.install(this, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "ChannelProbe.install failed: $t")
        }
        resolveProvider(cl)
        // 广告拦截（开屏/摇一摇/素材下载）统一交给 AdBlocker
        try {
            AdBlocker.install(this, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "AdBlocker.install failed: $t")
        }
        // 后台静默升级弹窗（用户主动检查放行）
        try {
            UpgradeBlocker.install(this, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "UpgradeBlocker.install failed: $t")
        }
        // 埋点 / 设备指纹上传断源（默认关，开关在面板「通用」）
        try {
            TrackerBlocker.install(this, cl)
        } catch (t: Throwable) {
            Log.w(TAG, "TrackerBlocker.install failed: $t")
        }
        hookProvider(cl)
        hookMainUi(cl)
        hookActivityResume(cl)
        TempCapture.install(this, cl)
        MtopHooks.hookRequestTap(this, cl)
        // 最终请求（真实 HTTP 动词 + 拼好的 header + url query）只能在这一层拿到
        MtopHooks.hookFinalRequest(this, cl)
        hookResponseFilter(cl)
        KingkongHook.install(this, cl)
        SearchAdHook.install(this, cl)
        HomeTabHook.install(this, cl)
        hookSearchActivity(cl)
        MtopHooks.hookMtopInstance(this, cl)
        MtopHooks.hookMtopInit(this, cl)
    }

    // ---------------------------------------------------------------- provider

    /** 通过 PackageManager 反查 provider 的类名，闲鱼改类名也能跟上 */
    private fun resolveProvider(cl: ClassLoader) {
        try {
            val ctx = currentApp() ?: return
            val pi = ctx.packageManager.getPackageInfo(
                ctx.packageName,
                PackageManager.GET_PROVIDERS,
            )
            val providers = pi.providers ?: return
            for (p in providers) {
                if (p.authority != null && p.authority.contains(PROVIDER_AUTHORITY)) {
                    providerClass = p.name
                    Log.i(TAG, "provider resolved: $providerClass")
                    return
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "resolveProvider failed, keep fallback: $t")
        }
    }

    private fun hookProvider(cl: ClassLoader) {
        try {
            val cls = Class.forName(providerClass, false, cl)
            val m = cls.getDeclaredMethod(
                "query",
                Uri::class.java,
                Array<String>::class.java,
                String::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            hook(m).setId("xysidecar-query")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val sel = chain.getArg(2)
                    if (sel !is String) return chain.proceed()
                    val selection = sel

                    if (selection.startsWith("xysidecar:restart")) {
                        val rc = MatrixCursor(arrayOf("k", "v"))
                        try {
                            var cx = Protocol.State.app
                            if (cx == null) {
                                val a0 = chain.getArg(0)
                                if (a0 is Context) cx = a0
                            }
                            RestartHelper.triggerRestart(cx)
                            rc.addRow(arrayOf<Any>("restart", "scheduled pid=" + android.os.Process.myPid()))
                        } catch (t: Throwable) {
                            rc.addRow(arrayOf<Any>("error", t.toString()))
                        }
                        return rc
                    }
                    if (selection.startsWith(Protocol.API)) return ProviderHandlers.apiCursor(selection)
                    if (selection.startsWith(Protocol.CACHE)) return ProviderHandlers.cacheCursor(selection)
                    if (selection.startsWith(Protocol.LOGS)) {
                        val lc = MatrixCursor(arrayOf(Protocol.COL_KEY, Protocol.COL_VALUE))
                        var li = 0
                        synchronized(Protocol.State.LOGS) {
                            for (t in Protocol.State.LOGS) {
                                lc.addRow(arrayOf<Any>("l" + li++, t))
                            }
                        }
                        return lc
                    }
                    if (selection.startsWith("xysidecar:hot")) {
                        val hc = MatrixCursor(arrayOf("k", "v"))
                        try {
                            val out = if (selection.contains("reload")) {
                                HotReloadProbe.reload()
                            } else {
                                HotReloadProbe.describe()
                            }
                            var i = 0
                            for (line in out.split("\n")) {
                                hc.addRow(arrayOf<Any>("h" + i++, line))
                            }
                        } catch (e: Throwable) {
                            hc.addRow(arrayOf<Any>("error", e.toString()))
                        }
                        return hc
                    }
                    if (selection.startsWith("xysidecar:run")) {
                        return ProviderHandlers.runCursor(Protocol.State.app)
                    }
                    if (selection.startsWith("xysidecar:build")) {
                        val bc = MatrixCursor(arrayOf("k", "v"))
                        bc.addRow(arrayOf<Any>("build", BuildTag.STAMP))
                        bc.addRow(arrayOf<Any>("apk", BuildTag.apkPath()))
                        return bc
                    }
                    if (selection.startsWith(Protocol.DIAG)) {
                        val c = MatrixCursor(arrayOf("k", "v"))
                        c.addRow(arrayOf<Any>("providerHooked", Protocol.State.providerHooked.toString()))
                        // 库体积：文件大小 vs 空闲页。空闲页占比高说明需要回收
                        // （DROP 重建 / 裁剪后没 vacuum），不是数据真的变多了。
                        try {
                            val d = TrafficStore.openForRead()
                            c.addRow(
                                arrayOf<Any>(
                                    "trafficDb",
                                    if (d == null) "未就绪" else TrafficStore.fileStat(d),
                                ),
                            )
                        } catch (ignored: Throwable) {
                        }
                        c.addRow(arrayOf<Any>("instanceHooks", Protocol.State.instanceHookCount.toString()))
                        c.addRow(arrayOf<Any>("initFired", Protocol.State.initFired.toString()))
                        c.addRow(arrayOf<Any>("captures", Protocol.State.captures.toString()))
                        c.addRow(arrayOf<Any>("tapHooks", Protocol.State.tapHooks.toString()))
                        c.addRow(arrayOf<Any>("finalTaps", Protocol.State.finalTapCount.toString()))
                        c.addRow(arrayOf<Any>("haveInstance", (Protocol.State.mtop != null).toString()))
                        c.addRow(arrayOf<Any>("haveContext", (Protocol.State.app != null).toString()))
                        c.addRow(arrayOf<Any>("capSearchAd", Protocol.State.capSearchAd.toString()))
                        // 面板「部分功能未适配」依据这 5 个标志，这里直接暴露便于排查
                        c.addRow(arrayOf<Any>("capSplash", Protocol.State.capSplash.toString()))
                        c.addRow(arrayOf<Any>("capFilter", Protocol.State.capFilter.toString()))
                        c.addRow(arrayOf<Any>("capUi", Protocol.State.capUi.toString()))
                        c.addRow(arrayOf<Any>("capTap", Protocol.State.capTap.toString()))
                        c.addRow(arrayOf<Any>("capMtop", Protocol.State.capMtop.toString()))
                        // 版本门禁：只做提示，不阻断 hook。
                        // 这里补一次检查 —— installAllHooks 跑时 app 还没 attach，
                        // 那时 check() 拿不到 context，targetVersion 会是空的。
                        try {
                            VersionGate.ensureChecked()
                        } catch (ignored: Throwable) {
                        }
                        c.addRow(arrayOf<Any>("targetVersion", VersionGate.currentVersion))
                        c.addRow(arrayOf<Any>("versionOk", VersionGate.supported.toString()))
                        // 挂载点健康度：每个功能一行 reg/hit/entry，含 CRITICAL 标记。
                        // 这是「体检」的运行时版本 —— 不依赖 mt / dexsql，直接问宿主进程
                        // 自己装上了几个钩子、被调用了几次。
                        try {
                            val lines = HookState.report()
                            var hi = 0
                            for (ln in lines) {
                                c.addRow(arrayOf<Any>("hook:" + hi++, ln))
                            }
                            val broken = HookState.summary()
                            c.addRow(arrayOf<Any>("hookBroken", if (broken.length == 0) "无" else broken))
                        } catch (ignored: Throwable) {
                        }
                        c.addRow(arrayOf<Any>("probe", chain.getArg(0).toString()))
                        var i = 0
                        synchronized(Protocol.State.tap) {
                            for (t in Protocol.State.tap) {
                                c.addRow(arrayOf<Any>("tap" + i++, t))
                            }
                        }
                        return c
                    }
                    if (selection.startsWith(Protocol.TRAFFIC)) return ProviderHandlers.trafficCursor(selection)
                    if (selection.startsWith(Protocol.TREE)) return ProviderHandlers.treeCursor(selection)
                    if (selection.startsWith(Protocol.EXPORT)) {
                        return ProviderHandlers.exportCursor(Protocol.State.app, selection)
                    }
                    if (selection.startsWith(Protocol.ANALYZE)) {
                        return ProviderHandlers.analyzeCursor(Protocol.State.app)
                    }
                    // REPLAY 必须排在 REL 之前：xysidecar:replay 也 startsWith("xysidecar:rel")
                    if (selection.startsWith(Protocol.REPLAY)) {
                        return ProviderHandlers.replayCursor(selection)
                    }
                    if (selection.startsWith(Protocol.REL)) {
                        return ProviderHandlers.relCursor(selection)
                    }
                    if (selection.startsWith(Protocol.IMPORT)) {
                        return ProviderHandlers.importCursor(Protocol.State.app, selection)
                    }
                    if (selection.startsWith(Protocol.DOC)) {
                        return ProviderHandlers.docCursor(Protocol.State.app, selection)
                    }
                    // 业务链：面板「自动」按钮背后的同名链，供无头验证/脚本调用
                    if (selection.startsWith(Protocol.CHAIN)) {
                        return ProviderHandlers.chainCursor(Protocol.State.app, selection)
                    }
                    // 自动任务调度器：状态 / 立即跑一轮 / 改间隔
                    if (selection.startsWith(Protocol.AUTO)) {
                        return ProviderHandlers.autoCursor(Protocol.State.app, selection)
                    }
                    if (selection == "xysidecar:searchad") return SearchAdHook.cursor()
                    if (selection == "xysidecar:hometab" || selection.startsWith("xysidecar:hometab:")) return HomeTabOrder.cursor(Protocol.State.app, selection)
                    if (selection == "xysidecar:kingkong" || selection.startsWith("xysidecar:kingkong:")) {
                        return KingkongOrder.cursor(Protocol.State.app, selection)
                    }
                    if (selection.startsWith(Protocol.PREFS) ||
                        selection.startsWith(Protocol.BODIES)
                    ) {
                        var pc: Context? = null
                        try {
                            val c0 = Reflect.call(chain.getThisObject()!!, "getContext")
                            if (c0 is Context) pc = c0
                        } catch (ignored: Throwable) {
                        }
                        if (selection.startsWith(Protocol.PREFS)) return ProviderHandlers.prefsCursor(pc, selection)
                        return ProviderHandlers.bodiesCursor()
                    }
                    // channel socket 通道：xysidecar:ch:<ping|status|reap|api...>
                    if (selection.startsWith(Protocol.CH)) {
                        val a3 = chain.getArg(3)
                        val chArgs = if (a3 is Array<*>) a3 as Array<Any?> else null
                        return ProviderHandlers.channelCursor(selection, chArgs)
                    }
                    if (!selection.startsWith(Protocol.SELECTION)) return chain.proceed()

                    val a3 = chain.getArg(3)
                    val args = if (a3 is Array<*>) a3 as Array<Any?> else null
                    var ctx: Context? = null
                    try {
                        val c = Reflect.call(chain.getThisObject()!!, "getContext")
                        if (c is Context) ctx = c
                    } catch (ignored: Throwable) {
                    }
                    return ProviderHandlers.dispatch(ctx, selection, args)
                }
            })
            Protocol.State.providerHooked = true
            Protocol.State.capMtop = true
            Log.i(TAG, "provider hooked (api102)")
        } catch (t: Throwable) {
            Log.e(TAG, "provider hook failed: $t")
        }
    }

    // ---------------------------------------------------------------- UI hooks

    /** 兜底：任何 Activity 恢复时都尝试挂入口（框架方法，只在本进程生效） */
    private fun hookActivityResume(cl: ClassLoader) {
        try {
            val at = Class.forName("android.app.ActivityThread", false, cl)
            var n = 0
            for (m in at.declaredMethods) {
                if ("handleResumeActivity" != m.name) continue
                try {
                    hook(m).setId("xysidecar-resume-$n")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val r = chain.proceed()
                            try {
                                val rec = chain.getArg(0)
                                val act = Reflect.field(rec!!, "activity")
                                if (act is Activity) {
                                    UiInjector.attach(act)
                                    // 回到前台也检查一次（去抖与模式判断在 AutoRunner 内）
                                    AutoRunner.onForeground(act.applicationContext)
                                }
                            } catch (ignored: Throwable) {
                            }
                            return r
                        }
                    })
                    n++
                } catch (ignored: Throwable) {
                }
            }
            Log.i(TAG, "resume hooks: $n")
        } catch (t: Throwable) {
            Log.w(TAG, "hookActivityResume failed: $t")
        }
    }

    private fun hookMainUi(cl: ClassLoader) {
        var main = "com.taobao.idlefish.maincontainer.activity.MainActivity"
        try {
            val c = currentApp()
            if (c != null) {
                val li = c.packageManager.getLaunchIntentForPackage(c.packageName)
                if (li != null && li.component != null) {
                    main = li.component!!.className
                }
            }
        } catch (ignored: Throwable) {
        }
        try {
            val cls = Class.forName(main, false, cl)
            var target: Method? = null
            // 只在 App 自己的类层级里找 onResume，别去 hook android.app.Activity
            var c: Class<*>? = cls
            while (c != null && !c.name.startsWith("android.")) {
                try {
                    val m = c.getDeclaredMethod("onResume")
                    m.isAccessible = true
                    target = m
                    break
                } catch (ignored: NoSuchMethodException) {
                }
                c = c.superclass
            }
            if (target == null) {
                Log.w(TAG, "no onResume in $main")
                return
            }
            hook(target).setId("xysidecar-ui").intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    val self = chain.getThisObject()
                    if (self is Activity) {
                        UiInjector.attach(self)
                        AutoRunner.onForeground(self.applicationContext)
                    }
                    return r
                }
            })
            Protocol.State.capUi = true
            Log.i(TAG, "ui hook installed on $main")
        } catch (t: Throwable) {
            Log.w(TAG, "ui hook failed ($main): $t")
        }
    }

    /**
     * 搜索页（Flutter Activity）单独挂 UI 钩子。
     *
     * 实测：ActivityThread.handleResumeActivity 钩子对 SearchResultActivity
     * 不生效（Flutter 走 FlutterActivityAndFragmentDelegate，路径不同），
     * 导致 UiInjector.attach() 从未被调用 —— 搜索页广告隐藏也就无从谈起。
     * 这里直接钩它自己的 onResume。
     */
    private fun hookSearchActivity(cl: ClassLoader) {
        try {
            val cls = Class.forName(
                "com.taobao.idlefish.search_implement.SearchResultActivity",
                false,
                cl,
            )
            var target: Method? = null
            var c: Class<*>? = cls
            while (c != null && !c.name.startsWith("android.")) {
                try {
                    val m = c.getDeclaredMethod("onResume")
                    m.isAccessible = true
                    target = m
                    break
                } catch (ignored: NoSuchMethodException) {
                }
                c = c.superclass
            }
            if (target == null) {
                Log.w(TAG, "SearchResultActivity.onResume not found")
                return
            }
            hook(target).setId("xysidecar-search-resume").intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val r = chain.proceed()
                    try {
                        val self = chain.getThisObject()
                        if (self is Activity) {
                            Log.i(TAG, "search page attach")
                            UiInjector.attach(self)
                        }
                    } catch (ignored: Throwable) {
                    }
                    return r
                }
            })
            Log.i(TAG, "hookSearchActivity installed")
        } catch (t: Throwable) {
            Log.w(TAG, "hookSearchActivity failed: $t")
        }
    }

    /** 响应过滤：挂 MtopResponse.getDataJsonObject()，在宿主读数据前加工 */
    private fun hookResponseFilter(cl: ClassLoader) {
        try {
            val cls = Class.forName("mtopsdk.mtop.domain.MtopResponse", false, cl)
            var n = 0
            // 只挂 getDataJsonObject。
            // 之前为了"搜索页读不到过滤结果"把 getData()/getOriginFastJsonObject() 也挂了，
            // 结果每次调用都要把 180KB JSON 解析+重序列化，主线程被拖死，
            // 表现为"第一次搜索卡住、输入丢失"（严重回归）。已回滚。
            for (m in cls.declaredMethods) {
                if ("getDataJsonObject" != m.name || m.parameterCount != 0) continue
                hook(m).setId("xysidecar-filter")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val r = chain.proceed()
                        if (r != null) {
                            Log.i(TAG, "GETOBJ id=" + System.identityHashCode(r))
                            try {
                                ResponseFilter.apply(chain.getThisObject(), r)
                            } catch (ignored: Throwable) {
                            }
                        }
                        return r
                    }
                })
                n++
            }
            Protocol.State.capFilter = n > 0
            Protocol.State.logLine(if (n > 0) "数据过滤已启用" else "数据过滤不可用")
            Log.i(TAG, "response filter hooks: $n")
        } catch (t: Throwable) {
            Log.w(TAG, "response filter hook failed: $t")
        }
    }

    companion object {
        private val TAG = MtopBridge.TAG
        private const val TARGET = "com.taobao.idlefish"
        private const val PROVIDER_FALLBACK =
            "com.taobao.idlefish.localization.LocalizationProvider"
        private const val PROVIDER_AUTHORITY = "com.taobao.idlefish.localization.provider"
        private const val LAUNCHER = "com.taobao.android.remoteobject.easy.MtopLauncher"

        private var providerClass = PROVIDER_FALLBACK

        private fun currentApp(): Context? {
            val c = Protocol.State.app
            if (c != null) return c
            try {
                val at = Class.forName("android.app.ActivityThread")
                val app = at.getMethod("currentApplication").invoke(null)
                if (app is Context) {
                    Protocol.State.app = app
                    return app
                }
            } catch (ignored: Throwable) {
            }
            return null
        }
    }
}
