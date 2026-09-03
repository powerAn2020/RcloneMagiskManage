# Project Structure v1.1

```text
android-rclone-root-manager/
├── app/                         # Android UI / controller
│   ├── feature-dashboard/
│   ├── feature-remotes/
│   ├── feature-files/
│   ├── feature-jobs/
│   ├── feature-mounts/
│   ├── feature-crypt/
│   └── feature-security/
├── gateway/                     # Root Gateway (privileged boundary)
│   ├── api/
│   ├── auth/
│   ├── acl/
│   ├── jobs/
│   ├── mounts/
│   ├── secrets/
│   ├── rclone/
│   ├── audit/
│   └── db/
├── magisk-module/
│   ├── module.prop
│   ├── service.sh
│   ├── post-fs-data.sh
│   ├── uninstall.sh
│   ├── bin/rclone
│   ├── bin/rclone-gateway
│   ├── config/
│   └── system/vendor/bin/
├── protocol/
│   ├── openapi-v1.yaml
│   └── errors.md
├── db/
│   └── schema-v1.sql
├── scripts/
│   ├── build-module.sh
│   ├── verify-security.sh
│   └── integration-test.sh
└── docs/
```

This checkout contains `app/` as the native Kotlin controller (Dashboard,
Remotes, Files, Jobs, Mounts, pairing, Safe Mode, and backup controls).
WebUI is intentionally excluded.

`scripts/verify-security.sh` runs the narrow source-security gate and Rust
tests. `scripts/integration-test.sh` runs the bounded Gateway health smoke on
the configured Android emulator.

## Runtime paths

```text
/data/adb/modules/rclone/                       Existing module root
/data/adb/modules/rclone/conf/rclone.conf       rclone config
/data/adb/modules/rclone/conf/env                user overrides (existing)
/data/adb/modules/rclone/conf/sync               legacy sync config
/data/adb/modules/rclone/conf/copy               legacy copy config
/data/adb/rclone-manage/                      New product state root (implemented)
 /data/adb/rclone-manage/db/state.db
 /data/adb/rclone-manage/secrets/
 /data/adb/rclone-manage/runtime/
 /data/adb/rclone-manage/logs/
 /data/adb/rclone-manage/runtime/gateway.sock  Gateway IPC endpoint (0600)
 /data/adb/rclone-manage/runtime/mount-<id>.conf  Root-only worker config
```
