#!/usr/bin/env sh
set -eu

# Bounded emulator smoke test. Set ADB, DEVICE, and BINARY to override defaults.
ADB=${ADB:-adb}
DEVICE=${DEVICE:-emulator-5554}
BINARY=${BINARY:-target/x86_64-linux-android/release/rclone-gateway}
REMOTE_BIN=${REMOTE_BIN:-/data/local/tmp/rclone-gateway-it}
REMOTE_ROOT=${REMOTE_ROOT:-/data/local/tmp/rclone-manager-it}
SOCKET="$REMOTE_ROOT/runtime/gateway.sock"
REMOTE_FILES=${REMOTE_FILES:-/data/local/tmp/rclone-manager-it-files}

# Git Bash converts POSIX-looking arguments when invoking a Windows adb.exe;
# disable that conversion so `/data/...` remains an Android path.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

cleanup() {
  "$ADB" -s "$DEVICE" shell "if test -f '$REMOTE_ROOT/runtime/gateway.pid'; then kill \$(cat '$REMOTE_ROOT/runtime/gateway.pid') 2>/dev/null || true; fi; rm -rf '$REMOTE_ROOT' '$REMOTE_FILES' '$REMOTE_BIN'" >/dev/null 2>&1 || true
}
trap cleanup EXIT

[ -f "$BINARY" ] || { printf '%s\n' "missing binary: $BINARY" >&2; exit 1; }
"$ADB" -s "$DEVICE" get-state >/dev/null
"$ADB" -s "$DEVICE" push "$BINARY" "$REMOTE_BIN" >/dev/null
"$ADB" -s "$DEVICE" shell "chmod 0755 '$REMOTE_BIN'; rm -rf '$REMOTE_ROOT' '$REMOTE_FILES'; mkdir -p '$REMOTE_ROOT/runtime' '$REMOTE_FILES'; printf 'rclone manager smoke\n' > '$REMOTE_FILES/item.txt'; '$REMOTE_BIN' serve --root '$REMOTE_ROOT' --socket '$SOCKET' >'$REMOTE_ROOT/gateway.log' 2>&1 & echo \$! > '$REMOTE_ROOT/runtime/gateway.pid'"

i=0
while [ "$i" -lt 10 ]; do
  if "$ADB" -s "$DEVICE" shell "test -S '$SOCKET'" >/dev/null 2>&1; then break; fi
  i=$((i + 1)); sleep 1
done
[ "$i" -lt 10 ] || { "$ADB" -s "$DEVICE" shell "cat '$REMOTE_ROOT/gateway.log'" >&2; exit 1; }

"$ADB" -s "$DEVICE" shell "$REMOTE_BIN probe --socket '$SOCKET'"
# The health endpoint is intentionally unauthenticated. Verify that a
# protected endpoint is not accidentally exposed, then exercise the bounded
# local pairing/token path using only the fixed request CLI.
if "$ADB" -s "$DEVICE" shell "$REMOTE_BIN request --socket '$SOCKET' --method GET --path /api/v1/system/info" >/dev/null 2>&1; then
  printf '%s\n' "protected endpoint unexpectedly accepted no token" >&2
  exit 1
fi
PAIR=$(
  "$ADB" -s "$DEVICE" shell "$REMOTE_BIN request --socket '$SOCKET' --method POST --path /api/v1/security/pairing/start"
)
CODE=$(printf '%s' "$PAIR" | sed -n 's/.*"pairingCode":"\([0-9][0-9][0-9][0-9][0-9][0-9]\)".*/\1/p')
[ -n "$CODE" ] || { printf '%s\n' "pairing response missing code" >&2; exit 1; }
PAIR_BODY=$(printf '{"pairingCode":"%s","clientName":"integration-smoke","publicKey":"integration-key"}' "$CODE" | base64 | tr -d '\r\n')
PAIRED=$(
  "$ADB" -s "$DEVICE" shell "$REMOTE_BIN request --socket '$SOCKET' --method POST --path /api/v1/security/pairing/complete --body-base64 '$PAIR_BODY'"
)
TOKEN=$(printf '%s' "$PAIRED" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { printf '%s\n' "pairing completion missing token" >&2; exit 1; }
"$ADB" -s "$DEVICE" shell "$REMOTE_BIN request --socket '$SOCKET' --method GET --path /api/v1/system/info --token '$TOKEN'" >/dev/null
# Exercise typed Gateway -> rclone local-provider configuration and file
# listing. The first paired client receives security.write but not
# remote.write, so grant only the one capability needed to create the isolated
# test remote. Its existing file.read scope and create-time ACL cover listing.
CLIENT_ID=$(printf '%s' "$PAIRED" | sed -n 's/.*"clientId":"\([^"]*\)".*/\1/p')
[ -n "$CLIENT_ID" ] || { printf '%s\n' "pairing completion missing client id" >&2; exit 1; }
for SCOPE in remote.write file.write file.delete job.execute; do
  GRANT_BODY=$(printf '{"scope":"%s","resource":"*"}' "$SCOPE" | base64 | tr -d '\r\n')
  "$ADB" -s "$DEVICE" shell "$REMOTE_BIN request --socket '$SOCKET' --method POST --path /api/v1/security/clients/$CLIENT_ID/grants --token '$TOKEN' --body-base64 '$GRANT_BODY'" >/dev/null
done
REMOTE_BODY=$(printf '{"name":"local-smoke","type":"local","basePath":"%s"}' "$REMOTE_FILES" | base64 | tr -d '\r\n')
REMOTE=$(
  "$ADB" -s "$DEVICE" shell "$REMOTE_BIN request --socket '$SOCKET' --method POST --path /api/v1/remotes --token '$TOKEN' --body-base64 '$REMOTE_BODY'"
)
REMOTE_ID=$(printf '%s' "$REMOTE" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
[ -n "$REMOTE_ID" ] || { printf '%s\n' "remote creation missing id" >&2; exit 1; }
"$ADB" -s "$DEVICE" shell "$REMOTE_BIN request --socket '$SOCKET' --method GET --path '/api/v1/files?remoteId=$REMOTE_ID&path=$REMOTE_FILES' --token '$TOKEN'" | grep -q 'item.txt'
"$ADB" -s "$DEVICE" shell "$REMOTE_BIN request --socket '$SOCKET' --method POST --path /api/v1/remotes/$REMOTE_ID/test --token '$TOKEN'" | grep -q '"ok":true'
printf '%s\n' "emulator integration smoke passed: $DEVICE"
