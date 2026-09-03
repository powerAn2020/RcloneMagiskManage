# Repository change plan v1.1

Baseline: NewFuture/rclone-fuse3-magisk, main branch reviewed 2026-09-02.

## Keep
- FUSE3 integration and Android mount helper.
- Existing rclone binary packaging/update workflow.
- Existing configuration compatibility where practical.
- Existing mount bind strategy as a lower-level mechanism.

## Replace / wrap
1. `service.sh`: stop auto-mounting every configured remote. Load explicit enabled mount profiles instead.
2. `sync.service.sh`: stop treating free-form shell lines as authoritative jobs. Convert to database-backed typed jobs; keep legacy file reader only for migration.
3. `env`: default RC must be loopback or Unix socket; no silent `RCLONE_RC_NO_AUTH=true` in product mode.
4. `rclone-kill-all`: replace global kill semantics with instance-aware stop by PID/job/mount; retain a manual emergency recovery command for maintenance.
5. Add `rclone-gateway` as the only App-facing privileged service.
6. Add health/state endpoints and persistent state.
7. Add native Kotlin App controller using the fixed Gateway request CLI and Android Keystore token storage; WebUI remains out of scope.

## Migration
- Read existing remotes with `rclone listremotes`.
- Import existing `sync` / `copy` lines into typed Job records after parsing with a safe compatibility parser.
- Import configured remotes without returning secrets to the App.
- Generate mount profiles only when user explicitly enables them.
- On first boot after upgrade, preserve old files and create a migration marker.
- Persist product state under `/data/adb/rclone-manage`; keep module directory for binaries and lifecycle scripts only.
