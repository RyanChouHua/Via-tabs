# Via Tabs Agent

## 目标

完成一个 agent，用于获取 Android Via 浏览器当前已打开标签页，并输出结构化结果。

目标输出：

```json
{
  "source": "via-browser",
  "strategy": "adb-ui-mvp",
  "tabs": [
    {
      "title": "Example Domain",
      "url": "https://example.com/"
    }
  ],
  "errors": []
}
```

## 资源整合结论

本次调研整合了三类方案：

1. ADB / UI Automator 路线。
2. BetterVia / Xposed / LSPatch 注入路线。
3. APK 反编译 / 私有数据读取路线。

最优解不是单一路线，而是分层实现：

| 层级 | 方案 | 适用环境 | 结论 |
| --- | --- | --- | --- |
| MVP | ADB UI 自动化 | 非 root、普通手机、Via 已打开 | 作为最小核心功能，最快验证 |
| 生产增强 | Xposed / LSPosed / LSPatch 注入 | root 或可重打包 Via | 最稳定，可直接读取 WebView 状态 |
| 研究增强 | APK / 数据目录分析 | root 或可导出 app 私有数据 | 作为辅助，不作为主路径 |

推荐落地顺序：

1. 先做 ADB UI 自动化，验证“能拿到当前可见标签页 URL”。
2. 再做滚动分页、坐标兜底、错误恢复。
3. 最后做 Xposed/LSPatch 模块，直接在 Via 进程内枚举 WebView 或内部 tab 管理对象。

## 已调研资源

### Android ADB

Android 官方文档说明，ADB 是电脑与 Android 设备通信的命令行工具，可用于安装、调试应用，并提供设备 shell。该能力正好覆盖启动 Via、点击 UI、读取 UI 层级等 MVP 需求。

参考：

- https://developer.android.com/tools/adb
- https://developer.android.com/tools/releases/platform-tools

### UI Automator

Android 官方 UI Automator 支持从应用进程外部与用户应用、系统应用交互，并能 dump UI 层级。它适合 MVP 的“打开 Via -> 进入标签页概览 -> 点击标签页 -> 读取地址栏”流程。

参考：

- https://developer.android.com/training/testing/other-components/ui-automator
- https://developer.android.com/reference/androidx/test/uiautomator/UiDevice

### WebView API

Android `WebView` 官方 API 提供 `getUrl()`、`getOriginalUrl()`、`getTitle()` 等方法。Xposed/LSPatch 增强版 agent 可以在 Via 进程内 hook WebView 实例，从对象层获取 URL 和标题，而不是依赖屏幕文本。

参考：

- https://developer.android.com/reference/android/webkit/WebView

### BetterVia

已克隆并分析：

```text
research/BetterVia
```

项目地址：

- https://github.com/JiGuroLGC/BetterVia

关键发现：

- BetterVia 是 Via 浏览器增强模块。
- 作用域包含 `mark.via` 与 `mark.via.gp`。
- 支持 LSPosed 和 LSPatch。
- 入口文件为 `app/src/main/assets/xposed_init`，指向 `com.jiguro.bettervia.Hook`。
- `Hook` 通过 `Application.attach(Context)` 获取 Via 进程 Context 和 ClassLoader。
- `ViaVersionDetector` 维护 Via 版本识别逻辑。
- `ViaClassMapping` 维护不同 Via 版本的混淆类和方法映射。
- BetterVia 支持 Via 6.6.0 到 7.0.0，并包含 Via 7.0.0 的改包 APK 资源。

这说明：如果要做生产级稳定采集，最佳增强路线是借鉴 BetterVia 的 Xposed/LSPatch 结构，在 Via 进程中采集 WebView 或内部标签页对象。

### LSPatch / LSPosed

LSPatch 是非 root Xposed 框架路线，可以通过修改 APK 让模块在目标 app 内加载；官方仓库已归档，但仍可用于理解和验证无 root 注入路线。

参考：

- https://github.com/LSPosed/LSPatch/releases
- https://lsposed.org/

## APK 分析结果

BetterVia 仓库内包含 Via 7.0.0 的改包 APK：

```text
research/BetterVia/Monet/Via_7.0.0_Moneted.apk
research/BetterVia/Monet_GP/Via_7.0.0_GP_Moneted.apk
```

已完成只读 ZIP 结构分析，确认 APK 内包含：

