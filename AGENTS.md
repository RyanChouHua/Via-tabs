# AGENTS.md

## 语言与风格

- 默认使用中文交流。
- 代码、命令、路径、类名、方法名和日志保持原文。
- 修改保持最小范围，优先复用现有结构。
- 不要修改与当前任务无关的文件。

## 项目重点

- Android 项目根目录：`ViaTabsAgent/`
- 模块包名：`com.viatabs.agent`
- 目标 Via 包名：`mark.via`、`mark.via.gp`
- 主要实现：
  - `Hook.java`：Via 进程 Hook、标签捕获、书签导入。
  - `MainActivity.java`：模块 App UI、本地导出管理。
  - `AgentStore.java`：SharedPreferences、日志、导出文件。
  - `ExportProvider.java`：模块和 Via 进程之间的设置/文件/日志通道。

## 当前产品边界

- 保留：标签导出、导入书签、按域名整理、本地导出数据管理、悬浮按钮样式和位置保存。
- 暂停：渐进打开恢复标签。
- 已知限制：Via 恢复但未实际加载的标签页，目前不保证能完整保存。

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

生成 APK 后，按版本号复制到：

```text
out/lsposed/ViaTabsAgent-LSPosed-debug.apk
Z:\fn246\BackUp\G15\资源\viatabs\ViaTabsAgent-LSPosed-<version>-debug.apk
```

## Git 规则

- 不提交 `research/`、`tools/`、`out/`、构建产物、日志和导出书签数据。
- 不提交第三方 APK、反编译产物、设备隐私数据。
- 每次 APK 需要安装测试时递增 `versionCode`，避免 Android 降级安装失败。

## 后续维护提示

- 修 Bug 先看手机端日志和 `/storage/emulated/0/Download/ViaTabsAgent/` 导出结果。
- Via 升级后优先检查动态 tab manager 捕获日志。
- 大功能先更新 `docs/maintenance.md` 的计划，再实现。
