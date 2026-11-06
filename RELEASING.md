# 构建与发布

1. 更新 release.json 与 RELEASE_NOTES.md，运行契约回归。
2. 确认 README 的宿主版本与验证边界准确，不提交私钥、抓包、设备截图或个人数据。
3. GitHub Secrets：RELEASE_KEYSTORE（Base64 JKS）、SIGNING_PASSWORD、MODULE_REPO_TOKEN（仅需对个人模块分发仓库的 contents:write）。工作流不把秘密写入 Git。
4. push main 触发构建；push v2026.11.06 类标签触发发布，源码与个人模块仓库使用同一 APK 字节及 SHA256SUMS。

主仓库：https://github.com/TanCan1998/uxian
个人模块分发：https://github.com/TanCan1998/io.github.tancan1998.uxian
官方收录：https://github.com/Xposed-Modules-Repo/submission

个人模块仓库为 README / SUMMARY / Release 分发，不存放源码。现采用 GitHub 账号所有的 io.github.tancan1998 前缀，官方收录仍须重新登记审核；个人仓库不代表已经被官方收录。未获目标仓库写权限前，不会自动向 Xposed-Modules-Repo 推送。

PR 构建没有发布密钥时生成一次性测试证书；标签正式发布必须使用持久签名。最初公开发行的签名与旧本地测试包不同。源码历史本地原始备份不公开上传。


旧包名已被官方机器人拒绝（U闲 #2460 / U音 #2461）；现发行采用 GitHub 前缀包名。旧发行资产保留历史，新包名必须重新提交官方收录审核。


## 官方模块仓库（2026-11-06 已批准）

官方登记 [#2462](https://github.com/Xposed-Modules-Repo/submission/issues/2462) 已获 XposedBot 批准。官方仓库：https://github.com/Xposed-Modules-Repo/io.github.tancan1998.uxian 。中文简介、README、SUMMARY、GPL 原文与首个 APK Release 已发布；官方 APK 与对应源码 Release 的 SHA-256 一致。网页目录/客户端索引可能有延迟，不能仅凭仓库已发布推定索引已经刷新。旧包名个人模块仓库已按维护者要求删除。

日常 CI 仍自动发布到维护者个人模块分发仓库；当前 fine-grained Token 无官方组织的写权限，官方仓库本次通过已登录网页初始化与发布，后续官方同步需组织可用凭据或继续网页操作。