- `AndroidManifest.xml`
- `classes.dex`
- `assets/error.html`
- `assets/logo.svg`
- `assets/opensug2.js`
- `assets/simple.txt`
- AndroidX WebKit / ViewPager / RecyclerView 等依赖痕迹
- Firebase / analytics 相关依赖痕迹

已使用 apktool 3.0.2 完成 Via 7.0.0 国内版反编译：

```text
research/apktool/via_700_cn_moneted
```

GP 版完整资源解析遇到 apktool framework 缓存问题，但已使用 `--no-res` 完成代码层反编译：

```text
research/apktool/via_700_gp_moneted_nores
```

APK 分析已确认 Via 7.0.0 的内部标签结构，生产增强方案应优先 hook `Le/h/a/e/c` 标签管理器，而不是只 hook 原生 `android.webkit.WebView`。

## 最优技术方案

### A. MVP：ADB UI 自动化

这是默认方案。

流程：

1. 检查 `adb devices`。
2. 自动识别 Via 包名：
   - `mark.via.gp`
   - `mark.via`
   - `mark.via.browser`
3. 使用 monkey 或 am start 启动 Via。
4. 使用 `adb shell wm size` 获取屏幕尺寸。
5. 使用 `adb shell uiautomator dump` 读取 UI 树。
6. 查找标签页按钮：
   - `tab`
   - `tabs`
   - `标签`
   - `窗口`
7. 进入标签页概览。
8. 识别标签页卡片。
9. 点击一个标签页。
10. 聚焦地址栏。
11. 再次 dump UI，读取 URL 文本。
12. 读取标题文本。
13. 返回标签页概览，继续下一个标签。
14. URL 去重并输出 JSON。

优点：

- 不需要 root。
- 不需要修改 Via。
- 最容易验证。
- 对用户设备侵入最低。

缺点：

- 依赖 UI 文本和布局。
- 不同 Via 主题可能需要坐标兜底。
- 地址栏不可见时可能读取失败。
- 标签页多时需要滚动分页。

### B. 生产增强：Xposed / LSPatch 注入

这是长期最优方案。

实现思路：

1. 复用 BetterVia 的模块形态。
2. 作用域设置为：
   - `mark.via`
   - `mark.via.gp`
3. 通过 `Application.attach(Context)` 拿到 Via ClassLoader。
4. 在 Via 7.0.0 上优先 Hook 内部标签管理器：
   - `Le/h/a/e/c.C()`：返回全部标签 URL。
   - `Le/h/a/e/c.d()`：返回当前标签集合 Bundle。
   - `Le/h/a/e/c.K(String, int)`：监听新建标签。
   - `Le/h/a/e/c.V(int)`：监听切换标签。
   - `Le/h/a/e/c.U(Bundle)`：监听恢复标签。
5. 同时 Hook 单标签包装和自定义 WebView 作为补充：
   - `Le/h/a/g/b.g()`：读取单个标签 URL。
   - `Le/h/a/g/b.f()`：读取单个标签 Bundle。
   - `Le/h/a/g/a.getUrl()`：读取 Via 自定义 WebView URL。
   - `android.webkit.WebView.getTitle()`：读取当前标题。
6. 对无法适配的版本退回通用 WebView hook：
   - 构造函数：记录 WebView 实例。
   - `loadUrl`：记录 URL 变化。
   - `goBack` / `goForward` / `reload`：刷新状态。
   - `onPageFinished` 或 WebViewClient 相关方法：刷新 title/url。
7. 将结果写入 app 外部私有目录：
   - `/storage/emulated/0/Android/data/<via_package>/files/ViaTabsAgent/tabs.json`
8. 外部 agent 通过 ADB pull 读取 JSON。

优点：

- 不依赖 UI 文本。
- 能直接拿到 WebView URL 和标题。
- 比 UI 自动化更稳定。
- 可扩展为实时监听。

缺点：

- root 设备需要 LSPosed。
- 非 root 设备需要 LSPatch 重打包 Via。
- 需要处理签名、升级、兼容性。
- Via 混淆类随版本变化，若要读取内部 tab manager，需要版本映射。

### C. 研究增强：反编译和私有数据

该方案只做辅助。

研究路径：

1. 使用 jadx 反编译 Via APK。
2. 搜索关键词：
   - `WebView`
   - `getUrl`
   - `getTitle`
   - `Tab`
   - `Session`
   - `SQLiteOpenHelper`
   - `SharedPreferences`
   - `ViewPager`
