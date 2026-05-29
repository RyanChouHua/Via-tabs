#!/system/bin/sh

PUBLIC_DIR="/storage/emulated/0/Download/ViaTabsAgent"
[ -d "$PUBLIC_DIR" ] && echo "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || date)" > "$PUBLIC_DIR/root-helper.stop"
rm -f "$PUBLIC_DIR/root-helper.request" \
      "$PUBLIC_DIR/root-helper.done" \
      "$PUBLIC_DIR/root-helper.status" \
      "$PUBLIC_DIR/root-helper.pid" 2>/dev/null || true
