#!/system/bin/sh

MODDIR="${MODDIR:-${0%/*}/..}"
. "$MODDIR/common/viatabs-lib.sh"

export_one() {
  TARGET_PKG="$1"
  UID_GID="$2"
  OUT_DIR="$3"

  case "$TARGET_PKG" in
    mark.via|mark.via.gp) ;;
    *)
      log_line "target=$TARGET_PKG skipped unsupported-target"
      SKIP_COUNT=$((SKIP_COUNT + 1))
      return 0
      ;;
  esac

  SAFE_PKG="$(safe_pkg_name "$TARGET_PKG")"
  OUT_DB="$OUT_DIR/$SAFE_PKG-via.db"
  SRC_DB="$(find_via_db "$TARGET_PKG")"

  log_line "target=$TARGET_PKG start"
  if [ -z "$SRC_DB" ]; then
    log_line "target=$TARGET_PKG skipped missing-db"
    SKIP_COUNT=$((SKIP_COUNT + 1))
    return 0
  fi

  am force-stop "$TARGET_PKG" 2>/dev/null || true
  if copy_db_safely "$SRC_DB" "$OUT_DB"; then
    chown "$UID_GID" "$OUT_DB" "${OUT_DB}-wal" "${OUT_DB}-shm" 2>/dev/null || true
    chmod 600 "$OUT_DB" "${OUT_DB}-wal" "${OUT_DB}-shm" 2>/dev/null || true
    restorecon -R "$OUT_DIR" 2>/dev/null || true
    SIZE="$(ls -l "$OUT_DB" 2>/dev/null | awk '{print $5}')"
    log_line "target=$TARGET_PKG ok source=$SRC_DB output=$OUT_DB size=${SIZE:-unknown}"
    OK_COUNT=$((OK_COUNT + 1))
  else
    log_line "target=$TARGET_PKG failed source=$SRC_DB output=$OUT_DB"
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
}

run_export() {
  ensure_public_dir
  log_line "export requested mode=${1:-manual}"
  write_status "running $(timestamp)"

  AGENT_DATA="$(agent_data_dir)"
  if [ ! -d "$AGENT_DATA" ]; then
    log_line "failed missing-agent-data path=$AGENT_DATA"
    write_status "failed missing-agent-data"
    echo "missing agent app data: $AGENT_DATA"
    echo "Open ViaTabsAgent once, then run export again."
    return 3
  fi

  UID_GID="$(agent_uid_gid)" || {
    log_line "failed cannot-read-agent-uid path=$AGENT_DATA"
    write_status "failed cannot-read-agent-uid"
    echo "cannot read app uid from $AGENT_DATA"
    return 4
  }

  OUT_DIR="$AGENT_DATA/files/offline-via-tabs"
  mkdir -p "$OUT_DIR"
  chown "$UID_GID" "$AGENT_DATA/files" "$OUT_DIR" 2>/dev/null || true
  chmod 700 "$OUT_DIR" 2>/dev/null || true

  OK_COUNT=0
  SKIP_COUNT=0
  FAIL_COUNT=0
  for target in $TARGETS; do
    export_one "$target" "$UID_GID" "$OUT_DIR"
  done

  SUMMARY="prepared=$OK_COUNT skipped=$SKIP_COUNT failed=$FAIL_COUNT"
  log_line "summary $SUMMARY"
  echo "$SUMMARY"
  echo "$(timestamp) $SUMMARY" > "$DONE_FILE"
  write_status "$SUMMARY"

  if [ "$OK_COUNT" -gt 0 ]; then
    return 0
  fi
  if [ "$FAIL_COUNT" -gt 0 ]; then
    return 1
  fi
  return 2
}

watch_loop() {
  ensure_public_dir
  if [ -f "$PID_FILE" ]; then
    OLD_PID="$(cat "$PID_FILE" 2>/dev/null)"
    if is_process_alive "$OLD_PID"; then
      log_line "watcher already running pid=$OLD_PID"
      return 0
    fi
  fi
  echo "$$" > "$PID_FILE"
  log_line "watcher started pid=$$"
  write_status "watching $(timestamp)"
  if [ -f "$REQUEST_FILE" ]; then
    rm -f "$REQUEST_FILE" 2>/dev/null || true
    log_line "watcher cleared stale request on start"
  fi
  rm -f "$STOP_FILE" 2>/dev/null || true

  while true; do
    if [ -f "$STOP_FILE" ]; then
      rm -f "$STOP_FILE" "$PID_FILE" 2>/dev/null || true
      log_line "watcher stopped by stop file"
      write_status "stopped $(timestamp)"
      return 0
    fi
    if [ -f "$REQUEST_FILE" ]; then
      REQ="$(cat "$REQUEST_FILE" 2>/dev/null)"
      rm -f "$REQUEST_FILE" 2>/dev/null || true
      run_export "request:${REQ:-file}" >/dev/null 2>&1
    fi
    sleep 3
  done
}

case "${1:-once}" in
  once)
    run_export "manual"
    ;;
  request)
    ensure_public_dir
    echo "$(timestamp)" > "$REQUEST_FILE"
    echo "request written: $REQUEST_FILE"
    ;;
  watch)
    watch_loop
    ;;
  status)
    ensure_public_dir
    [ -f "$STATUS_FILE" ] && cat "$STATUS_FILE" || echo "no status"
    ;;
  log)
    ensure_public_dir
    [ -f "$LOG_FILE" ] && tail -n 120 "$LOG_FILE" || echo "no log"
    ;;
  clear-log)
    ensure_public_dir
    : > "$LOG_FILE"
    echo "log cleared"
    ;;
  *)
    echo "usage: $0 {once|request|watch|status|log|clear-log}"
    exit 64
    ;;
esac
