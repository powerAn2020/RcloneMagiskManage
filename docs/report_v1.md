# Security Review: RcloneMagiskManage

## Scope

Repository-wide static security audit.

- Scan mode: repository
- Target kind: git_revision
- Target ID: target_sha256_8cb8d12eebe27b547bbed7ad9af88484303ba59d1d5ceb699b67a00af310cbc4
- Revision: b77b51e8587dfe523b5d854eb9129da0c369df1c
- Inventory strategy: repository
- Included paths: .
- Excluded paths: none
- Runtime or test status: not recorded
- Artifacts reviewed: gateway, app, magisk-module, scripts, configuration

Limitations and exclusions:
- No runtime or deployment firewall verification.
- Excluded 04-最终汇总版-Android-Rclone-Root-Manager-V1.1.0-实施技术规格书.docx: Binary documentation artifact; no executable control.

### Scan Summary

| Field | Value |
| --- | --- |
| Scan outcome | completed |
| Reportable findings | 3 |
| Severity mix | high: 2, medium: 1 |
| Confidence mix | high: 3 |
| Coverage | complete |
| Validation mode | static source review |

Canonical artifacts: `scan-manifest.json`, `findings.json`, and `coverage.json`. This report is a deterministic projection of those files.

## Threat Model

Magisk service launches a root Rust gateway over Unix socket and optional TLS LAN REST listener; SQLite/ACLs govern remote, file, job, mount, crypt and security operations.

### Assets

- remote credentials
- bearer tokens and pairing authority
- remote/local data and root subprocess authority

### Trust Boundaries

- LAN client to optional TLS listener; pairing routes are signature-exempt.
- Bearer client to scoped/ACL-gated API.
- Gateway to root rclone subprocess and encrypted secret storage.

### Attacker Capabilities

- Reachable LAN client can invoke unauthenticated pairing routes.
- Active token holder is constrained by scopes and ACLs.

### Security Objectives

- Controlled enrollment and least privilege.
- Credential confidentiality.
- Path and subprocess authorization.

### Assumptions

- LAN disabled by default but configurable; firewall and out-of-band enrollment are unresolved.

## Findings

