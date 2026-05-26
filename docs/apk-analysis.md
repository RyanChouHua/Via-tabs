# Via APK Analysis

## 结论

已分析 Via 7.0.0 APK，并确认最优生产路线不是只 hook `android.webkit.WebView`，而是优先 hook Via 自身的标签管理器。

推荐 hook 顺序：

1. `Le/h/a/e/c.C()`：直接读取全部标签 URL。
2. `Le/h/a/e/c.d()`：读取标签集合 Bundle，包含 `TITLE`、`URL`、`LIST`、`CUR`。
3. `Le/h/a/g/b.g()`：读取单个 tab URL，覆盖未实例化 WebView 的标签。
4. `Le/h/a/g/a.getUrl()`：读取 Via 自定义 WebView 当前 URL。
5. `android.webkit.WebView.getTitle()` / `android.webkit.WebView.getUrl()`：作为跨版本兜底。

## 已分析资源

BetterVia 仓库：

```text
research/BetterVia
```

Via 7.0.0 国内版 APK：

```text
research/BetterVia/Monet/Via_7.0.0_Moneted.apk
research/apktool/via_700_cn_moneted
```

Via 7.0.0 GP 版 APK：

```text
research/BetterVia/Monet_GP/Via_7.0.0_GP_Moneted.apk
research/apktool/via_700_gp_moneted_nores
```

## 工具状态

ADB 已验证：

```text
tools/adb/adb.exe
Android Debug Bridge version 1.0.41
Version 34.0.5-10900879
```

apktool 已验证：

```text
C:\A_Program\Env\apktool\apktool_3.0.2.jar
3.0.2
```

当前阻塞项：

- 未连接 Android 设备，无法做运行时 MVP 验证。
- 当前未安装 jadx，但 apktool 级 smali 分析已足够确认 Via 7.0.0 的标签结构。

## APK 核心结构

Manifest 关键点：

- 包名：`mark.via`
- Application：`mark.via.BrowserApp`
- 主 Activity：`mark.via.Shell`
- CustomTab Activity：`mark.via.CustomTab`

标签链路：

```text
mark.via.Shell
  -> k/a/y/ya
    -> Le/h/a/e/a interface
    -> Le/h/a/e/c tab manager implementation
      -> List<Le/h/a/g/b>
        -> Le/h/a/g/b tab wrapper
          -> Le/h/a/g/a custom WebView
```

关键 smali 文件：

```text
research/apktool/via_700_cn_moneted/smali/e/h/a/e/c.smali
research/apktool/via_700_cn_moneted/smali/e/h/a/g/b.smali
research/apktool/via_700_cn_moneted/smali/e/h/a/g/a.smali
research/apktool/via_700_cn_moneted/smali/k/a/y/ya.smali
research/apktool/via_700_cn_moneted/smali/mark/via/Shell.smali
```

## 标签管理器发现

`Le/h/a/e/c` 是 Via 7.0.0 的标签管理器实现：

- 字段 `c`：`List<Le/h/a/g/b>`，保存 tab wrapper。
- 字段 `e`：当前 tab index。
- `C()`：返回所有 tab URL 的 `List<String>`。
- `D()`：返回当前 `Le/h/a/g/a` WebView。
- `N()`：返回当前 `Le/h/a/g/b` tab wrapper。
- `d()`：返回标签状态 Bundle。
- `K(String, int)`：创建新 tab。
- `V(int)`：切换当前 tab。
- `U(Bundle)`：恢复标签列表。

`Le/h/a/g/b` 是单个标签包装对象：

- 字段 `a`：tab ID。
- 字段 `b`：实际 `Le/h/a/g/a` WebView。
- 字段 `c`：保存状态的 Bundle。
- `g()`：返回 URL；WebView 不存在时从 Bundle 的 `url` 读取。
- `l()`：保存 WebView 状态，包括 `url` 和 `scroll`。
- `n(Bundle)`：恢复单个标签状态。

`Le/h/a/g/a` 是 Via 自定义 WebView：

- 继承 `android.webkit.WebView`。
- 提供 `getTabId()` / `setTabId(int)`。
- 覆盖或使用 `getUrl()`、`loadUrl(String)`、`saveState(Bundle)`、`restoreState(Bundle)`。

## 类似项目整合

已整合到方案的外部项目/路线：

- BetterVia：Via 专用 Xposed/LSPatch 模块，可复用作用域、入口、版本检测和混淆映射思路。
- LSPosed：root 场景下的 Xposed 模块承载框架。
- LSPatch：非 root 场景下通过重打包目标 APK 注入模块。
- Firefox/Chrome 标签导出类扩展与流程：可参考导出格式、标题/URL 列表、文件输出、未加载标签处理等产品形态。

没有发现现成项目已经完成“Via 浏览器已打开标签页导出”。因此最优解是自研 ViaTabsAgent，技术上借鉴 BetterVia 的注入方式，并使用 APK 分析得到的 Via 内部标签管理器作为主采集点。

## MVP 验证条件

连接 Android 设备后，先验证低侵入路径：

```powershell
.\tools\adb\adb.exe devices
.\tools\adb\adb.exe shell pm list packages | findstr via
.\tools\adb\adb.exe shell monkey -p mark.via.gp -c android.intent.category.LAUNCHER 1
.\tools\adb\adb.exe shell uiautomator dump /sdcard/window.xml
.\tools\adb\adb.exe shell cat /sdcard/window.xml
```

生产增强路径验证：

1. 构建 Xposed/LSPatch 模块。
2. 作用域设置为 `mark.via` 或 `mark.via.gp`。
3. hook `Le/h/a/e/c.C()` 输出 URL 列表。
4. 再 hook `Le/h/a/e/c.d()` 补齐标题、当前 index、未加载标签状态。
5. 输出 JSON 到 app 外部文件目录，再通过 ADB 拉取。
