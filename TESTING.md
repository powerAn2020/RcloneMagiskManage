# Test Plan and Evidence

## Environment
- JDK: `C:\Development\Java\jdk-17.0.12`
- SDK: `C:\Development\JetBrains\AndroidSDK`
- NDK: `C:\Development\JetBrains\AndroidSDK\ndk\26.3.11579264`
- Device: `emulator-5554`, Android 15/API 35, `x86_64`
- State root: `/data/adb/rclone-manage`

## Latest run (2026-09-03)

- `cargo fmt --all --check`: pass.
- `cargo test --workspace`: 30/30 pass on Windows; Unix-only symlink and
  mock-rclone tests are compiled for Unix/Android host validation.
- `sh scripts/verify-security.sh`: pass.
- `sh scripts/build-module.sh`: pass; regenerated
  `dist/rclone-manager-x86_64-linux-android/`.
- Cross-build note (Windows): use `sh scripts/build-module.sh`, which exports
  the NDK 26.3 clang/ar paths; a bare `cargo build --target
  x86_64-linux-android` requires equivalent `CC_x86_64_linux_android`,
  `CXX_x86_64_linux_android`, and `AR_x86_64_linux_android` variables.
- `gradle :app:assembleDebug --no-daemon` with JDK 17: `BUILD SUCCESSFUL`.
- OpenAPI YAML parse and all seven shell `sh -n` checks: pass.
- `ADB='C:/Development/platform-tools/adb.exe' sh scripts/integration-test.sh`:
  health, protected-route rejection, one-time pairing, token issuance, and
  authenticated system-info smoke pass on `emulator-5554`; temporary state
  and process are cleaned. Full provider/file/job/delete execution remains a
  separate test because this disposable smoke intentionally avoids mutating a
  remote or relying on provider semantics.
- Audit hardening + concurrency gate (2026-09-03): audit writes executor `uid`,
  SHA-256 `path_hash`, and `latency_ms` fields (path-bearing operations use a
  separate hash input and do not retain plaintext paths); log cleanup now
  honors bounded `logRetentionDays`; job slot claims use an IMMEDIATE SQLite
  transaction so concurrent starts cannot exceed `maxConcurrentJobs`. Host
  suite: 30/30 pass; security verification: pass.
- Final rebuild/verification (2026-09-03): Android `x86_64-linux-android`
  Gateway cross-build and `sh scripts/build-module.sh` pass; OpenAPI YAML parse,
  seven shell `sh -n` checks, `sh scripts/verify-security.sh`, and
  `sh scripts/integration-test.sh` pass. APK streamed install/start on
  `emulator-5554` succeeds with no `FATAL EXCEPTION` or `AndroidRuntime`.
- Crypt compatibility gate (2026-09-03): generated crypt profiles now encode
  passwords with rclone-compatible AES-CTR `obscure` (random 16-byte IV,
  URL-safe base64); the plaintext password remains only in the encrypted
  Secret Store and is never returned by the API. Rust suite: 30/30 pass.
- Android delete-control refresh (2026-09-03): controller now exposes the
  complete file-delete preview/confirmation flow and labels Job retry in the
  action control; `gradle :app:assembleDebug --no-daemon` passed, APK streamed
  install/start passed on `emulator-5554`, and no fatal Android runtime error
  was observed.
- Handoff snapshot (2026-09-03): source, tests, module package, APK, known
  device gates, environment paths, and next-step verification order are
  consolidated in [`HANDOFF.md`](HANDOFF.md). No further implementation work
  is planned in this handoff state.
- Final crypt validation (2026-09-03): crypt profile names cannot shadow their
  parent remote section, preventing duplicate rclone config sections; suite
  increased to 24/24 and Android module/security/integration gates remain pass.
- Audit coverage refresh (2026-09-03): Remote create/test and Job create now
  emit audit events in addition to lifecycle operations; source build and
  23/23 Rust tests remain green.
- APK streamed install/start: pass; no `FATAL EXCEPTION` or `AndroidRuntime`.
- Final verification rerun (2026-09-03): formatting, 30 Rust tests, security
  gate, Android Gateway/module build, OpenAPI parse, all seven shell scripts,
  emulator integration smoke, and APK streamed install/start all pass. The
  emulator still lacks `/system/bin/su` and `fusermount3`, so root bridge and
  real FUSE evidence remain device-gated.
- Android typed-contract hardening (2026-09-03): `GatewayClient.request()` is
  private; UI calls health/info/list/safe-mode through named methods, and job,
  mount, and remote actions enforce their documented action enums before
  invoking the fixed Gateway CLI. APK build and install/start smoke pass.
