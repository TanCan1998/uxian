package com.xiandehuang.u

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 统一的钩子安装器。
 *
 * 解决三个此前散落各处的隐患：
 *
 * 1. **异常模式**。libxposed 的 `ExceptionMode` 有 DEFAULT / PROTECTIVE /
 *    PASSTHROUGH 三档。本项目此前一处都没显式设置，靠 module.prop 的全局配置
 *    兜底。显式设 PROTECTIVE 后，钩子体内任何未捕获异常都会被框架捕获、记日志、
 *    然后**当作没这个钩子继续执行** —— 宿主不会因为我们写错一行代码而闪退。
 *
 * 2. **健康度计数**。每个挂载点装上/装不上都进 [HookState]，
 *    面板和 diag 才能区分「钩子在但没走到」和「钩子根本没装上」。
 *
 * 3. **框架类黑名单**。hook android.* / java.* 这类 bootclasspath 类会被框架拒绝
 *    甚至影响全局（比如 hook SensorManager.registerListener 会波及所有 App 内功能）。
 *    在这里统一拦掉，写错就 fail-open 跳过。
 *
 * ⚠️ R8 雷区：不用 lambda 之外的 Kotlin 扩展写法，不用 Regex / ?.let{}。
 */
object HookKit {

    private const val TAG = "xysidecar"

    /** 拒绝 hook 的包前缀（bootclasspath / 框架类） */
    private val BLOCKED = arrayOf(
        "android.", "java.", "javax.", "dalvik.", "androidx.",
        "kotlin.", "kotlinx.", "com.android.internal.",
    )

    private fun isBlocked(cls: String): Boolean {
        for (p in BLOCKED) {
            if (cls.startsWith(p)) return true
        }
        return false
    }

    /**
     * 装一个钩子。失败返回 false，绝不向外抛。
     *
     * @param feature 功能名（进 HookState 分组），例如 "splash" / "upgrade"
     * @param label   挂载点标签，例如 "loadSplashAd"
     */
    @JvmStatic
    fun install(
        module: XposedModule,
        feature: String,
        label: String,
        m: Method?,
        hooker: XposedInterface.Hooker,
    ): Boolean {
        if (m == null) {
            HookState.failed(feature, label, "方法不存在")
            return false
        }
        val cls = m.declaringClass.name
        if (isBlocked(cls)) {
            HookState.failed(feature, label, "框架类黑名单 $cls")
            return false
        }
        try {
            module.hook(m)
                .setId("xysidecar-" + feature + "-" + label)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker)
            HookState.installed(feature, label)
            Log.i(TAG, "HookKit: $feature/$label 已装 ($cls#${m.name})")
            return true
        } catch (t: Throwable) {
            HookState.failed(feature, label, "hook 抛错 " + (t.message ?: t.javaClass.simpleName))
            return false
        }
    }

    /**
     * 在类里按方法名找方法（不看签名）。返回第一个匹配。
     *
     * 闲鱼是 R8 混淆过的，方法签名经常随版本变；只按名字找、再在钩子里做
     * 类型判断，比写死完整签名更耐版本变化。
     */
    @JvmStatic
    fun findByName(cls: Class<*>, name: String): Method? {
        try {
            for (m in cls.declaredMethods) {
                if (m.name == name) return m
            }
        } catch (ignored: Throwable) {
        }
        return null
    }

    /** 按方法名找全部重载 */
    @JvmStatic
    fun findAllByName(cls: Class<*>, name: String): List<Method> {
        val out = ArrayList<Method>()
        try {
            for (m in cls.declaredMethods) {
                if (m.name == name) out.add(m)
            }
        } catch (ignored: Throwable) {
        }
        return out
    }

    /** 安全加载类，失败返回 null */
    @JvmStatic
    fun loadClass(name: String, cl: ClassLoader): Class<*>? =
        try {
            Class.forName(name, false, cl)
        } catch (t: Throwable) {
            null
        }

    // ---------------------------------------------------------------- 常用钩子工厂

    /**
     * void 方法：不 proceed 即原方法体不执行。
     * 钩子体内部再包一层 try/catch —— 双保险，PROTECTIVE 是框架层兜底。
     */
    @JvmStatic
    fun skipVoid(
        feature: String,
        label: String,
        enabled: () -> Boolean,
    ): XposedInterface.Hooker = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            try {
                HookState.entry(feature)
                if (!enabled()) return chain.proceed()
                val n = HookState.hit(feature)
                if (n <= 3 || n % 100 == 0L) {
                    Log.i(TAG, "$feature hit $label（第 $n 次）")
                }
                return null
            } catch (t: Throwable) {
                Log.w(TAG, "$feature intercept 异常 $label: $t")
                return chain.proceed()
            }
        }
    }

    /** 布尔方法：返回固定值 */
    @JvmStatic
    fun constBool(
        feature: String,
        label: String,
        value: Boolean,
        enabled: () -> Boolean,
    ): XposedInterface.Hooker = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            try {
                HookState.entry(feature)
                if (!enabled()) return chain.proceed()
                HookState.hit(feature)
                return java.lang.Boolean.valueOf(value)
            } catch (t: Throwable) {
                Log.w(TAG, "$feature intercept 异常 $label: $t")
                return chain.proceed()
            }
        }
    }

    /** 整数方法：返回固定值 */
    @JvmStatic
    fun constInt(
        feature: String,
        label: String,
        value: Int,
        enabled: () -> Boolean,
    ): XposedInterface.Hooker = object : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            try {
                HookState.entry(feature)
                if (!enabled()) return chain.proceed()
                HookState.hit(feature)
                return java.lang.Integer.valueOf(value)
            } catch (t: Throwable) {
                Log.w(TAG, "$feature intercept 异常 $label: $t")
                return chain.proceed()
            }
        }
    }
}
