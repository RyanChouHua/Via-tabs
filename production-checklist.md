# Production Checklist

## 必需工具

MVP：

- Android SDK Platform Tools
- adb
- Android 手机或 Android Emulator
- Via 浏览器
- USB 调试或模拟器 ADB 连接

增强版：

- JDK 17
- Android Studio 或 Android Gradle Plugin
- jadx
- apktool
- LSPosed 或 LSPatch

## 当前环境检查

| 项目 | 当前状态 |
| --- | --- |
| git | 可用 |
| Java | 可用，Java 8 |
| adb | 可用，`tools/adb/adb.exe`，版本 34.0.5 |
| SDK adb | 可用，`C:\A_Program\Env\android-sdk\platform-tools\adb.exe`，版本 37.0.0 |
| apktool | 可用，`C:\A_Program\Env\apktool\apktool_3.0.2.jar` |
| jadx | 不可用 |
| Android 设备 | 未连接 |
| Android Emulator | 已安装并验证，AVD：`ViaTabs_API35` |
| BetterVia 源码 | 已克隆 |
| BetterVia APK ZIP 分析 | 已完成 |
| Via 7.0.0 APK apktool 分析 | 已完成国内版，GP 版代码层完成 |
| Via GP APK 模拟器验证 | 已安装并启动，包名 `mark.via.gp` |

## 模拟器验证路线

可以不连接手机，改用 Android Emulator。推荐先用官方 Android Studio Device Manager 创建 AVD；如果后续要验证 root / LSPosed，优先选择不带 Google Play Store 的 AOSP system image，因为 Google Play 镜像通常不能获取 root。

推荐 AVD：

- Pixel 系列手机 profile。
- Android 11 到 Android 14 均可先测。
- ADB UI 自动化 MVP：Google APIs 或 Google Play 镜像都可以。
- root / LSPosed 验证：AOSP 镜像优先。

创建并启动后检查：

```powershell
.\tools\adb\adb.exe devices
```

如果安装了 Android SDK，可用命令行查看和启动 AVD：

```powershell
emulator -list-avds
emulator -avd <avd_name>
```

当前已创建并验证：

```text
ViaTabs_API35
```

启动命令：

```powershell
$env:ANDROID_AVD_HOME='C:\Users\Ryan\.android\avd'
$env:ANDROID_SDK_ROOT='C:\A_Program\Env\android-sdk'
$env:ANDROID_HOME='C:\A_Program\Env\android-sdk'
C:\A_Program\Env\android-sdk\emulator\emulator.exe -avd ViaTabs_API35 -port 5554 -no-window -no-audio -gpu off -no-snapshot -no-boot-anim
```

已验证：

- `emulator-5554` 可通过 ADB 连接。
- `sys.boot_completed=1`。
- `mark.via.gp` 可安装并启动。
- Via 已打开 `https://example.com`。
- `uiautomator dump` 可读取到 `Example Domain`、`android.webkit.WebView`、`2 tabs`。

## MVP 验证步骤

```powershell
C:\A_Program\Env\android-sdk\platform-tools\adb.exe devices
C:\A_Program\Env\android-sdk\platform-tools\adb.exe install -r .\research\BetterVia\Monet_GP\Via_7.0.0_GP_Moneted.apk
C:\A_Program\Env\android-sdk\platform-tools\adb.exe shell pm list packages | findstr via
C:\A_Program\Env\android-sdk\platform-tools\adb.exe shell monkey -p mark.via.gp -c android.intent.category.LAUNCHER 1
C:\A_Program\Env\android-sdk\platform-tools\adb.exe shell uiautomator dump /sdcard/window.xml
C:\A_Program\Env\android-sdk\platform-tools\adb.exe shell cat /sdcard/window.xml
```

成功标准：

- `adb devices` 显示 `device`。
- 能找到 Via 包名。
- Via 能被启动。
- `window.xml` 中能看到当前 Via UI。
- 地址栏聚焦后，XML 中能读取 URL。

## Xposed/LSPatch 验证步骤

