# 本地资源清理说明

## 当前可释放空间

本项目迭代期间创建过模拟器、system image、反编译产物和多版 APK 输出。GitHub 仓库只需要源码和文档，下列内容均可重建或重新下载。

## 项目目录内

可删除：

```text
out/
ViaTabsAgent/app/build/
ViaTabsAgent/.gradle/
debug.log
saved-bookmarks.json
```

默认不上传：

```text
research/
tools/
```

说明：

- `research/` 包含 BetterVia 参考代码和 Via APK 反编译产物，体积大，不适合提交。
- `tools/` 包含 adb、lspatch 等本地工具，后续可从官方来源重新获取。
- `out/` 是构建、截图、修补 APK 输出。

## Android 模拟器资源

当前本机发现：

```text
C:\Users\Ryan\.android\avd\ViaTabs_API33.avd
C:\Users\Ryan\.android\avd\ViaTabs_API35.avd
C:\A_Program\Env\android-sdk\system-images\android-33\google_apis\x86_64
C:\A_Program\Env\android-sdk\system-images\android-35\google_apis\x86_64
```

建议：

- 如果短期不再用模拟器测试，可删除两个 AVD。
- 如果要保留一个，建议保留 API 35，删除 API 33。
- 如果硬盘压力很大，可同时删除未使用 system image；后续需要时再用 `sdkmanager` 下载。

删除 AVD 前建议确认：

```powershell
emulator -list-avds
```

删除 system image 建议优先使用 Android Studio SDK Manager 或 `sdkmanager --uninstall`，不建议手动删一半。

## GitHub 上传前检查

```powershell
git status --short
git diff --check
```

确认没有：

- APK
- 反编译目录
- 日志
- 书签导出数据
- 本地工具二进制
