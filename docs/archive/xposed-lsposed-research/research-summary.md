# Research Summary

## BetterVia

位置：

```text
research/BetterVia
```

来源：

```text
https://github.com/JiGuroLGC/BetterVia
```

结论：

- BetterVia 是 Via 浏览器增强模块。
- 支持 LSPosed 和 LSPatch。
- 作用域为 `mark.via` 和 `mark.via.gp`。
- 通过 `Application.attach(Context)` 注入 Via 进程。
- 使用版本检测和混淆映射适配 Via 6.6.0 到 7.0.0。
- 没有发现现成“导出已打开标签页”的功能。
- 其工程结构可以作为 ViaTabsAgent Xposed 增强版模板。

关键文件：

```text
app/src/main/AndroidManifest.xml
app/src/main/assets/xposed_init
app/src/main/java/com/jiguro/bettervia/Hook.java
app/src/main/java/com/jiguro/bettervia/ViaVersionDetector.java
app/src/main/java/com/jiguro/bettervia/ViaClassMapping.java
```

## APK 结构

已检查：

```text
research/BetterVia/Monet/Via_7.0.0_Moneted.apk
research/BetterVia/Monet_GP/Via_7.0.0_GP_Moneted.apk
```

发现：

- 存在 `classes.dex`。
- 存在 WebView / AndroidX / ViewPager 相关依赖痕迹。
- 已使用 apktool 3.0.2 完成 Via 7.0.0 国内版反编译：

```text
research/apktool/via_700_cn_moneted
```

- GP 版资源解析遇到 apktool framework 缓存问题，但已用 `--no-res` 完成代码层反编译：

```text
research/apktool/via_700_gp_moneted_nores
```

## Via 7.0.0 APK 核心发现

Manifest 关键点：

- 包名：`mark.via`
- Application：`mark.via.BrowserApp`
- 主 Activity：`mark.via.Shell`
- 自定义标签页 Activity：`mark.via.CustomTab`
- 自定义 Custom Tabs Service：`mark.via.service.CustomTabsConnectionService`
- WebView metrics opt-out：`android.webkit.WebView.MetricsOptOut=true`

核心标签页链路：

```text
mark.via.Shell
  -> k/a/y/ya
    -> Le/h/a/e/a interface
    -> Le/h/a/e/c tab manager implementation
      -> List<Le/h/a/g/b>
        -> Le/h/a/g/b tab wrapper
          -> Le/h/a/g/a custom WebView
```

关键类：

```text
Le/h/a/e/c.smali
Le/h/a/g/b.smali
Le/h/a/g/a.smali
k/a/y/ya.smali
```

`Le/h/a/e/c` 判断为 Via 7.0.0 的标签管理器：

- 字段 `c` 是 `List<Le/h/a/g/b>`，保存标签页包装对象。
- 字段 `e` 是当前标签页 index。
- `C()` 返回所有标签 URL 的 `List<String>`。
- `D()` 返回当前 `Le/h/a/g/a` WebView。
- `N()` 返回当前 `Le/h/a/g/b` tab wrapper。
- `d()` 返回包含标签状态的 `Bundle`。
- `U(Bundle)` 恢复标签列表。
- `K(String, int)` 创建新标签。
- `V(int)` 切换当前标签。

`Le/h/a/g/b` 判断为单个标签包装对象：

- 字段 `a` 是标签 ID。
- 字段 `b` 是当前实际 WebView：`Le/h/a/g/a`。
- 字段 `c` 是保存状态的 `Bundle`。
- `g()` 返回当前 URL；若 WebView 未实例化，则从 `Bundle["url"]` 读取。
- `f()` 返回保存后的 Bundle。
- `l()` 将 WebView 状态写入 Bundle，并保存 `url` 与 `scroll`。
- `n(Bundle)` 恢复单个标签状态。

`Le/h/a/g/a` 判断为 Via 自定义 WebView：

- 继承 `android.webkit.WebView`。
- 提供 `getTabId()` / `setTabId(int)`。
- 覆盖 `getUrl()`、`loadUrl(String)`、`saveState(Bundle)`、`restoreState(Bundle)`。
- 内部保存 `url`、颜色、滚动、referer 等状态。

已确认的优先 hook 点：

1. `Le/h/a/e/c.C()`：直接获取所有标签 URL，最干净。
2. `Le/h/a/e/c.d()`：获取当前标签集 Bundle，包含 `TITLE`、`URL`、`LIST`、`CUR`。
3. `Le/h/a/g/b.g()`：获取单个 tab 的 URL，支持未实例化标签。
4. `Le/h/a/g/a.getUrl()` / `android.webkit.WebView.getTitle()`：获取运行中 WebView 状态。
5. `Le/h/a/e/c.K()`、`V()`、`U()`：监听创建、切换、恢复标签。

结论：生产增强方案不应只 hook 原生 `android.webkit.WebView`。Via 7.0.0 已暴露更精确的内部结构，应优先 hook `Le/h/a/e/c` 标签管理器，再用 `Le/h/a/g/a` 自定义 WebView 作为补充。

## 推荐下一步

1. 安装 Android Platform Tools，先跑 ADB MVP。
2. 连接 Android 设备，验证 `adb devices`。
3. 用 Xposed/LSPatch 原型 hook `Le/h/a/e/c.C()`，输出 URL 列表。
4. 再 hook `Le/h/a/e/c.d()`，补齐 title、当前 index 和未实例化标签状态。
5. 参考 BetterVia 的版本映射机制维护 ViaTabsAgent 映射。
