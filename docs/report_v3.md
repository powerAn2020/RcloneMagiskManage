# RcloneMagiskManage 安全审计报告

> 审计时间：2026-09-10 | 审计范围：gateway (Rust) + Android App 层

---

## 执行摘要

| 风险等级 | 数量 |
|----------|------|
| 🔴 高危 | 3 |
| 🟠 中危 | 6 |
| 🟡 低危 / 信息 | 5 |

项目整体安全意识较强：使用了参数化 SQL 查询（无 SQL 注入风险）、常数时间签名比较（`subtle::ConstantTimeEq`）、AEAD 加密（XChaCha20-Poly1305）、路径遍历防护、HMAC 请求签名等。主要问题集中在密钥硬编码、本地客户端过度授权、部分信息泄漏和速率限制不完整等方面。

---

## 🔴 高危漏洞

### H1 — `obscure_rclone` 使用硬编码固定密钥（无安全保障）

**文件：** [`crypto.rs:138-151`](file:///c:/Users/meten/dev/RcloneMagiskManage/gateway/src/security/crypto.rs#L138-L151)

```rust
const KEY: [u8; 32] = [
    0x9c, 0x93, 0x5b, 0x48, ...  // 硬编码！
];
```

**问题：**  
这是从 rclone 官方源码直接复制的已知公开密钥（rclone `obscure` 命令的实现）。**任何人都能用 `rclone reveal` 还原被此函数加密的密码**。该函数用于将密码写入 rclone 配置文件 (`materialize_rclone_config`)——如果攻击者读取到 `runtime/rclone-*.conf` 临时文件，即可直接获取明文密码。

**影响：** 临时配置文件中的 remote 密码（secret）可被还原为明文。

**修复建议：**
- 这本身是 rclone 官方的 `obscure` 格式（非加密，仅混淆），属于 rclone 协议设计限制，无法回避。
- **关键防线：** 确保 `runtime/` 目录权限为 `0700`，临时 conf 文件用完即删（当前 `TempConfig::drop` 已实现），并在 `ensure_dirs` 中强制设置目录权限。
- 审计 `restrict_file` 是否在所有 conf 生成路径上都已调用。

---

### H2 — Unix Socket 客户端在配对时获得全权限（`admin.*` + `*`）

**文件：** [`clients.rs:158-177`](file:///c:/Users/meten/dev/RcloneMagiskManage/gateway/src/api/clients.rs#L158-L177)

```rust
} else {  // require_signature == false → Unix Socket 本地连接
    &[
        "system.read", "remote.read", "remote.write", "remote.delete",
        "file.read", "file.write", "file.delete", "job.read", "job.execute",
        "job.control", "mount.read", "mount.write", "audit.read",
        "security.read", "security.write",
        "admin.*",   // ← 导出明文凭据
        "*",         // ← 万能权限
    ]
}
```

**问题：**  
通过 Unix Socket 配对的客户端（即 Android App）自动获得 `admin.*` 和 `*` 超级权限。这意味着任何成功完成配对的本地 App 都能：
1. 调用 `remote_export` 接口导出**含明文密码**的 remote 配置（因为 `has_scope(&h, &s, "admin.*")` 返回 true）
2. 执行任何系统操作

虽然 Unix Socket 本身需要文件系统访问权限（`0600`），但如果 App 遭到恶意软件注入，所有云存储凭据将全部泄漏。

**修复建议：**
- 本地客户端的默认权限集中移除 `admin.*` 和 `*`
- `remote_export` 的凭据导出应改为**显式用户确认**后才授予（类似当前的 remote 删除确认流程）

---

### H3 — `verify_signature` 中签名校验的"可选化"破坏了安全模型

**文件：** [`auth.rs:44-49`](file:///c:/Users/meten/dev/RcloneMagiskManage/gateway/src/security/auth.rs#L44-L49)

```rust
let has = ["x-client-id", "x-timestamp", "x-nonce", "x-signature"]
    .iter()
    .any(|k| h.contains_key(*k));  // any — 只要存在任意一个请求头即触发
if !has {
    return Ok(());  // 没有任何签名头 → 直接跳过验证！
}
```

**问题：**  
签名验证是**可选**的：当请求不携带任何 HMAC 签名头时，`verify_signature` 直接返回 `Ok(())`。中间件 `signed_request` 在 `require_signature=false`（Unix Socket 模式）时同样不强制要求签名。

这意味着通过 Unix Socket 的所有请求只需带有合法 Bearer token 即可，**无需任何请求签名**。如果攻击者盗用了 Bearer token（数据库泄漏），即可无限制地调用所有 API，不受时间窗口和防重放保护。

**修复建议：**
- Unix Socket 本地请求豁免签名是合理的（减少 App 开发复杂度）
- 但应明确文档说明此设计决策，并**确保 token_hash 存储安全**（已做 SHA256，✅）
- 考虑为高危操作（删除 remote、恢复备份）额外增加签名要求

---

## 🟠 中危漏洞

### M1 — `bwLimit` 参数注入 rclone 命令行参数

**文件：** [`scheduler.rs:419-423`](file:///c:/Users/meten/dev/RcloneMagiskManage/gateway/src/engine/scheduler.rs#L417-L423)

```rust
if let Some(v) = raw
    .get("bwLimit")
    .and_then(|v| v.as_str())
    .filter(|v| crate::security::crypto::ini_line_safe(v) && v.len() <= 64)
{
    command.args(["--bwlimit", v]);
}
```

**问题：**  
`ini_line_safe` 仅检查 `\r\n\0`，**不过滤空格**。rclone 的 `--bwlimit` 参数格式为 `08:00,512 21:00,off`（支持空格分隔多时间段）。如果用户传入 `512 --log-file /data/sensitive.log`，Tokio `Command::args()` 会将其作为单独参数而非分割，Rust 的 `Command` 默认**不经过 shell**，所以空格注入**不会产生 shell 注入**。

但是，攻击者可能通过精心构造的参数影响 rclone 行为（例如覆盖配置文件路径），属于参数污染。

**修复建议：**
```rust
// 限制 bwLimit 只允许合法字符：数字、字母、冒号、逗号、空格
.filter(|v| v.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, ':' | ',' | ' ' | '.')))
```

---

### M2 — 配对码暴力破解窗口过大

**文件：** [`clients.rs:25`](file:///c:/Users/meten/dev/RcloneMagiskManage/gateway/src/api/clients.rs#L25)

```rust
let c = format!("{:06}", rand::random::<u32>() % 1_000_000);
```

**问题：**  
6 位数字配对码，搜索空间仅 100 万。速率限制为每 IP **5 次失败后封锁 60 秒**。对于 Unix Socket 本地连接：`source_key = "local:socket"` — **所有本地请求共享同一个失败计数器**，意味着只要任意恶意进程消耗 5 次尝试机会，合法 App 就会被锁定（本地 DoS）。

另外，60 秒到期后计数器重置，攻击者可以循环尝试：`5次/60秒 = 300次/小时 × 3.3小时 ≈ 1000次`，理论上约 1000 小时可遍历全空间（实际受人工干预）。

**修复建议：**
- 将配对码长度提升至 8 位或改用字母数字混合（增大搜索空间）
- 本地连接的失败计数应**基于进程 UID**，而非统一的 `"local:socket"` key

---

### M3 — `pair_start` 在 LAN 模式下不要求特定权限即可发起配对

**文件：** [`clients.rs:22-24`](file:///c:/Users/meten/dev/RcloneMagiskManage/gateway/src/api/clients.rs#L22-L24)

```rust
if s.require_signature {
    let _ = scope(&h, &s, "security.write")?;  // 结果被丢弃！
}
```

**问题：**  
即使 `scope()` 校验**失败**，其结果被 `let _ =` 丢弃，**错误被静默忽略**。任何未授权的 LAN 客户端都能发起配对（生成配对码）。

**修复建议：**
```rust
if s.require_signature {
    scope(&h, &s, "security.write")?;  // 移除 let _ =
}
```

---

### M4 — `remote_export` 可通过响应体泄漏 redactedJson 中的非密码字段

**文件：** [`remotes.rs:681-692`](file:///c:/Users/meten/dev/RcloneMagiskManage/gateway/src/api/remotes.rs#L681-L692)

```json
{
  "redactedJson": { "access_key_id": "AKIAIOSFODNN7EXAMPLE" }
}
```

**问题：**  
即便没有 `admin.*` 权限，`redactedJson` 中仍包含非密码类字段（如 AWS access_key_id，endpoint URL）。`is_rclone_password_key` 函数仅识别密码字段，但 `access_key_id` 不在列表中，因此会出现在非管理员响应中。

**修复建议：**
- 将 `access_key_id`、`access_key`、`client_id` 等识别字段加入 `is_rclone_password_key`
- 或将 `redactedJson` 从响应中完全移除，仅返回 `credentialsIncluded` 标志

---

### M5 — `nonce` 清理策略可能导致 nonce 表无限膨胀

**文件：** [`scheduler.rs:632`](file:///c:/Users/meten/dev/RcloneMagiskManage/gateway/src/engine/scheduler.rs#L632)

```rust
c.execute("DELETE FROM system_config WHERE (key LIKE 'request-nonce:%' ...) AND updated_at<?", params![now()])
```

**问题：**  
过期 nonce 的清理依赖调度器每 5 秒运行一次。如果攻击者在 60 秒窗口内发送大量合法签名请求（不同 nonce），`system_config` 表会快速膨胀。每个 nonce 条目 `updated_at = now() + 60`，调度器才会清除。**在 DoS 场景下，nonce 表可能撑爆存储。**

**修复建议：**
- 为 `system_config` 表中的 nonce 条目数量增加上限检查
- 或为 nonce 使用单独表并建立 `(client_id, nonce)` 唯一索引

---

### M6 — `validate_transfer_endpoint` 中目标权限检查过于宽松

**文件：** [`scheduler.rs:182-185`](file:///c:/Users/meten/dev/RcloneMagiskManage/gateway/src/engine/scheduler.rs#L182-L185)

```rust
if !destination.is_empty() {
    validate_transfer_endpoint(s, client, &destination, "file.read")
        .or_else(|_| validate_transfer_endpoint(s, client, &destination, "file.write"))?;
}
```

**问题：**  
目标路径权限校验顺序为先 `file.read` 后 `file.write`，只要满足其中之一即可。对于 `sync`/`copy` 等写操作，目标应**只需 `file.write`**；但由于 `or_else`，即使客户端只有 `file.read` 权限也能将数据同步到该目标（覆盖文件）。

**修复建议：**
```rust
// 对写操作类型，直接只校验 file.write
validate_transfer_endpoint(s, client, &destination, "file.write")?;
```

---

## 🟡 低危 / 信息性问题

### L1 — AndroidManifest 声明 `QUERY_ALL_PACKAGES` 权限

**文件：** [`AndroidManifest.xml:8`](file:///c:/Users/meten/dev/RcloneMagiskManage/app/src/main/AndroidManifest.xml#L8)

Google Play Store 对此权限有严格审查。确认是否实际需要枚举所有包，否则应通过 `<queries>` 标签（已有）替代。

---

### L2 — `health()` 端点无需认证且泄漏版本信息

**文件：** [`system.rs:22-28`](file:///c:/Users/meten/dev/RcloneMagiskManage/gateway/src/api/system.rs#L22-L28)

`/api/v1/system/health` 返回 `api_version` 字段且无需任何认证。版本信息可用于针对性攻击，建议至少删除版本字段或只返回 `{"status":"ok"}`。

---

### L3 — `master_key` 文件在密钥不存在时先写后设置权限

**文件：** [`crypto.rs:47-52`](file:///c:/Users/meten/dev/RcloneMagiskManage/gateway/src/security/crypto.rs#L47-L52)

```rust
fs::write(&p, k)?;       // 文件创建时权限取决于 umask
// ... 之后才
fs::set_permissions(&p, fs::Permissions::from_mode(0o600))?;
```

在写入与设置权限之间存在 **TOCTOU 窗口**（约几微秒），期间其他进程理论上可读取密钥文件。`server.rs` 中已通过 `umask(0o177)` 缓解，但建议将写入和权限设置合并。

---

### L4 — `logs_clear` 会永久删除审计日志

**文件：** [`system.rs:570-573`](file:///c:/Users/meten/dev/RcloneMagiskManage/gateway/src/api/system.rs#L570-L573)

```rust
let audit_cleared = {
    let conn = db(&s)?;
    conn.execute("DELETE FROM audit_log", [])?
};
```

`security.write` 权限的客户端（即所有本地客户端）可以清空全部审计记录，破坏事后取证能力。建议对审计日志清理额外要求 `admin.*` 权限。

---

### L5 — 备份文件包含加密密钥和 secrets，存放在同一目录

**文件：** [`system.rs:367-371`](file:///c:/Users/meten/dev/RcloneMagiskManage/gateway/src/api/system.rs#L367-L371)

bundle 备份将 `keys/` 和 `secrets/` 复制到 `backups/` 目录下，与数据库备份放在一起。如果 backups 目录通过其他渠道（如文件管理 App）泄漏，攻击者同时获得数据库 + 密钥 + 密文，可完全还原所有凭据。建议加密备份或将密钥单独存放。

---

## 安全亮点（值得保留的良好实践）

| 项目 | 位置 |
|------|------|
| ✅ HMAC 签名使用常数时间比较 | `auth.rs:90` |
| ✅ 所有 SQL 查询使用参数化（无 SQL 注入） | 全文件 |
| ✅ secrets 使用 XChaCha20-Poly1305 AEAD 加密 + 随机 nonce | `crypto.rs:56-80` |
| ✅ 配对码有 60 秒过期 + 失败锁定 | `clients.rs:100-131` |
| ✅ 路径遍历防护（`..` 检测 + 控制字符过滤） | `crypto.rs:231-243` |
| ✅ 删除操作要求二次确认 token | `remotes.rs:281-332` |
| ✅ Unix Socket 权限设为 0600 | `server.rs:209` |
| ✅ rclone 临时配置文件使用 Drop trait 自动清理 | `rclone.rs:19-25` |
| ✅ 审计日志记录所有关键操作 | 全文件 |

---

## 修复优先级

```
立即 (H 级)
  H1 → 明确文档化 obscure_rclone 的安全边界 + 加强 runtime/ 目录权限验证
  H2 → 本地配对移除 admin.* 和 * 权限
  H3 → 修复 pair_start 中被丢弃的错误

近期 (M 级)
  M1 → 限制 bwLimit 字符集
  M3 → pair_cancel 同样有相同的 let _ = 问题，一并修复
  M6 → 目标路径写操作只校验 file.write

计划 (L 级)
  L2 → health 端点移除版本信息
  L4 → 审计日志清空要求 admin.* 权限
  L5 → 考虑加密备份 bundle
```