- Destructive-state hardening (2026-09-03): file/Remote confirmation tokens
  are consumed in an SQLite `IMMEDIATE` transaction, and late Job worker exits
  preserve persisted cancellation. Regression tests pass in the 29/29 suite.
- Remote credential-control refresh (2026-09-03): Android create/edit Remote
  controls now accept documented scalar credential fields; Gateway encrypts
  them in Secret Store and continues returning only `secretRef`. APK build and
  streamed install/start pass.
- Maintenance cleanup refresh (2026-09-03): periodic expiry cleanup now also
  removes stale Remote deletion confirmation records.
- Job options refresh (2026-09-03): Android Job creation now exposes typed
  `transfers`, `checkers`, `bwLimit`, `overwrite`, and `deleteExcluded` fields;
  Gateway validation remains authoritative. APK build and install/start pass.
- Job finalization refresh (2026-09-03): completion audit writes now occur
  after releasing the DB mutex, preventing recursive-lock stalls on worker
  exit; the 30/30 suite and mock-rclone execution test pass.
- Log redaction refresh (2026-09-03): rclone stdout/stderr persisted to Job
  logs and error messages now redact credential-bearing lines; regression test
  passes in the 30/30 suite.
- Redaction coverage includes password/secret/token/access-key/private-key,
  bearer, authorization, and client-secret diagnostic labels.
- Path-control refresh (2026-09-03): remote, local, and mount path validators
  reject all ASCII control bytes, not only NUL/newline; regression coverage
  remains green in the 30/30 suite.
- UI safety refresh (2026-09-03): credential/password prompt fields use
  password masking while values still go directly to the typed Gateway request.
  README now includes upstream attribution and implementation boundary.
- Security-control refresh (2026-09-03): Android exposes typed grant revoke
  (`DELETE .../grants/{grantId}`) with numeric-ID validation before libsu.
  APK build and install/start pass.
- Safe Mode ordering refresh (2026-09-03): `CANCEL_REQUESTED` is persisted
  before TERM is sent to recorded Job workers, closing the exit-order race;
  late-exit regression remains green in the 30/30 suite.
- Mount recovery cleanup refresh (2026-09-03): failed recovery materialization,
  spawn, or PID acquisition removes the generated per-mount config; module
  build, security gate, and emulator smoke pass.
- Job preflight audit refresh (2026-09-03): ACL-revoked and invalid-argument
  preflight failures now emit redacted `job.run` audit events.
- Boundary hardening refresh (2026-09-03): request-body size limit now applies
  to direct Unix-socket HTTP clients as well as the fixed CLI; HMAC nonce
  check/insert uses an explicit SQLite `IMMEDIATE` transaction; cache path
  containment rejects lexical parent traversal and resolves existing
  ancestors; remote secret objects reject nested/non-scalar or multiline
  values. Rust suite: 26/26 pass.
- Maintenance lifecycle refresh (2026-09-03): added typed offline `stop`
  command. It terminates only DB-recorded Mount worker PIDs, unmounts derived
  bind targets, removes per-mount configs, and is called by module
  `uninstall.sh`; no global process kill is used.
- Runtime state/observability refresh (2026-09-03): Mount worker exit now
  transitions its own profile to `STOPPED` and removes only its generated
  config; failed starts reset state instead of leaving `STARTING`. Queued jobs
  re-check their creator's Remote/Path ACL before rclone execution. Audit
  `latencyMs` is now measured from the inbound request boundary; background
  scheduler events retain `0` because they have no HTTP request scope. HMAC
  header fields now have bounded, canonical nonce/signature validation.
- Safe Mode/maintenance refresh (2026-09-03): Safe Mode requests cancellation
  before terminating recorded Job PIDs, and worker finalization honors the
  persisted cancellation state. Offline `stop` also terminates recorded Job
  workers and closes their `job_run` records, preventing orphan transfers.

## Static checks
```powershell
cargo fmt --check
cargo check --workspace
cargo test --workspace
cargo build --release --target x86_64-linux-android -p rclone-gateway
sh scripts/verify-security.sh
```

Database restore is a maintenance-only operation and must run while the
Gateway is stopped:

```powershell
& 'C:\Development\platform-tools\adb.exe' -s emulator-5554 shell '/data/local/tmp/rclone-gateway restore --root /data/adb/rclone-manage --backup state-<timestamp>.db'
```

