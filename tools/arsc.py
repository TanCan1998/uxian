#!/usr/bin/env python3
"""手写最小 resources.arsc。

包含两类资源：
  type 1 = array  → xposed_scope（模块作用域，0x7f010000）
  type 2 = style  → UxianRestart（中转页主题，0x7f020000）
                    只覆盖 Splash 背景/图标与窗口底色，parent 指向系统全屏主题

结构：
RES_TABLE(0x0002)
 ├─ 全局字符串池 (字符串资源值)
 └─ RES_TABLE_PACKAGE(0x0200) id=0x7f
     ├─ typeStrings 池 ["array", "style"]
     ├─ keyStrings 池 ["xposed_scope", "UxianRestart"]
     ├─ RES_TABLE_TYPE_SPEC / RES_TABLE_TYPE  × 2
"""
import struct
import sys

import axml

RES_TABLE_TYPE = 0x0002
RES_TABLE_PACKAGE_TYPE = 0x0200
RES_TABLE_TYPE_TYPE = 0x0201
RES_TABLE_TYPE_SPEC_TYPE = 0x0202
RES_TABLE_ENTRY_FLAG_COMPLEX = 0x0001
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_COLOR_ARGB8 = 0x1C

PACKAGE_ID = 0x7F
ARRAY_TYPE_ID = 1
STYLE_TYPE_ID = 2

# 系统资源
THEME_FULLSCREEN = 0x0103012A          # Theme.DeviceDefault.NoActionBar.Fullscreen
COLOR_TRANSPARENT = 0x0106000D         # @android:color/transparent

# android:attr/*
ATTR_WINDOW_BACKGROUND = 0x01010054
ATTR_WINDOW_NO_TITLE = 0x01010056
ATTR_WINDOW_FULLSCREEN = 0x0101020D
ATTR_WINDOW_ANIM = 0x010100AE
ATTR_SPLASH_BG = 0x0101062C
ATTR_SPLASH_ICON = 0x0101062D
ATTR_SPLASH_DURATION = 0x0101062E
ATTR_WINDOW_DISABLE_PREVIEW = 0x01010222
ATTR_WINDOW_IS_TRANSLUCENT = 0x01010058


def u16(v):
    return struct.pack("<H", v)


def u32(v):
    return struct.pack("<I", v)


def chunk(t, header_size, body):
    return u16(t) + u16(header_size) + u32(8 + len(body)) + body


def string_pool(strings):
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
    body = (u32(len(strings)) + u32(0) + u32(0) + u32(strings_start) + u32(0)
            + offsets + data)
    return chunk(axml.RES_STRING_POOL_TYPE, 28, body)


def default_config():
    return u32(28) + b"\x00" * 24


def type_spec(type_id, entry_count):
    body = struct.pack("<BBH", type_id, 0, 0) + u32(entry_count)
    body += b"".join(u32(0) for _ in range(entry_count))
    return chunk(RES_TABLE_TYPE_SPEC_TYPE, 16, body)


def type_chunk(type_id, entry_count, entries):
    """entries: list of bytes（每个条目已序列化，允许 None 表示空洞）"""
    offsets = b""
    data = b""
    for e in entries:
        if e is None:
            offsets += u32(0xFFFFFFFF)
        else:
            offsets += u32(len(data))
            data += e
    cfg = default_config()
    header_size = 8 + 1 + 1 + 2 + 4 + 4 + len(cfg)
    entries_start = header_size + len(offsets)
    body = (struct.pack("<BBH", type_id, 0, 0) + u32(entry_count)
            + u32(entries_start) + cfg + offsets + data)
    return chunk(RES_TABLE_TYPE_TYPE, header_size, body)


