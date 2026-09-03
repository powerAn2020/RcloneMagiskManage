#!/system/bin/sh
umask 077
MODDIR=${0%/*}
ROOT=/data/adb/rclone-manage
SOCKET="$ROOT/runtime/gateway.sock"
LOG="$ROOT/logs/gateway.log"
mkdir -p "$ROOT"/db "$ROOT"/keys "$ROOT"/secrets "$ROOT"/runtime "$ROOT"/logs "$ROOT"/backups "$ROOT"/migrations "$ROOT"/cache
chmod 700 "$ROOT" 2>/dev/null || true
chmod 700 "$ROOT"/* 2>/dev/null || true
LAN_ARGS=""
LAN_CONF="$ROOT/runtime/lan.conf"
if [ -f "$LAN_CONF" ]; then
  chmod 600 "$LAN_CONF" 2>/dev/null || true
  LAN_ADDR=""
  TLS_CERT=""
  TLS_KEY=""
  TLS_CLIENT_CA=""
  while IFS='=' read -r KEY VALUE; do
    case "$KEY" in
      LAN_ADDR|TLS_CERT|TLS_KEY|TLS_CLIENT_CA) ;;
      ''|'#'*) continue ;;
      *) echo "ignored invalid LAN config key" >>"$LOG"; continue ;;
    esac
    case "$VALUE" in
      ''|*[!A-Za-z0-9:./_\-\[\]]*) echo "ignored invalid LAN config value for $KEY" >>"$LOG"; continue ;;
    esac
    case "$KEY" in
      LAN_ADDR) LAN_ADDR="$VALUE" ;;
      TLS_CERT) TLS_CERT="$VALUE" ;;
      TLS_KEY) TLS_KEY="$VALUE" ;;
      TLS_CLIENT_CA) TLS_CLIENT_CA="$VALUE" ;;
    esac
  done < "$LAN_CONF"
  if [ -n "$LAN_ADDR" ] && [ -n "$TLS_CERT" ] && [ -n "$TLS_KEY" ]; then
    LAN_ARGS="--lan-addr $LAN_ADDR --tls-cert $TLS_CERT --tls-key $TLS_KEY"
    if [ -n "$TLS_CLIENT_CA" ]; then LAN_ARGS="$LAN_ARGS --tls-client-ca $TLS_CLIENT_CA"; fi
  elif [ -n "$LAN_ADDR" ] || [ -n "$TLS_CERT" ] || [ -n "$TLS_KEY" ] || [ -n "$TLS_CLIENT_CA" ]; then
    echo "LAN config incomplete; listener remains disabled" >>"$LOG"
  fi
fi
if [ -x "$MODDIR/bin/rclone-gateway" ]; then
  # Import legacy sync/copy lines once; migration is idempotent.
  LEGACY=/data/adb/modules/rclone/conf
  if [ -d "$LEGACY" ]; then
    "$MODDIR/bin/rclone-gateway" migrate --root "$ROOT" --legacy "$LEGACY" >>"$LOG" 2>&1 || true
  fi
  if [ -f "$ROOT/runtime/gateway.pid" ]; then
    OLD_PID=$(cat "$ROOT/runtime/gateway.pid" 2>/dev/null || true)
    case "$OLD_PID" in
      ''|*[!0-9]*) ;;
      *) kill -TERM "$OLD_PID" 2>/dev/null || true ;;
    esac
  fi
  start_gateway() {
    rm -f "$SOCKET" "$ROOT/runtime/gateway.pid"
    # LAN_ARGS contains only validated, positional values from root-only
    # lan.conf; keep each value quoted when invoking the Gateway.
    if [ -n "$LAN_ARGS" ]; then
      # shellcheck disable=SC2086 -- values are restricted to safe path/address characters
      "$MODDIR/bin/rclone-gateway" serve --root "$ROOT" --socket "$SOCKET" $LAN_ARGS >>"$LOG" 2>&1 &
    else
      "$MODDIR/bin/rclone-gateway" serve --root "$ROOT" --socket "$SOCKET" >>"$LOG" 2>&1 &
    fi
    echo $! > "$ROOT/runtime/gateway.pid"
  }
  start_gateway
  # Wait briefly for the Unix endpoint before leaving boot service. This is a
  # readiness check only; it never sends requests or blocks boot indefinitely.
  READY=0
  i=0
  while [ "$i" -lt 10 ]; do
    if [ -S "$SOCKET" ]; then
      READY=1
      break
    fi
    sleep 1
    i=$((i + 1))
  done
  if [ "$READY" -ne 1 ]; then
    echo "gateway socket did not become ready" >>"$LOG"
  fi
  # Magisk service has no supervisor. Keep a narrow watchdog for this gateway
  # only; it never kills unrelated rclone workers or uses global pkill.
  if [ -f "$ROOT/runtime/gateway-watchdog.pid" ]; then
    WATCHDOG_OLD=$(cat "$ROOT/runtime/gateway-watchdog.pid" 2>/dev/null || true)
    case "$WATCHDOG_OLD" in ''|*[!0-9]*) ;; *) kill "$WATCHDOG_OLD" 2>/dev/null || true ;; esac
  fi
  (
    while sleep 30; do
      PID=$(cat "$ROOT/runtime/gateway.pid" 2>/dev/null || true)
      case "$PID" in
        ''|*[!0-9]*)
          COUNT=$(cat "$ROOT/runtime/gateway-crash-count" 2>/dev/null || echo 0)
          case "$COUNT" in ''|*[!0-9]*) COUNT=0 ;; esac
          COUNT=$((COUNT + 1))
          echo "$COUNT" > "$ROOT/runtime/gateway-crash-count"
          if [ "$COUNT" -ge 3 ]; then
            echo 1 > "$ROOT/runtime/safe-mode"
            chmod 600 "$ROOT/runtime/safe-mode" 2>/dev/null || true
          fi
          start_gateway ;;
        *)
          if ! kill -0 "$PID" 2>/dev/null; then
            COUNT=$(cat "$ROOT/runtime/gateway-crash-count" 2>/dev/null || echo 0)
            case "$COUNT" in ''|*[!0-9]*) COUNT=0 ;; esac
            COUNT=$((COUNT + 1))
            echo "$COUNT" > "$ROOT/runtime/gateway-crash-count"
            if [ "$COUNT" -ge 3 ]; then
              echo 1 > "$ROOT/runtime/safe-mode"
              chmod 600 "$ROOT/runtime/safe-mode" 2>/dev/null || true
            fi
            start_gateway
          elif [ -S "$SOCKET" ]; then
            # Crash count is consecutive-failure state. Once the Gateway has
            # stayed alive and its endpoint is present, clear old failures so
            # an unrelated historical crash cannot force Safe Mode later.
            echo 0 > "$ROOT/runtime/gateway-crash-count"
            chmod 600 "$ROOT/runtime/gateway-crash-count" 2>/dev/null || true
          fi ;;
      esac
    done
  ) >/dev/null 2>&1 &
  echo $! > "$ROOT/runtime/gateway-watchdog.pid"
fi
