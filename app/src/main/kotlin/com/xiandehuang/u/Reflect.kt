package com.xiandehuang.u

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 纯 java.lang.reflect 的极简调用器：新 API 和 legacy 版共用，不依赖 XposedHelpers */
object Reflect {

    @JvmStatic
    fun findClass(cl: ClassLoader?, name: String): Class<*>? =
        try {
            Class.forName(name, false, cl)
        } catch (e: ClassNotFoundException) {
            null
        }

    /** 精确按声明参数类型取方法（避免 build(Object,..) 抢走 build(MtopRequest,..)） */
    @JvmStatic
    fun methodExact(cls: Class<*>, name: String, vararg params: Class<*>): Method {
        var c: Class<*>? = cls
        while (c != null) {
            try {
                return c.getDeclaredMethod(name, *params).also { it.isAccessible = true }
            } catch (ignored: NoSuchMethodException) {
            }
            c = c.superclass
        }
        throw IllegalStateException("method not found: ${cls.name}#$name")
    }

    /** 按参数类型匹配方法（含父类）：先找精确匹配，再退回可赋值匹配 */
    @JvmStatic
    fun method(cls: Class<*>, name: String, isStatic: Boolean, vararg args: Any?): Method {
        tryExact(cls, name, isStatic, args)?.let { return it }

        var c: Class<*>? = cls
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name != name) continue
                if (Modifier.isStatic(m.modifiers) != isStatic) continue
                if (!matches(m.parameterTypes, args)) continue
                m.isAccessible = true
                return m
            }
            c = c.superclass
        }
        throw IllegalStateException(
            "method not found: ${cls.name}#$name(${args.size} args)"
        )
    }

    /** 参数类型与实参精确同型（不做父类容错） */
    private fun tryExact(
        cls: Class<*>,
        name: String,
        isStatic: Boolean,
        args: Array<out Any?>,
    ): Method? {
        var c: Class<*>? = cls
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name != name) continue
                if (Modifier.isStatic(m.modifiers) != isStatic) continue
                val t = m.parameterTypes
                if (t.size != args.size) continue
                var ok = true
                for (i in t.indices) {
                    val a = args[i]
                    if (a == null || box(t[i]) !== a.javaClass) {
                        ok = false
                        break
                    }
                }
                if (ok) {
                    m.isAccessible = true
                    return m
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun matches(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (types.size != args.size) return false
        for (i in types.indices) {
            val a = args[i]
            if (a == null) {
                if (types[i].isPrimitive) return false
                continue
            }
            if (!box(types[i]).isInstance(a)) return false
        }
        return true
    }

    private fun box(c: Class<*>): Class<*> = when {
        !c.isPrimitive -> c
        c == Int::class.javaPrimitiveType -> Integer::class.java
        c == Long::class.javaPrimitiveType -> java.lang.Long::class.java
        c == Boolean::class.javaPrimitiveType -> java.lang.Boolean::class.java
        c == Byte::class.javaPrimitiveType -> java.lang.Byte::class.java
        c == Char::class.javaPrimitiveType -> Character::class.java
        c == Short::class.javaPrimitiveType -> java.lang.Short::class.java
        c == Float::class.javaPrimitiveType -> java.lang.Float::class.java
        c == Double::class.javaPrimitiveType -> java.lang.Double::class.java
        else -> c
    }

    @JvmStatic
    @Throws(Exception::class)
    fun call(target: Any, name: String, vararg args: Any?): Any? =
        method(target.javaClass, name, false, *args).invoke(target, *args)

    @JvmStatic
    @Throws(Exception::class)
    fun callStatic(cls: Class<*>, name: String, vararg args: Any?): Any? =
        method(cls, name, true, *args).invoke(null, *args)

    @JvmStatic
    @Throws(Exception::class)
    fun field(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(target)
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw NoSuchFieldException("$name in ${target.javaClass.name}")
    }

    @JvmStatic
    fun staticField(cls: Class<*>, name: String): Any? =
        try {
            cls.getDeclaredField(name).also { it.isAccessible = true }.get(null)
        } catch (t: Throwable) {
            null
        }
}
