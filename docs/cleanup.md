# 本地资源清理说明

## 仓库应保留

```text
ViaTabsAgent/
docs/
README.md
AGENTS.md
.gitignore
```

## 默认不提交

```text
out/
research/
tools/
*.apk
*.aab
*.ap_
*.png
*.log
via-*.json
via-*.html
saved-bookmarks.json
```

说明：

- `out/` 是构建、截图、临时 APK、脚本调试输出。
- `research/` 是反编译、外部项目和实验资料，不适合提交。
- `tools/` 是本地工具二进制，可重新下载。
- APK 和手机导出数据只放外部备份目录，不放 Git。

## 当前已归档资料

```text
docs/archive/magisk-root-helper/
docs/archive/xposed-lsposed-research/
```

归档资料可提交，但不要在没有明确要求时恢复到主线。

## 清理检查

```powershell
git status --short --untracked-files=all
git diff --check
```

确认顶层没有第三方 APK、截图、旧 `release/`、旧 `out/`。

## 发布产物

构建 APK 后如需保存，复制到外部备份目录：

```powershell
Copy-Item -Force ViaTabsAgent\app\build\outputs\apk\debug\app-debug.apk `
  'Z:\fn246\BackUp\G15\资源\viatabs\ViaTabsAgent-<version>-debug.apk'
```

不要把 APK 放回仓库。
