#!/system/bin/sh
umask 077
ROOT=/data/adb/rclone-manage
mkdir -p "$ROOT"/db "$ROOT"/keys "$ROOT"/secrets "$ROOT"/runtime "$ROOT"/logs "$ROOT"/backups "$ROOT"/migrations "$ROOT"/cache
chmod 700 "$ROOT" 2>/dev/null || true
chmod 700 "$ROOT"/* 2>/dev/null || true
[ ! -f "$ROOT/runtime/lan.conf" ] || chmod 600 "$ROOT/runtime/lan.conf" 2>/dev/null || true
