#!/system/bin/sh
set -eu
ROOT=/data/adb/rclone-manage
LEGACY=/data/adb/modules/rclone/conf
mkdir -p "$ROOT/migrations"
STAMP=$(date +%s)
REPORT="$ROOT/migrations/discovery-$STAMP.txt"
{
  echo "version=1"
  echo "timestamp=$STAMP"
  for name in rclone.conf env sync copy; do
    path="$LEGACY/$name"
    if [ -f "$path" ]; then
      echo "$name=present path=$path"
    else
      echo "$name=missing path=$path"
    fi
  done
  echo "mode=read-only"
  echo "action=manual-review-required"
} > "$REPORT"
chmod 0600 "$REPORT"
echo "$REPORT"

