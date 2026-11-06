#!/usr/bin/env python3
"""把 app/src/main/assets 下的前端资源内嵌成 Kotlin 常量。

为什么不用 Android 的 assets：
    本工程的 APK 是手搓的 —— resources.arsc 由 tools/arsc.py 生成，
    assets 表根本不存在，AssetManager 读不到任何文件。
    所以模板/脚本/词典一律在编译期转成 Kotlin 字符串常量。

产物: app/src/main/kotlin/com/xiandehuang/u/TrafficAssets.kt

用法: python3 tools/embed_assets.py
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
OUT = os.path.join(ROOT, "app", "src", "main", "kotlin", "com", "xiandehuang", "u",
                   "TrafficAssets.kt")


def kt_string(s):
    """转成 Kotlin 的 raw string，避开所有转义麻烦。

    Kotlin raw string 里 $ 仍是模板插值起点，必须替换成 ${'$'}；
    三个引号连写也会提前结束，要拆开。
    """
    s = s.replace('"""', '"\\"\\"')
    # $ 后面跟标识符或 { 才会插值，保险起见全部转义
    s = s.replace("$", "${'$'}")
    return '"""' + s + '"""'


def main():
    html = open(os.path.join(ASSETS, "traffic.html"), encoding="utf-8").read()
    js = open(os.path.join(ASSETS, "traffic.js"), encoding="utf-8").read()
    doc = open(os.path.join(ASSETS, "api_doc.json"), encoding="utf-8").read()
    probep = os.path.join(ASSETS, "api_probe.json")
    probe = open(probep, encoding="utf-8").read() if os.path.exists(probep) else "{}"
    cdpath = os.path.join(ASSETS, "caller_doc.json")
    caller = open(cdpath, encoding="utf-8").read() if os.path.exists(cdpath) else "{}"

    # 词典压缩成一行，省得在 Kotlin 里带大量缩进空白
    doc = json.dumps(json.loads(doc), ensure_ascii=False, separators=(",", ":"))
    probe = json.dumps(json.loads(probe), ensure_ascii=False, separators=(",", ":"))
    caller = json.dumps(json.loads(caller), ensure_ascii=False, separators=(",", ":"))

    body = []
    body.append("package com.xiandehuang.u")
    body.append("")
    body.append("/**")
    body.append(" * 前端资源常量。**由 tools/embed_assets.py 自动生成，不要手改**。")
    body.append(" *")
    body.append(" * 源文件：app/src/main/assets/{traffic.html, traffic.js, api_doc.json}")
    body.append(" */")
    body.append("object TrafficAssets {")
    body.append("")
    body.append("    /** 抓包报告模板，__DATA__ / __JS__ 是占位符 */")
    body.append("    @JvmField val HTML: String = " + kt_string(html))
    body.append("")
    body.append("    /** 报告的前端脚本 */")
    body.append("    @JvmField val JS: String = " + kt_string(js))
    body.append("")
    body.append("    /** 接口词典（意图/字段说明/分类） */")
    body.append("    @JvmField val API_DOC: String = " + kt_string(doc))
    body.append("")
    body.append("    /** 重放实测结论：api -> {replay, why}，由 tools/probe_replay.sh 产出 */")
    body.append("    @JvmField val API_PROBE: String = " + kt_string(probe))
    body.append("")
    body.append("    /** 调用链类名 → 中文说明 */")
    body.append("    @JvmField val CALLER_DOC: String = " + kt_string(caller))
    body.append("")
    body.append("    /** 词典对象（classes / layers / replay），避免每处都重复解析 */")
    body.append("    @JvmField val LABELS: org.json.JSONObject = org.json.JSONObject(CALLER_DOC)")
    body.append("}")
    body.append("")

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    data = "\n".join(body)
    # 资源未变时保留旧生成文件，避免每次构建重写 110KB 并触发 kotlinc 全量重编。
    if os.path.exists(OUT):
        with open(OUT, encoding="utf-8") as old:
            if old.read() == data:
                print("unchanged %s" % OUT)
                return 0
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(data)
    print("wrote %s (%.1f KB)" % (OUT, os.path.getsize(OUT) / 1024))
    return 0


if __name__ == "__main__":
    sys.exit(main())