The command accepts only a filename below `backups/`, verifies SQLite
integrity, creates a `pre-restore-*.db` and private key/secret safety copy,
removes stale WAL/SHM files, and atomically installs the selected backup. New DB backups also have
an adjacent private `<stem>.bundle/` containing `keys/` and `secrets/`; when
present, restore copies those files back as well. DB-only backups remain
compatible but cannot restore encrypted credentials that are not included.

## HMAC request check
When signing is enabled, send `Authorization: Bearer TOKEN`, `X-Client-Id`, `X-Timestamp`, `X-Nonce`, and `X-Signature`. The canonical payload is:

```text
METHOD\nPATH?QUERY\nSHA256(BODY)\nTIMESTAMP\nNONCE
```

The timestamp window is 60 seconds and each `(clientId, nonce)` is single-use. Unix-socket callers may continue using Bearer-only authentication.

## LAN TLS/mTLS check
The LAN listener is disabled unless all required options are present. A startup
with `--lan-addr` but without `--tls-cert` and `--tls-key` must fail before
serving. With a server certificate/key, the listener accepts TLS only and
requires HMAC on every non-pairing endpoint. Adding `--tls-client-ca` requires
a client certificate signed by that CA (mTLS); a client without one must fail
the TLS handshake. Pairing start/complete are intentionally the only unsigned
routes so a new client can obtain its Bearer token over the authenticated TLS
channel.

## Emulator deployment
```powershell
& 'C:\Development\platform-tools\adb.exe' -s emulator-5554 root
& 'C:\Development\platform-tools\adb.exe' -s emulator-5554 push '.\target\x86_64-linux-android\release\rclone-gateway' '/data/local/tmp/rclone-gateway'
& 'C:\Development\platform-tools\adb.exe' -s emulator-5554 shell 'chmod 0755 /data/local/tmp/rclone-gateway'
& 'C:\Development\platform-tools\adb.exe' -s emulator-5554 shell 'mkdir -p /data/local/tmp/rclone-manager'
```

The repeatable bounded smoke can also be run from Git Bash/WSL:

```sh
sh scripts/integration-test.sh
```

Start Gateway with `/data/local/tmp/rclone-manager` during development. Production root is `/data/adb/rclone-manage`; the Magisk service script owns startup there.

## Required cases
1. Health returns `200` and `status=ok`.
2. Protected endpoints reject missing/invalid Bearer tokens.
3. Pairing code is one-time and expires in 300 seconds.
4. Token storage contains only SHA-256 hash.
5. Remote credentials produce an encrypted secret blob and only `secretRef` is returned.
6. `../`, NUL, dangerous local paths, symlink-resolved protected trees, and mount paths outside `/mnt/rclone-*`, `/sdcard/*`, `/data/media/0/*` are rejected.
7. Jobs persist `job_run`, PID/state, scheduler `nextRunAt`, obey typed rclone commands only, and explicit/scheduled starts honor `maxConcurrentJobs`.
8. File deletion and Remote deletion require matching, single-use, 60-second confirmation tokens; Remote deletion also rejects referenced Mount/Crypt profiles.
9. Audit records contain operation/client/uid/resource/remote/path hash/result/error/latency
   without secret material or plaintext file paths.
