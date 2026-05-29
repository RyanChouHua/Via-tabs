# 维护计划

## 当前稳定基线

- 当前开发版本：`0.5.8-sh-only`
- `versionCode`：`53`
- 本轮功能：长按 `解析数据库` 设置解析范围；主页不再提供全局导出；备份管理导出时可设置书签文件夹名前缀。

## 可回退基线

- 当前源码路线：`0.5.7-pure-app-no-tag-source`
- `versionCode`：`52`
- Git tag：`v0.5.7-sh-only`
- 核心链路：App 保存 `prepare-via-all-db.sh` -> 手机终端 root 执行脚本 -> 复制 Via DB 到 App 私有目录 -> App 解析 prepared DB -> 本地备份/标签管理 -> 导出 HTML/JSON。
- 目标包：`mark.via`、`mark.via.gp`

## 当前主线

- 只维护 sh 提取 + App 离线解析。
- 书签导入只生成 Netscape Bookmark HTML，由用户在 Via 内手动导入。
- 国内版和 GP 版备份分开保存、分开管理。
- 解析范围通过长按 `解析数据库` 设置，可选全部、国内版或 GP 版。
- 书签导出只从备份管理入口执行，导出前可设置书签根文件夹名前缀。
- App 本地 SQLite 是主库，JSON/HTML 只是导出产物。

## 已暂停方案

### Magisk/KSU/APatch root helper

暂停原因：

- 对当前个人使用场景，终端 `sh` 更直观、更安全。
- Magisk 模块安装错误有开机风险，维护成本高于收益。
- 已归档到 `docs/archive/magisk-root-helper/`，默认不要恢复到主线。

### LSPosed/Xposed/Hook

暂停原因：

- Via 大量未打开恢复标签无法稳定通过 Hook 读取。
- 进程内导入书签和悬浮按钮路线维护成本高。
- 相关研究资料归档到 `docs/archive/xposed-lsposed-research/`。

### 渐进打开恢复标签

暂停原因：

- 恢复标签数量不可控，自动打开可能导致 Via 卡死、流量消耗或系统杀进程。
- 当前需求更适合直接读取 Via DB，而不是强行加载页面。

## 发布步骤

1. 修改功能。
2. 按安装需求决定是否递增 `versionCode`：
   - 如果要覆盖安装当前手机上的更高版本，必须递增。
   - 如果刻意对齐旧版本，例如 `0.5.7` 的 `versionCode 52`，需要先卸载旧 App 或使用降级安装。
3. 运行：

```powershell
git diff --check
cd ViaTabsAgent
gradle assembleDebug
```

4. 需要留存 APK 时复制到外部备份目录，不提交到仓库：

```powershell
Copy-Item -Force ViaTabsAgent\app\build\outputs\apk\debug\app-debug.apk `
  'Z:\fn246\BackUp\G15\资源\viatabs\ViaTabsAgent-<version>-debug.apk'
```

5. 功能验证通过后提交 Git 并打 tag；可用版本不要只保留 APK。

## 调试入口

- App 内“日志”。
- 手机日志导出：

```text
/storage/emulated/0/Download/ViaTabsAgent/agent-log.txt
```

- sh 脚本：

```text
/storage/emulated/0/Download/ViaTabsAgent/prepare-via-all-db.sh
```

- prepared DB：

```text
/data/user/0/com.viatabs.agent/files/offline-via-tabs/mark_via-via.db
/data/user/0/com.viatabs.agent/files/offline-via-tabs/mark_via_gp-via.db
```

## 不要提交

- `research/`
- `tools/`
- `out/`
- APK/AAB/构建产物
- 手机导出的书签、日志、截图
- 第三方 APK 和反编译产物
