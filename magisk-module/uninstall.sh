#!/system/bin/sh
MODDIR=${0%/*}
ROOT=/data/adb/rclone-manage
# Stop only Mount workers recorded by this manager before terminating the
# Gateway. The command never performs a global process kill and preserves all
# persistent DB, secret, backup, and audit data.
if [ -x "$MODDIR/bin/rclone-gateway" ]; then
  "$MODDIR/bin/rclone-gateway" stop --root "$ROOT" >/dev/null 2>&1 || true
fi
if [ -f "$ROOT/runtime/gateway.pid" ]; then
  PID=$(cat "$ROOT/runtime/gateway.pid" 2>/dev/null || true)
  case "$PID" in
    ''|*[!0-9]*) ;;
    *) kill "$PID" 2>/dev/null || true ;;
  esac
fi
if [ -f "$ROOT/runtime/gateway-watchdog.pid" ]; then
  WATCHDOG_PID=$(cat "$ROOT/runtime/gateway-watchdog.pid" 2>/dev/null || true)
  case "$WATCHDOG_PID" in
    ''|*[!0-9]*) ;;
    *) kill "$WATCHDOG_PID" 2>/dev/null || true ;;
  esac
fi
rm -f "$ROOT/runtime/gateway.sock" "$ROOT/runtime/gateway.pid" "$ROOT/runtime/gateway-watchdog.pid"

# Check if KEEP_ON_UNINSTALL flag exists in data directory
if [ -f "$ROOT/KEEP_ON_UNINSTALL" ]; then
  # Persistent DB, secrets, backups, and configs intentionally remain.
  rm -f "$ROOT/KEEP_ON_UNINSTALL" 2>/dev/null || true
else
  # Thoroughly remove data directory if flag not set
  rm -rf "$ROOT"
fi
