# Android Rclone Root Manager — 交接文档

更新时间：2026-09-03（Asia/Shanghai）

本轮功能补齐与验证更新：2026-09-03。

## 1. 项目定位

V1.1.0 Android Root 控制平面：Android App 只调用固定 Gateway request 合约；Rust Gateway 承担 Root、认证、ACL、Secret Store、SQLite、Job、Mount 与 rclone 进程管理；Magisk 模块只负责二进制和生命周期脚本。WebUI 按产品决定不实现。

生产持久化根目录固定为 `/data/adb/rclone-manage`，模块目录 `/data/adb/modules/rclone-manager` 仅保存程序和脚本；旧版 `/data/adb/modules/rclone/conf` 仅作为迁移输入。

## 2. 当前实现

### Rust Gateway

- Unix socket HTTP API；可选显式地址 TLS/mTLS LAN listener。
- SQLite WAL、schema v1、迁移历史、备份/恢复（DB + keys/secrets bundle）。
- Bearer token、pairing、scope、Remote ACL、Path ACL；固定 `request` CLI，拒绝 raw RC、shell、任意参数。
- HMAC method/path/query/body/timestamp/nonce 签名与重放保护。
- Remote CRUD/import/enable/disable/credential-free export/test。
- XChaCha20-Poly1305 Secret Store；Remote/Crypt 密钥不通过 API 返回。
- File list/mkdir/upload/download/copy/move/delete preview+confirmation。
- Job：copy/sync/move/bisync/delete；scheduler、network/battery policy、pause/resume/cancel/retry、job runs/log。
- `maxConcurrentJobs` 使用 SQLite 事务抢占，显式和调度启动均受限。
- Mount profile、VFS cache 限制、read-only、enable/disable、PID recovery、派生 bind target。
- Crypt profile；临时配置使用 rclone 兼容 AES-CTR `obscure` 密码格式。
- Safe Mode、watchdog crash counter、日志 size/retention 清理。
- legacy `rclone.conf`/`sync`/`copy` 迁移及错误脱敏。
- 审计字段包含 UID、path SHA-256、latency 字段；文件路径不明文写入 audit。
- Unix socket 直接客户端同样受 64 KiB body 上限；HMAC nonce 使用 SQLite `IMMEDIATE` 事务；缓存路径拒绝词法 `..`/symlink 越界；Secret 对象拒绝嵌套与多行值。
- Job 执行前重新校验 Remote/Path ACL；Job/Mount worker 退出会回收状态、PID 和临时配置；Safe Mode/离线 stop 只终止数据库记录的 worker。
- 新增 `rclone-gateway stop --root PATH`，Magisk `uninstall.sh` 会先调用它。

### Android App

Kotlin native controller + libsu 固定 Gateway request bridge + Android Keystore token。已覆盖 Dashboard、Remote、Files、Jobs、Mounts、Crypt、Security、Backup、Safe Mode、Settings、Migration；文件删除有 preview/二次确认，Job action 包含 retry。

### Magisk

`post-fs-data.sh` 创建并保护 `/data/adb/rclone-manage`；`service.sh` 负责迁移、Gateway 启动、10 秒 socket readiness、仅 Gateway PID watchdog、三次失败进入 Safe Mode；`uninstall.sh` 停止进程但保留持久化数据。

## 3. 最近修复

1. 审计记录增加 `uid/path_hash/latency_ms`，并区分资源 ID 与路径 hash。
2. `logRetentionDays` 进入周期日志清理。
3. Job slot claim 改为 SQLite transaction，消除并发计数竞态。
4. 删除 Job ACL 使用 `file.delete` 校验源路径。
5. Crypt 密码改为 rclone `obscure`；Crypt 名称禁止与父 Remote 重名。
6. Android 增加文件删除确认控制。
7. Remote/Crypt/Mount section 名称统一限制为安全标识符，阻断 INI section 注入。
8. Android `GatewayClient` 的通用 request 入口改为私有；UI 通过命名 typed 方法访问系统/列表接口，Job/Mount/Remote action 在客户端和 Gateway 两侧均限制为文档枚举。
9. 文件/Remote 确认令牌使用 SQLite `IMMEDIATE` 事务原子消费；晚到 Job worker 退出不会覆盖已持久化的取消状态。
10. Android Remote 创建/编辑现已提供标量凭据字段；凭据仍由 Gateway 加密存储，API 仅返回 `secretRef`。
11. Android Job 创建现已提供 `transfers/checkers/bwLimit/overwrite/deleteExcluded` typed 选项。
12. Job worker 完成状态写入与 audit 已拆开 DB mutex 生命周期，避免 worker 退出时递归锁死。
13. Remote/local/mount 路径统一拒绝 ASCII 控制字符，阻断参数与日志注入。
14. Android 凭据与 Crypt 密码输入框启用密码遮罩；README 补充上游致谢和边界说明。
15. Android Security 控制面补齐 typed Grant revoke，并在客户端校验 grant ID 为数字。
16. Safe Mode 先持久化 `CANCEL_REQUESTED` 再终止 Job worker，消除退出顺序竞态。
17. Mount recovery 在物化、spawn 或 PID 获取失败时清理临时配置，避免凭据残留。
18. Job 执行前 ACL 拒绝和非法参数失败现在写入脱敏 `job.run` audit 事件。

