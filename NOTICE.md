# 来源、版权与第三方声明

Copyright (C) 2026 U 闲 contributors (GitHub: TanCan1998).
本项目主要由 AI 生成与迭代；本声明仅适用于贡献者有权许可的内容，不覆盖第三方既有版权。
项目整体许可证：GPL-3.0-only，见 LICENSE。

特别感谢 [芝麻粒 M / aw1y2z/Sesame-M](https://github.com/aw1y2z/Sesame-M)，其 RPC 通道、任务过滤、失败黑名单和调度思路为本项目提供了重要参考，相关代码注释保留来源。参考源码基线为 `569fac60031e1642acbcd8bece62de56e1ae91b5`。同时感谢其上游 [Dragon813/Sesame-GR](https://github.com/Dragon813/Sesame-GR)、[SenOffical/Sesame-TK](https://github.com/SenOffical/Sesame-TK)、[constanline/XQuickEnergy](https://github.com/constanline/XQuickEnergy)、[pansong291/XQuickEnergy](https://github.com/pansong291/XQuickEnergy)，以及 TKaxv-7S、LazyImmortal、Fansirsqi 等芝麻粒生态维护者。

芝麻粒 M 与 Sesame-GR / Sesame-TK 的参考实现按其 GPL-3.0 条款保留；XQuickEnergy 两条上游为 Apache-2.0。相关原文保存在 licenses/，不据此宣称本项目直接使用全部上游代码或 UI。
libxposed API / service：Apache-2.0；LSPosed 框架：GPL-3.0，作为运行环境不随 APK 打包。Kotlin 标准库：Apache-2.0；NanoHTTPD 2.3.1：BSD-3-Clause，二者随 U 闲 APK 打包。legacy Xposed 入口源码保留，API 采用 Apache-2.0；当前 CI 不构建 legacy。
Miuix 仅为芝麻粒 M 使用的组件，本项目未使用/打包；保留其许可证作为上游来源说明。
Robolectric Android API jar、R8、Kotlin 编译器、Pillow 与 uber-apk-signer 仅为构建工具，仓库不重新分发它们的二进制。来源和校验记录见 tools/dependencies.json。
