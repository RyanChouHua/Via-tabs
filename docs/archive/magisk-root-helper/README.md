# ViaTabs Root Helper 归档

这是已暂停的 Magisk / KernelSU / APatch root helper 实验路线。

当前主线已经回到终端 `sh`：

```sh
su
sh /storage/emulated/0/Download/ViaTabsAgent/prepare-via-all-db.sh
```

除非明确重新评估开机风险和维护成本，不要把本目录代码恢复到 App 主线或默认发布流程。

## 原设计

模块只负责 root 侧导出 Via DB，ViaTabsAgent App 负责解析、备份管理、备注、删除和书签 HTML 导出。

原输出 DB：

```text
/data/user/0/com.viatabs.agent/files/offline-via-tabs/mark_via-via.db
/data/user/0/com.viatabs.agent/files/offline-via-tabs/mark_via_gp-via.db
```

原状态/日志：

```text
/storage/emulated/0/Download/ViaTabsAgent/root-helper.status
/storage/emulated/0/Download/ViaTabsAgent/root-helper.log
```

## 文件

```text
viatabs_root_helper/
├── module.prop
├── skip_mount
├── customize.sh
├── service.sh
├── action.sh
├── uninstall.sh
├── common/
│   ├── viatabs-lib.sh
│   └── export-via-db.sh
└── webroot/
    ├── index.html
    ├── app.js
    └── style.css
```

## 如需重新打包

从仓库根目录：

```powershell
New-Item -ItemType Directory -Force out/root-helper
tar -a -c -f out/root-helper/ViaTabsRootHelper-0.1.1-magisk-ksu.zip `
  -C docs/archive/magisk-root-helper/viatabs_root_helper .
```

重新启用前必须重新做安全检查：

- 不使用 `post-fs-data.sh`。
- `service.sh` 不阻塞开机。
- 不包含 `META-INF/update-binary` 或 `updater-script`。
- 不包含 `rm -rf`、`dd`、`mount`、`setenforce`、`reboot`、`resetprop`、`/dev/block`、`system.prop`、`sepolicy.rule`。
- 不修改 `/system`、`/vendor`、`/product`、`/odm`。