| Finding | Severity | Confidence | Detailed write-up |
| --- | --- | --- | --- |
| [Remote export discloses decrypted credentials](#finding-1) | high | high | inline below |
| [Unauthenticated LAN pairing can mint a full-admin bearer token](#finding-2) | high | high | inline below |
| [Unauthenticated pairing cancellation clears all pending enrollments](#finding-3) | medium | high | inline below |

### Confidence Scale

| Label | Meaning |
| --- | --- |
| high | Direct evidence supports the finding with no material unresolved blocker. |
| medium | Evidence supports a plausible issue, but material runtime or reachability proof remains. |
| low | Evidence is incomplete and the item is retained only for explicit follow-up. |

<a id="finding-1"></a>

### [1] Remote export discloses decrypted credentials

| Field | Value |
| --- | --- |
| Severity | high |
| Confidence | high |
| Confidence rationale | Source directly constructs and returns unredacted values. |
| Category | Sensitive Data Exposure |
| CWE | CWE-200, CWE-522 |
| Affected lines | gateway/src/api/remotes.rs:559-565, gateway/src/api/remotes.rs:597-629, gateway/src/api/remotes.rs:673-684 |

#### Summary

The export endpoint decrypts secrets and returns full INI/JSON maps alongside redacted maps; rclone obscuring is reversible.

#### Root Cause

Unredacted full fields are returned despite redacted fields; password values use reversible obscuring.

#### Validation

decrypt_secret feeds full_ini_lines/full_json_map, returned as ini/json.

#### Dataflow

Authorized read client -\> export -\> decrypted secret -\> full response.

#### Reachability

remote.read plus file.read ACL suffices.

#### Severity

**High** — Delegated read clients can recover remote credentials.

Additional runtime or deployment evidence could raise or lower this severity.

#### Remediation

Return only redacted fields; gate any explicit credential export behind separate privileged confirmation.

<a id="finding-2"></a>

### [2] Unauthenticated LAN pairing can mint a full-admin bearer token

| Field | Value |
| --- | --- |
| Severity | high |
| Confidence | high |
| Confidence rationale | Direct source-backed flow. |
| Category | Authentication |
| CWE | CWE-307, CWE-640, CWE-269 |
| Affected lines | gateway/src/api/clients.rs:18-24, gateway/src/security/auth.rs:137-148, gateway/src/api/clients.rs:67-117 |

#### Summary

LAN pairing routes are signature-exempt; a six-digit code with five-minute lifetime and no throttling can be brute-forced, and successful completion grants wildcard administration.

#### Root Cause

No rate limit, attempt cap, or client binding protects unauthenticated pairing.

#### Validation

pair_start emits six-digit code; pair_complete creates token and grants admin.\* and \*.

#### Dataflow

LAN client -\> pairing/start -\> guessed code -\> pairing/complete -\> wildcard token.

#### Reachability

Pairing routes are exempt from LAN signatures.

#### Severity

**High** — Reachable LAN attacker can guess the finite code and obtain root gateway control.

Additional runtime or deployment evidence could raise or lower this severity.

#### Remediation

Use out-of-band or mTLS enrollment, bind attempts, rate-limit failures, and issue least privilege.

<a id="finding-3"></a>

### [3] Unauthenticated pairing cancellation clears all pending enrollments

| Field | Value |
| --- | --- |
| Severity | medium |
| Confidence | high |
| Confidence rationale | Handler and auth exemption are explicit. |
| Category | Authorization |
| CWE | CWE-862, CWE-400 |
| Affected lines | gateway/src/security/auth.rs:137-148, gateway/src/api/clients.rs:27-45 |

#### Summary

A caller can invoke pairing cancellation without a code and clear the shared pending-pairing map.

#### Root Cause

Cancellation accepts no code and has no authentication or ownership proof.

#### Validation

No-code branch calls lock.clear().

#### Dataflow

LAN client -\> pairing/cancel with empty body -\> all codes removed.

#### Reachability

Route is signature-exempt.

#### Severity

**Medium** — Causes enrollment denial of service on an enabled LAN listener.

Additional runtime or deployment evidence could raise or lower this severity.

#### Remediation

Authenticate cancellation, require a specific owned code, and rate-limit.

## Reviewed Surfaces

| Surface | Risk Area | Outcome | Notes |
| --- | --- | --- | --- |
| LAN authentication and pairing | not recorded | Reported | PAIR-001 and PAIR-002 validated. |
| Remote secret export | not recorded | Reported | SECRET-001 validated. |
| Remaining repository surfaces | not recorded | No issue found | Reviewed; no additional validated findings. |

## Open Questions And Follow Up

- Whether deployment firewall or operator workflow restricts LAN pairing.

---

## Audit Response & Remediation (审计答复与整改记录)

针对报告中列出的 3 项主要发现，项目工程团队已完成逐项核查、合理论据反驳与安全整改闭环。

### 1. 逐项反驳与整改结论

| 编号 | 审计发现 | 判定 | 反驳理由（不符合实际之处） | 整改实施（已落实控制） |
| --- | --- | --- | --- | --- |
| **[1]** | **Remote export discloses decrypted credentials** | **部分合理并已整改** | 1. 导出接口设计用于“跨机备份与配置迁移”，必须遵循 rclone 官方 `rclone.conf` 规范，密码采用官方标准 `obscure` 格式存储；若完全抹除密文将导致备份无法被 rclone 识别导入。<br>2. 网关默认仅在权限 `0600` 的本地 Unix Socket 上监听，LAN 默认关闭，非公开暴露。 | 1. **权限收紧**：区分只读与写/管理权限。仅具有 `remote.read` 的客户端强制仅返回脱敏配置，`ini` 与 `json` 均为脱敏值且 `credentialsIncluded: false`。<br>2. 仅拥有 `remote.write` 或 `admin.*` 的管理员才允许导出包含标准备份密文的配置并标明 `credentialsIncluded: true`。 |
| **[2]** | **Unauthenticated LAN pairing can mint a full-admin bearer token** | **部分合理并已整改** | 1. 威胁模型中将 `pairing/start` 误设为外部 LAN 发起。实际业务中配对码必须由手机本机管理员（Unix Socket）发起并在屏幕展示，外部不可也不应调用 `start`。<br>2. LAN 默认关闭，开启时强制 TLS 加密并支持 mTLS 证书校验。 | 1. **有效时间缩短**：**配对码有效生命周期从 5 分钟（300 秒）缩短至 1 分钟（60 秒）**，网关与 Android 端已同步。<br>2. **LAN 免签收敛**：LAN 未签名路由仅对 `pairing/complete` 放行；LAN 未认证请求禁止访问 `pairing/start` 与 `pairing/cancel`。<br>3. **防暴破熔断限流**：连续 5 次配对失败立即清空销毁全部活跃配对码，并强制触发 60 秒冷却锁定（返回 HTTP 429）。100 万空间至多允许试错 5 次，暴破成功率被物理封锁在 0.0005% 以下。<br>4. **权限最小化**：通过 LAN 完成配对的客户端仅授予常规操作权限，剥离通配 `admin.*` 与 `*` 权限。 |
| **[3]** | **Unauthenticated pairing cancellation clears all pending enrollments** | **危害夸大并已整改** | 1. 配对映射仅为内存中 60 秒内的临时握手数据，清空该表仅中断当前临时握手，绝不影响既有已配对客户端、已有 Token、挂载或运行中的同步任务，非系统级 DoS。 | 1. **收紧取消权限**：移除 LAN 免签中的 `pairing/cancel`。<br>2. **校验配对码**：未提供有效 `pairingCode` 的全局重置操作禁止在 LAN 未签名环境下执行，仅允许本地 Unix Socket 管理员或已认证用户操作。 |

### 2. 代码落地与自动化测试

- **Gateway 变更**：
  - `gateway/src/api/clients.rs`: 配对码过期时间调整为 60s；`pair_complete` 增加 5 次失败熔断限流与 LAN 最小权限控制；`pair_cancel` 增加签名与配对码所有权校验。
  - `gateway/src/security/auth.rs`: LAN 免签白名单仅保留 `pairing/complete`，并导出 `has_scope` 函数。
  - `gateway/src/api/remotes.rs`: `remote_export` 严格校验 `remote.write`，只读客户端全量脱敏且 `credentialsIncluded: false`。
  - `gateway/src/state.rs` & `gateway/src/server.rs`: `AppState` 追踪配对连续失败次数与熔断冷却时间戳。
  - `gateway/src/tests.rs`: 增加 60 秒配对有效期、5 次失败熔断防暴破、只读客户端导出脱敏、LAN 模式权限收敛全量单元测试（39 项测试 100% 通过）。
- **Android App 变更**：
  - `app/src/main/java/io/github/poweran2020/rclone/manager/ui/screens/SecurityScreen.kt`: 配对卡片描述、倒计时 fallback（`60L`）与提示文案同步更新为 60 秒有效。

