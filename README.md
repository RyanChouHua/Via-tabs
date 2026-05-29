# ViaTabsAgent

ViaTabsAgent 是一个面向 Via 浏览器的本地标签备份工具。

当前主线是 **终端 root sh 提取 Via 数据库，App 离线解析、管理和导出标签**。不再依赖 LSPosed/Xposed/Hook，也不再使用 Magisk/KSU 模块作为默认路径。

## 目标

- `mark.via`
- `mark.via.gp`

## 核心流程

1. 在 App 中点 `保存脚本`，生成：

```text
/storage/emulated/0/Download/ViaTabsAgent/prepare-via-all-db.sh
```

2. 在手机终端执行：

```sh
su
sh /storage/emulated/0/Download/ViaTabsAgent/prepare-via-all-db.sh
```

3. 脚本会分别尝试提取国内版和 GP 版 Via 数据库：

```text
/data/user/0/com.viatabs.agent/files/offline-via-tabs/mark_via-via.db
/data/user/0/com.viatabs.agent/files/offline-via-tabs/mark_via_gp-via.db
```

4. 回到 App，点 `解析数据库`。
5. 在 `备份管理` 中按备份批次、国内版/GP 版分开管理标签。
6. 用 `导出书签` 生成 Via 可手动导入的 Netscape Bookmark HTML。

## 功能

- 保存并提示执行 root sh 提取脚本。
- 解析 Via 数据库中可恢复标签。
- 本地 SQLite 管理备份和标签。
- 按备份批次、来源版本、域名、搜索词筛选。
- 支持标题/备注编辑、删除/恢复、永久清理。
- 支持按域名整理导出书签 HTML。
- 日志写入 App 私有文件，并导出到：

```text
/storage/emulated/0/Download/ViaTabsAgent/agent-log.txt
```

## 当前版本

当前源码已经提交为 sh-only 基线：

```text
versionCode 52
versionName 0.5.7-pure-app-no-tag-source
git tag v0.5.7-sh-only
```

注意：本仓库历史里没有原始 `0.5.7` Git tag，只有备份目录中的 APK。`v0.5.7-sh-only` 是根据该 APK 元数据和当前 sh-only 路线整理出的可回退基线。

## 项目结构

```text
.
├── ViaTabsAgent/
│   └── app/src/main/
│       ├── assets/prepare-via-all-db.sh
│       └── java/com/viatabs/agent/
│           ├── MainActivity.java
│           ├── AgentStore.java
│           ├── OfflineViaTabsReader.java
│           ├── LocalTabStore.java
│           ├── ManagedBackup.java
│           ├── ManagedTab.java
│           ├── BookmarkHtml.java
│           └── ...
├── docs/
│   ├── maintenance.md
│   ├── cleanup.md
│   └── archive/
│       ├── magisk-root-helper/
│       └── xposed-lsposed-research/
├── AGENTS.md
└── README.md
```

## 构建

```powershell
cd ViaTabsAgent
gradle assembleDebug
```

本机可用：

```powershell
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-7.4.2-bin\48ivgl02cpt2ed3fh9dbalvx8\gradle-7.4.2\bin\gradle.bat" assembleDebug
```

输出：

```text
ViaTabsAgent/app/build/outputs/apk/debug/app-debug.apk
```

## 归档路线

- Magisk/KSU/APatch root helper 已归档到 `docs/archive/magisk-root-helper/`。
- LSPosed/Xposed/Hook 研究资料已归档到 `docs/archive/xposed-lsposed-research/`。
- 当前主线不从这些归档路线继续开发，除非明确重新评估。

## 不提交内容

- APK/AAB/构建产物。
- `out/`、`research/`、`tools/`。
- 第三方 APK、反编译产物、设备隐私数据。
- 手机导出的 JSON/HTML、日志和截图。
