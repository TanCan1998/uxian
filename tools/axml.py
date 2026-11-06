#!/usr/bin/env python3
"""极简 Android 二进制 XML (AXML) 编码器 —— 只够编出我们的 manifest。

没有 aapt2 的 aarch64 版，所以这里手写 RES_XML 结构：
  StringPool(UTF-16) + ResourceMap + StartNamespace + Elements + EndNamespace
framework 属性的 resource id 必须写对，否则 PackageParser 的 obtainAttributes 取不到值。
"""
import struct
import sys

RES_XML_TYPE = 0x0003
RES_STRING_POOL_TYPE = 0x0001
RES_XML_RESOURCE_MAP_TYPE = 0x0180
RES_XML_START_NAMESPACE_TYPE = 0x0100
RES_XML_END_NAMESPACE_TYPE = 0x0101
RES_XML_START_ELEMENT_TYPE = 0x0102
RES_XML_END_ELEMENT_TYPE = 0x0103

TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_BOOLEAN = 0x12
TYPE_INT_COLOR_ARGB8 = 0x1C

ANDROID_NS = "http://schemas.android.com/apk/res/android"

# framework 属性 id（frameworks/base/core/res/res/values/public.xml）
ATTR_IDS = {
    "label": 0x01010001,
    "theme": 0x01010000,
    "configChanges": 0x0101001F,
    "windowNoTitle": 0x01010056,
    "windowFullscreen": 0x0101020D,
    "windowAnimationStyle": 0x010100AE,
    "windowActionModeOverlay": 0x010102DD,
    "windowTranslucentStatus": 0x010103EF,
    "windowDrawsSystemBarBackgrounds": 0x01010450,
    "statusBarColor": 0x01010451,
    "navigationBarColor": 0x01010452,
    "windowLayoutInDisplayCutoutMode": 0x01010586,
    "windowSplashScreenBackground": 0x0101062C,
    "windowSplashScreenAnimatedIcon": 0x0101062D,
    "windowSplashScreenAnimationDuration": 0x0101062E,
    "launchMode": 0x0101001D,
    "icon": 0x01010002,
    "name": 0x01010003,
    "exported": 0x01010010,
    "excludeFromRecents": 0x01010017,
    "value": 0x01010024,
    "resource": 0x01010025,
    "minSdkVersion": 0x0101020c,
    "versionCode": 0x0101021b,
    "versionName": 0x0101021c,
    "targetSdkVersion": 0x01010270,
    "allowBackup": 0x01010280,
    "authorities": 0x01010018,
}


class Pool:
    def __init__(self):
        self.items = []
        self.index = {}

    def get(self, s):
        if s not in self.index:
            self.index[s] = len(self.items)
            self.items.append(s)
        return self.index[s]


def u16(v):
    return struct.pack("<H", v)


def u32(v):
    return struct.pack("<I", v)


def chunk(chunk_type, header_size, body):
    # ResChunk_header = u16 type + u16 headerSize + u32 size  (8 字节)
    return u16(chunk_type) + u16(header_size) + u32(8 + len(body)) + body


def string_pool(strings):
    """UTF-16 池。每个字符串：u16 长度（>0x7FFF 时用 0x8000|hi + u32 lo）+ UTF-16 + 0x0000"""
    offsets = b""
    data = b""
    for s in strings:
        offsets += u32(len(data))
        raw = s.encode("utf-16-le")
        n = len(raw) // 2
        if n > 0x7FFF:
            data += u16(0x8000 | (n >> 16)) + u32(n & 0xFFFF)
        else:
            data += u16(n)
        data += raw + b"\x00\x00"
    while len(data) % 4:
        data += b"\x00"

    strings_start = 28 + len(offsets)
    body = u32(len(strings)) + u32(0) + u32(0) + u32(strings_start) + u32(0) + offsets + data
    return chunk(RES_STRING_POOL_TYPE, 28, body)


def resource_map():
    ids = sorted(ATTR_IDS.values())
    body = b"".join(u32(i) for i in ids)
    return chunk(RES_XML_RESOURCE_MAP_TYPE, 8, body)


def start_namespace(prefix_idx, uri_idx):
    body = u32(0xFFFFFFFF) + u32(0xFFFFFFFF) + u32(prefix_idx) + u32(uri_idx)
    return chunk(RES_XML_START_NAMESPACE_TYPE, 16, body)


