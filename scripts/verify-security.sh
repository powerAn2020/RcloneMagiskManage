#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

fail() { printf '%s\n' "security verification failed: $*" >&2; exit 1; }

# Narrow checks catch accidental reintroduction of raw RC or arbitrary process
# execution without rejecting the allow-listed libsu Shell wrapper.
if rg -n '(/api/v1/rc/|core/command|options/set|pluginsctl/|Runtime\.getRuntime|ProcessBuilder)' app gateway/src/main.rs | rg -v 'assert!|assert_eq!'; then
  fail "raw RC or arbitrary process execution pattern found"
fi
rg -n 'request --socket|allowed_request|require_signature|tls-client-ca' gateway/src/main.rs README.md >/dev/null \
  || fail "fixed request/TLS boundary not present"
rg -n 'secretRef|encrypt_secret|token_hash|X-Signature' gateway/src/main.rs >/dev/null \
  || fail "secret/auth protections not present"
rg -n '^    private suspend fun request\(' app/src/main/java/com/android/rclone/manager/GatewayClient.kt >/dev/null \
  || fail "Android client exposes a generic Gateway request"
if rg -n '^    (public )?suspend fun request\(' app/src/main/java/com/android/rclone/manager/GatewayClient.kt; then
  fail "Android client generic request must remain private"
fi

cargo fmt --all --check
cargo test --workspace
printf '%s\n' 'security verification passed'
