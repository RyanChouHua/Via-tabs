#!/system/bin/sh

AGENT_PKG="com.viatabs.agent"
TARGETS="mark.via mark.via.gp"
PUBLIC_DIR="/storage/emulated/0/Download/ViaTabsAgent"
LOG_FILE="$PUBLIC_DIR/root-helper.log"
REQUEST_FILE="$PUBLIC_DIR/root-helper.request"
DONE_FILE="$PUBLIC_DIR/root-helper.done"
STATUS_FILE="$PUBLIC_DIR/root-helper.status"
PID_FILE="$PUBLIC_DIR/root-helper.pid"
STOP_FILE="$PUBLIC_DIR/root-helper.stop"
MAX_LOG_BYTES="${MAX_LOG_BYTES:-65536}"

timestamp() {
  date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || date
}

ensure_public_dir() {
  mkdir -p "$PUBLIC_DIR" 2>/dev/null || true
  chmod 0775 "$PUBLIC_DIR" 2>/dev/null || true
}

log_line() {
  ensure_public_dir
  echo "$(timestamp)  $*" >> "$LOG_FILE"
  trim_log_file
}

write_status() {
  ensure_public_dir
  echo "$*" > "$STATUS_FILE"
}

trim_log_file() {
  [ -f "$LOG_FILE" ] || return 0
  SIZE="$(wc -c < "$LOG_FILE" 2>/dev/null || echo 0)"
  [ "$SIZE" -gt "$MAX_LOG_BYTES" ] || return 0
  tail -c "$MAX_LOG_BYTES" "$LOG_FILE" > "${LOG_FILE}.tmp" 2>/dev/null \
    && mv "${LOG_FILE}.tmp" "$LOG_FILE" 2>/dev/null || rm -f "${LOG_FILE}.tmp" 2>/dev/null || true
}

is_process_alive() {
  PID="$1"
  [ -n "$PID" ] || return 1
  [ -d "/proc/$PID" ] || return 1
  CMDLINE="$(tr '\000' ' ' < "/proc/$PID/cmdline" 2>/dev/null || true)"
  echo "$CMDLINE" | grep -q "export-via-db.sh"
}

agent_data_dir() {
  echo "/data/user/0/$AGENT_PKG"
}

agent_uid_gid() {
  AGENT_DATA="$(agent_data_dir)"
  APP_UID="$(stat -c '%u' "$AGENT_DATA" 2>/dev/null || ls -ldn "$AGENT_DATA" 2>/dev/null | awk '{print $3}')"
  APP_GID="$(stat -c '%g' "$AGENT_DATA" 2>/dev/null || ls -ldn "$AGENT_DATA" 2>/dev/null | awk '{print $4}')"
  [ -n "$APP_GID" ] || APP_GID="$APP_UID"
  [ -n "$APP_UID" ] || return 1
  echo "$APP_UID:$APP_GID"
}

safe_pkg_name() {
  echo "$1" | tr . _
}

find_via_db() {
  TARGET_PKG="$1"
  for candidate in \
    "/data/user/0/$TARGET_PKG/databases/via" \
    "/data/data/$TARGET_PKG/databases/via" \
    "/data_mirror/data_ce/null/0/$TARGET_PKG/databases/via"
  do
    if [ -f "$candidate" ]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

copy_db_safely() {
  SRC_DB="$1"
  OUT_DB="$2"
  OUT_WAL="${OUT_DB}-wal"
  OUT_SHM="${OUT_DB}-shm"

  rm -f "$OUT_DB" "$OUT_WAL" "$OUT_SHM"
  if command -v sqlite3 >/dev/null 2>&1; then
    sqlite3 "$SRC_DB" ".backup '$OUT_DB'" || return 1
  else
    cp "$SRC_DB" "$OUT_DB" || return 1
    [ -f "${SRC_DB}-wal" ] && cp "${SRC_DB}-wal" "$OUT_WAL" 2>/dev/null || true
    [ -f "${SRC_DB}-shm" ] && cp "${SRC_DB}-shm" "$OUT_SHM" 2>/dev/null || true
  fi
  [ -f "$OUT_DB" ] || return 1
  return 0
}