3. 定位标签页管理对象。
4. 对比 BetterVia 的版本映射方式，维护 Via 版本差异。
5. root 设备下读取 `/data/data/<package>/`，确认是否有 session 文件或数据库。

不建议作为默认路径，因为：

- 非 root 通常无法读私有目录。
- Via 可能不持久化所有打开标签。
- 数据结构随版本变化。
- 直接读数据比进程内 WebView 枚举更脆弱。

## 生产环境配置

### 当前本机状态

已验证：

| 项目 | 状态 |
| --- | --- |
| git | 可用 |
| Java | 可用，当前为 Java 8 |
| BetterVia 源码 | 已克隆到 `research/BetterVia` |
| BetterVia APK 结构 | 已完成 ZIP 层分析 |
| adb | 可用，`tools/adb/adb.exe`，版本 34.0.5 |
| jadx | 未安装或未加入 PATH |
| apktool | 可用，`C:\A_Program\Env\apktool\apktool_3.0.2.jar`，版本 3.0.2 |
| Via 7.0.0 APK apktool 分析 | 已完成国内版，GP 版代码层完成 |
| Android 设备连接 | 未检测到 |

### 推荐生产工具

Windows 环境建议安装：

```text
Android SDK Platform Tools
JDK 17
Android Studio 或 Gradle Android Plugin 可用环境
jadx
apktool
LSPosed 或 LSPatch
```

MVP 只强制需要：

```text
adb
Android 手机
Via 浏览器
USB 调试
```

Xposed/LSPatch 增强需要：

```text
JDK 17
Android Gradle Plugin
LSPosed 或 LSPatch
BetterVia 式模块工程
```

## 最小核心功能验证

当前环境无法完成真实设备验证，原因：

- 本机未检测到 `adb`。
- 当前没有可用 Android 设备连接。
- 无法启动 Via 或读取真实 UI 树。

已完成的本地验证：

1. 确认 git 可用。
2. 克隆 BetterVia 源码。
3. 读取 BetterVia Manifest，确认 Xposed 模块声明。
4. 读取 `xposed_init`，确认 hook 入口。
5. 读取 `Hook.java`，确认通过 `Application.attach` 注入 Via 进程。
6. 读取 `ViaVersionDetector.java`，确认 Via 版本检测策略。
7. 读取 `ViaClassMapping.java`，确认按 Via 版本维护混淆映射。
8. 只读分析 BetterVia 内置 Via APK，确认存在 `classes.dex` 与 WebView 相关依赖。

真实 MVP 验证命令应为：

```powershell
adb devices
adb shell pm list packages | findstr via
adb shell monkey -p mark.via.gp -c android.intent.category.LAUNCHER 1
adb shell uiautomator dump /sdcard/window.xml
adb shell cat /sdcard/window.xml
```

MVP 验收标准：

1. 能检测设备。
2. 能识别 Via 包名。
3. 能启动 Via。
4. 能 dump 当前 UI。
5. 能识别标签页入口或使用坐标打开标签页概览。
6. 能进入至少 1 个标签页。
7. 能读取当前地址栏 URL。
8. 能输出 JSON。

## 迭代计划

### V0：方案和环境

状态：已完成。

产物：

- `agent.md`
- `research/BetterVia`
- BetterVia hook 结构分析
- APK ZIP 层分析
- 当前环境缺口清单

### V1：ADB MVP

目标：

- 获取当前可见标签页 URL。
- 支持手动坐标兜底。
- 输出 JSON。

验收：

- 至少成功读取 1 个 Via 已打开标签页。
- 失败时返回明确错误原因。

### V2：ADB 稳定版

目标：

- 自动滚动标签页概览。
- 支持多语言 UI。
- 支持顶部/底部工具栏。
- 支持截图和 UI XML 留档。
- 支持重试、超时、错误恢复。

验收：

- 在 10 个以上标签页中稳定采集。
- 重复 URL 自动去重。
- 失败标签页记录到 `errors`。

### V3：Xposed/LSPatch Agent

目标：

- 构建 ViaTabsAgent Android 模块。
- 优先 hook Via 7.0.0 内部标签管理器 `Le/h/a/e/c`。
- 补充 hook Via 自定义 WebView `Le/h/a/g/a`。
- 输出 tabs JSON 到外部目录。
- ADB 读取 JSON。

