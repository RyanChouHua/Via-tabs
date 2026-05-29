#!/system/bin/sh
set -u

AGENT_PKG="${1:-com.viatabs.agent}"
TARGETS="mark.via mark.via.gp"

if [ "$(id -u)" != "0" ]; then
  exec su -c "sh '$0' '$AGENT_PKG'"
fi

AGENT_DATA="/data/user/0/$AGENT_PKG"
if [ ! -d "$AGENT_DATA" ]; then
  echo "missing agent app data: $AGENT_DATA"
  echo "Open ViaTabsAgent once, then run this script again."
  exit 3
fi

APP_UID="$(stat -c '%u' "$AGENT_DATA" 2>/dev/null || ls -ldn "$AGENT_DATA" 2>/dev/null | awk '{print $3}')"
APP_GID="$(stat -c '%g' "$AGENT_DATA" 2>/dev/null || ls -ldn "$AGENT_DATA" 2>/dev/null | awk '{print $4}')"
if [ -z "$APP_UID" ]; then
  echo "cannot read app uid from $AGENT_DATA"
  exit 4
fi
if [ -z "$APP_GID" ]; then
  APP_GID="$APP_UID"
fi

OUT_DIR="$AGENT_DATA/files/offline-via-tabs"
mkdir -p "$OUT_DIR"
chown "$APP_UID:$APP_GID" "$OUT_DIR" 2>/dev/null || true
chmod 700 "$OUT_DIR" 2>/dev/null || true

OK_COUNT=0
SKIP_COUNT=0
FAIL_COUNT=0

prepare_one() {
  TARGET_PKG="$1"
  SAFE_PKG="$(echo "$TARGET_PKG" | tr . _)"
  OUT_DB="$OUT_DIR/$SAFE_PKG-via.db"
  OUT_WAL="${OUT_DB}-wal"
  OUT_SHM="${OUT_DB}-shm"

  SRC_DB=""
  for candidate in \
    "/data/user/0/$TARGET_PKG/databases/via" \
    "/data/data/$TARGET_PKG/databases/via" \
    "/data_mirror/data_ce/null/0/$TARGET_PKG/databases/via"
  do
    if [ -z "$SRC_DB" ] && [ -f "$candidate" ]; then
      SRC_DB="$candidate"
    fi
  done

  echo "---- $TARGET_PKG ----"
  if [ -z "$SRC_DB" ]; then
    echo "skipped: missing Via db"
    SKIP_COUNT=$((SKIP_COUNT + 1))
    return 0
  fi

  am force-stop "$TARGET_PKG" 2>/dev/null || true
  rm -f "$OUT_DB" "$OUT_WAL" "$OUT_SHM"

  if command -v sqlite3 >/dev/null 2>&1; then
    if ! sqlite3 "$SRC_DB" ".backup '$OUT_DB'"; then
      echo "failed: sqlite backup failed"
      FAIL_COUNT=$((FAIL_COUNT + 1))
      return 0
    fi
  else
    if ! cp "$SRC_DB" "$OUT_DB"; then
      echo "failed: db copy failed"
      FAIL_COUNT=$((FAIL_COUNT + 1))
      return 0
    fi
    [ -f "${SRC_DB}-wal" ] && cp "${SRC_DB}-wal" "$OUT_WAL" 2>/dev/null || true
    [ -f "${SRC_DB}-shm" ] && cp "${SRC_DB}-shm" "$OUT_SHM" 2>/dev/null || true
  fi

  chown "$APP_UID:$APP_GID" "$OUT_DB" "$OUT_WAL" "$OUT_SHM" 2>/dev/null || true
  chmod 600 "$OUT_DB" "$OUT_WAL" "$OUT_SHM" 2>/dev/null || true
  restorecon -R "$OUT_DIR" 2>/dev/null || true

  if [ ! -f "$OUT_DB" ]; then
    echo "failed: output db missing"
    FAIL_COUNT=$((FAIL_COUNT + 1))
    return 0
  fi

  echo "prepared Via db"
  echo "target=$TARGET_PKG"
  echo "source=$SRC_DB"
  echo "output=$OUT_DB"
  ls -lZ "$OUT_DB" "$OUT_WAL" "$OUT_SHM" 2>/dev/null || ls -l "$OUT_DB" "$OUT_WAL" "$OUT_SHM" 2>/dev/null || true
  OK_COUNT=$((OK_COUNT + 1))
}

for target in $TARGETS; do
  prepare_one "$target"
done

echo "---- summary ----"
echo "prepared=$OK_COUNT skipped=$SKIP_COUNT failed=$FAIL_COUNT"

if [ "$OK_COUNT" -gt 0 ]; then
  exit 0
fi
if [ "$FAIL_COUNT" -gt 0 ]; then
  exit 1
fi
exit 2
