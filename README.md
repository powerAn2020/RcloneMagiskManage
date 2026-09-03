# Android Rclone Root Manager

Rust Gateway implementation and Magisk module foundation. The service owns `/data/adb/rclone-manage`, uses a Unix-domain socket, SQLite, scoped bearer clients, encrypted secret blobs, path/remote ACL checks, and typed remote/file/job/mount routes. It never exposes raw rclone RC or arbitrary shell.

交接与当前验证状态见 [`HANDOFF.md`](HANDOFF.md)；测试证据见 [`TESTING.md`](TESTING.md)。

Implemented gateway capabilities include least-privilege pairing and grant management, optional HMAC request signing with replay protection, typed secret-to-rclone config materialization, scheduled/recoverable jobs, network/battery policy gates, upload/download/file operations, dry-run delete confirmation, audit/job logs, and idempotent legacy sync/copy migration. The Android App is the controller; WebUI is intentionally excluded by product decision.

Job creation exposes bounded typed options (`transfers`, `checkers`, `bwLimit`,
`overwrite`, `deleteExcluded`); Remote create/edit exposes scalar credentials
that are encrypted by the Gateway. Provider diagnostics written to Job logs
are redacted when they contain credential-bearing lines.

## Target

- Development host: Windows
- Test device: `emulator-5554`, Android API 35, `x86_64`
- NDK: `26.3.11579264`
- First binary target: `x86_64-linux-android`

## Build

```powershell
rustup target add x86_64-linux-android
cargo build --release --target x86_64-linux-android -p rclone-gateway
```

For Windows NDK builds, set `CC_x86_64_linux_android`, `CXX_x86_64_linux_android`, and `AR_x86_64_linux_android` to the NDK 26.3 LLVM `clang.exe`, `clang++.exe`, and `llvm-ar.exe` paths. The checked-in `.cargo/config.toml` supplies the linker and Android API-34 link target.

## Module package

```powershell
$env:CC_x86_64_linux_android='C:\Development\JetBrains\AndroidSDK\ndk\26.3.11579264\toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe'
$env:CXX_x86_64_linux_android='C:\Development\JetBrains\AndroidSDK\ndk\26.3.11579264\toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe'
$env:AR_x86_64_linux_android='C:\Development\JetBrains\AndroidSDK\ndk\26.3.11579264\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-ar.exe'
.
\scripts\build-module.sh
```

Security and emulator smoke helpers:

```sh
sh scripts/verify-security.sh
ADB=/path/to/adb sh scripts/integration-test.sh
```

The integration helper uses an isolated `/data/local/tmp` state root, performs
a health probe, and removes its temporary Gateway process and files on exit.

## Emulator smoke test

```powershell
& 'C:\Development\platform-tools\adb.exe' -s emulator-5554 push '.\target\x86_64-linux-android\release\rclone-gateway' '/data/local/tmp/rclone-gateway'
& 'C:\Development\platform-tools\adb.exe' -s emulator-5554 shell 'chmod 0755 /data/local/tmp/rclone-gateway'
& 'C:\Development\platform-tools\adb.exe' -s emulator-5554 shell '/data/local/tmp/rclone-gateway serve --socket /data/local/tmp/rclone-manager/gateway.sock --state-dir /data/local/tmp/rclone-manager/state > /data/local/tmp/rclone-manager/gateway.log 2>&1 &'
& 'C:\Development\platform-tools\adb.exe' -s emulator-5554 shell '/data/local/tmp/rclone-gateway probe --socket /data/local/tmp/rclone-manager/gateway.sock --state-dir /data/local/tmp/rclone-manager/state'
```

Production state is `/data/adb/rclone-manage`; the socket is `/data/adb/rclone-manage/runtime/gateway.sock`. The Magisk module directory remains reserved for binaries and lifecycle scripts.

LAN access is opt-in and never starts by default. Enable only with a TLS
certificate and private key; plain TCP is not supported:

```text
/data/adb/modules/rclone-manager/bin/rclone-gateway serve \
  --root /data/adb/rclone-manage \
  --lan-addr 192.168.1.20:8443 \
  --tls-cert /data/adb/rclone-manage/keys/server.pem \
  --tls-key /data/adb/rclone-manage/keys/server.key \
  [--tls-client-ca /data/adb/rclone-manage/keys/clients-ca.pem]
```

LAN requests require Bearer authentication plus the HMAC method/path/query/body
signature and single-use nonce. Pairing start/complete are the only unsigned
endpoints and must be protected by the TLS channel; supplying
`--tls-client-ca` enables mandatory mTLS. Keep certificate, key, and CA files
root-only (`0700` directory, `0600` files). The Android App currently uses the
Unix socket; LAN is for explicitly configured clients.

For the Magisk service, place optional LAN settings in the root-only file
`/data/adb/rclone-manage/runtime/lan.conf`:

