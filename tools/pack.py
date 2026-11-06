#!/usr/bin/env python3
"""打包 APK：resources.arsc 必须「不压缩 + 4 字节对齐」（targetSdk 30+ 硬性要求）。

没有 zipalign（Google 只发 x86_64），这里用 zipfile 自己控制：
在 local header 的 extra 字段里补 padding，使数据起始偏移对上 4 字节。
"""
import struct
import sys
import zipfile

ALIGN = 4
ALIGNED = "resources.arsc"
STORED = {"resources.arsc"}


def add(zf, name, data, compress=True):
    zi = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    zi.compress_type = zipfile.ZIP_DEFLATED if compress else zipfile.ZIP_STORED
    zi.external_attr = 0o644 << 16

    if name in STORED:
        # 计算 extra 需要多长：30(固定头) + 文件名 + extra 之后才是数据
        offset = zf.fp.tell() + 30 + len(name.encode())
        pad = (ALIGN - offset % ALIGN) % ALIGN
        if pad:
            if pad < 4:
                pad += ALIGN
            # 0xd935 是 zip 里常用的对齐占位 id
            zi.extra = struct.pack("<HH", 0xD935, pad - 4) + b"\x00" * (pad - 4)

    zf.writestr(zi, data)


def main(out, files):
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
        for path, arcname in files:
            name = arcname or path.split("/")[-1]
            with open(path, "rb") as f:
                data = f.read()
            add(zf, name, data, compress=(name not in STORED))

    # 自检：确认 resources.arsc 的数据偏移是 4 的倍数、且是 stored
    with zipfile.ZipFile(out) as zf:
        for zi in zf.infolist():
            if zi.filename == ALIGNED:
                with open(out, "rb") as f:
                    f.seek(zi.header_offset + 26)
                    nlen, elen = struct.unpack("<HH", f.read(4))
                    data_off = zi.header_offset + 30 + nlen + elen
                print("  %s: stored=%s data_offset=%d align=%d"
                      % (zi.filename, zi.compress_type == zipfile.ZIP_STORED,
                         data_off, data_off % ALIGN))


if __name__ == "__main__":
    # 用法: pack.py out.apk name1=path1 name2=path2 ...
    out = sys.argv[1]
    pairs = []
    for arg in sys.argv[2:]:
        name, _, path = arg.partition("=")
        pairs.append((path, name))
    main(out, pairs)