10. Restart recovery marks interrupted jobs failed, stops stale mount states, preserves DB/secrets/jobs/mounts/migration history, and resumes `@reboot` jobs once.
11. HMAC signing accepts a fresh request and rejects stale timestamp, bad signature, or reused nonce.
12. First paired client has read-only scopes plus `security.write`; later clients require an explicit grant.
13. Legacy migration creates typed Jobs, records malformed lines in `migration_errors`, and is idempotent.
14. Job/gateway logs rotate when a file exceeds 10 MiB; rotated files keep restrictive permissions.
15. `GET /api/v1/jobs/{id}/runs` exposes execution records without credentials; `GET/POST /api/v1/system/backups` lists/creates uniquely named DB backups with SHA-256 checksums and private key/secret bundles; offline `restore --backup NAME` validates and atomically restores a selected backup and its bundle when present.
16. Fixed `request` CLI accepts only allow-listed typed API paths, rejects traversal/control bytes, caps body at 64 KiB, and works over the emulator Unix socket.
17. Security API can disable another client and rotate its token; rotated token is returned only in the immediate response and stored as a hash.
18. Crypt profiles persist typed metadata and an encrypted optional password, expose passwordConfigured without secrets, provide encryption test status, and enforce the parent Remote Path ACL.
19. Gateway resolves rclone only from the documented allow-listed executable paths or `RCLONE_BIN`.
20. Mount `enable`/`disable` persists the profile flag; enabled profiles are recovered after Gateway boot unless Safe Mode is active.
21. `upload` accepts only local source -> remote destination; `download` accepts only remote source -> local destination.
22. Job listing/detail/run/action/delete endpoints do not reveal or mutate jobs whose remote source/destination is outside the caller's Remote ACL.
23. Network (`WIFI`, `UNMETERED`, `VPN`) and battery (`CHARGING`, `BATTERY_30`) policies leave jobs queued when the device cannot prove the required condition.
24. Mount creation and lifecycle actions require the caller's Remote/Path ACL; cache directories remain below `/data/adb/rclone-manage/cache`; per-mount generated configs persist only for the worker lifetime and are removed during stop/Safe Mode/recovery cleanup. `/mnt/rclone-<name>` profiles derive only `/data/media/0/<name>` for bind exposure and unmount it on stop/disable.
25. Request CLI returns non-zero for HTTP 4xx/5xx while preserving the JSON error body on stdout.
26. Magisk `service.sh` performs bounded socket readiness polling, supervises only the Gateway PID, persists crash count, enters persistent Safe Mode after three failed restart observations, and watchdog PID is persisted and terminated by `uninstall.sh`.
27. Optional Magisk LAN configuration reads only root-owned `runtime/lan.conf`, validates four allow-listed keys/values, and disables LAN when the tuple is incomplete or malformed.
28. Disabling a mount terminates its recorded PID and removes its generated worker config; starting an already active mount is rejected.
29. Scheduler reconciles recorded mount PIDs with `kill -0`, marks dead workers stopped, unmounts its derived bind target, and removes their config without global process killing.
30. Android controller exposes only the fixed typed Gateway contract for remote import/edit/enable/disable/credential-free export, file copy/move, all five job types plus schedule/policy fields, job runs/log/retry, mount enable/disable, Crypt create/list/test, typed runtime settings, and migration-status controls.
31. Direct Unix clients cannot bypass the 64 KiB body limit; replay nonce insertion is serialized; cache and secret validation reject traversal/injection forms; offline stop/uninstall cleans only manager-owned mounts.
32. Mount worker exits and failed starts leave no stale active profile/config; queued work is denied if its Remote/Path ACL was revoked before execution; request-originated audit records report measured latency; HMAC header fields are bounded and canonical.
33. Safe Mode and offline maintenance stop recorded Job workers before state finalization; cancellation state cannot be overwritten by a late worker exit.
34. Emulator smoke covers unauthenticated health only, protected endpoint rejection, pairing start/complete, immediate token issuance, and an authenticated typed info request; all state remains under the disposable test root.

## Evidence levels
- Source/unit: Rust tests and review.
- Build: host check/test and Android cross-build.
- Install: binary pushed to emulator.
- Runtime: socket response, logs, SQLite inspection.
- Device: provider connectivity, FUSE3, SELinux, Magisk/KernelSU lifecycle require rooted image/device; emulator API tests do not prove these.

## Requirement coverage
| Specification area | Current implementation/evidence | Gate |
|---|---|---|
| Gateway, Unix socket, SQLite/WAL, state root and permissions | Rust Gateway; emulator health and `0700/0600` permission smoke | Verified on emulator |
| Bearer auth, pairing, scopes, Remote ACL, Path ACL | Gateway handlers, allow-list tests, pairing/HMAC smoke | Verified source/runtime; multi-client abuse testing pending |
| HMAC request signing and replay protection | Canonical method/path/query/body hash/timestamp/nonce; unit + ADB-forward smoke | Verified |
| Typed remotes/files/jobs/mounts/crypt | Typed routes, fixed request CLI, Android controller | Build and basic runtime verified; cloud/FUSE execution pending |
| Jobs, scheduler, policies, recovery and logs | SQLite job/job_run state machine, atomic bounded slot claims, scheduler/PID reconciliation, size + retention cleanup | Source/unit verified; long-running soak pending |
| Legacy migration | rclone.conf/sync/copy parser, idempotent marker, redacted errors | Host migration smoke + unit verified |
| Backups and rollback | Checksummed DB plus private key/secret bundle, offline integrity/schema-checked restore with safety copies | Unit verified; device rollback pending |
| Magisk/KernelSU lifecycle | `post-fs-data.sh`, supervised Gateway, optional validated `runtime/lan.conf`, and uninstall cleanup | Shell syntax/package verified; boot and positive `su` bridge pending |
| FUSE3, mount namespace, bind mount, SELinux | Mount worker lifecycle, restricted cache paths, strict derived bind target and stop cleanup | Source/unit verified; emulator lacks `fusermount3`; device gate pending |
| LAN TLS/mTLS | Explicit `--lan-addr` + TLS cert/key; optional client CA enforces mTLS; LAN non-pairing requests require HMAC; no insecure listener | Source/unit + Android loopback TLS/mTLS smoke verified; physical LAN and certificate-rotation test pending |
| Android UI | Native App typed control surface, Keystore token storage, launch smoke | Launch/build verified; full interaction/accessibility test pending |

