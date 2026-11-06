package com.xiandehuang.u

/**
 * 构建标识：用来验证"热更到底生效了没有"。
 *
 * STAMP 由 tools/build.sh 在编译前重写成当前时间戳字面量。
 * 运行时通过 xysidecar:build 读回来 —— 覆盖 base.apk 后重启宿主，
 * 一眼就能看出加载的是新代码还是旧代码，不用靠 UI 现象反推。
 */
object BuildTag {

    /** @BUILD_STAMP@ 由构建脚本替换 */
    @JvmField
    var STAMP: String = "1106-165549"

    /** 当前模块 APK 的实际路径（从宿主视角看） */
    @JvmStatic
    fun apkPath(): String = runCatching {
        Protocol.State.app?.packageManager
            ?.getApplicationInfo("com.xiandehuang.u", 0)?.sourceDir
    }.getOrNull() ?: "?"
}
