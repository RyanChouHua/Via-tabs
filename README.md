# ViaTabsAgent

ViaTabsAgent 是一个面向 Via 浏览器的 LSPosed/Xposed 模块，用于在 Via 内读取当前已加载标签页，并将标签导出为书签文件或直接写入 Via 书签。

当前适配目标：

- `mark.via`
- `mark.via.gp`
- LSPosed 2.0.2 及以上版本

## 功能

- Via 内悬浮圆形按钮，支持拖动并按 Via 包名保存位置。
- 点击悬浮按钮后预览当前可保存标签数量，再确认保存。
- 默认导出 JSON 标签数据。
- 可导出 Netscape Bookmark HTML，便于书签导入。
- 可直接导入到 Via 书签数据库。
- 可按域名整理书签，常见二级后缀和移动端前缀会被归并。
- App 内提供功能开关、按钮样式设置、日志、最近结果和本地导出数据管理。
- 本地导出数据管理支持按来源包名、备注、时间排序、批量备注、批量删除和批量导入。

## 当前限制

- 只能稳定保存已经实际加载进 WebView 的标签页。
- Via 启动恢复但尚未手动点开的标签，当前不能保证完整读取。
- “渐进打开恢复标签”路线已暂停，避免大量标签自动加载带来的崩溃和流量风险。
- 书签导入依赖 Via 当前版本内部数据库结构，Via 升级后可能需要重新适配。

## 项目结构

```text
.
├── ViaTabsAgent/                 # Android LSPosed 模块源码
│   ├── app/src/main/java/com/viatabs/agent/
│   │   ├── Hook.java             # Via 进程 Hook 与标签捕获/书签写入
│   │   ├── MainActivity.java     # 模块 App 设置与本地管理界面
│   │   ├── AgentStore.java       # 配置、日志、导出文件管理
│   │   ├── ExportProvider.java   # 模块与 Via 进程之间的 ContentProvider 通道
│   │   └── ExportReceiver.java
│   └── app/src/main/assets/xposed_init
└── README.md
```

## 构建

前置环境：

- JDK 17
- Android SDK
- Android Gradle Plugin 7.2.2 可用缓存或网络
- `xposed-api-82_compileonly.jar` 位于 `ViaTabsAgent/app/libs/compile_only/`
- Gradle 7.4.2 或兼容版本

构建命令：

```powershell
cd ViaTabsAgent
gradle assembleDebug
```

输出：

```text
ViaTabsAgent/app/build/outputs/apk/debug/app-debug.apk
```

## 下载

仓库内保留当前调试构建产物：

```text
release/ViaTabsAgent-LSPosed-0.4.5-lsposed-ui-debug.apk
```

## 使用

1. 安装构建出的 APK。
2. 在 LSPosed 中启用 ViaTabsAgent，并勾选 `mark.via` 或 `mark.via.gp`。
3. 强制停止目标 Via 后重新打开。
4. 在 Via 页面右侧看到悬浮圆形按钮后，点击读取当前标签并确认保存。
5. 导出文件位于手机：

```text
/storage/emulated/0/Download/ViaTabsAgent/
```

## 后续方向

- 深入分析 Via 恢复标签的持久化结构，寻找“不打开页面也能读取全部恢复标签”的路径。
- 增强不同 Via 版本的兼容性检测。
- 给本地导出管理增加更清晰的批量操作反馈。
- 抽离 Hook 规则和书签数据库写入逻辑，降低 Via 升级后的维护成本。

## 说明

本项目用于个人自动化和学习研究。仓库保留 `.gitignore`，用于避免误提交 APK、构建产物、日志、书签导出、反编译产物和本地工具。
