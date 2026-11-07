# U 闲

当前版本 **1.0**，与 U 音统一版本命名；内部版本码 11061731，沿用正式签名，可覆盖上一版。此次仅同步版本，功能与上一正式版一致。

**AI 生成项目**：本项目主要代码、文档、测试及构建配置由 AI（OpenAI ChatGPT / Codex，通过 Minis）生成并迭代，由项目维护者提出需求、审核并提供实测反馈。AI 生成不代表已经过完整人工安全审计。

闲鱼净化和增强模块：开屏与部分信息流广告过滤、消息与入口整理、首页导航和金刚位排序、任务执行计划与进度、调试和临时抓包。各功能独立开关，按实际钩子挂载情况报告适配。

## 已实测宿主版本

| 宿主 | 包名 | 验证来源 |
| --- | --- | --- |
| 闲鱼 7.28.91 / versionCode 529 | `com.taobao.idlefish` | 维护者于 2026-11-06 确认全部现有功能正常 |

2026-11-06：用户确认当前安装版本的全部现有功能在上述宿主上实测正常。历史还验证过闲鱼 7.28.80 / 525。公开版另将早期硬编码的城市/精确坐标改为空值，以保护维护者隐私；依赖位置的任务尚未针对此项调整重新进行真机验证，可能由服务端拒绝或采用默认区域。CI/契约回归不替代新的真机测试。

不设置宿主版本号门禁；逐项检查类、方法签名、返回类型与实际挂载结果。挂载成功、实际调用和拦截命中分别报告。其他宿主版本尚未验证。

## 安装与使用

下载 [Releases](https://github.com/TanCan1998/uxian/releases) 中的 APK，在兼容 libxposed API 102 的框架中启用，并将作用域设为对应宿主，然后重启宿主。包名为 `io.github.tancan1998.uxian`。仅发布现代 API 102 构建；U 闲最低 Android 8，U 音最低 Android 9。

U 闲设置通过闲鱼中的模块入口进入；功能按需启用。

临时抓包和报告可能包含当前账户的请求、响应或身份信息，仅保存在本机，分享前需自行脱敏。HTTP 调试默认关闭，仅绑定 127.0.0.1，但未设置鉴权；开启后同机其他应用可能访问，请仅在调试时启用。

首次公开发行使用独立持久签名，无法直接覆盖早期本地测试证书安装的同包名模块。请先备份配置，再由使用者决定如何迁移；不要卸载宿主。

## 构建

GitHub Actions 使用 `ubuntu-latest`、Temurin JDK 17，以及发布时查询的最新官方稳定 Action：checkout v7.0.1、setup-java v6.2.0、upload-artifact v7.0.2，固定完整提交 SHA，Dependabot 每周检查更新。`ubuntu-latest` 跟随 GitHub 托管的最新稳定 Ubuntu 镜像。

```sh
python3 tools/bootstrap.py
python3 -m pip install -r requirements-build.txt
sh tools/build.sh new
```

依赖版本、下载地址和 SHA-256 在 `tools/dependencies.json`；工具下载到 `.tools/`，不依赖维护者设备路径。产物为 `build/new/U闲.apk`。普通本地构建使用测试证书；正式 CI 使用 GitHub Secrets 中的持久密钥。推送 `v*` 标签后发布相同 APK 与 SHA256SUMS 到源码仓库和个人模块分发仓库，详见 [发布说明](RELEASING.md)。

## 隐私与源码范围

公开 Git 历史已压缩，仅保留关键源码与发布配置提交，作者邮箱使用 GitHub noreply。仓库排除真实抓包数据库、请求回执、账户和位置示例、截图、宿主反编译代码、会话、令牌、签名私钥、本地研究记录及构建产物。模块运行所需的账户状态来自本机宿主，不在源码中预置真实账户。AI/宿主/框架均非本项目的官方背书方。

临时抓包和报告可能包含当前账户的请求、响应或身份信息，仅保存在本机，分享前需自行脱敏。HTTP 调试默认关闭，仅绑定 127.0.0.1，但未设置鉴权；开启后同机其他应用可能访问，请仅在调试时启用。

## 开源协议与致谢

本项目以 **GNU GPL v3.0（GPL-3.0-only）** 发布，完整条款见 [LICENSE](LICENSE)。复制、修改与分发须遵守 GPL，包括分发修改版时提供相应源码及保留版权/许可证声明。第三方组件保留各自许可证，见 [NOTICE](NOTICE.md) 和 `licenses/`；GPL 不授予宿主商标、账号或服务端内容的权利。本项目不包含官方宿主 APK 或反编译源码。

特别感谢 [芝麻粒 M / aw1y2z/Sesame-M](https://github.com/aw1y2z/Sesame-M)，其 RPC 通道、任务过滤、失败黑名单和调度思路为本项目提供了重要参考，相关代码注释保留来源。参考源码基线为 `569fac60031e1642acbcd8bece62de56e1ae91b5`。同时感谢其上游 [Dragon813/Sesame-GR](https://github.com/Dragon813/Sesame-GR)、[SenOffical/Sesame-TK](https://github.com/SenOffical/Sesame-TK)、[constanline/XQuickEnergy](https://github.com/constanline/XQuickEnergy)、[pansong291/XQuickEnergy](https://github.com/pansong291/XQuickEnergy)，以及 TKaxv-7S、LazyImmortal、Fansirsqi 等芝麻粒生态维护者。

本项目不是上述项目的官方版本，也未获得其背书。


## 官方收录包名迁移（2026.11.06.1）

官方机器人拒绝旧包名 `com.xiandehuang.u` 的域名验证。本发行版改为 `io.github.tancan1998.uxian`，源码命名空间、模块入口、资源包名、Provider authority、重启 action 和调用方检查均同步迁移。旧版 Release 仅保留历史，不应与新版同时启用在同一宿主。

新旧包名不同，无法原地覆盖。迁移时请先停用旧模块，再安装并启用新版、设置宿主作用域后重启宿主；不要同时启用两份模块。模块发行签名继续使用原项目的持久签名。当前已装模块未在本次发布中替换。宿主私有配置键维持原样，但新包名发行版尚未完成真机回归，不能把旧安装版的实测结论视为新版验收。


## 官方模块仓库（2026-11-06 已批准）

官方登记 [#2462](https://github.com/Xposed-Modules-Repo/submission/issues/2462) 已获 XposedBot 批准。官方仓库：https://github.com/Xposed-Modules-Repo/io.github.tancan1998.uxian 。中文简介、README、SUMMARY、GPL 原文与首个 APK Release 已发布；官方 APK 与对应源码 Release 的 SHA-256 一致。网页目录/客户端索引可能有延迟，不能仅凭仓库已发布推定索引已经刷新。旧包名个人模块仓库已按维护者要求删除。

日常 CI 仍自动发布到维护者个人模块分发仓库；当前 fine-grained Token 无官方组织的写权限，官方仓库本次通过已登录网页初始化与发布，后续官方同步需组织可用凭据或继续网页操作。