def end_namespace(prefix_idx, uri_idx):
    body = u32(0xFFFFFFFF) + u32(0xFFFFFFFF) + u32(prefix_idx) + u32(uri_idx)
    return chunk(RES_XML_END_NAMESPACE_TYPE, 16, body)


def start_element(name_idx, attrs):
    """attrs: list of (ns_idx, name_idx, raw_value_or_None, data_type, data)"""
    ext = u32(0xFFFFFFFF) + u32(name_idx)
    ext += u16(0x0014) + u16(0x0014) + u16(len(attrs)) + u16(0) + u16(0) + u16(0)
    body = u32(0xFFFFFFFF) + u32(0xFFFFFFFF) + ext
    for ns_idx, nm_idx, raw_idx, dtype, data in attrs:
        body += u32(ns_idx) + u32(nm_idx) + u32(raw_idx)
        # Res_value = u16 size(8) + u8 res0(0) + u8 dataType + u32 data
        body += u16(8) + struct.pack("<B", 0) + struct.pack("<B", dtype) + u32(data & 0xFFFFFFFF)
    return chunk(RES_XML_START_ELEMENT_TYPE, 16, body)


def end_element(name_idx):
    body = u32(0xFFFFFFFF) + u32(0xFFFFFFFF) + u32(0xFFFFFFFF) + u32(name_idx)
    return chunk(RES_XML_END_ELEMENT_TYPE, 16, body)


class Builder:
    def __init__(self):
        self.pool = Pool()
        # 关键：资源映射表是按「字符串池下标」索引的，
        # 所以所有 attribute 名必须按 resource id 升序排在池子最前面。
        self.attr_order = sorted(ATTR_IDS.items(), key=lambda kv: kv[1])
        for name, _rid in self.attr_order:
            self.pool.get(name)
        self.out = b""
        self.stack = []

    def _attr(self, name, value, is_string=True):
        # 硬约束：属性名必须在 ATTR_IDS 里，否则 resource map 里该位为 0，
        # 系统会把之后所有属性的 id 全部错位（踩过：excludeFromRecents 缺失
        # 导致 android:theme 变成 0x0，主题完全不生效）。
        if name not in ATTR_IDS:
            raise KeyError("axml: 属性 %r 未在 ATTR_IDS 中登记，会破坏 resource map" % name)
        ns = self.pool.get(ANDROID_NS)
        nm = self.pool.get(name)
        if is_string:
            return (ns, nm, self.pool.get(str(value)), TYPE_STRING, self.pool.get(str(value)))
        return (ns, nm, 0xFFFFFFFF, TYPE_INT_BOOLEAN if isinstance(value, bool) else TYPE_INT_DEC,
                1 if value is True else (0 if value is False else int(value)))

    def ref(self, name, res_id):
        """android:xxx=\"@0x7f010000\" 这种资源引用"""
        ns = self.pool.get(ANDROID_NS)
        nm = self.pool.get(name)
        return (ns, nm, 0xFFFFFFFF, TYPE_REFERENCE, res_id)

    def color(self, name, argb):
        """android:xxx=\"#AARRGGBB\" 这种颜色值"""
        ns = self.pool.get(ANDROID_NS)
        nm = self.pool.get(name)
        return (ns, nm, 0xFFFFFFFF, TYPE_INT_COLOR_ARGB8, argb & 0xFFFFFFFF)

    def open(self, tag, attrs=()):
        idx = self.pool.get(tag)
        self.stack.append(idx)
        self.out += start_element(idx, list(attrs))

    def close(self):
        idx = self.stack.pop()
        self.out += end_element(idx)

    def simple(self, tag, attrs=()):
        self.open(tag, attrs)
        self.close()

    def build(self, package):
        # 注意：先把命名空间的前缀/URI 放进池子再序列化，否则索引会落在池外
        pre = self.pool.get("android")
        uri = self.pool.get(ANDROID_NS)
        pool = string_pool(self.pool.items)
        rm = resource_map()
        ns = start_namespace(pre, uri)
        body = pool + rm + ns + self.out + end_namespace(pre, uri)
        return chunk(RES_XML_TYPE, 8, body)


def _release_version():
    import json
    from pathlib import Path
    return json.loads((Path(__file__).resolve().parents[1] / 'release.json').read_text())


def _build_version_code():
    return _release_version()['version_code']


def _build_version_name():
    return _release_version()['version_name']