1. 确认目标 Via 包名：`mark.via` 或 `mark.via.gp`。
2. 构建 ViaTabsAgent Xposed 模块。
3. 在 LSPosed 中将模块作用域设置为 Via。
4. 或使用 LSPatch 将模块集成到 Via APK。
5. 启动 Via。
6. Via 7.0.0 优先 hook：

```text
Le/h/a/e/c.C()
Le/h/a/e/c.d()
Le/h/a/g/b.g()
Le/h/a/g/a.getUrl()
```

7. 其他版本无法匹配映射时，再退回 hook `WebView.getUrl()` 和 `WebView.getTitle()`。
8. 写出：

```text
/storage/emulated/0/Android/data/<via_package>/files/ViaTabsAgent/tabs.json
```

9. 通过 ADB 拉取：

```powershell
adb pull /storage/emulated/0/Android/data/mark.via.gp/files/ViaTabsAgent/tabs.json
```

## 生产风险

- LSPatch 官方仓库已归档，长期维护需要评估 fork 或替代框架。
- Via 新版本混淆名可能变化。
- Android 11+ 对外部存储、包可见性、后台限制更严格。
- 非 root 重打包会涉及签名变化，可能影响升级和数据保留。

## 当前生产增强验证命令

构建 agent：

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
$env:ANDROID_HOME='C:\A_Program\Env\android-sdk'
$env:ANDROID_SDK_ROOT='C:\A_Program\Env\android-sdk'
$env:Path="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
.\research\BetterVia\gradlew.bat -p .\ViaTabsAgent assembleDebug
```

使用 LSPatch 将 agent 集成进 Via 7.0.0 GP APK：

```powershell
& 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\java.exe' `
  -jar '.\tools\lspatch\lspatch.jar' `
  -f `
  -o '.\out\lspatch' `
  -m '.\ViaTabsAgent\app\build\outputs\apk\debug\app-debug.apk' `
  '.\research\BetterVia\Monet_GP\Via_7.0.0_GP_Moneted.apk'
```

安装并验证读取标签、保存书签、创建分组：

```powershell
$adb='C:\A_Program\Env\android-sdk\platform-tools\adb.exe'
$serial='emulator-5556'

& $adb -s $serial install -r '.\out\lspatch\Via_7.0.0_GP_Moneted-398-lspatched.apk'
& $adb -s $serial shell logcat -c
& $adb -s $serial shell monkey -p mark.via.gp -c android.intent.category.LAUNCHER 1
Start-Sleep -Seconds 10
& $adb -s $serial shell am broadcast -a com.viatabs.agent.SAVE_TABS_TO_BOOKMARKS --es folder ViaTabsAgent
Start-Sleep -Seconds 3
& $adb -s $serial shell am broadcast -a com.viatabs.agent.GROUP_TABS --es group SessionGroup --ez bookmarks true
Start-Sleep -Seconds 3
& $adb -s $serial shell logcat -d -s ViaTabsAgent LSPosed-Bridge
& $adb -s $serial shell cat /sdcard/Android/data/mark.via.gp/files/ViaTabsAgent/saved-bookmarks.json
& $adb -s $serial shell cat /sdcard/Android/data/mark.via.gp/files/ViaTabsAgent/tab-groups.json
```

验收标准：

- logcat 出现 `saved tabs to bookmarks`。
- logcat 出现 `grouped tabs`。
- `saved-bookmarks.json` 包含当前标签页快照、目标书签文件夹、`inserted` 和 `skipped` 统计。
- `tab-groups.json` 追加当前分组快照。
- `file://` 等 Via 内部页面不写入原生书签，但仍保留在分组 JSON 中。

注意事项：

- `am broadcast --es folder` 和 `--es group` 的值尽量不要包含空格；Windows/ADB shell 多层转义容易导致参数被拆开。
- 首次 `pm clear mark.via.gp` 后 Via 会进入欢迎页，需要先点同意，再等待标签管理器初始化。
- Android 15 模拟器与当前 LSPatch 版本兼容性较差；当前验证优先使用 Android 13 模拟器。
