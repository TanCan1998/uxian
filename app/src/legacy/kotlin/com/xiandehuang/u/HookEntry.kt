package com.xiandehuang.u

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.net.Uri
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 闲鱼 sidecar（legacy Xposed API 82 入口）。
 *
 * 把 App 自带的 LocalizationProvider 改造成 mtop 入口。
 *
 * 选它（实测）：
 *  1. 可被外部访问（shell content query 有返回）；
 *  2. 调用会同步拉起已 force-stop 的闲鱼进程 —— 无需常驻；
 *  3. 只有 16 个方法，query 是唯一读入口，挂一个就够。
 */
class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (TARGET != lpparam.packageName) return
        Protocol.State.classLoader = lpparam.classLoader
        hookProvider(lpparam)
        hookMtopInit(lpparam)
    }

    private fun hookProvider(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                PROVIDER, lpparam.classLoader, "query",
                Uri::class.java, Array<String>::class.java, String::class.java,
                Array<String>::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val sel = param.args[2]
                        if (sel !is String) return
                        val selection = sel
                        if (!selection.startsWith(Protocol.SELECTION)) return

                        val args = param.args[3] as? Array<*>
                        // ContentProvider 本身不是 Context，要 getContext()
                        var ctx: Context? = null
                        try {
                            val c = XposedHelpers.callMethod(param.thisObject, "getContext")
                            if (c is Context) ctx = c
                        } catch (ignored: Throwable) {
                        }
                        param.result = dispatch(
                            ctx, selection,
                            args?.map { it as Any? }?.toTypedArray(),
                        )
                    }
                },
            )
            XposedBridge.log("[xysidecar] provider hooked")
        } catch (t: Throwable) {
            XposedBridge.log("[xysidecar] provider hook failed: $t")
        }
    }

    private fun hookMtopInit(lpparam: XC_LoadPackage.LoadPackageParam) {
        for (m in arrayOf("initMtopSdk", "firstMtopRequest")) {
            try {
                XposedHelpers.findAndHookMethod(
                    LAUNCHER, lpparam.classLoader, m, Application::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val a = if (param.args.isNotEmpty()) param.args[0] else null
                            Protocol.State.markReady(a as? Context)
                        }
                    },
                )
            } catch (t: Throwable) {
                XposedBridge.log("[xysidecar] init hook ($m) failed: $t")
            }
        }
        // 不要 hook android.app.Application#onCreate —— 框架类，会污染所有进程
    }

    private fun dispatch(
        ctx: Context?,
        selection: String,
        args: Array<Any?>?,
    ): Cursor {
        val context = ctx ?: Protocol.State.app ?: return Protocol.err("no context")

        val req = Protocol.parse(selection, args)
            ?: return Protocol.err("bad request: expect xysidecar:mtop[:base64] + 3 args")

        Protocol.State.awaitReady(12000)

        return try {
            val ac = context.applicationContext
            Protocol.ok(MtopBridge.request(ac ?: context, req[0], req[1], req[2]))
        } catch (t: Throwable) {
            XposedBridge.log("[xysidecar] mtop call failed: $t")
            Protocol.err(t.javaClass.simpleName + ": " + t.message)
        }
    }

    companion object {
        private const val TARGET = "com.taobao.idlefish"
        private const val PROVIDER =
            "com.taobao.idlefish.localization.LocalizationProvider"
        private const val LAUNCHER = "com.taobao.android.remoteobject.easy.MtopLauncher"
    }
}