```text
LAN_ADDR=192.168.1.20:8443
TLS_CERT=/data/adb/rclone-manage/keys/server.pem
TLS_KEY=/data/adb/rclone-manage/keys/server.key
TLS_CLIENT_CA=/data/adb/rclone-manage/keys/clients-ca.pem
```

The service accepts only these four keys and safe path/address characters;
incomplete or malformed configuration leaves LAN disabled.

The Magisk watchdog records Gateway restart failures in
`runtime/gateway-crash-count`. After three failed restart observations it
creates `runtime/safe-mode`; the next Gateway boot persists Safe Mode and does
not launch scheduled jobs or enabled mounts. Disable Safe Mode through the
authenticated API to clear the marker and counter.

The Gateway resolves the rclone core in this order: `RCLONE_BIN`, the manager module's `bin/rclone`, the existing rclone module's `bin/rclone` or `system/vendor/bin/rclone`, then `/system/vendor/bin/rclone` and `/vendor/bin/rclone`. This allows the manager module to wrap an already-installed upstream rclone core without copying credentials or replacing its packaging.

The Android controller must invoke only the fixed Gateway client contract (module binary path shown):

```text
/data/adb/modules/rclone-manager/bin/rclone-gateway request --socket /data/adb/rclone-manage/runtime/gateway.sock --method GET --path /api/v1/system/health [--token TOKEN] [--body-base64 BASE64]
```

The request subcommand allow-lists API paths, rejects traversal/control bytes, limits bodies to 64 KiB, and emits only the HTTP response body. It is suitable as the `libsu` bridge for the future Android App.

## Acknowledgements

The Magisk lifecycle and rclone-on-Android integration are informed by
[NewFuture/rclone-fuse3-magisk](https://github.com/NewFuture/rclone-fuse3-magisk).
The implementation retains an independent Gateway/ACL/Secret Store boundary;
it does not expose that project's raw rclone RC or shell surface to the App.
Command and crypt behavior follow the [rclone RC](https://rclone.org/rc/),
[mount](https://rclone.org/commands/rclone_mount/), and
[crypt](https://rclone.org/crypt/) documentation.

Remote enable/disable and credential-free export are typed endpoints. Remote
deletion is intentionally two-step: the first `DELETE /api/v1/remotes/{id}`
returns a 60-second confirmation token; repeat it with
`{"confirmationToken":"..."}`. Job retry and bounded log retrieval are
available through `/api/v1/jobs/{id}/retry` and `/api/v1/jobs/{id}/log`.

Audit entries include executor UID, operation latency field, and a SHA-256
`pathHash` for file-bearing operations. Plaintext local/remote paths are not
persisted in the audit log. `logRetentionDays` and `logMaxBytes` are bounded
runtime settings applied by periodic log maintenance.

Crypt profiles accept an optional password through the write-only request
field, store it as an encrypted Secret Store blob, and expose only
`passwordConfigured`. `/api/v1/crypt/{id}/test` validates that the typed
rclone `crypt` configuration can be materialized. Runtime settings are
available at `/api/v1/system/settings`; migration history is exposed at
`/api/v1/system/migration`.

Crypt temporary configs encode passwords with rclone-compatible AES-CTR
`obscure` values; plaintext is never returned and the generated config is
removed after the operation.

Database backups can be restored only as an offline maintenance operation. Stop the Gateway first, then run `rclone-gateway restore --root /data/adb/rclone-manage --backup state-<timestamp>.db`; the command validates the backup, creates `pre-restore-*.db` and private key/secret safety copies, and atomically replaces `db/state.db`. New backups include a matching `state-<timestamp>-<uuid>.bundle/` containing root-only `keys/` and `secrets/`, so encrypted credentials remain recoverable; older DB-only backups restore database state without replacing those files.

For maintenance or module removal, `rclone-gateway stop --root /data/adb/rclone-manage`
terminates only Mount worker PIDs recorded in the manager database, unmounts
their derived shared-storage targets, removes worker configs, and marks profiles
stopped. The Magisk `uninstall.sh` invokes this command before stopping the
Gateway; persistent state is retained.

## Android App

`app/` contains the native controller: Dashboard health, Remotes/Files/Jobs/Mounts/Audit/Backups/Crypt views, typed remote/job/mount/file actions (including copy, move, upload, download, and delete preview), job-run inspection, pairing, and Safe Mode control. It uses `libsu` solely to invoke the fixed request contract; it does not open the rclone RC endpoint. Build with `gradle :app:assembleDebug` under JDK 17.

Mount cache directories are automatically placed below `/data/adb/rclone-manage/cache/`; generated rclone configs for short-lived operations are removed after use, while enabled mount configs remain root-only for the worker lifetime and are removed on stop, Safe Mode, or recovery cleanup.

When a mount profile uses `/mnt/rclone-<name>` as its FUSE mount point, the
Gateway derives the restricted shared-storage bind target
`/data/media/0/<name>`, retries the bind while the FUSE worker becomes ready,
and unmounts that derived target during stop/disable. No arbitrary bind target
is accepted.
