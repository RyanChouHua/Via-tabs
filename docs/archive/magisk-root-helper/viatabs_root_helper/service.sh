#!/system/bin/sh

MODDIR=${0%/*}
export MODDIR

# Late-start service is non-blocking. Keep this watcher lightweight.
(
  i=0
  boot_done=0
  while [ "$i" -lt 60 ]; do
    if [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ]; then
      boot_done=1
      break
    fi
    i=$((i + 1))
    sleep 2
  done
  [ "$boot_done" = "1" ] || exit 0
  [ -d /storage/emulated/0 ] || exit 0
  sh "$MODDIR/common/export-via-db.sh" watch >/dev/null 2>&1
) &