def array_entry(key_index, global_pool_indices):
    entry = u16(16) + u16(RES_TABLE_ENTRY_FLAG_COMPLEX) + u32(key_index)
    entry += u32(0)
    entry += u32(len(global_pool_indices))
    for i, gi in enumerate(global_pool_indices):
        entry += u32(0x01000000 + i)
        entry += u16(8) + struct.pack("<BB", 0, TYPE_STRING) + u32(gi)
    return entry


def style_entry(key_index, parent, maps):
    """maps: [(attr_res_id, dataType, data)]"""
    entry = u16(16) + u16(RES_TABLE_ENTRY_FLAG_COMPLEX) + u32(key_index)
    entry += u32(parent)                 # parent = 系统全屏主题
    entry += u32(len(maps))
    for attr, dt, data in maps:
        entry += u32(attr)
        entry += u16(8) + struct.pack("<BB", 0, dt) + u32(data)
    return entry


def package_chunk(package_name, arrays, styles):
    type_strings = string_pool(["array", "style", "drawable"])
    keys = [k for k, _ in arrays] + [s[0] for s in styles] + ["ic_launcher"]
    key_strings = string_pool(keys)

    header_size = 288
    name = package_name.encode("utf-16-le")
    name = name + b"\x00" * (256 - len(name))

    type_strings_off = header_size
    key_strings_off = header_size + len(type_strings)

    body = u32(PACKAGE_ID) + name
    body += u32(type_strings_off) + u32(3) + u32(key_strings_off) + u32(len(keys))
    body += u32(0)
    body += type_strings + key_strings

    # type 1: array
    body += type_spec(ARRAY_TYPE_ID, len(arrays))
    body += type_chunk(ARRAY_TYPE_ID, len(arrays),
                       [array_entry(i, idxs) for i, (_, idxs) in enumerate(arrays)])

    # type 2: style
    body += type_spec(STYLE_TYPE_ID, len(styles))
    body += type_chunk(STYLE_TYPE_ID, len(styles),
                       [style_entry(len(arrays) + i, parent, maps)
                        for i, (_, parent, maps) in enumerate(styles)])
    # type 3: drawable, global string index 1 is the packaged PNG path.
    body += type_spec(3, 1)
    icon_entry = u16(8) + u16(0) + u32(len(arrays) + len(styles))
    icon_entry += u16(8) + struct.pack("<BB", 0, TYPE_STRING) + u32(1)
    body += type_chunk(3, 1, [icon_entry])
    return chunk(RES_TABLE_PACKAGE_TYPE, header_size, body)


def build(package_name="com.xiandehuang.u", array_name="xposed_scope",
          values=("com.taobao.idlefish",)):
    global_pool = string_pool(list(values) + ["res/drawable-nodpi/ic_launcher.png"])
    # 中转页主题：Splash 底色与页面一致、图标透明、时长 0，做到冷启动无缝
    restart_style = ("UxianRestart", THEME_FULLSCREEN, [
        # Splash 底与中转页底色完全一致：系统强制建这个窗口，
        # 同色后视觉上无缝，不会看到「先黑一下/两个页面」。
        (ATTR_SPLASH_BG, TYPE_INT_COLOR_ARGB8, 0xFF1A1D22),
        (ATTR_SPLASH_ICON, 0x01, COLOR_TRANSPARENT),
        (ATTR_SPLASH_DURATION, TYPE_INT_DEC, 0),
        (ATTR_WINDOW_BACKGROUND, TYPE_INT_COLOR_ARGB8, 0xFF1A1D22),
        # 不让系统画启动预览窗口（Splash），这是「两个页面」的直接来源
        (ATTR_WINDOW_DISABLE_PREVIEW, TYPE_INT_DEC, 1),
    ])
    pkg = package_chunk(
        package_name,
        [(array_name, [0] * len(values))],
        [restart_style],
    )
    return chunk(RES_TABLE_TYPE, 12, u32(1) + global_pool + pkg)


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "resources.arsc"
    data = build()
    with open(out, "wb") as f:
        f.write(data)
    print("wrote %s (%d bytes)" % (out, len(data)))
