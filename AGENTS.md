# AGENTS.md

## 语言与风格

- 默认使用中文交流。
- 代码、命令、路径、类名、方法名和日志保持原文。
- 修改保持最小范围，优先复用现有结构。
- 不要修改与当前任务无关的文件。

## 当前主线

- Android 项目根目录：`ViaTabsAgent/`
- App 包名：`com.viatabs.agent`
- 目标 Via 包名：`mark.via`、`mark.via.gp`
- 当前路线：终端 root `sh` 提取 Via 数据库，App 离线解析、管理和导出标签。
- 不使用 LSPosed/Xposed/Hook 作为当前主线。
- 不使用 Magisk/KSU/APatch 模块作为当前主线；相关代码仅归档保留。

## 主要实现

- `ViaTabsAgent/app/src/main/assets/prepare-via-all-db.sh`：root 终端脚本，同时尝试提取 `mark.via` 和 `mark.via.gp` 数据库。
- `MainActivity.java`：App UI、保存脚本、解析数据库、备份/标签管理、导出和日志入口。
- `AgentStore.java`：Download 文件写入、App 日志、偏好设置。
- `OfflineViaTabsReader.java`：读取 prepared Via DB，解析可恢复标签。
- `LocalTabStore.java`：App 私有 SQLite，保存备份和标签。
- `BookmarkHtml.java` / `BookmarkBatches.java`：生成 Via 可手动导入的 Netscape Bookmark HTML。

## 产品边界

- 保留：保存脚本、解析 prepared DB、本地备份管理、标签增删改查、备注、按域名整理、HTML/JSON 导出、日志。
- 书签导入采用手动 HTML 导入 Via，不直接写 Via 书签 DB。
- 国内版和 GP 版备份分开管理，不合并来源。
- 暂停：LSPosed/Xposed Hook、Magisk/KSU root helper、自动渐进打开恢复标签。

## 构建验证

优先运行：

```powershell
cd ViaTabsAgent
gradle assembleDebug
```

本机可用：

```powershell
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-7.4.2-bin\48ivgl02cpt2ed3fh9dbalvx8\gradle-7.4.2\bin\gradle.bat" assembleDebug
```

常规检查：

```powershell
git diff --check
```

## Git 规则

- 0.5.x 之前出现过“只有 APK、无源码 tag”的问题；完成可用版本后必须提交并打 tag。
- 不提交 `research/`、`tools/`、`out/`、构建产物、日志和导出书签数据。
- 不提交第三方 APK、反编译产物、设备隐私数据。
- 每次 APK 需要覆盖安装测试时递增 `versionCode`；如果刻意回到旧 `versionCode`，需要说明只能卸载后安装或使用降级安装。

## 归档资料

- Magisk/KSU/APatch root helper：`docs/archive/magisk-root-helper/`
- LSPosed/Xposed/Hook 研究资料：`docs/archive/xposed-lsposed-research/`
- 归档资料不是当前主线，不要在没有明确要求时恢复到 App 或构建配置。

## 后续维护提示

- 修 Bug 先看手机端 `prepare-via-all-db.sh` 终端输出、App 日志和 `/storage/emulated/0/Download/ViaTabsAgent/agent-log.txt`。
- prepared DB 目标路径：

```text
/data/user/0/com.viatabs.agent/files/offline-via-tabs/mark_via-via.db
/data/user/0/com.viatabs.agent/files/offline-via-tabs/mark_via_gp-via.db
```

- 大功能先更新 `docs/maintenance.md`，再实现。
