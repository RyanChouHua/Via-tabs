# 维护计划

## 当前稳定基线

- 版本：`0.4.5-lsposed-ui`
- `versionCode`：`13`
- 核心链路：Via 内悬浮按钮 -> 预览确认 -> 捕获已加载 WebView 标签 -> 导出 JSON/HTML -> 可选导入 Via 书签。
- 本地管理：按导出批次合并 HTML/JSON，支持备注筛选、排序、批量备注、批量删除、批量导入。

## 已暂停方案

### 渐进打开恢复标签

暂停原因：

- 恢复标签数量不可控，自动打开可能导致 Via 卡死、流量消耗或系统杀进程。
- 当前需求更适合继续研究 Via 的恢复标签持久化结构，而不是强行加载页面。

后续如果恢复：

- 必须默认关闭。
- 必须有二次确认。
- 必须限制总数、间隔、失败中止条件。
- 优先只做调试入口，不并入默认保存流程。

## 下一阶段优先级

1. 研究 Via 恢复标签持久化数据
   - 目标：未加载标签也能读取 URL/title。
   - 方向：分析 Via 私有目录、session bundle、tab manager 字段和恢复逻辑。
   - 输出：先写 `docs/recovered-tabs-research.md`，再改代码。

2. Hook 稳定性整理
   - 将动态 tab manager 扫描、WebView fallback、书签数据库写入拆成更清晰的内部方法。
   - 增加关键失败日志摘要，减少刷屏。

3. 本地导出管理体验
   - 批量导入完成后给出更明确的成功/失败结果。
   - 增加“打开导出目录”或“复制路径”类轻量能力。

4. 兼容性
   - 保持 `mark.via` 和 `mark.via.gp` 双版本测试。
   - Via 升级后，先收集注入日志，再改动态识别规则。

## 发布步骤

1. 修改功能。
2. 递增 `ViaTabsAgent/app/build.gradle` 中的 `versionCode` 和 `versionName`。
3. 运行：

```powershell
git diff --check
cd ViaTabsAgent
gradle assembleDebug
```

4. 复制 APK：

```powershell
Copy-Item -Force ViaTabsAgent\app\build\outputs\apk\debug\app-debug.apk out\lsposed\ViaTabsAgent-LSPosed-debug.apk
```

5. 如需手机测试，再复制带版本号 APK 到备份目录。

## 调试入口

- App 内“日志”：查看模块侧日志。
- 手机导出目录：

```text
/storage/emulated/0/Download/ViaTabsAgent/
```

- Via 私有缓存快照：

```text
/storage/emulated/0/Android/data/mark.via/files/ViaTabsAgent/
/storage/emulated/0/Android/data/mark.via.gp/files/ViaTabsAgent/
```

## 不要提交

- `research/`
- `tools/`
- `out/`
- `saved-bookmarks.json`
- 手机导出的书签、日志、截图
- 第三方 APK 和反编译产物