## Migration smoke test
```powershell
& 'C:\Development\platform-tools\adb.exe' -s emulator-5554 shell '/data/local/tmp/rclone-gateway migrate --root /data/adb/rclone-manage --legacy /data/adb/modules/rclone/conf'
```
The command prints `migrated_jobs=N`; rerunning prints `already_migrated=true`. Invalid legacy lines are retained in `migration_errors` and never become shell arguments.

## Historical observed run (2026-09-02; superseded by latest status below)
- `cargo fmt --check`: pass after formatting.
- `cargo test --workspace`: 3/3 pass (path guard, mount guard, token hashing).
- Android cross-build: pass for `x86_64-linux-android` using NDK 26.3 and API 34 linker compatibility; binary runs on Android API 35.
- ADB install: binary pushed to `emulator-5554`; `adbd` root available.
- Runtime: Gateway created SQLite WAL DB, 32-byte `keys/master.key`, state subdirectories, and Unix socket. `adb forward tcp:18080 localfilesystem:.../gateway.sock` then `GET /api/v1/system/health` returned `200` with `{"status":"ok","api_version":"1.1.0","auth":"configured"}`.

## Latest implementation status (2026-09-03)
- Host unit suite: 6/6 pass (path/mount/local guards, token hashing, encrypted secret/decryption AAD, schedule parser).
- Android cross-build: pass for `x86_64-linux-android`; NDK clang/ar explicitly configured from `26.3.11579264`.
- Gateway now includes HMAC timestamp/nonce signing, least-privilege pairing grants, grant/revoke APIs, typed secret materialization, scheduler/recovery, dry-run object statistics, job logs, and idempotent legacy `sync`/`copy` migration with `migration_errors`.
- Module package now includes `post-fs-data.sh`; service performs one-time migration and clears stale socket before startup.
- Runtime smoke (emulator `emulator-5554`, root `adbd`): rebuilt binary pushed, gateway started under `/data/local/tmp/rclone-manager`, socket mode observed as `srw-------`, and HTTP-over-ADB-forward returned `{"status":"ok","api_version":"1.1.0","auth":"configured"}`.
- Authentication smoke: unauthenticated `GET /api/v1/system/info` returned HTTP `401`; pairing start returned a six-digit code with 300-second expiry.
- Request CLI smoke: emulator invocation of `rclone-gateway request ... GET /api/v1/system/health` returned the health JSON body.
- Module packaging: `sh scripts/build-module.sh` succeeded and produced `dist/rclone-manager-x86_64-linux-android/` with executable gateway, `post-fs-data.sh`, `service.sh`, and `uninstall.sh`.
- Final verification: `cargo fmt --check`, `cargo test --workspace` (7/7), Android release cross-build, and all five shell `sh -n` checks pass on 2026-09-03.
- Latest cross-build after backup/job-run additions: `cargo build --release --target x86_64-linux-android -p rclone-gateway` pass.
- Android App build: with `JAVA_HOME=C:\Development\Java\jdk-17.0.12`, `gradle :app:assembleDebug --no-daemon` completed `BUILD SUCCESSFUL`; APK at `app/build/outputs/apk/debug/app-debug.apk`.
- Android App build after typed create controls (`Remote`, `Job`, `Mount`): `gradle :app:assembleDebug --no-daemon` completed `BUILD SUCCESSFUL` on 2026-09-03.
- Android App build after complete P0 controls (`Files`, typed Remote/Job/Mount actions, Job/Mount actions, pairing completion, Safe Mode): `gradle :app:assembleDebug --no-daemon` completed `BUILD SUCCESSFUL`.
- Android App UI smoke after Files/create controls: reinstall returned `Success`; Activity resumed/visible; no `FATAL EXCEPTION` or `AndroidRuntime` errors in the captured logcat.
- Android App Root bridge: migrated to `libsu:core:6.0.0`; JitPack dependency resolved, debug APK rebuilt/reinstalled, and MainActivity resumed without an AndroidRuntime exception. The API call itself reports `gateway request failed` on this ADB-root image because `/system/bin/su` is absent; a Magisk/KernelSU image with `su` is required for positive bridge proof.
- Final APK smoke after Keystore token storage and all controls: streamed install returned `Success`; MainActivity resumed on `emulator-5554`; no `FATAL EXCEPTION`/`AndroidRuntime` entries.
- Final Gateway cross-build after request CLI and App integration: Android `x86_64-linux-android` release build pass on 2026-09-03.
- Final combined build after Crypt profile API: Gateway Android cross-build and `gradle :app:assembleDebug --no-daemon` both pass on 2026-09-03.
- Final static gate after rclone.conf migration and security rotation additions: `cargo fmt --check`, `cargo test --workspace` (8/8), Android x86_64 cross-build, and all shell syntax checks pass.
- Latest Gateway cross-build after enabled-mount recovery and rclone path resolution: `cargo build --release --target x86_64-linux-android -p rclone-gateway` pass.
- Android App install/start: `adb install -r` returned `Success`; `am start -n com.android.rclone.manager/.MainActivity` showed the Activity resumed and visible on `emulator-5554`; no `FATAL EXCEPTION` was found in the captured logcat window.
- OpenAPI validation: `yaml.safe_load(open('openapi-v1.yaml'))` succeeded.
- Strict endpoint allow-list tests now cover dynamic IDs/actions, reject unknown job actions, malformed grant IDs, traversal in query strings, and raw RC paths.
- Pairing completion was smoke-tested after a lock-lifetime fix: emulator returned `clientId`, one-time token, and `expiresIn=2592000`; authenticated `/api/v1/system/info` returned rclone version and `root=true`.
- Invalid typed endpoint smoke: `request ... POST /api/v1/jobs/abc/anything` exits non-zero with `request not allowed`.
- Module build script now exports NDK 26.3 clang/ar automatically when `NDK_ROOT` is not overridden; `sh scripts/build-module.sh` succeeded and regenerated `dist/rclone-manager-x86_64-linux-android/`.
- Latest static gate: `cargo fmt --check`, `cargo test --workspace` (10/10), Android `x86_64-linux-android` release cross-build, `gradle :app:assembleDebug --no-daemon`, OpenAPI parse, and module shell syntax all passed on 2026-09-03.
- Current static gate (2026-09-03): `cargo fmt --all --check` and `cargo test --workspace` pass with 17/17 tests, including HMAC method/path/body-hash/timestamp/nonce binding and replay rejection, migration-error redaction, oversized-log rotation with `0600` rotated files, schema/integrity-checked backup restore, LAN option validation, strict derived bind-target validation, and component-aware cache containment.
- Gateway/module refresh after migration redaction and restore CLI hardening (2026-09-03): Android `x86_64-linux-android` release binary and `dist/rclone-manager-x86_64-linux-android/` were regenerated successfully; five lifecycle scripts still pass `sh -n`.
- Fresh host migration smoke (`build/migration-smoke3`): `rclone.conf` produced one remote, `sync` produced one typed job, malformed/blank legacy lines were recorded in `migration_errors`, and a second invocation reported `already_migrated=true` without duplicates.
- Latest emulator permission smoke after Gateway startup: state root `0700`, database `0600`, runtime socket `0600`; health returned HTTP 200. The root image has no `/system/bin/su`, so positive Magisk/KernelSU App bridge remains unavailable.
- Shell lifecycle syntax gate after watchdog addition: `service.sh` and `uninstall.sh` passed `sh -n`; package regenerated with the supervised Gateway lifecycle scripts.
- Compatibility smoke: Android `probe --socket ... --state-dir ...` returned the same HTTP health JSON; Gateway package version now reports `1.1.0` consistently in Cargo, module metadata, and API version.
- App control-surface build/smoke after Crypt, backup-create, upload/download, and remote-test controls: `gradle :app:assembleDebug --no-daemon` succeeded; streamed APK install succeeded; `MainActivity` focused on `emulator-5554` with no `FATAL EXCEPTION`/`AndroidRuntime` entries.
- Current artifact refresh (2026-09-03): Android Gateway release cross-build completed after the HMAC middleware test; `sh scripts/build-module.sh` regenerated `dist/rclone-manager-x86_64-linux-android/`; `gradle :app:assembleDebug --no-daemon` completed `BUILD SUCCESSFUL`.
- Android controller refresh (2026-09-03): after adding typed remote edit, copy/move, all job types/schedule-policy fields, job runs, mount enable/disable, Crypt listing, and security grant/ACL/client actions, `gradle :app:assembleDebug --no-daemon` completed `BUILD SUCCESSFUL`.
- Android mount-control refresh (2026-09-03): mount creation now exposes remote path, cache directory/mode/limits, and read-only options; mount action prompt exposes `start/stop/enable/disable`; `gradle :app:assembleDebug --no-daemon` completed `BUILD SUCCESSFUL` and the APK was installed/launched on `emulator-5554` without `FATAL EXCEPTION` or `AndroidRuntime` errors.
- Current emulator smoke (2026-09-03): rebuilt Gateway pushed to `emulator-5554`; `probe` returned health JSON; pairing completion returned a client ID and 30-day token expiry; authenticated system info returned `root=true` and rclone version; state root/database/socket modes were `0700/0600/0600`; APK streamed install succeeded and `MainActivity` was resumed with no `FATAL EXCEPTION` or `AndroidRuntime` entries.
- Restore CLI safety smoke (2026-09-03): emulator invocation with `--backup ../state.db` exited non-zero with `invalid backup name`; production Gateway remained healthy afterward.
- Final artifact rebuild (2026-09-03): `cargo build --release --target x86_64-linux-android -p rclone-gateway` and `sh scripts/build-module.sh` completed after restore hardening; `dist/rclone-manager-x86_64-linux-android/bin/rclone-gateway` matches the rebuilt 1.1.0 Gateway.
- TLS listener rebuild (2026-09-03): Rustls/ring-based Android `x86_64-linux-android` release build and module packaging completed after adding explicit TLS/mTLS LAN support; target and packaged Gateway SHA-256 are both `2930ed04a856243040885761a97bba90db078d34dea219d3b7c02f1ba1f3ebc9`.
- Scripted security/integration checks (2026-09-03): `sh scripts/verify-security.sh` passed source-boundary checks plus the 16-test Rust suite; `ADB='C:/Development/platform-tools/adb.exe' sh scripts/integration-test.sh` passed health probe on `emulator-5554` and cleaned its temporary binary/state root.
- Transport-status refresh (2026-09-03): `/api/v1/system/info` now reports `lanEnabled` and `mtlsRequired` from non-secret persisted startup state; Android App exposes a typed “Transport status” action without exposing certificate paths or keys.
- Latest APK smoke (2026-09-03): rebuilt `app-debug.apk` installed successfully on `emulator-5554`, `MainActivity` resumed, and the captured logcat contained no `FATAL EXCEPTION` or `AndroidRuntime` entries.
- Final APK gate (2026-09-03): with `JAVA_HOME=C:\Development\Java\jdk-17.0.12`, `gradle :app:assembleDebug --no-daemon` completed `BUILD SUCCESSFUL`; APK remains at `app/build/outputs/apk/debug/app-debug.apk`.
- Lifecycle readiness refresh (2026-09-03): `service.sh` now performs a bounded 10-second Unix-socket readiness check after launch; source and regenerated module scripts pass `sh -n`.
- Process-control refresh (2026-09-03): Gateway PID liveness/termination now uses `/system/bin/kill` on Android (host `kill` elsewhere), avoiding dependence on an inherited PATH; Rust tests and Android cross-build pass.
- Final Gateway/module rebuild after restore safety-copy uniqueness change (2026-09-03): Android cross-build and `sh scripts/build-module.sh` both completed successfully; regenerated module includes bounded service readiness polling.
- Log-permission hardening (2026-09-03): Gateway explicitly applies `0600` to newly written job logs and rotated `.log.1` files instead of relying only on umask; unit test passes.
- Latest emulator deployment (2026-09-03): rebuilt Gateway was pushed and restarted without rebooting the device; `probe` returned `status=ok`, and state/database/socket permissions remained `0700/0600/0600`.
- HMAC integration smoke (2026-09-03): ADB-forwarded signed `GET /api/v1/system/health` returned HTTP `200`; repeating the identical nonce returned HTTP `401`.
- Local-provider mount smoke (2026-09-03): Gateway created and launched a typed mount profile on the emulator, but rclone exited because `fusermount3` is absent; Gateway reconciled the recorded PID to `STOPPED` and removed the worker config. This is an environment limitation, not a bypass of mount lifecycle handling.
- Bind-target guard (2026-09-03): unit coverage accepts only `/mnt/rclone-<safe-name>` and derives `/data/media/0/<safe-name>`; traversal, nested, and non-FUSE paths are rejected. Runtime bind remains device-gated because the emulator lacks `fusermount3`.
- TLS material hardening (2026-09-03): LAN startup now rejects missing/non-regular TLS files and, on Unix/Android, rejects certificate/key/CA permissions exposing group/other bits; Android release cross-build, module packaging, 17-test suite, and scripted emulator health smoke passed afterward.
- Bind/cache hardening rebuild (2026-09-03): mount stop, disable, Safe Mode, and stale-PID reconciliation now unmount the derived shared-storage target; cache containment uses path components rather than string prefixes. Android release build, module packaging, security gate, and emulator integration smoke passed.
- LAN TLS/mTLS smoke (2026-09-03): Android x86_64 Gateway started explicit TLS and mTLS listeners on emulator loopback. TLS pairing returned HTTP `201`; unsigned health returned HTTP `401` (`AUTH signed request required on LAN`); mTLS handshake without a client certificate failed with `certificate required`; a CA-signed client certificate completed pairing with HTTP `201`. Test certificates were temporary and are not shipped.
- Security/rollback refresh (2026-09-03): local transfer paths now resolve the nearest existing ancestor and reject symlink escapes into protected trees; job action/delete enforce the same source/destination ACL as read/list; Remote deletion is a two-step, single-use 60-second confirmation and rejects referenced Mount/Crypt profiles; DB backups now include root-only key/secret bundles. Host Rust suite remains green (17 tests on Windows); Unix adds the symlink and mock-rclone execution tests.
- Typed control-surface refresh (2026-09-03): added explicit Remote import, enable/disable, credential-free export, Job retry, and bounded Job log endpoints to Gateway/OpenAPI/App; Android x86_64 Gateway packaging, `gradle :app:assembleDebug --no-daemon`, YAML parse, shell syntax, and emulator health integration all passed.
- Final artifact refresh (2026-09-03): regenerated the x86_64 Android module after backup-bundle, protected-tree, Remote confirmation, and Job ACL changes; `cargo fmt`, `cargo test` (17/17), security gate, package build, and emulator integration smoke all pass.
- Final ACL/mount gate (2026-09-03): Mount create/start now enforces read-only vs read-write Remote ACL; mount-point validation rejects empty names and control bytes. Release module rebuild, 17 Rust tests, OpenAPI parse, all shell syntax checks, emulator health smoke, and APK install/start passed.
- Crypt gate (2026-09-03): Crypt profiles now persist optional passwords only as encrypted Secret Store blobs, expose `passwordConfigured` without secret material, and provide typed encryption-status testing; schema/OpenAPI/App were synchronized and all build/security/emulator gates passed.
- Settings/migration gate (2026-09-03): added typed `system/settings` GET/PUT with bounded retention/cache/concurrency values and `system/migration` status reporting; synchronized OpenAPI and Android controls. Rust tests, Android module build, APK build, YAML parse, shell syntax, and emulator health smoke passed.
- Functional settings/Crypt gate (2026-09-03): log rotation reads `logMaxBytes`, mount cache size is checked against `cacheMaxBytes`, and Crypt test materializes a typed rclone `crypt` section from encrypted password storage; Rust suite increased to 19/19.
- Concurrency gate (2026-09-03): scheduler and explicit Job starts now honor bounded `maxConcurrentJobs` (1..4, default 2); release module rebuild, security gate, 19 Rust tests, and emulator health integration passed.
- Storage/Crypt hardening (2026-09-03): settings now drive log rotation and mount cache-size limits; mount symlink roots are canonicalized on Unix/Android; Crypt config materialization uses encrypted password blobs; restore replaces stale key/secret files instead of leaving rollback residue.
- Final rebuild (2026-09-03): x86_64 Android Gateway/module, APK, OpenAPI parse, six shell syntax checks, security gate (19/19), emulator health integration, and APK install/start all passed after concurrency, Crypt, Settings, and rollback hardening.
- Final verification refresh (2026-09-03): private restore replacement now removes stale key/secret symlinks as well as regular files; module regenerated and `verify-security.sh` plus emulator integration smoke passed again (19/19).
- Crash-safe lifecycle gate (2026-09-03): watchdog now persists restart failures, enters Safe Mode after three failed restart observations, Gateway imports the marker at boot, and authenticated Safe Mode disable clears marker/counter; shell syntax, module build, security gate, OpenAPI parse, and emulator health smoke passed.
- Settings API polish (2026-09-03): fresh installations now report typed defaults (14-day retention, 10 MiB rotation, 32 GiB cache, concurrency 2) instead of an empty settings object; Rust suite remains 19/19.

## Remaining target-device gates
FUSE3 mount namespace/bind behavior (the emulator lacks `fusermount3`), SELinux policy, Magisk/KernelSU boot watchdog and positive `su` bridge (no `/system/bin/su`), real cloud-provider operations, physical-LAN certificate rotation, Android UI interactions beyond launch, runtime log-rotation scheduling, device-level backup rollback, and long-running soak/recovery remain unverified on the emulator. TLS/mTLS loopback behavior is covered by the smoke above; a successful compile or socket startup does not prove those remaining device behaviors.