def manifest(package="com.xiandehuang.u", label="U闲", activity=".ProbeActivity",
             minversion="93", description="U 闲：闲鱼增强模块（跳过开屏、消息过滤、入口面板）"):
    b = Builder()
    b.open("manifest", [
        (0xFFFFFFFF, b.pool.get("package"), b.pool.get(package), TYPE_STRING, b.pool.get(package)),
        # versionCode 必须每次构建递增：Vector 判断"模块是否被重新安装过"
        # 靠的就是它。固定为 1 时重装后框架认为版本没变，不会提议热重载。
        # 用 天+时+分 编码，够单调递增，也方便反查构建时间。
        b._attr("versionCode", _build_version_code(), is_string=False),
        b._attr("versionName", _build_version_name()),
    ])
    b.simple("uses-sdk", [
        b._attr("minSdkVersion", 26, is_string=False),
        b._attr("targetSdkVersion", 34, is_string=False),
    ])
    # targetSdk 30+ 的包可见性：不声明 queries 就看不到别的 App，
    # getApplicationIcon("com.taobao.idlefish") 会抛 NameNotFoundException。
    b.open("queries")
    b.simple("package", [b._attr("name", "com.taobao.idlefish")])
    b.close()
    b.open("application", [
        b._attr("label", label),
        b.ref("icon", 0x7F030000),
        b._attr("allowBackup", False, is_string=False),
        # 同上：manifest 的 theme 读不到，主题在代码里 setTheme
    ])
    b.simple("meta-data", [b._attr("name", "xposedmodule"), b._attr("value", "true")])
    b.simple("meta-data", [b._attr("name", "xposeddescription"), b._attr("value", description)])
    b.simple("meta-data", [b._attr("name", "xposedminversion"), b._attr("value", minversion)])
    b.simple("meta-data", [b._attr("name", "xposedscope"), b.ref("resource", 0x7F010000)])
    b.simple("provider", [
        b._attr("name", "io.github.libxposed.service.XposedProvider"),
        b._attr("authorities", "com.xiandehuang.u.XposedService"),
        b._attr("exported", True, is_string=False),
    ])
    b.simple("activity", [
        b._attr("name", ".RestartActivity"),
        b._attr("exported", True, is_string=False),
        b._attr("excludeFromRecents", True, is_string=False),
        # launchMode=singleInstance：任务栈里永远只有一个中转页实例。
        # 之前重复触发会叠出两个页面。
        b._attr("launchMode", 3, is_string=False),
        # 注意：不要再加 noHistory —— 它在失焦时销毁 Activity，
        # 拉起目标 App 的瞬间正好会失焦，销毁重建就会看起来像「又出现一个」。
        b._attr("configChanges", 0x0FFF, is_string=False),
        # 注意：manifest 的 android:theme 在本项目里系统读不到
        # （实测 ActivityInfo.theme 恒为 0x0），主题改由 RestartActivity.onCreate
        # 里的 setTheme(0x7f020000) 指定，那里实测生效。
        # 这里保留一份只为文档完整，不影响运行。
        # 关掉 Activity 切换动画（进入/退出都 0）
        b.ref("windowAnimationStyle", 0x01030001),
        # 刘海屏也铺满
        b._attr("windowLayoutInDisplayCutoutMode", 1, is_string=False),
        # Android 12+ 强制为每个 Activity 画一帧 Splash Screen，
        # 主题色不同就会先闪一个「深色占位页」，看起来像多了一个中转页。
        # 把它调成和页面底色一致 + 图标透明 + 时长 0，做到无缝。
        b.color("windowSplashScreenBackground", 0xFF1A1D22),
        b.ref("windowSplashScreenAnimatedIcon", 0x0106000D),   # @android:color/transparent
        b._attr("windowSplashScreenAnimationDuration", 0, is_string=False),
    ])
    b.simple("provider", [
        b._attr("name", ".ScopeProbeProvider"),
        b._attr("authorities", "com.xiandehuang.u.scope"),
        b._attr("exported", True, is_string=False),
    ])
    b.close()
    b.close()
    return b.build(package)


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "AndroidManifest.xml"
    minv = sys.argv[2] if len(sys.argv) > 2 else "93"
    data = manifest(minversion=minv)
    with open(out, "wb") as f:
        f.write(data)
    print("wrote %s (%d bytes)" % (out, len(data)))
