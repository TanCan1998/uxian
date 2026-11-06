# 构建与发布

1. 更新 release.json 与 RELEASE_NOTES.md，运行契约回归。
2. 确认 README 的宿主版本与验证边界准确，不提交私钥、抓包、设备截图或个人数据。
3. GitHub Secrets：RELEASE_KEYSTORE（Base64 JKS）、SIGNING_PASSWORD、MODULE_REPO_TOKEN（仅需对个人模块分发仓库的 contents:write）。工作流不把秘密写入 Git。
4. push main 触发构建；push v2026.11.06 类标签触发发布，源码与个人模块仓库使用同一 APK 字节及 SHA256SUMS。

主仓库：https://github.com/TanCan1998/uxian
个人模块分发：https://github.com/TanCan1998/com.xiandehuang.u
官方收录：https://github.com/Xposed-Modules-Repo/submission

个人模块仓库为 README / SUMMARY / Release 分发，不存放源码。官方 Xposed 仓库收录需要登记及包名域名验证；现有 com.xiandehuang 前缀尚未完成所有权验证，个人仓库不代表已经被官方收录。未获目标仓库写权限前，不会自动向 Xposed-Modules-Repo 推送。

PR 构建没有发布密钥时生成一次性测试证书；标签正式发布必须使用持久签名。最初公开发行的签名与旧本地测试包不同。源码历史本地原始备份不公开上传。