## 4. 最新验证证据

- `rtk cargo fmt --all --check`：通过。
- `rtk cargo test --workspace`：30/30 通过（Windows 主机；Unix 条件测试在 Unix/Android 目标编译）。
- `rtk sh scripts/verify-security.sh`：通过。
- `rtk sh scripts/build-module.sh`：通过，产物：`dist/rclone-manager-x86_64-linux-android/`。
- Android Gateway `x86_64-linux-android` release cross-build：通过。
- `rtk gradle :app:assembleDebug --no-daemon`（JDK 17）：通过。
- OpenAPI YAML 解析：通过；7 个 shell 脚本 `sh -n`：通过。
- `rtk sh scripts/integration-test.sh`：`emulator-5554` health、保护路由拒绝、pairing、token、认证 info smoke 通过并清理临时状态。
- APK 安装/启动：通过；未发现 `FATAL EXCEPTION` 或 `AndroidRuntime`。
- Android typed-contract hardening：通过；客户端不再暴露公开通用 Gateway 路径调用，APK 已重新构建、安装并启动。
- Destructive-state hardening：通过；确认令牌原子消费、取消状态保护回归测试通过，Gateway/Magisk 产物已重新生成。
- Remote credential-control refresh：通过；APK 已重新构建、安装并启动。
- Job options refresh：通过；APK 已重新构建、安装并启动。
- Job finalization refresh：通过；30/30 测试及 mock-rclone 执行路径通过，模块产物已重新生成。
- Log redaction refresh：通过；rclone 诊断日志中的凭据关键词行会被替换为 `[REDACTED]`，模块产物已重新生成。
- Path-control refresh：通过；路径控制字符回归测试通过，模块产物已重新生成。
- UI safety refresh：通过；APK 已重新构建、安装并启动。
- Security-control refresh：通过；APK 已重新构建、安装并启动。
- Safe Mode ordering refresh：通过；30/30 测试、模块构建及 emulator smoke 通过。
- Mount recovery cleanup refresh：通过；模块构建、安全门禁及 emulator smoke 通过。
- Job preflight audit refresh：通过；30/30 测试、模块构建及 emulator smoke 通过。

产物：

- Gateway：`target/x86_64-linux-android/release/rclone-gateway`
- Magisk 包：`dist/rclone-manager-x86_64-linux-android/`
- APK：`app/build/outputs/apk/debug/app-debug.apk`

## 5. 尚未取得的设备级证据

不能把编译或 socket smoke 当作以下功能已验证：真实 FUSE3 挂载、mount namespace/bind mount、SELinux policy/label、真实 Magisk/KernelSU boot lifecycle、正向 `su` bridge、云 Provider、实体 LAN/证书轮换、完整 UI 可访问性、长时 scheduler/recovery/rotation soak、设备级 backup rollback、性能 RSS/P95。当前模拟器缺少 `fusermount3` 和 `/system/bin/su`。

## 6. 下一位开发者建议顺序

1. 在真实 Root Android（Magisk 或 KernelSU）安装 `dist/rclone-manager-x86_64-linux-android`，不自动重启设备，逐项执行 FUSE/SELinux/boot/su 验收。
2. 用真实或本地可控 Provider 验证 Remote/File/Job/Crypt 全链路，检查生成配置在目标 rclone 版本可解密。
3. 执行 physical LAN TLS/mTLS、证书轮换、pairing、HMAC/replay 测试。
4. 做 7×24 scheduler、Gateway/rclone crash recovery、日志清理与备份回滚 soak。
5. 做 Android UI 逐项交互和可访问性测试，并把结果追加到 `TESTING.md`。

## 7. 重要约束

- 不重启设备，不 force-stop SystemUI，除非用户明确授权。
- App 不得直接访问 raw rclone RC、shell 或任意 exec。
- Secret、token、证书私钥不得进入 API response、日志或迁移错误明文。
- 真实 FUSE/SELinux/Magisk 行为必须在目标设备上记录为独立证据等级。