验收：

- 不打开标签页概览也能获取当前 WebView URL。
- 能读取当前标签集合 URL。
- 能实时更新当前页 URL/title。
- 在 Via 6.6.0 到 7.0.0 至少一个版本上验证。

### V4：内部 Tab Manager 适配

目标：

- 通过 jadx 定位 Via 内部标签页管理类。
- 参考 BetterVia 维护版本映射表。
- 直接枚举完整标签集合。

验收：

- 不依赖 UI。
- 不依赖当前可见 WebView。
- 可获取所有打开标签。

## 最终结论

最优解是“两阶段 agent”：

1. 第一阶段使用 ADB UI 自动化完成最小核心功能，低侵入、易验证、适合当前目标。
2. 第二阶段借鉴 BetterVia，做 Xposed/LSPatch 注入模块，在 Via 进程内优先采集 `Le/h/a/e/c` 标签管理器状态，再用 `Le/h/a/g/a` 自定义 WebView 兜底，作为生产级稳定方案。

不建议一开始直接读 Via 私有数据。它需要 root，且无法保证当前打开标签一定有稳定持久化结构。相比之下，WebView 进程内读取和 ADB UI 自动化都更符合实际可交付路径。

## 当前 Agent 功能

当前已经进入 Xposed/LSPatch 生产增强路线，核心模块位于：

```text
ViaTabsAgent/app/src/main/java/com/viatabs/agent/Hook.java
```

已实现能力：

1. 在 Via 7.0.0 进程内 hook 内部标签管理器 `e.h.a.e.c`。
2. 通过 `C()` 读取当前所有已打开标签页 URL。
3. 通过单标签 WebView `getTitle()` 或保存状态 `Bundle` 补充标题。
4. 扩展 Via 原生会话保存逻辑，避免只保存当前标签附近少量标签的问题。
5. 将完整会话写出到 `agent-session.json`。
6. 启动 Via 后可恢复 agent 保存过但 Via 原生未恢复的标签页。
7. 一键保存当前已打开标签页到 Via 原生书签文件夹。
8. 保存标签页分组快照，并可同步创建同名 Via 书签文件夹。
9. 支持参考 OneTab/Chrome/Edge 后的移动端分组管理：稳定 `groupId`、组名、颜色、归档状态、删除和整组恢复。
10. 在 Via 浏览器内注入轻量操作面板，手机端可直接点击完成保存、分组、恢复、归档和删除；ADB 广播仅作为开发调试入口。

### 手机端操作方式

推荐生产使用 LSPosed 2.0.2 以上版本：

1. 在 LSPosed 中启用 ViaTabsAgent 模块。
2. 作用域选择 `mark.via` 和/或 `mark.via.gp`。
3. 强制停止并重新打开 Via。
4. 进入 Via 后，页面右侧会出现 `Tabs` 悬浮按钮。
5. 点击 `Tabs`，在面板中选择：
   - `Save tabs to bookmarks`
   - `Create tab group`
   - `Restore latest group`
   - `Archive latest group`
   - `Delete latest group`
   - `Refresh tabs snapshot`

当前面板是最小可用版本，优先保证不破坏 Via 原有界面。后续可以继续迭代为底部操作栏、Via 菜单项或独立模块 Activity。

调试广播：

```powershell
$adb='C:\A_Program\Env\android-sdk\platform-tools\adb.exe'
$serial='emulator-5556'

& $adb -s $serial shell am broadcast -a com.viatabs.agent.DUMP_TABS
& $adb -s $serial shell am broadcast -a com.viatabs.agent.OPEN_TEST_TABS --ei count 10
& $adb -s $serial shell am broadcast -a com.viatabs.agent.SWITCH_TAB --ei index 3
& $adb -s $serial shell am broadcast -a com.viatabs.agent.SET_RESTORE_ALWAYS --ez enabled true
& $adb -s $serial shell am broadcast -a com.viatabs.agent.CLOSE_VIA
& $adb -s $serial shell am broadcast -a com.viatabs.agent.RESTORE_AGENT_SESSION
& $adb -s $serial shell am broadcast -a com.viatabs.agent.SAVE_TABS_TO_BOOKMARKS --es folder ViaTabsAgent
& $adb -s $serial shell am broadcast -a com.viatabs.agent.GROUP_TABS --es group SessionGroup --es color green --ez bookmarks true
& $adb -s $serial shell am broadcast -a com.viatabs.agent.RESTORE_TAB_GROUP --es group SessionGroup --ez dedupe true
& $adb -s $serial shell am broadcast -a com.viatabs.agent.RESTORE_TAB_GROUP --es group SessionGroup --ei index 2 --ez dedupe true
& $adb -s $serial shell am broadcast -a com.viatabs.agent.ARCHIVE_TAB_GROUP --es group SessionGroup --ez archived true
& $adb -s $serial shell am broadcast -a com.viatabs.agent.DELETE_TAB_GROUP --es group SessionGroup
```

写出文件：

```text
/sdcard/Android/data/mark.via.gp/files/ViaTabsAgent/tabs.json
/sdcard/Android/data/mark.via.gp/files/ViaTabsAgent/agent-session.json
/sdcard/Android/data/mark.via.gp/files/ViaTabsAgent/saved-bookmarks.json
/sdcard/Android/data/mark.via.gp/files/ViaTabsAgent/tab-groups.json
```

书签保存策略：

- Via 7.0.0 GP 版书签数据库名为 `via`。
- 文件夹表：`bookmark_folders(_id,title,parent_folder_id,ordering,created_at,last_updated_at)`。
- 条目表：`bookmark_items(_id,url,title,folder_id,ordering,last_updated_at,created_at)`。
- Agent 会创建或复用指定名称的顶层书签文件夹。
- 同一文件夹内相同 URL 会跳过，避免重复插入。
- 只写入 `http://` 和 `https://` URL；Via 内部首页等 `file://` URL 会记录到 JSON，但不写入原生书签。

已验证结果：

- 在 Android 13 模拟器 `emulator-5556` 上，LSPatch 后的 Via 7.0.0 GP 版可加载 agent。
- Via 启动时恢复未关闭标签后，打开 10 个测试标签，再退出并重启，agent 可读取已打开标签页。
- 执行 `SAVE_TABS_TO_BOOKMARKS` 后，12 个当前标签中 11 个 HTTP/HTTPS 标签写入 Via 原生书签，1 个 Via 内部 `file://` 首页被正确跳过。
- 执行 `GROUP_TABS` 后，分组快照写入 `tab-groups.json`，并创建同名书签文件夹。

## 分组功能设计参考

参考 OneTab、Chrome Android 标签分组和 Edge Collections 后，ViaTabsAgent 采用更适合 Via 移动端的轻量方案：

- OneTab 的核心优点是把当前标签转换成本地列表，并支持单个或全部恢复；ViaTabsAgent 当前落地为 `GROUP_TABS` 保存分组、`RESTORE_TAB_GROUP` 整组恢复或按 `index`/`url` 恢复单个标签。
- Chrome Android 标签分组强调组名、颜色、恢复、删除和非活跃分组；ViaTabsAgent 当前落地为 `groupId`、`group`、`color`、`archived`、`DELETE_TAB_GROUP`。
- Edge Collections 更像长期资料集合，支持以后逐个或全部打开；ViaTabsAgent 当前通过 `tab-groups.json` 保存长期集合，并可同步写入 Via 原生书签文件夹。
- Via 移动端没有桌面扩展 API，也没有稳定公开的 tab group UI；因此先把分组做成进程内 agent 能力和 JSON 数据模型，后续可以在单独 UI 或脚本层调用。
- `close=true` 目前只记录 `closeRequested`，暂不自动关闭当前标签，避免在关闭方法未完全适配前误删用户现场。

分组 JSON 字段：

```json
{
  "groupId": "stable-id",
  "group": "SessionGroup",
  "color": "green",
  "archived": false,
  "closeRequested": false,
  "folderId": "via-bookmark-folder-id",
  "tabCount": 4,
  "bookmarkableCount": 3,
  "tabs": []
}
```

参考资料：

- OneTab Chrome Web Store: https://chromewebstore.google.com/detail/onetab/chphlpgkkbolifaimnlloiipkdnihall
- Chrome Android tabs and tab groups: https://support.google.com/chrome/answer/2391819
- Edge Collections: https://support.microsoft.com/en-us/microsoft-edge/organize-your-ideas-with-collections-in-microsoft-edge-60fd7bba-6cfd-00b9-3787-b197231b507e
