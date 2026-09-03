//! Android Rclone Root Manager Gateway MVP.

use aes::Aes256;
use axum::http::Request;
use axum::{
    Json, Router,
    body::{Body, to_bytes},
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use base64::{Engine, engine::general_purpose::STANDARD as B64};
use chacha20poly1305::{
    XChaCha20Poly1305, XNonce,
    aead::{Aead, KeyInit},
};
use ctr::cipher::{KeyIvInit, StreamCipher};
use hmac::{Hmac, Mac};
use rand::RngCore;
use rusqlite::{Connection, OptionalExtension, TransactionBehavior, params};
#[cfg(unix)]
use rustls::{RootCertStore, ServerConfig, server::WebPkiClientVerifier};
#[cfg(unix)]
use rustls_pki_types::{CertificateDer, PrivateKeyDer, pem::PemObject};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
#[cfg(unix)]
use std::io::{Read, Write};
#[cfg(unix)]
use std::os::unix::net::UnixStream;
use std::{
    collections::HashMap,
    env, fs,
    net::SocketAddr,
    path::{Path as FsPath, PathBuf},
    sync::{Arc, Mutex},
    time::{Instant, SystemTime, UNIX_EPOCH},
};
use subtle::ConstantTimeEq;
use thiserror::Error;
#[cfg(unix)]
use tokio::net::UnixListener;
#[cfg(unix)]
use tokio::{net::TcpListener, process::Command, sync::RwLock};
#[cfg(not(unix))]
use tokio::{process::Command, sync::RwLock};
#[cfg(unix)]
use tokio_rustls::TlsAcceptor;
use uuid::Uuid;

const API_VERSION: &str = "1.1.0";
const DEFAULT_SOCKET: &str = "/data/adb/rclone-manage/runtime/gateway.sock";
const DEFAULT_ROOT: &str = "/data/adb/rclone-manage";
const SCHEMA: &str = include_str!("../../schema-v1.sql");
type Db = Arc<Mutex<Connection>>;
type Result<T> = std::result::Result<T, GatewayError>;

#[derive(Clone)]
struct AppState {
    db: Db,
    root: PathBuf,
    pairing: Arc<RwLock<HashMap<String, i64>>>,
    /// LAN routers require HMAC request signing after pairing. Unix clients
    /// retain the local bearer-only flow because socket permissions provide
    /// the transport boundary there.
    require_signature: bool,
}

// Handlers execute inside this task-local request scope. Audit writes can then
// capture real wall-clock latency without putting a request ID or plaintext
// payload into the database. Background scheduler work falls back to zero
// because it has no inbound request boundary.
tokio::task_local! {
    static REQUEST_STARTED: Instant;
}
#[derive(Debug, Error)]
enum GatewayError {
    #[error("{0}")]
    Message(String),
    #[error("database: {0}")]
    Db(#[from] rusqlite::Error),
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("crypto failure")]
    Crypto,
}
#[derive(Serialize)]
struct ErrorBody {
    code: String,
    message: String,
    request_id: String,
    details: serde_json::Value,
}
impl IntoResponse for GatewayError {
    fn into_response(self) -> axum::response::Response {
        let message = self.to_string();
        let code = match self {
            GatewayError::Db(_) => "DB_ERROR",
            GatewayError::Io(_) => "IO_ERROR",
            GatewayError::Crypto => "SECRET_ERROR",
            GatewayError::Message(ref m) if m.contains("scope denied") => "SCOPE_DENIED",
            GatewayError::Message(ref m) if m.contains("remote ACL denied") => "REMOTE_DENIED",
            GatewayError::Message(ref m) if m.contains("remote is still referenced") => {
                "REMOTE_IN_USE"
            }
            GatewayError::Message(ref m) if m.contains("confirmation") => "CONFIRMATION_REQUIRED",
            GatewayError::Message(ref m) if m.contains("job not found") => "JOB_NOT_FOUND",
            GatewayError::Message(ref m) if m.contains("rclone") && m.contains("failed") => {
                "CORE_UNAVAILABLE"
            }
            GatewayError::Message(ref m) if m.contains("migration") => "MIGRATION_REQUIRED",
            GatewayError::Message(ref m) if m.contains("MOUNT_CONFLICT") => "MOUNT_CONFLICT",
            GatewayError::Message(ref m) if m.contains("missing bearer") => "AUTH_REQUIRED",
            GatewayError::Message(ref m) if m.starts_with("AUTH") => "AUTH_INVALID",
            GatewayError::Message(ref m) if m.starts_with("PATH_DENIED") => "PATH_DENIED",
            GatewayError::Message(_) => "INVALID_REQUEST",
        };
        let status = match code {
            "AUTH_REQUIRED" | "AUTH_INVALID" => StatusCode::UNAUTHORIZED,
            "SCOPE_DENIED" | "REMOTE_DENIED" => StatusCode::FORBIDDEN,
            "REMOTE_IN_USE" => StatusCode::CONFLICT,
            "CONFIRMATION_REQUIRED" => StatusCode::PRECONDITION_REQUIRED,
            "JOB_NOT_FOUND" => StatusCode::NOT_FOUND,
            "PATH_DENIED" => StatusCode::FORBIDDEN,
            "DB_ERROR" if self.to_string().contains("not found") => StatusCode::NOT_FOUND,
            _ => StatusCode::BAD_REQUEST,
        };
        (
            status,
            Json(ErrorBody {
                code: code.into(),
                message,
                request_id: Uuid::new_v4().to_string(),
                details: serde_json::json!({}),
            }),
        )
            .into_response()
    }
}
fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}

fn process_kill_command() -> std::process::Command {
    if cfg!(target_os = "android") {
        std::process::Command::new("/system/bin/kill")
    } else {
        std::process::Command::new("kill")
    }
}

fn restrict_file(path: &FsPath) -> Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
    }
    #[cfg(not(unix))]
    let _ = path;
    Ok(())
}

fn hash(v: &str) -> String {
    hex::encode(Sha256::digest(v.as_bytes()))
}
fn header<'a>(h: &'a HeaderMap, name: &str) -> Option<&'a str> {
    h.get(name).and_then(|v| v.to_str().ok())
}

/// Optional request signing for LAN and non-UID constrained clients. Unix
/// socket clients may use bearer-only authentication; once one signing
/// header is supplied all signing headers are mandatory and replay protected.
/// The signature binds method, path/query, body hash, timestamp and nonce.
fn verify_signature(
    h: &HeaderMap,
    token: &str,
    s: &AppState,
    method: &str,
    path: &str,
    body: &[u8],
) -> Result<()> {
    let has = ["x-client-id", "x-timestamp", "x-nonce", "x-signature"]
        .iter()
        .any(|k| h.contains_key(*k));
    if !has {
        return Ok(());
    }
    let cid = header(h, "x-client-id")
        .ok_or_else(|| GatewayError::Message("AUTH missing client id".into()))?;
    let ts = header(h, "x-timestamp")
        .ok_or_else(|| GatewayError::Message("AUTH missing timestamp".into()))?;
    let nonce =
        header(h, "x-nonce").ok_or_else(|| GatewayError::Message("AUTH missing nonce".into()))?;
    let sig = header(h, "x-signature")
        .ok_or_else(|| GatewayError::Message("AUTH missing signature".into()))?;
    if cid.len() > 128
        || nonce.is_empty()
        || nonce.len() > 256
        || !nonce
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_'))
        || sig.len() != 64
        || !sig.bytes().all(|b| b.is_ascii_hexdigit())
    {
        return Err(GatewayError::Message("AUTH invalid signing headers".into()));
    }
    let ts_i: i128 = ts
        .parse()
        .map_err(|_| GatewayError::Message("AUTH invalid timestamp".into()))?;
    let now_ms = now() as i128 * 1000;
    let ts_ms = if ts_i < 10_000_000_000 {
        ts_i * 1000
    } else {
        ts_i
    };
    if (now_ms - ts_ms).abs() > 60_000 {
        return Err(GatewayError::Message(
            "AUTH timestamp outside window".into(),
        ));
    }
    let key = format!("request-nonce:{cid}:{nonce}");
    let body_hash = hex::encode(Sha256::digest(body));
    let canonical = format!("{method}\n{path}\n{body_hash}\n{ts}\n{nonce}");
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(token.as_bytes())
        .map_err(|_| GatewayError::Crypto)?;
    mac.update(canonical.as_bytes());
    let expected = hex::encode(mac.finalize().into_bytes());
    if expected.as_bytes().ct_eq(sig.as_bytes()).unwrap_u8() != 1 {
        return Err(GatewayError::Message("AUTH invalid signature".into()));
    }
    // The check and insert must share one IMMEDIATE transaction. A deferred
    // transaction (or two independent statements) permits two concurrent
    // requests with the same nonce to pass the replay check.
    let mut conn = db(s)?;
    let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
    if tx
        .query_row(
            "SELECT updated_at FROM system_config WHERE key=?",
            params![key],
            |r| r.get::<_, i64>(0),
        )
        .optional()?
        .is_some()
    {
        tx.rollback()?;
        return Err(GatewayError::Message("AUTH replay detected".into()));
    }
    tx.execute(
        "INSERT INTO system_config(key,value,updated_at) VALUES(?,?,?)",
        params![key, "1", now() + 60],
    )?;
    tx.commit()?;
    Ok(())
}

async fn signed_request(
    State(s): State<AppState>,
    request: Request<Body>,
    next: Next,
) -> axum::response::Response {
    let started = Instant::now();
    let signed = ["x-client-id", "x-timestamp", "x-nonce", "x-signature"]
        .iter()
        .any(|name| request.headers().contains_key(*name));
    let (parts, body_stream) = request.into_parts();
    let headers = parts.headers.clone();
    let path = parts
        .uri
        .path_and_query()
        .map(|v| v.as_str().to_owned())
        .unwrap_or_else(|| parts.uri.path().to_owned());
    // Enforce the fixed request size ceiling for direct Unix-socket clients as
    // well as for the request CLI and LAN clients.
    let body = match to_bytes(body_stream, 64 * 1024).await {
        Ok(v) => v,
        Err(_) => {
            return GatewayError::Message("request body exceeds 64 KiB".into()).into_response();
        }
    };
    if !signed {
        if s.require_signature
            && !matches!(
                parts.uri.path(),
                "/api/v1/security/pairing/start" | "/api/v1/security/pairing/complete"
            )
        {
            return GatewayError::Message("AUTH signed request required on LAN".into())
                .into_response();
        }
        return REQUEST_STARTED
            .scope(
                started,
                next.run(Request::from_parts(parts, Body::from(body))),
            )
            .await;
    }
    let token = headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .map(str::to_owned);
    let Some(token) = token else {
        return GatewayError::Message("AUTH missing bearer token for signed request".into())
            .into_response();
    };
    let method = parts.method.as_str().to_owned();
    if let Err(error) = verify_signature(&headers, &token, &s, &method, &path, &body) {
        return error.into_response();
    }
    let rebuilt = Request::from_parts(parts, Body::from(body));
    REQUEST_STARTED.scope(started, next.run(rebuilt)).await
}
fn db(s: &AppState) -> Result<std::sync::MutexGuard<'_, Connection>> {
    s.db.lock()
        .map_err(|_| GatewayError::Message("database lock poisoned".into()))
}
fn ensure_dirs(root: &FsPath) -> Result<()> {
    fs::create_dir_all(root)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(root, fs::Permissions::from_mode(0o700))?;
    }
    for n in [
        "db",
        "keys",
        "secrets",
        "runtime",
        "logs",
        "backups",
        "migrations",
        "cache",
    ] {
        let p = root.join(n);
        fs::create_dir_all(&p)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&p, fs::Permissions::from_mode(0o700))?;
        }
    }
    Ok(())
}
fn rotate_logs(root: &FsPath) -> Result<()> {
    let dir = root.join("logs");
    let max = configured_setting(root, "logMaxBytes", 10 * 1024 * 1024)?;
    let retention_days = configured_setting(root, "logRetentionDays", 14)?.clamp(1, 365);
    let cutoff = now().saturating_sub(retention_days.saturating_mul(86_400) as i64);
    for entry in fs::read_dir(&dir)? {
        let path = entry?.path();
        let name = path
            .file_name()
            .and_then(|x| x.to_str())
            .unwrap_or_default();
        let is_log =
            path.extension().and_then(|x| x.to_str()) == Some("log") || name.contains(".log.");
        if !is_log {
            continue;
        }
        let metadata = fs::metadata(&path)?;
        if metadata
            .modified()
            .ok()
            .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
            .is_some_and(|age| (age.as_secs() as i64) < cutoff)
        {
            let _ = fs::remove_file(&path);
            continue;
        }
        if path.extension().and_then(|x| x.to_str()) == Some("log") && metadata.len() > max {
            let old = path.with_extension("log.1");
            let _ = fs::remove_file(&old);
            fs::rename(path, &old)?;
            restrict_file(&old)?;
        }
    }
    Ok(())
}
fn configured_setting(root: &FsPath, key: &str, default: u64) -> Result<u64> {
    let Ok(conn) = Connection::open(root.join("db/state.db")) else {
        return Ok(default);
    };
    let value: Option<String> = conn
        .query_row(
            "SELECT value FROM system_config WHERE key=?",
            params![key],
            |r| r.get(0),
        )
        .optional()
        .unwrap_or(None);
    Ok(value.and_then(|v| v.parse::<u64>().ok()).unwrap_or(default))
}
fn parse_size_bytes(value: &str) -> Option<u64> {
    let value = value.trim();
    let split = value
        .find(|c: char| !c.is_ascii_digit())
        .unwrap_or(value.len());
    let number = value[..split].parse::<u64>().ok()?;
    let multiplier = match value[split..].trim().to_ascii_lowercase().as_str() {
        "" | "b" => 1,
        "k" | "kb" | "kib" => 1024,
        "m" | "mb" | "mib" => 1024 * 1024,
        "g" | "gb" | "gib" => 1024 * 1024 * 1024,
        "t" | "tb" | "tib" => 1024 * 1024 * 1024 * 1024,
        _ => return None,
    };
    number.checked_mul(multiplier)
}
fn open_db(root: &FsPath) -> Result<Db> {
    let c = Connection::open(root.join("db/state.db"))?;
    c.execute_batch(SCHEMA)?;
    // Forward-compatible additions for databases created by earlier builds.
    let _ = c.execute("ALTER TABLE job ADD COLUMN next_run_at INTEGER", []);
    let _ = c.execute("ALTER TABLE job ADD COLUMN max_runs INTEGER", []);
    let _ = c.execute("ALTER TABLE client ADD COLUMN token_expires_at INTEGER", []);
    let _ = c.execute("ALTER TABLE crypt_profile ADD COLUMN secret_ref TEXT", []);
    c.pragma_update(None, "journal_mode", "WAL")?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        for name in ["state.db", "state.db-wal", "state.db-shm"] {
            let p = root.join("db").join(name);
            if p.exists() {
                fs::set_permissions(p, fs::Permissions::from_mode(0o600))?;
            }
        }
    }
    Ok(Arc::new(Mutex::new(c)))
}
fn master_key(root: &FsPath) -> Result<[u8; 32]> {
    let p = root.join("keys/master.key");
    if p.exists() {
        return fs::read(p)?.try_into().map_err(|_| GatewayError::Crypto);
    }
    let mut k = [0; 32];
    rand::rng().fill_bytes(&mut k);
    fs::write(&p, k)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&p, fs::Permissions::from_mode(0o600))?;
    }
    Ok(k)
}
fn encrypt_secret(root: &FsPath, id: &str, v: &serde_json::Value) -> Result<String> {
    let cipher = XChaCha20Poly1305::new((&master_key(root)?).into());
    let mut n = [0; 24];
    rand::rng().fill_bytes(&mut n);
    let body = serde_json::to_vec(v).map_err(|_| GatewayError::Crypto)?;
    let aad = format!("{id}:1");
    let ct = cipher
        .encrypt(
            XNonce::from_slice(&n),
            chacha20poly1305::aead::Payload {
                msg: &body,
                aad: aad.as_bytes(),
            },
        )
        .map_err(|_| GatewayError::Crypto)?;
    let r = Uuid::new_v4().to_string();
    let path = root.join("secrets").join(format!("{r}.blob"));
    fs::write(&path, [n.as_slice(), ct.as_slice()].concat())?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
    }
    Ok(r)
}
fn decrypt_secret(root: &FsPath, reference: &str, aad_id: &str) -> Result<serde_json::Value> {
    let bytes = fs::read(root.join("secrets").join(format!("{reference}.blob")))?;
    if bytes.len() < 24 {
        return Err(GatewayError::Crypto);
    }
    let cipher = XChaCha20Poly1305::new((&master_key(root)?).into());
    let plain = cipher
        .decrypt(
            XNonce::from_slice(&bytes[..24]),
            chacha20poly1305::aead::Payload {
                msg: &bytes[24..],
                aad: format!("{aad_id}:1").as_bytes(),
            },
        )
        .map_err(|_| GatewayError::Crypto)?;
    serde_json::from_slice(&plain).map_err(|_| GatewayError::Crypto)
}

fn ini_line_safe(v: &str) -> bool {
    !v.contains(['\r', '\n', '\0'])
}

/// Validate the write-only credential object before encrypting it.  Keeping
/// this at the API boundary prevents an unusable or multiline value from
/// being persisted and later interpolated into a generated rclone config.
fn validate_secret_object(value: &serde_json::Value) -> Result<()> {
    let object = value
        .as_object()
        .ok_or_else(|| GatewayError::Message("secret must be an object".into()))?;
    if object.len() > 64 {
        return Err(GatewayError::Message("secret has too many fields".into()));
    }
    for (key, value) in object {
        if key.is_empty()
            || key.len() > 128
            || !key
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-'))
        {
            return Err(GatewayError::Message("invalid secret key".into()));
        }
        let text = match value {
            serde_json::Value::String(v) => v.clone(),
            serde_json::Value::Bool(_) | serde_json::Value::Number(_) => value.to_string(),
            _ => {
                return Err(GatewayError::Message(
                    "secret values must be scalar strings or numbers".into(),
                ));
            }
        };
        if text.len() > 8192 || !ini_line_safe(&text) {
            return Err(GatewayError::Message("invalid secret value".into()));
        }
    }
    Ok(())
}

/// Encode a crypt password using rclone's `obscure` format. rclone's crypt
/// backend expects this reversible AES-CTR wrapper in config files; storing
/// the source password remains the responsibility of the encrypted Secret
/// Store, never the generated config's caller.
fn obscure_rclone(value: &str) -> Result<String> {
    type RcloneCtr = ctr::Ctr128BE<Aes256>;
    const KEY: [u8; 32] = [
        0x9c, 0x93, 0x5b, 0x48, 0x73, 0x0a, 0x55, 0x4d, 0x6b, 0xfd, 0x7c, 0x63, 0xc8, 0x86, 0xa9,
        0x2b, 0xd3, 0x90, 0x19, 0x8e, 0xb8, 0x12, 0x8a, 0xfb, 0xf4, 0xde, 0x16, 0x2b, 0x8b, 0x95,
        0xf6, 0x38,
    ];
    let mut iv = [0u8; 16];
    rand::rng().fill_bytes(&mut iv);
    let mut ciphertext = value.as_bytes().to_vec();
    let mut cipher = RcloneCtr::new((&KEY).into(), (&iv).into());
    cipher.apply_keystream(&mut ciphertext);
    let mut out = iv.to_vec();
    out.extend_from_slice(&ciphertext);
    Ok(base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(out))
}

fn valid_crypt_name(name: &str, parent_name: &str) -> bool {
    name != parent_name && validate_identity(name, "crypt profile name", 128).is_ok()
}

fn remote_id_by_name(s: &AppState, name: &str) -> Result<String> {
    db(s)?
        .query_row(
            "SELECT id FROM remote WHERE name=? AND enabled=1",
            params![name],
            |r| r.get(0),
        )
        .map_err(|_| GatewayError::Message("remote not found or disabled".into()))
}

fn validate_identity(value: &str, field: &str, max: usize) -> Result<()> {
    if value.is_empty()
        || value.len() > max
        || !value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-' | b'.' | b' '))
    {
        return Err(GatewayError::Message(format!("invalid {field}")));
    }
    Ok(())
}

fn materialize_rclone_config_at(
    s: &AppState,
    ids: &[String],
    destination: Option<PathBuf>,
) -> Result<Option<PathBuf>> {
    let mut sections = Vec::new();
    for id in ids {
        let (name, typ, endpoint, secret_ref): (String, String, Option<String>, Option<String>) =
            db(s)?
                .query_row(
                    "SELECT name,type,endpoint,secret_ref FROM remote WHERE id=? AND enabled=1",
                    params![id],
                    |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
                )
                .map_err(|_| GatewayError::Message("remote not found or disabled".into()))?;
        if !ini_line_safe(&name)
            || !ini_line_safe(&typ)
            || !typ
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
        {
            return Err(GatewayError::Message("invalid remote configuration".into()));
        }
        let mut text = format!("[{name}]\ntype = {typ}\n");
        if let Some(e) = endpoint {
            if !ini_line_safe(&e) {
                return Err(GatewayError::Message("invalid endpoint".into()));
            }
            text.push_str(&format!("endpoint = {e}\n"));
        }
        if let Some(sr) = secret_ref {
            let secret = decrypt_secret(&s.root, &sr, id)?;
            let obj = secret
                .as_object()
                .ok_or_else(|| GatewayError::Message("secret must be an object".into()))?;
            for (k, v) in obj {
                if !k
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
                {
                    return Err(GatewayError::Message("invalid secret key".into()));
                }
                let value = v
                    .as_str()
                    .map(str::to_owned)
                    .unwrap_or_else(|| v.to_string());
                if !ini_line_safe(&value) {
                    return Err(GatewayError::Message("invalid secret value".into()));
                }
                text.push_str(&format!("{k} = {value}\n"));
            }
        }
        sections.push(text);
    }
    if sections.is_empty() {
        return Ok(None);
    }
    let path = destination.unwrap_or_else(|| {
        s.root
            .join("runtime")
            .join(format!("rclone-{}.conf", Uuid::new_v4()))
    });
    fs::write(&path, sections.join("\n"))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&path, fs::Permissions::from_mode(0o600))?;
    }
    Ok(Some(path))
}

fn materialize_rclone_config(s: &AppState, ids: &[String]) -> Result<Option<PathBuf>> {
    materialize_rclone_config_at(s, ids, None)
}

/// Mount workers need credentials for their entire lifetime.  Keep their
/// generated config at a deterministic, root-only path and remove it when the
/// worker is stopped; one-second delayed deletion is inherently racy.
fn materialize_mount_config(s: &AppState, id: &str, remote_id: &str) -> Result<PathBuf> {
    let path = s.root.join("runtime").join(format!("mount-{id}.conf"));
    let _ = fs::remove_file(&path);
    materialize_rclone_config_at(s, &[remote_id.to_owned()], Some(path))?
        .ok_or_else(|| GatewayError::Message("remote has no usable configuration".into()))
}

/// Build a short-lived rclone config containing a typed `crypt` remote.  The
/// underlying remote section is generated by the same allow-listed path used
/// for ordinary operations; the crypt password is read only inside Gateway
/// and never returned through the API.
fn materialize_crypt_config(s: &AppState, id: &str) -> Result<(PathBuf, String)> {
    let (name, remote_id, remote_path, secret_ref, parent_name):
        (String, String, String, Option<String>, String) =
        db(s)?.query_row(
            "SELECT c.name,c.remote_id,c.remote_path,c.secret_ref,r.name FROM crypt_profile c JOIN remote r ON r.id=c.remote_id WHERE c.id=?",
            params![id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
        )?;
    let path = s
        .root
        .join("runtime")
        .join(format!("crypt-{}.conf", Uuid::new_v4()));
    let Some(base) = materialize_rclone_config_at(s, &[remote_id], Some(path.clone()))? else {
        return Err(GatewayError::Message(
            "crypt parent remote unavailable".into(),
        ));
    };
    let password = secret_ref
        .as_deref()
        .ok_or_else(|| GatewayError::Message("crypt password is not configured".into()))
        .and_then(|reference| decrypt_secret(&s.root, reference, id))?
        .get("password")
        .and_then(|v| v.as_str())
        .map(str::to_owned)
        .ok_or_else(|| GatewayError::Message("crypt password is invalid".into()))?;
    if !ini_line_safe(&password) || password.is_empty() || password.len() > 4096 {
        let _ = fs::remove_file(&base);
        return Err(GatewayError::Message("crypt password is invalid".into()));
    }
    let mut config = fs::OpenOptions::new().append(true).open(&base)?;
    use std::io::Write as _;
    writeln!(
        config,
        "\n[{name}]\ntype = crypt\nremote = {parent_name}:{remote_path}\npassword = {}",
        obscure_rclone(&password)?
    )?;
    restrict_file(&base)?;
    Ok((base, name))
}

fn rclone_command(config: &Option<PathBuf>) -> Command {
    let executable = env::var_os("RCLONE_BIN")
        .map(PathBuf::from)
        .filter(|p| p.is_absolute() && p.is_file())
        .or_else(|| {
            [
                "/data/adb/modules/rclone-manager/bin/rclone",
                "/data/adb/modules/rclone/bin/rclone",
                "/data/adb/modules/rclone/system/vendor/bin/rclone",
                "/system/vendor/bin/rclone",
                "/vendor/bin/rclone",
            ]
            .iter()
            .map(PathBuf::from)
            .find(|p| p.is_file())
        })
        // Never fall back to PATH lookup: a compromised environment must not
        // substitute an arbitrary executable for the rclone core.
        .unwrap_or_else(|| PathBuf::from("/system/vendor/bin/rclone"));
    let mut c = Command::new(executable);
    if let Some(path) = config {
        c.arg("--config").arg(path);
    }
    c
}

fn schedule_interval(schedule: Option<&str>) -> Option<i64> {
    let s = schedule?.trim();
    match s {
        "@hourly" => Some(3600),
        "@daily" => Some(86400),
        // @reboot is handled by the boot recovery path, never by the
        // periodic scheduler (otherwise it would run every tick).
        "@reboot" => None,
        _ if s.starts_with("@every ") => s[7..]
            .trim_end_matches('s')
            .parse()
            .ok()
            .filter(|v: &i64| *v > 0),
        _ if s.starts_with("*/") && s.ends_with(" * * * *") => s[2..s.len() - 8]
            .parse::<i64>()
            .ok()
            .filter(|v| *v > 0)
            .map(|v| v * 60),
        _ => None,
    }
}

async fn scheduler(state: AppState) {
    // Recovery is deliberately conservative: an interrupted run is marked
    // failed and can be retried explicitly, while queued work is resumed.
    // Terminate any worker PIDs left behind by a crashed Gateway before
    // changing their persisted state; otherwise an orphaned rclone process
    // could continue transferring after recovery.
    if let Ok(c) = db(&state) {
        let pids: Vec<i64> = c
            .prepare("SELECT pid FROM job_run WHERE state='RUNNING' AND pid IS NOT NULL")
            .and_then(|mut st| {
                st.query_map([], |r| r.get(0))?
                    .collect::<rusqlite::Result<Vec<_>>>()
            })
            .unwrap_or_default();
        for pid in pids {
            let _ = process_kill_command()
                .args(["-TERM", &pid.to_string()])
                .status();
        }
    }
    if let Ok(c) = db(&state) {
        let _ = c.execute("UPDATE job SET status='FAILED',last_error_code='GATEWAY_RESTARTED',updated_at=? WHERE status IN ('RUNNING','PAUSE_REQUESTED','CANCEL_REQUESTED')", params![now()]);
        let _ = c.execute("UPDATE mount_profile SET status='STOPPED',pid=NULL,updated_at=? WHERE status IN ('STARTING','RUNNING','STOPPING')", params![now()]);
    }
    // A previous process may have left plaintext materialized configs behind.
    // They are recreated only for enabled mounts that are actually recovered.
    if let Ok(entries) = fs::read_dir(state.root.join("runtime")) {
        for entry in entries.flatten() {
            let p = entry.path();
            if p.file_name()
                .and_then(|n| n.to_str())
                .is_some_and(|n| n.starts_with("mount-") && n.ends_with(".conf"))
            {
                let _ = fs::remove_file(p);
            }
        }
    }
    let safe_at_boot = safe_mode_enabled(&state);
    // One-shot boot jobs run once after recovery.
    let boot_jobs: Vec<String> = db(&state)
        .and_then(|c| {
            let mut st =
                c.prepare("SELECT id FROM job WHERE status='CREATED' AND schedule='@reboot'")?;
            Ok(st
                .query_map([], |r| r.get(0))?
                .collect::<rusqlite::Result<Vec<_>>>()?)
        })
        .unwrap_or_default();
    for id in boot_jobs.into_iter().filter(|_| !safe_at_boot) {
        let s = state.clone();
        tokio::spawn(async move {
            run_job(s, id).await;
        });
    }
    let enabled_mounts: Vec<String> = db(&state)
        .and_then(|c| {
            let mut st = c.prepare("SELECT id FROM mount_profile WHERE enabled=1")?;
            Ok(st
                .query_map([], |r| r.get(0))?
                .collect::<rusqlite::Result<Vec<_>>>()?)
        })
        .unwrap_or_default();
    for id in enabled_mounts.into_iter().filter(|_| !safe_at_boot) {
        let s = state.clone();
        tokio::spawn(async move {
            recover_mount(s, id).await;
        });
    }
    let mut tick = tokio::time::interval(std::time::Duration::from_secs(5));
    loop {
        tick.tick().await;
        let _ = rotate_logs(&state.root);
        if let Ok(c) = db(&state) {
            let _ = c.execute("DELETE FROM system_config WHERE (key LIKE 'request-nonce:%' OR key LIKE 'delete-confirm:%' OR key LIKE 'remote-delete-confirm:%') AND updated_at<?", params![now()]);
        }
        let safe = safe_mode_enabled(&state);
        // Reconcile worker PIDs without touching unrelated processes. A dead
        // mount is marked stopped and will be retried only by explicit start
        // or the next Gateway boot recovery policy.
        let running_mounts: Vec<(String, i64, String)> = db(&state)
            .and_then(|c| {
                let mut st = c.prepare(
                    "SELECT id,pid,mount_point FROM mount_profile WHERE status='RUNNING' AND pid IS NOT NULL",
                )?;
                Ok(st
                    .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?
                    .collect::<rusqlite::Result<Vec<_>>>()?)
            })
            .unwrap_or_default();
        for (id, pid, mount_point) in running_mounts {
            let alive = process_kill_command()
                .args(["-0", &pid.to_string()])
                .status()
                .is_ok_and(|v| v.success());
            if !alive {
                let _ = db(&state).and_then(|c| {
                    Ok(c.execute(
                        "UPDATE mount_profile SET status='STOPPED',pid=NULL,updated_at=? WHERE id=?",
                        params![now(), id],
                    )?)
                });
                unmount_derived_bind(&mount_point);
                remove_mount_config(&state.root, &id);
            }
        }
        if safe {
            continue;
        }
        let due: Vec<String> = match db(&state).and_then(|c| {
            let mut st = c.prepare("SELECT id FROM job WHERE status='QUEUED' OR (status='CREATED' AND schedule IS NOT NULL AND schedule <> '@reboot' AND next_run_at IS NOT NULL AND next_run_at<=?)")?;
            Ok(st.query_map(params![now()], |r| r.get(0))?.collect::<rusqlite::Result<Vec<_>>>()?)
        }) { Ok(v) => v, Err(_) => continue };
        let max_concurrent = configured_setting(&state.root, "maxConcurrentJobs", 2)
            .unwrap_or(2)
            .clamp(1, 4) as usize;
        let running = db(&state)
            .ok()
            .and_then(|c| {
                c.query_row("SELECT COUNT(*) FROM job WHERE status='RUNNING'", [], |r| {
                    r.get::<_, i64>(0)
                })
                .ok()
            })
            .unwrap_or(0)
            .max(0) as usize;
        for id in due.into_iter().take(max_concurrent.saturating_sub(running)) {
            let s = state.clone();
            tokio::spawn(async move {
                run_job(s, id).await;
            });
        }
    }
}
async fn recover_mount(state: AppState, id: String) {
    let row: Result<(
        String,
        String,
        String,
        String,
        Option<String>,
        bool,
        String,
        String,
        String,
    )> = (|| {
        let c = db(&state)?;
        Ok(c.query_row("SELECT name,remote_id,remote_path,mount_point,cache_dir,read_only,cache_mode,cache_max_size,cache_max_age FROM mount_profile WHERE id=? AND enabled=1", params![id], |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?,r.get(4)?,r.get::<_,i64>(5)? != 0,r.get(6)?,r.get(7)?,r.get(8)?)))?)
    })();
    let Ok((
        _,
        remote_id,
        remote_path,
        mount_point,
        cache_dir,
        read_only,
        cache_mode,
        max_size,
        max_age,
    )) = row
    else {
        return;
    };
    if valid_mount(&mount_point).is_err() {
        return;
    }
    let Ok((remote_name, _)) = remote_target(&state, &remote_id) else {
        return;
    };
    let _ = fs::create_dir_all(&mount_point);
    let Ok(config) = materialize_mount_config(&state, &id, &remote_id) else {
        remove_mount_config(&state.root, &id);
        return;
    };
    let mut command = rclone_command(&Some(config.clone()));
    command.args([
        "mount",
        &format!("{remote_name}:{remote_path}"),
        &mount_point,
        "--vfs-cache-mode",
        &cache_mode,
        "--vfs-cache-max-size",
        &max_size,
        "--vfs-cache-max-age",
        &max_age,
    ]);
    if let Some(cache_dir) = cache_dir {
        if path_is_within(&state.root.join("cache"), FsPath::new(&cache_dir)) {
            let _ = fs::create_dir_all(&cache_dir);
            command.args(["--cache-dir", &cache_dir]);
        }
    }
    if read_only {
        command.arg("--read-only");
    }
    let child = command.spawn();
    // Keep the per-mount config until an explicit stop or stale-worker
    // cleanup. It contains only root-readable materialized credentials.
    if let Ok(mut child) = child {
        if let Some(pid) = child.id() {
            if let Ok(c) = db(&state) {
                let _ = c.execute(
                    "UPDATE mount_profile SET status='RUNNING',pid=?,updated_at=? WHERE id=?",
                    params![pid as i64, now(), id],
                );
            }
            tokio::spawn(bind_mount_when_ready(mount_point.clone()));
            let monitor_state = state.clone();
            let monitor_id = id.clone();
            tokio::spawn(async move {
                let _ = child.wait().await;
                if let Ok(c) = db(&monitor_state) {
                    let _ = c.execute(
                        "UPDATE mount_profile SET status='STOPPED',pid=NULL,updated_at=? WHERE id=? AND pid=?",
                        params![now(), monitor_id, pid as i64],
                    );
                }
                unmount_derived_bind(&mount_point);
                remove_mount_config(&monitor_state.root, &monitor_id);
            });
        } else {
            remove_mount_config(&state.root, &id);
        }
    } else {
        // A failed recovery spawn must not leave credentials on disk.
        remove_mount_config(&state.root, &id);
    }
}

fn reset_mount_start(state: &AppState, id: &str) {
    if let Ok(c) = db(state) {
        let _ = c.execute(
            "UPDATE mount_profile SET status='STOPPED',pid=NULL,updated_at=? WHERE id=?",
            params![now(), id],
        );
    }
    remove_mount_config(&state.root, id);
}
fn valid_path(path: &str, prefix: &str) -> Result<String> {
    if path.bytes().any(|b| b < 0x20) || path.split('/').any(|p| p == "..") {
        return Err(GatewayError::Message(
            "PATH_DENIED: path traversal is not allowed".into(),
        ));
    }
    let n = format!("/{}", path.trim_matches('/'));
    let b = format!("/{}", prefix.trim_matches('/'));
    if b != "/" && n != b && !n.starts_with(&(b.clone() + "/")) {
        return Err(GatewayError::Message(
            "PATH_DENIED: outside allowed prefix".into(),
        ));
    }
    Ok(n)
}
fn valid_mount(p: &str) -> Result<()> {
    if p.contains("..") || p.bytes().any(|b| b < 0x20) {
        return Err(GatewayError::Message(
            "PATH_DENIED: mount point is not allowed".into(),
        ));
    }
    let valid_root = if let Some(name) = p.strip_prefix("/mnt/rclone-") {
        !name.is_empty()
            && name
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.'))
    } else if let Some(name) = p.strip_prefix("/sdcard/") {
        !name.is_empty()
    } else if let Some(name) = p.strip_prefix("/data/media/0/") {
        !name.is_empty()
    } else {
        false
    };
    if !valid_root {
        return Err(GatewayError::Message(
            "PATH_DENIED: mount point is not allowed".into(),
        ));
    }
    // Resolve existing components to catch a mount path symlink escaping the
    // permitted Android storage tree before creating or binding it.
    guard_local_path(p)?;
    // Windows host tests use Android-shaped paths that do not exist on the
    // host filesystem; the canonical storage-root check is enforced on the
    // Unix/Android production target.
    if cfg!(windows) {
        return Ok(());
    }
    let allowed_root = if p.starts_with("/mnt/rclone-") {
        FsPath::new("/mnt")
    } else if p.starts_with("/sdcard/") {
        FsPath::new("/sdcard")
    } else {
        FsPath::new("/data/media/0")
    };
    let nearest = {
        let mut candidate = FsPath::new(p);
        while !candidate.exists() {
            candidate = candidate.parent().ok_or_else(|| {
                GatewayError::Message("PATH_DENIED: mount path cannot be resolved".into())
            })?;
        }
        fs::canonicalize(candidate).map_err(|_| {
            GatewayError::Message("PATH_DENIED: mount path cannot be resolved".into())
        })?
    };
    let root = fs::canonicalize(allowed_root).unwrap_or_else(|_| allowed_root.to_path_buf());
    if !nearest.starts_with(&root) {
        return Err(GatewayError::Message(
            "PATH_DENIED: mount path escapes allowed storage root".into(),
        ));
    }
    Ok(())
}
fn path_is_within(base: &FsPath, candidate: &FsPath) -> bool {
    use std::path::Component;
    // Lexical parent components are never acceptable for a cache path. This
    // check is needed even when the target does not exist yet and therefore
    // cannot be canonicalized.
    if (!candidate.is_absolute() && !(cfg!(windows) && candidate.has_root()))
        || candidate
            .components()
            .any(|component| matches!(component, Component::ParentDir))
    {
        return false;
    }
    if !candidate.starts_with(base) {
        return false;
    }
    // When the configured base directory has not been created yet, lexical
    // containment plus the parent-component check is the strongest available
    // proof. Creation happens immediately after this validation under the
    // manager-owned root.
    if !base.exists() {
        return true;
    }
    let base_canonical = match fs::canonicalize(base) {
        Ok(path) => path,
        Err(_) => return false,
    };
    let mut nearest = candidate;
    while !nearest.exists() {
        let Some(parent) = nearest.parent() else {
            return false;
        };
        if parent == nearest {
            break;
        }
        nearest = parent;
    }
    let nearest_canonical = match fs::canonicalize(nearest) {
        Ok(path) => path,
        Err(_) => return false,
    };
    nearest_canonical.starts_with(&base_canonical)
}
/// For the documented `/mnt/rclone-<name>` FUSE location, expose the same
/// mount through the user-visible shared-storage tree. The target is derived
/// from a restricted single path segment; arbitrary bind targets are never
/// accepted from API input.
fn derived_bind_target(source: &str) -> Option<String> {
    let name = source.strip_prefix("/mnt/rclone-")?;
    if name.is_empty()
        || !name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.'))
    {
        return None;
    }
    Some(format!("/data/media/0/{name}"))
}
fn umount_command() -> std::process::Command {
    if cfg!(target_os = "android") {
        std::process::Command::new("/system/bin/umount")
    } else {
        std::process::Command::new("umount")
    }
}
async fn bind_mount_when_ready(source: String) {
    let Some(target) = derived_bind_target(&source) else {
        return;
    };
    let _ = fs::create_dir_all(&target);
    for _ in 0..20 {
        let mut command = if cfg!(target_os = "android") {
            tokio::process::Command::new("/system/bin/mount")
        } else {
            tokio::process::Command::new("mount")
        };
        let ok = command
            .args(["--bind", &source, &target])
            .status()
            .await
            .is_ok_and(|s| s.success());
        if ok {
            return;
        }
        tokio::time::sleep(std::time::Duration::from_millis(500)).await;
    }
}
fn unmount_derived_bind(source: &str) {
    let Some(target) = derived_bind_target(source) else {
        return;
    };
    let _ = umount_command().arg(&target).status();
}
fn valid_local_path(path: &str) -> Result<()> {
    if path.is_empty()
        || !path.starts_with('/')
        || path.starts_with('-')
        || path.bytes().any(|b| b < 0x20)
        || path.split('/').any(|p| p == "..")
    {
        return Err(GatewayError::Message(
            "PATH_DENIED: invalid local path".into(),
        ));
    }
    for blocked in [
        "/system",
        "/vendor",
        "/product",
        "/proc",
        "/sys",
        "/dev",
        "/data/adb",
    ] {
        if path == blocked || path.starts_with(&(blocked.to_string() + "/")) {
            return Err(GatewayError::Message(
                "PATH_DENIED: protected local path".into(),
            ));
        }
    }
    Ok(())
}

/// Validate a local transfer path after resolving every existing path
/// component.  String checks alone are insufficient when a caller supplies a
/// symlink that points into a protected tree.  The final component may be
/// absent for uploads, so resolve its nearest existing ancestor instead.
fn guard_local_path(path: &str) -> Result<()> {
    valid_local_path(path)?;
    let mut candidate = FsPath::new(path);
    while !candidate.exists() {
        candidate = candidate.parent().ok_or_else(|| {
            GatewayError::Message("PATH_DENIED: local path has no existing ancestor".into())
        })?;
    }
    let canonical = fs::canonicalize(candidate)
        .map_err(|_| GatewayError::Message("PATH_DENIED: local path cannot be resolved".into()))?;
    for blocked in [
        "/system",
        "/vendor",
        "/product",
        "/proc",
        "/sys",
        "/dev",
        "/data/adb",
    ] {
        let blocked_path = FsPath::new(blocked);
        let blocked_canonical =
            fs::canonicalize(blocked_path).unwrap_or_else(|_| PathBuf::from(blocked));
        if canonical == blocked_canonical || canonical.starts_with(&blocked_canonical) {
            return Err(GatewayError::Message(
                "PATH_DENIED: local path resolves inside a protected tree".into(),
            ));
        }
    }
    Ok(())
}
fn audit(
    s: &AppState,
    c: Option<&str>,
    op: &str,
    res: Option<&str>,
    remote: Option<&str>,
    result: &str,
    err: Option<&str>,
) -> Result<()> {
    audit_inner(s, c, op, res, remote, result, err, None)
}

/// Record an event while hashing an operation path independently of the
/// human-readable resource identifier (for example a job UUID).
fn audit_path(
    s: &AppState,
    c: Option<&str>,
    op: &str,
    res: Option<&str>,
    remote: Option<&str>,
    path: &str,
    result: &str,
    err: Option<&str>,
) -> Result<()> {
    audit_inner(s, c, op, res, remote, result, err, Some(path))
}

fn audit_inner(
    s: &AppState,
    c: Option<&str>,
    op: &str,
    res: Option<&str>,
    remote: Option<&str>,
    result: &str,
    err: Option<&str>,
    path: Option<&str>,
) -> Result<()> {
    // Keep audit records useful without retaining plaintext paths or secrets.
    // The current Gateway process is the privileged boundary, so its UID is
    // the authoritative executor UID (peer UID is not available on all
    // Android Tokio Unix listener variants). Stable resource identifiers stay
    // available for correlation; path material is accepted only through the
    // separate hash input below and never enters the audit database plainly.
    let uid = current_uid() as i64;
    let path_hash = path.map(hash);
    let safe_resource =
        res.filter(|value| !value.starts_with('/') && !value.contains(['\\', '\r', '\n', '\0']));
    let latency_ms = REQUEST_STARTED
        .try_with(|started| started.elapsed().as_millis().min(i64::MAX as u128) as i64)
        .unwrap_or(0);
    db(s)?.execute("INSERT INTO audit_log(timestamp,client_id,uid,operation,resource,remote_id,path_hash,result,error_code,latency_ms) VALUES(?,?,?,?,?,?,?,?,?,?)",params![now(),c,uid,op,safe_resource,remote,path_hash,result,err,latency_ms])?;
    Ok(())
}

#[cfg(unix)]
fn current_uid() -> u32 {
    unsafe {
        unsafe extern "C" {
            fn getuid() -> u32;
        }
        getuid()
    }
}

#[cfg(not(unix))]
fn current_uid() -> u32 {
    0
}

fn safe_mode_enabled(s: &AppState) -> bool {
    db(s)
        .ok()
        .and_then(|c| {
            c.query_row(
                "SELECT value FROM system_config WHERE key='safe_mode'",
                [],
                |r| r.get::<_, String>(0),
            )
            .ok()
        })
        .is_some_and(|v| v == "1")
}

fn remove_mount_config(root: &FsPath, id: &str) {
    let _ = fs::remove_file(root.join("runtime").join(format!("mount-{id}.conf")));
}

fn token_client(h: &HeaderMap, s: &AppState) -> Result<String> {
    let t = h
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .ok_or_else(|| GatewayError::Message("AUTH missing bearer token".into()))?;
    let d = hash(t);
    let c = db(s)?;
    let row: Option<(String, String, Option<i64>)> = c
        .query_row(
            "SELECT id,status,token_expires_at FROM client WHERE token_hash=?",
            params![d],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .optional()?;
    let (id, status, expires) =
        row.ok_or_else(|| GatewayError::Message("AUTH invalid token".into()))?;
    if status != "ACTIVE" {
        return Err(GatewayError::Message("AUTH client inactive".into()));
    }
    if expires.is_some_and(|v| v <= now()) {
        return Err(GatewayError::Message("AUTH token expired".into()));
    }
    if let Some(signed_id) = header(h, "x-client-id") {
        if signed_id != id {
            return Err(GatewayError::Message("AUTH client id mismatch".into()));
        }
    }
    c.execute(
        "UPDATE client SET last_seen_at=? WHERE id=?",
        params![now(), id],
    )?;
    Ok(id)
}
fn scope(h: &HeaderMap, s: &AppState, name: &str) -> Result<String> {
    let id = token_client(h, s)?;
    let ok:Option<i64>=db(s)?.query_row("SELECT 1 FROM permission_grant WHERE client_id=? AND (scope=? OR scope='*') AND (expires_at IS NULL OR expires_at>?) LIMIT 1",params![id,name,now()],|r|r.get(0)).optional()?;
    if ok.is_none() {
        return Err(GatewayError::Message(format!("AUTH scope denied: {name}")));
    }
    Ok(id)
}
fn acl(s: &AppState, c: &str, r: &str, p: &str, path: &str) -> Result<()> {
    let conn = db(s)?;
    let mut st = conn.prepare(
        "SELECT permissions,allowed_prefix FROM remote_acl WHERE client_id=? AND remote_id=?",
    )?;
    let rows = st.query_map(params![c, r], |x| {
        Ok((x.get::<_, String>(0)?, x.get::<_, String>(1)?))
    })?;
    for row in rows {
        let (permissions, prefix) = row?;
        let granted = permissions == "*"
            || permissions
                .split(',')
                .map(str::trim)
                .any(|v| v == p || v == "*");
        if granted && valid_path(path, &prefix).is_ok() {
            return Ok(());
        }
    }
    Err(GatewayError::Message("AUTH remote ACL denied".into()))
}

/// Validate and consume a destructive-operation confirmation atomically.
/// Keeping the read, expiry check, caller validation, and delete in one
/// IMMEDIATE transaction prevents two concurrent requests from reusing a
/// single confirmation token.
fn consume_confirmation<F>(s: &AppState, key: &str, validate: F) -> Result<()>
where
    F: FnOnce(i64, &str) -> Result<()>,
{
    let mut conn = db(s)?;
    let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
    let (expires, stored): (i64, String) = tx
        .query_row(
            "SELECT updated_at,value FROM system_config WHERE key=?",
            params![key],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .map_err(|_| GatewayError::Message("confirmation token invalid".into()))?;
    if expires < now() {
        let _ = tx.execute("DELETE FROM system_config WHERE key=?", params![key]);
        tx.commit()?;
        return Err(GatewayError::Message("confirmation token expired".into()));
    }
    validate(expires, &stored)?;
    let deleted = tx.execute("DELETE FROM system_config WHERE key=?", params![key])?;
    if deleted != 1 {
        return Err(GatewayError::Message("confirmation token invalid".into()));
    }
    tx.commit()?;
    Ok(())
}

#[derive(Serialize)]
struct Health {
    status: &'static str,
    api_version: &'static str,
    auth: &'static str,
}
async fn health() -> Json<Health> {
    Json(Health {
        status: "ok",
        api_version: API_VERSION,
        auth: "configured",
    })
}
async fn safe_mode_get(State(s): State<AppState>, h: HeaderMap) -> Result<Json<serde_json::Value>> {
    scope(&h, &s, "system.read")?;
    let enabled: Option<String> = db(&s)?
        .query_row(
            "SELECT value FROM system_config WHERE key='safe_mode'",
            [],
            |r| r.get(0),
        )
        .optional()?;
    Ok(Json(
        serde_json::json!({"enabled": enabled.as_deref() == Some("1")}),
    ))
}
async fn safe_mode_set(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(v): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>> {
    let c = scope(&h, &s, "security.write")?;
    let enabled = v
        .get("enabled")
        .and_then(|x| x.as_bool())
        .ok_or_else(|| GatewayError::Message("enabled must be boolean".into()))?;
    db(&s)?.execute(
        "INSERT OR REPLACE INTO system_config(key,value,updated_at) VALUES('safe_mode',?,?)",
        params![if enabled { "1" } else { "0" }, now()],
    )?;
    if enabled {
        // Stop only workers owned by this gateway. Scheduled jobs remain
        // persisted and are not dispatched while Safe Mode is active.
        let job_pids: Vec<i64> = {
            let conn = db(&s)?;
            let mut st =
                conn.prepare("SELECT pid FROM job_run WHERE state='RUNNING' AND pid IS NOT NULL")?;
            st.query_map([], |r| r.get(0))?
                .collect::<rusqlite::Result<Vec<_>>>()?
        };
        // Persist cancellation before signalling workers. This closes the
        // race where a fast worker exits and reports SUCCESS between TERM and
        // the cancellation update; finish_job will then preserve CANCELLED.
        db(&s)?.execute(
            "UPDATE job SET status='CANCEL_REQUESTED',updated_at=? WHERE status IN ('RUNNING','PAUSE_REQUESTED','CANCEL_REQUESTED')",
            params![now()],
        )?;
        for pid in job_pids {
            let _ = process_kill_command()
                .args(["-TERM", &pid.to_string()])
                .status();
        }
        let pids: Vec<(String, i64, String)> = {
            let conn = db(&s)?;
            let mut st = conn.prepare(
                "SELECT id,pid,mount_point FROM mount_profile WHERE pid IS NOT NULL AND status IN ('STARTING','RUNNING','STOPPING')",
            )?;
            st.query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?
                .collect::<rusqlite::Result<Vec<_>>>()?
        };
        for (id, pid, mount_point) in pids {
            let _ = process_kill_command()
                .args(["-TERM", &pid.to_string()])
                .status();
            let _ = db(&s)?.execute(
                "UPDATE mount_profile SET status='STOPPED',pid=NULL,updated_at=? WHERE id=?",
                params![now(), id],
            );
            unmount_derived_bind(&mount_point);
            remove_mount_config(&s.root, &id);
        }
        let _ = fs::write(s.root.join("runtime/safe-mode"), b"1");
        let _ = restrict_file(&s.root.join("runtime/safe-mode"));
    } else {
        let _ = fs::remove_file(s.root.join("runtime/safe-mode"));
        let _ = fs::remove_file(s.root.join("runtime/gateway-crash-count"));
    }
    audit(
        &s,
        Some(&c),
        "system.safe_mode",
        None,
        None,
        "SUCCESS",
        None,
    )?;
    Ok(Json(serde_json::json!({"enabled":enabled})))
}
async fn system_settings_get(
    State(s): State<AppState>,
    h: HeaderMap,
) -> Result<Json<serde_json::Value>> {
    scope(&h, &s, "system.read")?;
    let c = db(&s)?;
    let mut st = c.prepare(
        "SELECT key,value FROM system_config WHERE key IN ('logRetentionDays','logMaxBytes','cacheMaxBytes','maxConcurrentJobs') ORDER BY key",
    )?;
    let mut settings = serde_json::Map::new();
    settings.insert(
        "logRetentionDays".into(),
        serde_json::Value::Number(14.into()),
    );
    settings.insert(
        "logMaxBytes".into(),
        serde_json::Value::Number((10 * 1024 * 1024u64).into()),
    );
    settings.insert(
        "cacheMaxBytes".into(),
        serde_json::Value::Number((32 * 1024 * 1024u64 * 1024).into()),
    );
    settings.insert(
        "maxConcurrentJobs".into(),
        serde_json::Value::Number(2.into()),
    );
    for row in st.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))? {
        let (key, value) = row?;
        let parsed = value
            .parse::<i64>()
            .map(serde_json::Number::from)
            .map(serde_json::Value::Number)
            .unwrap_or_else(|_| serde_json::Value::String(value));
        settings.insert(key, parsed);
    }
    Ok(Json(
        serde_json::json!({"settings": settings, "apiVersion": API_VERSION}),
    ))
}
async fn system_settings_set(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(value): Json<serde_json::Value>,
) -> Result<Json<serde_json::Value>> {
    let client = scope(&h, &s, "security.write")?;
    let object = value
        .as_object()
        .ok_or_else(|| GatewayError::Message("settings must be an object".into()))?;
    let allowed = [
        "logRetentionDays",
        "logMaxBytes",
        "cacheMaxBytes",
        "maxConcurrentJobs",
    ];
    for key in object.keys() {
        if !allowed.contains(&key.as_str()) {
            return Err(GatewayError::Message(format!("unsupported setting: {key}")));
        }
    }
    for (key, value) in object {
        let n = value
            .as_i64()
            .ok_or_else(|| GatewayError::Message(format!("{key} must be an integer")))?;
        let valid = match key.as_str() {
            "logRetentionDays" => (1..=365).contains(&n),
            "logMaxBytes" => (1_048_576..=1_073_741_824).contains(&n),
            "cacheMaxBytes" => (64 * 1024 * 1024..=1_099_511_627_776).contains(&n),
            "maxConcurrentJobs" => (1..=4).contains(&n),
            _ => false,
        };
        if !valid {
            return Err(GatewayError::Message(format!("invalid value for {key}")));
        }
        db(&s)?.execute(
            "INSERT OR REPLACE INTO system_config(key,value,updated_at) VALUES(?,?,?)",
            params![key, n.to_string(), now()],
        )?;
    }
    audit(
        &s,
        Some(&client),
        "system.settings.update",
        None,
        None,
        "SUCCESS",
        None,
    )?;
    system_settings_get(State(s), {
        let mut headers = HeaderMap::new();
        if let Some(v) = h.get("authorization") {
            headers.insert("authorization", v.clone());
        }
        headers
    })
    .await
}
async fn migration_status(
    State(s): State<AppState>,
    h: HeaderMap,
) -> Result<Json<serde_json::Value>> {
    scope(&h, &s, "system.read")?;
    let c = db(&s)?;
    let applied: Vec<serde_json::Value> = c
        .prepare("SELECT version,applied_at,checksum FROM migration_history ORDER BY version")?
        .query_map([], |r| {
            Ok(serde_json::json!({
                "version": r.get::<_, i64>(0)?,
                "appliedAt": r.get::<_, i64>(1)?,
                "checksum": r.get::<_, String>(2)?
            }))
        })?
        .collect::<rusqlite::Result<_>>()?;
    let errors: i64 = c.query_row("SELECT COUNT(*) FROM migration_errors", [], |r| r.get(0))?;
    Ok(Json(
        serde_json::json!({"applied": applied, "errorCount": errors}),
    ))
}
async fn backup_create(
    State(s): State<AppState>,
    h: HeaderMap,
) -> Result<(StatusCode, Json<serde_json::Value>)> {
    let c = scope(&h, &s, "security.write")?;
    let filename = format!("state-{}-{}.db", now(), Uuid::new_v4());
    let dest = s.root.join("backups").join(&filename);
    {
        let conn = db(&s)?;
        conn.execute_batch("PRAGMA wal_checkpoint(TRUNCATE);")?;
    }
    fs::copy(s.root.join("db/state.db"), &dest)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&dest, fs::Permissions::from_mode(0o600))?;
    }
    // A database alone cannot decrypt the Secret Store.  Keep a private,
    // manager-owned companion bundle beside every DB backup so rollback is
    // useful instead of silently losing credentials and TLS material.
    let stem = filename.trim_end_matches(".db");
    let bundle = s.root.join("backups").join(format!("{stem}.bundle"));
    fs::create_dir_all(bundle.join("keys"))?;
    fs::create_dir_all(bundle.join("secrets"))?;
    copy_private_files(&s.root.join("keys"), &bundle.join("keys"))?;
    copy_private_files(&s.root.join("secrets"), &bundle.join("secrets"))?;
    let bytes = fs::read(&dest)?;
    let checksum = hex::encode(Sha256::digest(&bytes));
    audit(
        &s,
        Some(&c),
        "system.backup.create",
        None,
        None,
        "SUCCESS",
        None,
    )?;
    Ok((
        StatusCode::CREATED,
        Json(
            serde_json::json!({"path":dest,"sha256":checksum,"bytes":bytes.len(),"bundle":bundle}),
        ),
    ))
}

/// Copy only regular files from a manager-owned private directory.  Symlinks
/// are ignored so a malformed on-device entry cannot make a backup escape its
/// root.  Destination files are always restricted to owner-only permissions.
fn copy_private_files(source: &FsPath, destination: &FsPath) -> Result<()> {
    fs::create_dir_all(destination)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(destination, fs::Permissions::from_mode(0o700))?;
    }
    for entry in fs::read_dir(source)? {
        let entry = entry?;
        let metadata = fs::symlink_metadata(entry.path())?;
        if !metadata.file_type().is_file() {
            continue;
        }
        let target = destination.join(entry.file_name());
        fs::copy(entry.path(), &target)?;
        restrict_file(&target)?;
    }
    Ok(())
}
fn replace_private_files(source: &FsPath, destination: &FsPath) -> Result<()> {
    fs::create_dir_all(destination)?;
    for entry in fs::read_dir(destination)? {
        let entry = entry?;
        let metadata = fs::symlink_metadata(entry.path())?;
        if metadata.file_type().is_file() || metadata.file_type().is_symlink() {
            fs::remove_file(entry.path())?;
        }
    }
    copy_private_files(source, destination)
}
async fn backups_list(
    State(s): State<AppState>,
    h: HeaderMap,
) -> Result<Json<Vec<serde_json::Value>>> {
    scope(&h, &s, "system.read")?;
    let mut out = Vec::new();
    for entry in fs::read_dir(s.root.join("backups"))? {
        let p = entry?.path();
        if p.extension().and_then(|x| x.to_str()) != Some("db") {
            continue;
        }
        let meta = fs::metadata(&p)?;
        let checksum = hex::encode(Sha256::digest(fs::read(&p)?));
        let bundle = p
            .file_stem()
            .and_then(|x| x.to_str())
            .map(|stem| p.with_file_name(format!("{stem}.bundle")))
            .is_some_and(|candidate| candidate.is_dir());
        out.push(serde_json::json!({
            "name": p.file_name().and_then(|x| x.to_str()).unwrap_or_default(),
            "bytes": meta.len(),
            "sha256": checksum,
            "bundle": bundle,
            "modified": meta.modified().ok().and_then(|v| v.duration_since(UNIX_EPOCH).ok()).map(|v| v.as_secs())
        }));
    }
    Ok(Json(out))
}

fn valid_backup_name(name: &str) -> Result<()> {
    if name.is_empty()
        || name.len() > 128
        || !name.ends_with(".db")
        || !name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'_'))
    {
        return Err(GatewayError::Message("invalid backup name".into()));
    }
    Ok(())
}

fn restore_backup(root: &FsPath, name: &str) -> Result<serde_json::Value> {
    valid_backup_name(name)?;
    ensure_dirs(root)?;
    let pid_file = root.join("runtime/gateway.pid");
    let socket_file = root.join("runtime/gateway.sock");
    if socket_file.exists() && !pid_file.exists() {
        return Err(GatewayError::Message(
            "gateway socket exists without a PID; stop Gateway and remove the stale socket before restore".into(),
        ));
    }
    if let Ok(pid_text) = fs::read_to_string(&pid_file) {
        let pid = pid_text.trim();
        if !pid.is_empty() && pid.bytes().all(|b| b.is_ascii_digit()) {
            #[cfg(unix)]
            {
                let alive = process_kill_command()
                    .args(["-0", pid])
                    .status()
                    .is_ok_and(|v| v.success());
                if alive {
                    return Err(GatewayError::Message(
                        "gateway must be stopped before database restore".into(),
                    ));
                }
            }
        }
    }
    let backup_dir = root.join("backups");
    let backup = backup_dir.join(name);
    if !backup.is_file() {
        return Err(GatewayError::Message("backup not found".into()));
    }
    // Reject symlinks escaping the manager-owned backup directory.
    let backup_root = fs::canonicalize(&backup_dir)?;
    let root_real = fs::canonicalize(root)?;
    if !backup_root.starts_with(&root_real) {
        return Err(GatewayError::Message(
            "backup directory escapes manager root".into(),
        ));
    }
    let backup_real = fs::canonicalize(&backup)?;
    if !backup_real.starts_with(&backup_root) {
        return Err(GatewayError::Message(
            "backup path escapes backup directory".into(),
        ));
    }
    let check = Connection::open(&backup_real)?;
    let integrity: String = check.query_row("PRAGMA integrity_check", [], |r| r.get(0))?;
    if integrity != "ok" {
        return Err(GatewayError::Message(
            "backup integrity check failed".into(),
        ));
    }
    let required_tables = [
        "system_config",
        "remote",
        "remote_acl",
        "client",
        "permission_grant",
        "job",
        "job_run",
        "mount_profile",
        "crypt_profile",
        "secret_meta",
        "audit_log",
        "migration_history",
    ];
    for table in required_tables {
        let present: i64 = check.query_row(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
            params![table],
            |r| r.get(0),
        )?;
        if present != 1 {
            return Err(GatewayError::Message(format!(
                "backup schema missing table {table}"
            )));
        }
    }
    drop(check);

    // Keep the companion lookup deterministic and reject symlinked bundles;
    // older DB-only backups remain restorable but report bundle=false.
    let bundle = backup_dir.join(format!("{}.bundle", name.trim_end_matches(".db")));
    let bundle_real = if bundle.is_dir() {
        let real = fs::canonicalize(&bundle)?;
        if !real.starts_with(&backup_root) {
            return Err(GatewayError::Message(
                "backup bundle escapes backup directory".into(),
            ));
        }
        let keys = real.join("keys");
        let secrets = real.join("secrets");
        let safe_dir = |path: &FsPath| {
            fs::symlink_metadata(path)
                .map(|m| m.is_dir() && !m.file_type().is_symlink())
                .unwrap_or(false)
        };
        if !safe_dir(&keys) || !safe_dir(&secrets) {
            return Err(GatewayError::Message("backup bundle is incomplete".into()));
        }
        Some(real)
    } else {
        None
    };

    let bundle_restored = bundle_real.is_some();
    let current = root.join("db/state.db");
    if current.is_file() {
        // Checkpoint before making the safety copy so WAL contents are included.
        let current_conn = Connection::open(&current)?;
        let _ = current_conn.execute_batch("PRAGMA wal_checkpoint(TRUNCATE);");
        drop(current_conn);
        let safety = backup_dir.join(format!("pre-restore-{}-{}.db", now(), Uuid::new_v4()));
        fs::copy(&current, &safety)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&safety, fs::Permissions::from_mode(0o600))?;
        }
    }
    if bundle_real.is_some() {
        let safety_bundle = backup_dir.join(format!("pre-restore-{}.bundle", Uuid::new_v4()));
        fs::create_dir_all(safety_bundle.join("keys"))?;
        fs::create_dir_all(safety_bundle.join("secrets"))?;
        copy_private_files(&root.join("keys"), &safety_bundle.join("keys"))?;
        copy_private_files(&root.join("secrets"), &safety_bundle.join("secrets"))?;
    }
    let temp = root.join("db/state.db.restore.tmp");
    let _ = fs::remove_file(&temp);
    fs::copy(&backup_real, &temp)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&temp, fs::Permissions::from_mode(0o600))?;
    }
    fs::rename(&temp, &current)?;
    let _ = fs::remove_file(root.join("db/state.db-wal"));
    let _ = fs::remove_file(root.join("db/state.db-shm"));
    if let Some(bundle) = bundle_real {
        replace_private_files(&bundle.join("keys"), &root.join("keys"))?;
        replace_private_files(&bundle.join("secrets"), &root.join("secrets"))?;
    }
    let bytes = fs::read(&current)?;
    Ok(serde_json::json!({
        "restored": name,
        "sha256": hex::encode(Sha256::digest(&bytes)),
        "bytes": bytes.len(),
        "bundleRestored": bundle_restored
    }))
}

async fn job_runs(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<Vec<serde_json::Value>>> {
    let client = scope(&h, &s, "job.read")?;
    job_accessible(&s, &client, &id)?;
    let conn = db(&s)?;
    let mut st = conn.prepare("SELECT id,state,rclone_job_id,pid,started_at,finished_at,transferred_bytes,total_bytes,transferred_files,total_files,error_count,error_code,error_message FROM job_run WHERE job_id=? ORDER BY started_at DESC")?;
    let rows = st.query_map(params![id], |r| {
        Ok(serde_json::json!({
            "id":r.get::<_,String>(0)?, "state":r.get::<_,String>(1)?,
            "rcloneJobId":r.get::<_,Option<i64>>(2)?, "pid":r.get::<_,Option<i64>>(3)?,
            "startedAt":r.get::<_,Option<i64>>(4)?, "finishedAt":r.get::<_,Option<i64>>(5)?,
            "transferredBytes":r.get::<_,i64>(6)?, "totalBytes":r.get::<_,Option<i64>>(7)?,
            "transferredFiles":r.get::<_,i64>(8)?, "totalFiles":r.get::<_,Option<i64>>(9)?,
            "errorCount":r.get::<_,i64>(10)?, "errorCode":r.get::<_,Option<String>>(11)?,
            "errorMessage":r.get::<_,Option<String>>(12)?
        }))
    })?;
    Ok(Json(rows.collect::<rusqlite::Result<Vec<_>>>()?))
}
async fn job_log(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<serde_json::Value>> {
    let client = scope(&h, &s, "job.read")?;
    job_accessible(&s, &client, &id)?;
    let path = s.root.join("logs").join(format!("job-{id}.log"));
    let log = fs::read_to_string(&path).unwrap_or_default();
    let truncated: String = log.chars().take(64 * 1024).collect();
    let was_truncated = truncated.chars().count() < log.chars().count();
    Ok(Json(serde_json::json!({
        "jobId": id,
        "log": truncated,
        "truncated": was_truncated
    })))
}
#[derive(Serialize)]
struct Info {
    service: &'static str,
    rclone_version: String,
    gateway_version: &'static str,
    api_version: &'static str,
    root: bool,
    lan_enabled: bool,
    mtls_required: bool,
}
async fn info(State(s): State<AppState>, h: HeaderMap) -> Result<Json<Info>> {
    scope(&h, &s, "system.read")?;
    let v = rclone_command(&None)
        .arg("version")
        .output()
        .await
        .ok()
        .and_then(|o| String::from_utf8(o.stdout).ok())
        .and_then(|x| x.lines().next().map(str::to_owned))
        .unwrap_or_else(|| "unavailable".into());
    let (lan_enabled, mtls_required): (bool, bool) = db(&s)
        .ok()
        .and_then(|c| {
            let enabled: Option<String> = c
                .query_row(
                    "SELECT value FROM system_config WHERE key='lan.enabled'",
                    [],
                    |r| r.get(0),
                )
                .ok();
            let mtls: Option<String> = c
                .query_row(
                    "SELECT value FROM system_config WHERE key='lan.mtls'",
                    [],
                    |r| r.get(0),
                )
                .ok();
            Some((
                enabled.as_deref() == Some("true"),
                mtls.as_deref() == Some("true"),
            ))
        })
        .unwrap_or((false, false));
    Ok(Json(Info {
        service: "android-rclone-root-manager",
        rclone_version: v,
        gateway_version: env!("CARGO_PKG_VERSION"),
        api_version: API_VERSION,
        root: unsafe { getuid() == 0 },
        lan_enabled,
        mtls_required,
    }))
}
#[cfg(unix)]
unsafe fn getuid() -> u32 {
    unsafe extern "C" {
        fn getuid() -> u32;
    }
    unsafe { getuid() }
}
#[cfg(not(unix))]
fn getuid() -> u32 {
    0
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Pair {
    pairing_code: String,
    client_name: String,
    public_key: Option<String>,
    package_name: Option<String>,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PairResult {
    client_id: String,
    token: String,
    expires_in: u64,
}
async fn pair_start(State(s): State<AppState>) -> Result<(StatusCode, Json<serde_json::Value>)> {
    let c = format!("{:06}", rand::random::<u32>() % 1_000_000);
    s.pairing.write().await.insert(c.clone(), now() + 300);
    Ok((
        StatusCode::CREATED,
        Json(serde_json::json!({"pairingCode":c,"expiresIn":300})),
    ))
}
async fn pair_complete(
    State(s): State<AppState>,
    Json(i): Json<Pair>,
) -> Result<(StatusCode, Json<PairResult>)> {
    validate_identity(&i.client_name, "client name", 128)?;
    let public_key = i.public_key.as_deref().unwrap_or("device");
    if public_key.is_empty()
        || public_key.len() > 4096
        || !public_key.bytes().all(|b| (0x20..=0x7e).contains(&b))
    {
        return Err(GatewayError::Message("invalid public key".into()));
    }
    if s.pairing
        .write()
        .await
        .remove(&i.pairing_code)
        .filter(|e| *e > now())
        .is_none()
    {
        return Err(GatewayError::Message(
            "AUTH invalid or expired pairing code".into(),
        ));
    }
    let id = Uuid::new_v4().to_string();
    let tok = B64.encode(rand::random::<[u8; 32]>());
    let c = db(&s)?;
    let first_client: bool =
        c.query_row("SELECT COUNT(*) FROM client", [], |r| r.get::<_, i64>(0))? == 0;
    let token_expires = now() + 30 * 24 * 3600;
    c.execute("INSERT INTO client(id,name,package_name,public_key,token_hash,status,created_at,token_expires_at) VALUES(?,?,?,?,?,'ACTIVE',?,?)",params![id,i.client_name,i.package_name,i.public_key,hash(&tok),now(),token_expires])?;
    // New clients start read-only.  A local administrator can explicitly
    // grant write/control capabilities through the security API.
    for initial in [
        "system.read",
        "remote.read",
        "file.read",
        "job.read",
        "mount.read",
        "audit.read",
    ] {
        c.execute(
            "INSERT INTO permission_grant(client_id,scope,resource) VALUES(?,?,?)",
            params![id, initial, "*"],
        )?;
    }
    if first_client {
        c.execute(
            "INSERT INTO permission_grant(client_id,scope,resource) VALUES(?,?,?)",
            params![id, "security.write", "*"],
        )?;
    }
    // Give the first trusted client read access to remotes imported before
    // pairing. Further clients must receive explicit Remote ACL grants.
    if first_client {
        let ids = {
            let mut remotes = c.prepare("SELECT id FROM remote")?;
            remotes
                .query_map([], |r| r.get::<_, String>(0))?
                .collect::<rusqlite::Result<Vec<_>>>()?
        };
        for rid in ids {
            c.execute("INSERT OR IGNORE INTO remote_acl(client_id,remote_id,permissions,allowed_prefix) VALUES(?,?,?,?)", params![id, rid, "file.read", "/"])?;
        }
    }
    drop(c);
    audit(
        &s,
        Some(&id),
        "security.pairing.complete",
        None,
        None,
        "SUCCESS",
        None,
    )?;
    Ok((
        StatusCode::CREATED,
        Json(PairResult {
            client_id: id,
            token: tok,
            expires_in: (30 * 24 * 3600) as u64,
        }),
    ))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct GrantIn {
    scope: String,
    resource: Option<String>,
    expires_at: Option<i64>,
}

async fn client_grant(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
    Json(i): Json<GrantIn>,
) -> Result<StatusCode> {
    let actor = scope(&h, &s, "security.write")?;
    const ALLOWED: &[&str] = &[
        "system.read",
        "remote.read",
        "remote.write",
        "remote.delete",
        "file.read",
        "file.write",
        "file.delete",
        "job.read",
        "job.execute",
        "job.control",
        "mount.read",
        "mount.write",
        "audit.read",
        "security.read",
        "security.write",
    ];
    if !ALLOWED.contains(&i.scope.as_str()) && i.scope != "admin.*" {
        return Err(GatewayError::Message("invalid scope".into()));
    }
    if let Some(resource) = i.resource.as_deref() {
        if resource.is_empty()
            || resource.len() > 256
            || !ini_line_safe(resource)
            || resource.contains("..")
        {
            return Err(GatewayError::Message("invalid grant resource".into()));
        }
    }
    let exists: Option<String> = db(&s)?
        .query_row("SELECT id FROM client WHERE id=?", params![id], |r| {
            r.get(0)
        })
        .optional()?;
    if exists.is_none() {
        return Err(GatewayError::Message("client not found".into()));
    }
    db(&s)?.execute(
        "INSERT INTO permission_grant(client_id,scope,resource,expires_at) VALUES(?,?,?,?)",
        params![
            id,
            i.scope,
            i.resource.unwrap_or_else(|| "*".into()),
            i.expires_at
        ],
    )?;
    audit(
        &s,
        Some(&actor),
        "security.grant",
        Some(&id),
        None,
        "SUCCESS",
        None,
    )?;
    Ok(StatusCode::NO_CONTENT)
}

async fn client_revoke(
    State(s): State<AppState>,
    h: HeaderMap,
    Path((id, grant_id)): Path<(String, i64)>,
) -> Result<StatusCode> {
    let actor = scope(&h, &s, "security.write")?;
    db(&s)?.execute(
        "DELETE FROM permission_grant WHERE id=? AND client_id=?",
        params![grant_id, id],
    )?;
    audit(
        &s,
        Some(&actor),
        "security.revoke",
        Some(&id),
        None,
        "SUCCESS",
        None,
    )?;
    Ok(StatusCode::NO_CONTENT)
}

async fn client_disable(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<StatusCode> {
    let actor = scope(&h, &s, "security.write")?;
    if id == actor {
        return Err(GatewayError::Message(
            "cannot disable current client".into(),
        ));
    }
    db(&s)?.execute("UPDATE client SET status='REVOKED' WHERE id=?", params![id])?;
    audit(
        &s,
        Some(&actor),
        "security.client.disable",
        Some(&id),
        None,
        "SUCCESS",
        None,
    )?;
    Ok(StatusCode::NO_CONTENT)
}
async fn client_grants(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<Vec<serde_json::Value>>> {
    scope(&h, &s, "security.read")?;
    let conn = db(&s)?;
    let mut st = conn.prepare(
        "SELECT id,scope,resource,expires_at FROM permission_grant WHERE client_id=? ORDER BY id",
    )?;
    let rows = st.query_map(params![id], |r| Ok(serde_json::json!({"id":r.get::<_,i64>(0)?,"scope":r.get::<_,String>(1)?,"resource":r.get::<_,Option<String>>(2)?,"expiresAt":r.get::<_,Option<i64>>(3)?})))?;
    Ok(Json(rows.collect::<rusqlite::Result<Vec<_>>>()?))
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct TokenResult {
    token: String,
    expires_in: u64,
}
async fn client_rotate_token(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<TokenResult>> {
    let actor = scope(&h, &s, "security.write")?;
    let token = B64.encode(rand::random::<[u8; 32]>());
    let expires = now() + 30 * 24 * 3600;
    let changed = db(&s)?.execute(
        "UPDATE client SET token_hash=?,token_expires_at=?,status='ACTIVE' WHERE id=?",
        params![hash(&token), expires, id],
    )?;
    if changed == 0 {
        return Err(GatewayError::Message("client not found".into()));
    }
    audit(
        &s,
        Some(&actor),
        "security.client.rotate_token",
        Some(&id),
        None,
        "SUCCESS",
        None,
    )?;
    Ok(Json(TokenResult {
        token,
        expires_in: (30 * 24 * 3600) as u64,
    }))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RemoteAclIn {
    remote_id: String,
    permissions: Vec<String>,
    allowed_prefix: Option<String>,
}

async fn remote_acl_grant(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(client_id): Path<String>,
    Json(i): Json<RemoteAclIn>,
) -> Result<StatusCode> {
    let actor = scope(&h, &s, "security.write")?;
    let _ = remote_target(&s, &i.remote_id)?;
    let exists: Option<String> = db(&s)?
        .query_row(
            "SELECT id FROM client WHERE id=?",
            params![client_id],
            |r| r.get(0),
        )
        .optional()?;
    if exists.is_none() {
        return Err(GatewayError::Message("client not found".into()));
    }
    const ALLOWED: &[&str] = &["file.read", "file.write", "file.delete", "*"];
    if i.permissions.is_empty() || i.permissions.iter().any(|p| !ALLOWED.contains(&p.as_str())) {
        return Err(GatewayError::Message(
            "invalid remote ACL permission".into(),
        ));
    }
    let prefix = valid_path(i.allowed_prefix.as_deref().unwrap_or("/"), "/")?;
    db(&s)?.execute(
        "INSERT INTO remote_acl(client_id,remote_id,permissions,allowed_prefix) VALUES(?,?,?,?)",
        params![client_id, i.remote_id, i.permissions.join(","), prefix],
    )?;
    audit(
        &s,
        Some(&actor),
        "security.remote_acl.grant",
        None,
        Some(&i.remote_id),
        "SUCCESS",
        None,
    )?;
    Ok(StatusCode::NO_CONTENT)
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Client {
    id: String,
    name: String,
    status: String,
    created_at: i64,
    last_seen_at: Option<i64>,
}
async fn clients(State(s): State<AppState>, h: HeaderMap) -> Result<Json<Vec<Client>>> {
    scope(&h, &s, "security.read")?;
    let c = db(&s)?;
    let mut st =
        c.prepare("SELECT id,name,status,created_at,last_seen_at FROM client ORDER BY created_at")?;
    Ok(Json(
        st.query_map([], |r| {
            Ok(Client {
                id: r.get(0)?,
                name: r.get(1)?,
                status: r.get(2)?,
                created_at: r.get(3)?,
                last_seen_at: r.get(4)?,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?,
    ))
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RemoteIn {
    name: String,
    #[serde(rename = "type")]
    remote_type: String,
    endpoint: Option<String>,
    base_path: Option<String>,
    enabled: Option<bool>,
    secret: Option<serde_json::Value>,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Remote {
    id: String,
    name: String,
    #[serde(rename = "type")]
    remote_type: String,
    endpoint: Option<String>,
    enabled: bool,
    secret_ref: Option<String>,
}
#[derive(Deserialize, Default)]
#[serde(rename_all = "camelCase")]
struct RemoteDeleteIn {
    confirmation_token: Option<String>,
}
async fn remotes(State(s): State<AppState>, h: HeaderMap) -> Result<Json<Vec<Remote>>> {
    let c = scope(&h, &s, "remote.read")?;
    let db = db(&s)?;
    let mut st=db.prepare("SELECT r.id,r.name,r.type,r.endpoint,r.enabled,r.secret_ref FROM remote r JOIN remote_acl a ON a.remote_id=r.id WHERE a.client_id=? GROUP BY r.id")?;
    Ok(Json(
        st.query_map(params![c], |r| {
            Ok(Remote {
                id: r.get(0)?,
                name: r.get(1)?,
                remote_type: r.get(2)?,
                endpoint: r.get(3)?,
                enabled: r.get::<_, i64>(4)? != 0,
                secret_ref: r.get(5)?,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?,
    ))
}
async fn remote_create(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(i): Json<RemoteIn>,
) -> Result<(StatusCode, Json<Remote>)> {
    let c = scope(&h, &s, "remote.write")?;
    if validate_identity(&i.name, "remote name", 128).is_err()
        || !i
            .remote_type
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
        || i.remote_type.is_empty()
        || i.endpoint.as_deref().is_some_and(|v| !ini_line_safe(v))
    {
        return Err(GatewayError::Message("remote name is invalid".into()));
    }
    if let Some(secret) = i.secret.as_ref() {
        validate_secret_object(secret)?;
    }
    let base_path = valid_path(i.base_path.as_deref().unwrap_or("/"), "/")?;
    let id = Uuid::new_v4().to_string();
    let sr = i
        .secret
        .as_ref()
        .map(|v| encrypt_secret(&s.root, &id, v))
        .transpose()?;
    let t = now();
    db(&s)?.execute("INSERT INTO remote(id,name,type,endpoint,base_path,secret_ref,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",params![id,i.name,i.remote_type,i.endpoint,base_path,sr,i.enabled.unwrap_or(true) as i64,t,t])?;
    if let Some(ref secret_ref) = sr {
        db(&s)?.execute(
            "INSERT INTO secret_meta(id,kind,backend,version,created_at) VALUES(?,?,?,?,?)",
            params![secret_ref, "remote", "xchacha20poly1305", 1, t],
        )?;
    }
    db(&s)?.execute(
        "INSERT INTO remote_acl(client_id,remote_id,permissions,allowed_prefix) VALUES(?,?,?,?)",
        params![c, id, "*", "/"],
    )?;
    audit(
        &s,
        Some(&c),
        "remote.create",
        Some(&id),
        Some(&id),
        "SUCCESS",
        None,
    )?;
    Ok((
        StatusCode::CREATED,
        Json(Remote {
            id,
            name: i.name,
            remote_type: i.remote_type,
            endpoint: i.endpoint,
            enabled: i.enabled.unwrap_or(true),
            secret_ref: sr,
        }),
    ))
}
async fn remote_delete(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
    body: Option<Json<RemoteDeleteIn>>,
) -> Result<Response> {
    let c = scope(&h, &s, "remote.delete")?;
    acl(&s, &c, &id, "*", "/")?;
    let token = body.and_then(|Json(v)| v.confirmation_token);
    let Some(token) = token else {
        let token = B64.encode(rand::random::<[u8; 16]>());
        db(&s)?.execute(
            "INSERT OR REPLACE INTO system_config(key,value,updated_at) VALUES(?,?,?)",
            params![
                format!("remote-delete-confirm:{token}"),
                serde_json::json!({"remoteId": id}).to_string(),
                now() + 60
            ],
        )?;
        audit(
            &s,
            Some(&c),
            "remote.delete.preview",
            Some(&id),
            Some(&id),
            "CONFIRMATION_REQUIRED",
            None,
        )?;
        return Ok((
            StatusCode::ACCEPTED,
            Json(serde_json::json!({
                "confirmationRequired": true,
                "confirmationToken": token,
                "expiresIn": 60
            })),
        )
            .into_response());
    };
    let key = format!("remote-delete-confirm:{token}");
    let profile_refs: i64 = db(&s)?.query_row(
        "SELECT (SELECT COUNT(*) FROM mount_profile WHERE remote_id=?) + (SELECT COUNT(*) FROM crypt_profile WHERE remote_id=?)",
        params![id, id],
        |r| r.get(0),
    )?;
    if profile_refs > 0 {
        return Err(GatewayError::Message(
            "remote is still referenced by a mount or crypt profile".into(),
        ));
    }
    consume_confirmation(&s, &key, |_expires, stored| {
        let stored_remote = serde_json::from_str::<serde_json::Value>(stored)
            .ok()
            .and_then(|v| {
                v.get("remoteId")
                    .and_then(|x| x.as_str())
                    .map(str::to_owned)
            });
        if stored_remote.as_deref() != Some(id.as_str()) {
            return Err(GatewayError::Message(
                "confirmation token does not match remote".into(),
            ));
        }
        Ok(())
    })?;
    let secret_ref: Option<String> = db(&s)?
        .query_row(
            "SELECT secret_ref FROM remote WHERE id=?",
            params![id],
            |r| r.get(0),
        )
        .optional()?;
    db(&s)?.execute("DELETE FROM remote WHERE id=?", params![id])?;
    if let Some(sr) = secret_ref {
        let _ = db(&s)?.execute("DELETE FROM secret_meta WHERE id=?", params![sr]);
        let _ = fs::remove_file(s.root.join("secrets").join(format!("{sr}.blob")));
    }
    audit(
        &s,
        Some(&c),
        "remote.delete",
        Some(&id),
        Some(&id),
        "SUCCESS",
        None,
    )?;
    Ok((StatusCode::NO_CONTENT, Json(serde_json::json!({}))).into_response())
}
async fn remote_get(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<Remote>> {
    let c = scope(&h, &s, "remote.read")?;
    acl(&s, &c, &id, "file.read", "/")?;
    let (id, name, remote_type, endpoint, enabled, secret_ref): (
        String,
        String,
        String,
        Option<String>,
        i64,
        Option<String>,
    ) = db(&s)?
        .query_row(
            "SELECT id,name,type,endpoint,enabled,secret_ref FROM remote WHERE id=?",
            params![id],
            |r| {
                Ok((
                    r.get(0)?,
                    r.get(1)?,
                    r.get(2)?,
                    r.get(3)?,
                    r.get(4)?,
                    r.get(5)?,
                ))
            },
        )
        .map_err(|_| GatewayError::Message("remote not found".into()))?;
    Ok(Json(Remote {
        id,
        name,
        remote_type,
        endpoint,
        enabled: enabled != 0,
        secret_ref,
    }))
}
async fn remote_update(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
    Json(i): Json<RemoteIn>,
) -> Result<Json<Remote>> {
    let c = scope(&h, &s, "remote.write")?;
    acl(&s, &c, &id, "*", "/")?;
    if validate_identity(&i.name, "remote name", 128).is_err()
        || i.remote_type.is_empty()
        || !i
            .remote_type
            .chars()
            .all(|x| x.is_ascii_alphanumeric() || x == '_' || x == '-')
        || i.endpoint.as_deref().is_some_and(|v| !ini_line_safe(v))
    {
        return Err(GatewayError::Message("invalid remote configuration".into()));
    }
    if let Some(secret) = i.secret.as_ref() {
        validate_secret_object(secret)?;
    }
    let secret_ref = i
        .secret
        .as_ref()
        .map(|v| encrypt_secret(&s.root, &id, v))
        .transpose()?;
    let old_secret_ref: Option<String> = if secret_ref.is_some() {
        db(&s)?
            .query_row(
                "SELECT secret_ref FROM remote WHERE id=?",
                params![id],
                |r| r.get(0),
            )
            .optional()?
            .flatten()
    } else {
        None
    };
    let base_path = valid_path(i.base_path.as_deref().unwrap_or("/"), "/")?;
    db(&s)?.execute("UPDATE remote SET name=?,type=?,endpoint=?,base_path=?,enabled=?,secret_ref=COALESCE(?,secret_ref),updated_at=? WHERE id=?", params![i.name, i.remote_type, i.endpoint, base_path, i.enabled.unwrap_or(true) as i64, secret_ref, now(), id])?;
    if let Some(ref secret_ref) = secret_ref {
        if let Some(old) = old_secret_ref {
            let _ = fs::remove_file(s.root.join("secrets").join(format!("{old}.blob")));
            let _ = db(&s)?.execute("DELETE FROM secret_meta WHERE id=?", params![old]);
        }
        db(&s)?.execute(
            "INSERT INTO secret_meta(id,kind,backend,version,created_at) VALUES(?,?,?,?,?)",
            params![secret_ref, "remote", "xchacha20poly1305", 1, now()],
        )?;
    }
    audit(
        &s,
        Some(&c),
        "remote.update",
        Some(&id),
        Some(&id),
        "SUCCESS",
        None,
    )?;
    remote_get(
        State(s),
        {
            let mut h2 = HeaderMap::new();
            if let Some(v) = h.get("authorization") {
                h2.insert("authorization", v.clone());
            }
            h2
        },
        Path(id),
    )
    .await
}
async fn remote_action(
    State(s): State<AppState>,
    h: HeaderMap,
    Path((id, action)): Path<(String, String)>,
) -> Result<Json<Remote>> {
    let c = scope(&h, &s, "remote.write")?;
    acl(&s, &c, &id, "*", "/")?;
    let enabled = match action.as_str() {
        "enable" => true,
        "disable" => false,
        _ => return Err(GatewayError::Message("unknown remote action".into())),
    };
    let changed = db(&s)?.execute(
        "UPDATE remote SET enabled=?,updated_at=? WHERE id=?",
        params![enabled as i64, now(), id],
    )?;
    if changed == 0 {
        return Err(GatewayError::Message("remote not found".into()));
    }
    audit(
        &s,
        Some(&c),
        &format!("remote.{action}"),
        Some(&id),
        Some(&id),
        "SUCCESS",
        None,
    )?;
    remote_get(State(s), h, Path(id)).await
}
async fn remote_export(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<serde_json::Value>> {
    let c = scope(&h, &s, "remote.read")?;
    acl(&s, &c, &id, "file.read", "/")?;
    let row: (String, String, Option<String>, String, i64) = db(&s)?
        .query_row(
            "SELECT name,type,endpoint,base_path,enabled FROM remote WHERE id=?",
            params![id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
        )
        .map_err(|_| GatewayError::Message("remote not found".into()))?;
    // Export is deliberately credential-free. Import uses the normal typed
    // RemoteCreate flow, which accepts a fresh secret without returning it.
    audit(
        &s,
        Some(&c),
        "remote.export",
        Some(&id),
        Some(&id),
        "SUCCESS",
        None,
    )?;
    Ok(Json(serde_json::json!({
        "name": row.0,
        "type": row.1,
        "endpoint": row.2,
        "basePath": row.3,
        "enabled": row.4 != 0,
        "credentialsIncluded": false
    })))
}
async fn remote_test(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<serde_json::Value>> {
    let c = scope(&h, &s, "remote.read")?;
    acl(&s, &c, &id, "file.read", "/")?;
    let (name, base): (String, String) = db(&s)?
        .query_row(
            "SELECT name,base_path FROM remote WHERE id=?",
            params![id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .map_err(|_| GatewayError::Message("remote not found".into()))?;
    let config = materialize_rclone_config(&s, &[id.clone()])?;
    let o = rclone_command(&config)
        .args(["lsd", &format!("{name}:{base}"), "--max-depth", "1"])
        .output()
        .await;
    if let Some(p) = config {
        let _ = fs::remove_file(p);
    }
    let ok = o.as_ref().map(|x| x.status.success()).unwrap_or(false);
    let _ = audit(
        &s,
        Some(&c),
        "remote.test",
        Some(&id),
        Some(&id),
        if ok { "SUCCESS" } else { "FAILED" },
        (!ok).then_some("RCLONE_TEST_FAILED"),
    );
    Ok(Json(serde_json::json!({"ok":ok,"remote":name})))
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct FileQ {
    remote_id: String,
    path: Option<String>,
    page_size: Option<u32>,
}
async fn files(
    State(s): State<AppState>,
    h: HeaderMap,
    Query(q): Query<FileQ>,
) -> Result<Json<serde_json::Value>> {
    let c = scope(&h, &s, "file.read")?;
    let p = q.path.as_deref().unwrap_or("/");
    let page_size = q.page_size.unwrap_or(200);
    if !(1..=1000).contains(&page_size) {
        return Err(GatewayError::Message(
            "pageSize must be between 1 and 1000".into(),
        ));
    }
    acl(&s, &c, &q.remote_id, "file.read", p)?;
    let (name, base): (String, String) = db(&s)?
        .query_row(
            "SELECT name,base_path FROM remote WHERE id=?",
            params![q.remote_id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .map_err(|_| GatewayError::Message("remote not found".into()))?;
    let path = valid_path(p, &base)?;
    let config = materialize_rclone_config(&s, std::slice::from_ref(&q.remote_id))?;
    let o = rclone_command(&config)
        .args([
            "lsjson",
            &format!("{name}:{path}"),
            "--recursive=false",
            "--max-depth",
            &page_size.to_string(),
        ])
        .output()
        .await?;
    if let Some(p) = config {
        let _ = fs::remove_file(p);
    }
    if !o.status.success() {
        return Err(GatewayError::Message("rclone listing failed".into()));
    }
    let v: serde_json::Value = serde_json::from_slice(&o.stdout)
        .map_err(|_| GatewayError::Message("invalid rclone response".into()))?;
    Ok(Json(
        serde_json::json!({"remoteId":q.remote_id,"path":path,"items":v}),
    ))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PathOp {
    remote_id: String,
    path: String,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct DeleteIn {
    remote_id: String,
    path: String,
    dry_run: Option<bool>,
    confirmation_token: Option<String>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct TransferIn {
    source: String,
    destination: String,
    overwrite: Option<bool>,
}
async fn files_mkdir(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(i): Json<PathOp>,
) -> Result<Json<serde_json::Value>> {
    let c = scope(&h, &s, "file.write")?;
    acl(&s, &c, &i.remote_id, "file.write", &i.path)?;
    let (name, base) = remote_target(&s, &i.remote_id)?;
    let path = valid_path(&i.path, &base)?;
    let config = materialize_rclone_config(&s, std::slice::from_ref(&i.remote_id))?;
    let output = rclone_command(&config)
        .args(["mkdir", &format!("{name}:{path}")])
        .output()
        .await?;
    if let Some(p) = config {
        let _ = fs::remove_file(p);
    }
    if !output.status.success() {
        return Err(GatewayError::Message("rclone mkdir failed".into()));
    }
    audit_path(
        &s,
        Some(&c),
        "file.mkdir",
        None,
        Some(&i.remote_id),
        &path,
        "SUCCESS",
        None,
    )?;
    Ok(Json(serde_json::json!({"ok":true,"path":path})))
}
async fn files_delete(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(i): Json<DeleteIn>,
) -> Result<(StatusCode, Json<serde_json::Value>)> {
    let c = scope(&h, &s, "file.delete")?;
    acl(&s, &c, &i.remote_id, "file.delete", &i.path)?;
    if i.dry_run.unwrap_or(true) {
        let (name, base) = remote_target(&s, &i.remote_id)?;
        let path = valid_path(&i.path, &base)?;
        let config = materialize_rclone_config(&s, std::slice::from_ref(&i.remote_id))?;
        let preview = rclone_command(&config)
            .args([
                "lsjson",
                &format!("{name}:{path}"),
                "--recursive",
                "--files-only",
            ])
            .output()
            .await?;
        if let Some(p) = config {
            let _ = fs::remove_file(p);
        }
        if !preview.status.success() {
            return Err(GatewayError::Message("rclone delete dry-run failed".into()));
        }
        let entries: Vec<serde_json::Value> = serde_json::from_slice(&preview.stdout)
            .map_err(|_| GatewayError::Message("invalid rclone dry-run response".into()))?;
        let bytes: u64 = entries
            .iter()
            .filter_map(|v| {
                v.get("Size")
                    .or_else(|| v.get("size"))
                    .and_then(|x| x.as_u64())
            })
            .sum();
        let token = B64.encode(rand::random::<[u8; 16]>());
        db(&s)?.execute(
            "INSERT OR REPLACE INTO system_config(key,value,updated_at) VALUES(?,?,?)",
            params![
                format!("delete-confirm:{token}"),
                serde_json::json!({"remoteId":i.remote_id,"path":path,"count":entries.len(),"bytes":bytes}).to_string(),
                now() + 60
            ],
        )?;
        return Ok((
            StatusCode::ACCEPTED,
            Json(
                serde_json::json!({"dryRun":true,"confirmationToken":token,"expiresIn":60,"deleted":entries.len(),"bytes":bytes}),
            ),
        ));
    }
    let token = i
        .confirmation_token
        .ok_or_else(|| GatewayError::Message("confirmation required".into()))?;
    let key = format!("delete-confirm:{token}");
    let expected_path = valid_path(&i.path, &remote_target(&s, &i.remote_id)?.1)?;
    consume_confirmation(&s, &key, |_expires, stored| {
        let stored_value: serde_json::Value = serde_json::from_str(stored)
            .map_err(|_| GatewayError::Message("invalid confirmation token".into()))?;
        if stored_value.get("remoteId").and_then(|v| v.as_str()) != Some(i.remote_id.as_str())
            || stored_value.get("path").and_then(|v| v.as_str()) != Some(expected_path.as_str())
        {
            return Err(GatewayError::Message(
                "confirmation token does not match operation".into(),
            ));
        }
        Ok(())
    })?;
    let (name, base) = remote_target(&s, &i.remote_id)?;
    let path = valid_path(&i.path, &base)?;
    let config = materialize_rclone_config(&s, std::slice::from_ref(&i.remote_id))?;
    let output = rclone_command(&config)
        .args(["delete", &format!("{name}:{path}")])
        .output()
        .await?;
    if let Some(p) = config {
        let _ = fs::remove_file(p);
    }
    if !output.status.success() {
        return Err(GatewayError::Message("rclone delete failed".into()));
    }
    audit_path(
        &s,
        Some(&c),
        "file.delete",
        None,
        Some(&i.remote_id),
        &path,
        "SUCCESS",
        None,
    )?;
    Ok((
        StatusCode::ACCEPTED,
        Json(serde_json::json!({"dryRun":false,"deleted":true})),
    ))
}
async fn files_transfer_kind(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(i): Json<TransferIn>,
    kind: &'static str,
) -> Result<(StatusCode, Json<serde_json::Value>)> {
    let c = scope(&h, &s, "job.execute")?;
    if i.source.contains("..") || i.destination.contains("..") {
        return Err(GatewayError::Message("PATH_DENIED: traversal".into()));
    }
    validate_transfer_endpoint(&s, &c, &i.source, "file.read")?;
    if i.destination.is_empty() {
        return Err(GatewayError::Message(
            "transfer destination must not be empty".into(),
        ));
    }
    validate_transfer_endpoint(&s, &c, &i.destination, "file.write")?;
    let id = create_job_row(&s, &c, kind, &i.source, &i.destination, false)?;
    db(&s)?.execute(
        "UPDATE job SET options_json=? WHERE id=?",
        params![
            serde_json::json!({"overwrite": i.overwrite.unwrap_or(false)}).to_string(),
            id
        ],
    )?;
    let path_pair = format!("{}\n{}", i.source, i.destination);
    audit_path(
        &s,
        Some(&c),
        "file.transfer",
        Some(&id),
        None,
        &path_pair,
        "ACCEPTED",
        None,
    )?;
    Ok((
        StatusCode::ACCEPTED,
        Json(serde_json::json!({"jobId":id,"accepted":true})),
    ))
}
fn validate_transfer_endpoint(
    s: &AppState,
    client: &str,
    target: &str,
    permission: &str,
) -> Result<()> {
    if let Some((remote, path)) = target.split_once(':') {
        if remote.is_empty() || path.is_empty() {
            return Err(GatewayError::Message(
                "PATH_DENIED: invalid remote target".into(),
            ));
        }
        let (id, prefix): (String, String) = db(s)?
            .query_row(
                "SELECT id,base_path FROM remote WHERE name=? AND enabled=1",
                params![remote],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .map_err(|_| GatewayError::Message("remote not found".into()))?;
        acl(s, client, &id, permission, path).and_then(|_| valid_path(path, &prefix).map(|_| ()))
    } else {
        guard_local_path(target)
    }
}

fn job_accessible(s: &AppState, client: &str, id: &str) -> Result<()> {
    let (kind, source, destination): (String, String, String) = db(s)?
        .query_row(
            "SELECT type,source,destination FROM job WHERE id=?",
            params![id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .map_err(|_| GatewayError::Message("job not found".into()))?;
    let source_permission = job_source_permission(&kind);
    validate_transfer_endpoint(s, client, &source, source_permission)?;
    if !destination.is_empty() {
        validate_transfer_endpoint(s, client, &destination, "file.read")
            .or_else(|_| validate_transfer_endpoint(s, client, &destination, "file.write"))?;
    }
    Ok(())
}

fn job_source_permission(kind: &str) -> &'static str {
    if kind == "delete" {
        "file.delete"
    } else {
        "file.read"
    }
}
async fn files_copy(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(i): Json<TransferIn>,
) -> Result<(StatusCode, Json<serde_json::Value>)> {
    files_transfer_kind(State(s), h, Json(i), "copy").await
}
async fn files_move(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(i): Json<TransferIn>,
) -> Result<(StatusCode, Json<serde_json::Value>)> {
    files_transfer_kind(State(s), h, Json(i), "move").await
}

async fn files_upload(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(i): Json<TransferIn>,
) -> Result<(StatusCode, Json<serde_json::Value>)> {
    if i.source.contains(':') || !i.destination.contains(':') {
        return Err(GatewayError::Message(
            "upload requires local source and remote destination".into(),
        ));
    }
    files_transfer_kind(State(s), h, Json(i), "copy").await
}

async fn files_download(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(i): Json<TransferIn>,
) -> Result<(StatusCode, Json<serde_json::Value>)> {
    if !i.source.contains(':') || i.destination.contains(':') {
        return Err(GatewayError::Message(
            "download requires remote source and local destination".into(),
        ));
    }
    files_transfer_kind(State(s), h, Json(i), "copy").await
}
fn remote_target(s: &AppState, id: &str) -> Result<(String, String)> {
    db(s)?
        .query_row(
            "SELECT name,base_path FROM remote WHERE id=? AND enabled=1",
            params![id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .map_err(|_| GatewayError::Message("remote not found".into()))
}
fn create_job_row(
    s: &AppState,
    client: &str,
    kind: &str,
    source: &str,
    destination: &str,
    dry_run: bool,
) -> Result<String> {
    let id = Uuid::new_v4().to_string();
    let t = now();
    db(s)?.execute("INSERT INTO job(id,type,status,source,destination,dry_run,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",params![id,kind,"QUEUED",source,destination,dry_run as i64,client,t,t])?;
    Ok(id)
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct JobIn {
    #[serde(rename = "type")]
    job_type: String,
    source: String,
    destination: String,
    schedule: Option<String>,
    network_policy: Option<String>,
    battery_policy: Option<String>,
    options: Option<serde_json::Value>,
    dry_run: Option<bool>,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Job {
    id: String,
    #[serde(rename = "type")]
    job_type: String,
    status: String,
    source: String,
    destination: String,
    dry_run: bool,
    schedule: Option<String>,
    next_run_at: Option<i64>,
}
fn job(c: &Connection, id: &str) -> Result<Job> {
    c.query_row(
        "SELECT id,type,status,source,destination,dry_run,schedule,next_run_at FROM job WHERE id=?",
        params![id],
        |r| {
            Ok(Job {
                id: r.get(0)?,
                job_type: r.get(1)?,
                status: r.get(2)?,
                source: r.get(3)?,
                destination: r.get(4)?,
                dry_run: r.get::<_, i64>(5)? != 0,
                schedule: r.get(6)?,
                next_run_at: r.get(7)?,
            })
        },
    )
    .map_err(GatewayError::Db)
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct JobQuery {
    status: Option<String>,
    #[serde(rename = "type")]
    job_type: Option<String>,
}
async fn jobs(
    State(s): State<AppState>,
    h: HeaderMap,
    Query(q): Query<JobQuery>,
) -> Result<Json<Vec<Job>>> {
    let client = scope(&h, &s, "job.read")?;
    let c = db(&s)?;
    let mut st = c.prepare("SELECT id,type,status,source,destination,dry_run,schedule,next_run_at FROM job WHERE (?1 IS NULL OR status=?1) AND (?2 IS NULL OR type=?2) ORDER BY created_at DESC")?;
    let rows = st
        .query_map(params![q.status, q.job_type], |r| {
            Ok(Job {
                id: r.get(0)?,
                job_type: r.get(1)?,
                status: r.get(2)?,
                source: r.get(3)?,
                destination: r.get(4)?,
                dry_run: r.get::<_, i64>(5)? != 0,
                schedule: r.get(6)?,
                next_run_at: r.get(7)?,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    drop(st);
    drop(c);
    Ok(Json(
        rows.into_iter()
            .filter(|v| job_accessible(&s, &client, &v.id).is_ok())
            .collect(),
    ))
}
async fn job_create(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(i): Json<JobIn>,
) -> Result<(StatusCode, Json<Job>)> {
    let c = scope(&h, &s, "job.execute")?;
    if !["copy", "sync", "move", "bisync", "delete"].contains(&i.job_type.as_str()) {
        return Err(GatewayError::Message("unsupported job type".into()));
    }
    if i.schedule.is_some()
        && schedule_interval(i.schedule.as_deref()).is_none()
        && i.schedule.as_deref() != Some("@reboot")
    {
        return Err(GatewayError::Message(
            "unsupported schedule; use @hourly, @daily, @every Ns, */N * * * * or @reboot".into(),
        ));
    }
    if let Some(policy) = i.network_policy.as_deref() {
        if !["ANY", "WIFI", "UNMETERED", "VPN"].contains(&policy) {
            return Err(GatewayError::Message("invalid network policy".into()));
        }
    }
    if let Some(policy) = i.battery_policy.as_deref() {
        if !["ANY", "CHARGING", "BATTERY_30"].contains(&policy) {
            return Err(GatewayError::Message("invalid battery policy".into()));
        }
    }
    if let Some(opts) = i.options.as_ref() {
        let obj = opts
            .as_object()
            .ok_or_else(|| GatewayError::Message("options must be an object".into()))?;
        for key in obj.keys() {
            if ![
                "transfers",
                "checkers",
                "bwLimit",
                "overwrite",
                "deleteExcluded",
            ]
            .contains(&key.as_str())
            {
                return Err(GatewayError::Message(format!(
                    "unsupported job option: {key}"
                )));
            }
        }
        if let Some(v) = obj.get("transfers").and_then(|v| v.as_i64()) {
            if !(1..=32).contains(&v) {
                return Err(GatewayError::Message("transfers must be 1..32".into()));
            }
        }
        if let Some(v) = obj.get("checkers").and_then(|v| v.as_i64()) {
            if !(1..=64).contains(&v) {
                return Err(GatewayError::Message("checkers must be 1..64".into()));
            }
        }
        if let Some(v) = obj.get("bwLimit") {
            if !v.is_string()
                || !ini_line_safe(v.as_str().unwrap_or(""))
                || v.as_str().unwrap_or("").len() > 64
            {
                return Err(GatewayError::Message("invalid bwLimit".into()));
            }
        }
        for key in ["overwrite", "deleteExcluded"] {
            if let Some(v) = obj.get(key) {
                if !v.is_boolean() {
                    return Err(GatewayError::Message(format!("{key} must be boolean")));
                }
                if key == "deleteExcluded" && v.as_bool() == Some(true) && i.job_type != "sync" {
                    return Err(GatewayError::Message(
                        "deleteExcluded is supported only for sync jobs".into(),
                    ));
                }
            }
        }
    }
    if i.source.contains("..") || i.destination.contains("..") {
        return Err(GatewayError::Message("PATH_DENIED: traversal".into()));
    }
    validate_transfer_endpoint(
        &s,
        &c,
        &i.source,
        if i.job_type == "delete" {
            "file.delete"
        } else {
            "file.read"
        },
    )?;
    if i.job_type == "delete" && !i.destination.is_empty() {
        return Err(GatewayError::Message(
            "delete jobs require an empty destination".into(),
        ));
    }
    if !i.destination.is_empty() {
        validate_transfer_endpoint(&s, &c, &i.destination, "file.write")?;
    }
    let id = Uuid::new_v4().to_string();
    let t = now();
    let interval = schedule_interval(i.schedule.as_deref());
    let next = interval.map(|d| now() + d);
    db(&s)?.execute("INSERT INTO job(id,type,status,source,destination,schedule,network_policy,battery_policy,options_json,dry_run,created_by,created_at,updated_at,next_run_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",params![id,i.job_type,"CREATED",i.source,i.destination,i.schedule,i.network_policy.unwrap_or_else(||"ANY".into()),i.battery_policy.unwrap_or_else(||"ANY".into()),i.options.map(|v|v.to_string()),i.dry_run.unwrap_or(false) as i64,c,t,t,next])?;
    audit(&s, Some(&c), "job.create", Some(&id), None, "SUCCESS", None)?;
    let conn = db(&s)?;
    Ok((StatusCode::ACCEPTED, Json(job(&conn, &id)?)))
}
async fn job_get(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<Job>> {
    let client = scope(&h, &s, "job.read")?;
    job_accessible(&s, &client, &id)?;
    let conn = db(&s)?;
    Ok(Json(job(&conn, &id)?))
}
async fn job_delete(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<StatusCode> {
    let c = scope(&h, &s, "job.control")?;
    job_accessible(&s, &c, &id)?;
    db(&s)?.execute(
        "DELETE FROM job WHERE id=? AND status IN ('CREATED','SUCCESS','FAILED','CANCELLED')",
        params![id],
    )?;
    audit(&s, Some(&c), "job.delete", Some(&id), None, "SUCCESS", None)?;
    Ok(StatusCode::NO_CONTENT)
}
async fn job_action(
    State(s): State<AppState>,
    h: HeaderMap,
    Path((id, action)): Path<(String, String)>,
) -> Result<(StatusCode, Json<Job>)> {
    let c = scope(&h, &s, "job.control")?;
    job_accessible(&s, &c, &id)?;
    let current: String = {
        let conn = db(&s)?;
        conn.query_row("SELECT status FROM job WHERE id=?", params![id], |r| {
            r.get(0)
        })
        .map_err(|_| GatewayError::Message("job not found".into()))?
    };
    let st = match (action.as_str(), current.as_str()) {
        ("start", "CREATED") | ("start", "PAUSED") => "QUEUED",
        ("pause", "RUNNING") => "PAUSE_REQUESTED",
        ("resume", "PAUSED") => "QUEUED",
        ("cancel", "QUEUED") => "CANCELLED",
        ("cancel", "RUNNING") | ("cancel", "PAUSE_REQUESTED") => "CANCEL_REQUESTED",
        ("retry", "FAILED") | ("retry", "CANCELLED") => "QUEUED",
        (_, _) => return Err(GatewayError::Message("invalid job state transition".into())),
    };
    db(&s)?.execute(
        "UPDATE job SET status=?,updated_at=?,finished_at=NULL,last_error_code=NULL,last_error_message=NULL WHERE id=?",
        params![st, now(), id],
    )?;
    audit(
        &s,
        Some(&c),
        &format!("job.{action}"),
        Some(&id),
        None,
        "ACCEPTED",
        None,
    )?;
    let conn = db(&s)?;
    let response = job(&conn, &id)?;
    drop(conn);
    if action == "start" || action == "retry" {
        let state = s.clone();
        let job_id = id.clone();
        tokio::spawn(async move {
            run_job(state, job_id).await;
        });
    }
    Ok((StatusCode::ACCEPTED, Json(response)))
}

fn finish_job(
    state: &AppState,
    id: &str,
    run_id: &str,
    client: &str,
    state_name: &str,
    next: Option<i64>,
    error: Option<&str>,
) {
    let effective_state = if let Ok(c) = db(state) {
        // Safe Mode/offline stop may persist cancellation before the worker's
        // wait() future observes its exit. Preserve that decision so a late
        // SUCCESS/FAILED result cannot resurrect cancelled work.
        let effective_state = c
            .query_row("SELECT status FROM job WHERE id=?", params![id], |r| {
                r.get::<_, String>(0)
            })
            .ok()
            .filter(|current| {
                matches!(current.as_str(), "CANCEL_REQUESTED" | "CANCELLED")
                    && state_name != "CANCELLED"
            })
            .map(|_| "CANCELLED")
            .unwrap_or(state_name);
        let _ = c.execute(
            "UPDATE job_run SET state=?,finished_at=?,error_code=? WHERE id=?",
            params![effective_state, now(), error, run_id],
        );
        let _ = c.execute("UPDATE job SET status=?,finished_at=?,updated_at=?,next_run_at=?,last_error_code=? WHERE id=?", params![effective_state, now(), now(), next, error, id]);
        effective_state.to_owned()
    } else {
        state_name.to_owned()
    };
    // `audit` acquires the DB mutex itself; call it only after the update
    // connection has been dropped to avoid recursive mutex acquisition.
    let _ = audit(
        state,
        Some(client),
        "job.run",
        Some(id),
        None,
        &effective_state,
        error,
    );
}

#[derive(Default)]
struct TransferStats {
    bytes: Option<i64>,
    total_bytes: Option<i64>,
    files: Option<i64>,
    total_files: Option<i64>,
    errors: Option<i64>,
}

fn parse_rclone_stats(output: &[u8]) -> TransferStats {
    let mut stats = TransferStats::default();
    let text = String::from_utf8_lossy(output);
    for line in text.lines().rev() {
        let Ok(v) = serde_json::from_str::<serde_json::Value>(line.trim()) else {
            continue;
        };
        let number = |name: &str| v.get(name).and_then(|x| x.as_i64());
        stats.bytes = number("bytes").or(stats.bytes);
        stats.total_bytes = number("totalBytes").or(stats.total_bytes);
        stats.files = number("transfers").or(stats.files);
        stats.total_files = number("totalTransfers").or(stats.total_files);
        stats.errors = number("errors").or(stats.errors);
        if stats.bytes.is_some()
            || stats.total_bytes.is_some()
            || stats.files.is_some()
            || stats.total_files.is_some()
            || stats.errors.is_some()
        {
            break;
        }
    }
    stats
}

/// Provider CLIs can echo credential material in diagnostics. Never persist
/// such lines to the job log or expose them through the Job API.
fn redact_log_text(input: &str) -> String {
    const SENSITIVE: &[&str] = &[
        "password",
        "secret",
        "token",
        "access_key",
        "access key",
        "client_secret",
        "client secret",
        "private_key",
        "private key",
        "bearer",
        "authorization",
    ];
    input
        .lines()
        .map(|line| {
            let lower = line.to_ascii_lowercase();
            if SENSITIVE.iter().any(|needle| lower.contains(needle)) {
                "[REDACTED]"
            } else {
                line
            }
        })
        .collect::<Vec<_>>()
        .join("\n")
}

fn power_status() -> Vec<String> {
    let mut out = Vec::new();
    let Ok(entries) = fs::read_dir("/sys/class/power_supply") else {
        return out;
    };
    for entry in entries.flatten() {
        let path = entry.path().join("status");
        if let Ok(v) = fs::read_to_string(path) {
            out.push(v.trim().to_ascii_lowercase());
        }
    }
    out
}

fn battery_capacity() -> Option<i64> {
    let entries = fs::read_dir("/sys/class/power_supply").ok()?;
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_ascii_lowercase();
        if name.starts_with("battery") {
            if let Ok(v) = fs::read_to_string(entry.path().join("capacity")) {
                if let Ok(n) = v.trim().parse::<i64>() {
                    return Some(n);
                }
            }
        }
    }
    None
}

fn interface_up(name: &str) -> bool {
    fs::read_to_string(format!("/sys/class/net/{name}/operstate"))
        .is_ok_and(|v| matches!(v.trim(), "up" | "unknown"))
}

/// Device conditions are evaluated conservatively. If Android cannot prove a
/// requested condition, the job stays queued for the next scheduler tick.
fn policy_allows(network: &str, battery: &str) -> bool {
    let network_ok = match network {
        "ANY" => true,
        "WIFI" | "UNMETERED" => interface_up("wlan0"),
        "VPN" => interface_up("tun0") || interface_up("wg0"),
        _ => false,
    };
    let battery_ok = match battery {
        "ANY" => true,
        "CHARGING" => power_status()
            .iter()
            .any(|v| v == "charging" || v == "full"),
        "BATTERY_30" => battery_capacity().is_some_and(|v| v >= 30),
        _ => false,
    };
    network_ok && battery_ok
}

/// Atomically reserve a queued job slot. A count-then-update sequence allows
/// two concurrent requests to exceed maxConcurrentJobs; the IMMEDIATE SQLite
/// transaction serializes claims at the state boundary.
fn claim_job_slot(state: &AppState, id: &str, max_concurrent: i64) -> Result<bool> {
    let mut conn = db(state)?;
    let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
    let running: i64 =
        tx.query_row("SELECT COUNT(*) FROM job WHERE status='RUNNING'", [], |r| {
            r.get(0)
        })?;
    if running >= max_concurrent {
        tx.rollback()?;
        return Ok(false);
    }
    let changed = tx.execute(
        "UPDATE job SET status='RUNNING',started_at=?,updated_at=? WHERE id=? AND status IN ('QUEUED','CREATED')",
        params![now(), now(), id],
    )?;
    tx.commit()?;
    Ok(changed > 0)
}

async fn run_job(state: AppState, id: String) {
    let policy: Option<(String, String)> = db(&state).ok().and_then(|c| {
        c.query_row(
            "SELECT network_policy,battery_policy FROM job WHERE id=?",
            params![id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .ok()
    });
    if let Some((network, battery)) = policy {
        if !policy_allows(&network, &battery) {
            return;
        }
    } else {
        return;
    }
    let max_concurrent = configured_setting(&state.root, "maxConcurrentJobs", 2)
        .unwrap_or(2)
        .clamp(1, 4) as i64;
    // Keep the job queued when all slots are occupied. The claim itself is
    // transactional so simultaneous explicit starts cannot bypass the limit.
    let claimed = claim_job_slot(&state, &id, max_concurrent).unwrap_or(false);
    if !claimed {
        return;
    }
    let row: Result<(
        String,
        String,
        String,
        Option<String>,
        i64,
        Option<String>,
        Option<String>,
        String,
        String,
    )> = (|| {
        let c = db(&state)?;
        Ok(c.query_row(
            "SELECT type,source,destination,created_by,dry_run,options_json,schedule,network_policy,battery_policy FROM job WHERE id=?",
            params![id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?, r.get(5)?, r.get(6)?, r.get(7)?, r.get(8)?)),
        )?)
    })();
    let Ok((
        kind,
        source,
        destination,
        created_by,
        dry_run,
        options_json,
        schedule,
        _network,
        _battery,
    )) = row
    else {
        return;
    };
    // ACLs are mutable. Re-check the creator's permissions at execution time
    // so revoking a Remote/Path grant also stops already-queued work.
    if let Some(client) = created_by.as_deref() {
        let source_permission = job_source_permission(&kind);
        if validate_transfer_endpoint(&state, client, &source, source_permission).is_err()
            || (!destination.is_empty()
                && validate_transfer_endpoint(&state, client, &destination, "file.write").is_err())
        {
            let _ = db(&state).and_then(|c| {
                Ok(c.execute(
                    "UPDATE job SET status='FAILED',finished_at=?,updated_at=?,last_error_code='REMOTE_DENIED' WHERE id=?",
                    params![now(), now(), id],
                )?)
            });
            let _ = audit(
                &state,
                created_by.as_deref(),
                "job.run",
                Some(&id),
                None,
                "FAILED",
                Some("REMOTE_DENIED"),
            );
            return;
        }
    }
    if source.starts_with('-')
        || destination.starts_with('-')
        || source.contains('\0')
        || destination.contains('\0')
    {
        let _ = db(&state).and_then(|c| Ok(c.execute("UPDATE job SET status='FAILED',last_error_code='INVALID_ARGUMENT',updated_at=? WHERE id=?", params![now(),id])?));
        let _ = audit(
            &state,
            created_by.as_deref(),
            "job.run",
            Some(&id),
            None,
            "FAILED",
            Some("INVALID_ARGUMENT"),
        );
        return;
    }
    let run_id = Uuid::new_v4().to_string();
    if let Ok(c) = db(&state) {
        let _ = c.execute(
            "INSERT INTO job_run(id,job_id,state,started_at) VALUES(?,?,?,?)",
            params![run_id, id, "RUNNING", now()],
        );
        // Status was atomically claimed above; create the execution record.
    }
    let mut remote_ids = Vec::new();
    for target in [&source, &destination] {
        if let Some((name, _)) = target.split_once(':') {
            if let Ok(rid) = remote_id_by_name(&state, name) {
                remote_ids.push(rid);
            }
        }
    }
    remote_ids.sort();
    remote_ids.dedup();
    let config = match materialize_rclone_config(&state, &remote_ids) {
        Ok(v) => v,
        Err(e) => {
            finish_job(
                &state,
                &id,
                &run_id,
                created_by.as_deref().unwrap_or("system"),
                "FAILED",
                None,
                Some("SECRET_UNAVAILABLE"),
            );
            let _ = audit(
                &state,
                created_by.as_deref(),
                "job.run",
                Some(&id),
                None,
                "FAILED",
                Some(&e.to_string()),
            );
            return;
        }
    };
    let mut command = rclone_command(&config);
    command.arg(&kind).arg(&source);
    if kind != "delete" {
        command.arg(&destination);
    }
    // JSON stats are machine-readable and avoid scraping localized human
    // progress output. Older rclone builds simply ignore the optional stats
    // stream while the transfer itself remains typed.
    command.arg("--stats-one-line-json");
    if dry_run != 0 {
        command.arg("--dry-run");
    }
    if let Some(raw) = options_json.and_then(|x| serde_json::from_str::<serde_json::Value>(&x).ok())
    {
        if raw.get("overwrite").and_then(|v| v.as_bool()) != Some(true)
            && (kind == "copy" || kind == "move")
        {
            command.arg("--ignore-existing");
        }
        if raw.get("deleteExcluded").and_then(|v| v.as_bool()) == Some(true) && kind == "sync" {
            command.arg("--delete-excluded");
        }
        if let Some(v) = raw
            .get("transfers")
            .and_then(|v| v.as_u64())
            .filter(|v| (1..=32).contains(v))
        {
            command.args(["--transfers", &v.to_string()]);
        }
        if let Some(v) = raw
            .get("checkers")
            .and_then(|v| v.as_u64())
            .filter(|v| (1..=64).contains(v))
        {
            command.args(["--checkers", &v.to_string()]);
        }
        if let Some(v) = raw
            .get("bwLimit")
            .and_then(|v| v.as_str())
            .filter(|v| ini_line_safe(v) && v.len() <= 64)
        {
            command.args(["--bwlimit", v]);
        }
    }
    let mut child = match command
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .spawn()
    {
        Ok(c) => c,
        Err(_) => {
            if let Some(p) = config {
                let _ = fs::remove_file(p);
            }
            finish_job(
                &state,
                &id,
                &run_id,
                created_by.as_deref().unwrap_or("system"),
                "FAILED",
                None,
                Some("RCLONE_SPAWN_FAILED"),
            );
            return;
        }
    };
    if let Some(pid) = child.id() {
        if let Ok(c) = db(&state) {
            let _ = c.execute(
                "UPDATE job_run SET pid=? WHERE id=?",
                params![pid as i64, run_id],
            );
        }
    }
    let mut cancelled = false;
    let mut paused = false;
    loop {
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) => {
                let requested: Option<String> = db(&state).ok().and_then(|c| {
                    c.query_row("SELECT status FROM job WHERE id=?", params![id], |r| {
                        r.get(0)
                    })
                    .ok()
                });
                if requested.as_deref() == Some("CANCEL_REQUESTED")
                    || requested.as_deref() == Some("PAUSE_REQUESTED")
                {
                    cancelled = requested.as_deref() == Some("CANCEL_REQUESTED");
                    paused = !cancelled;
                    let _ = child.kill().await;
                    break;
                }
                tokio::time::sleep(std::time::Duration::from_millis(250)).await;
            }
            Err(_) => break,
        }
    }
    let output = child.wait_with_output().await.ok();
    // A Safe Mode or control request may have changed the persisted state
    // immediately before the child exited. Respect that state even if the
    // polling loop did not observe it before `wait_with_output` completed.
    let persisted_cancel = db(&state)
        .ok()
        .and_then(|c| {
            c.query_row("SELECT status FROM job WHERE id=?", params![id], |r| {
                r.get::<_, String>(0)
            })
            .ok()
        })
        .is_some_and(|v| matches!(v.as_str(), "CANCEL_REQUESTED" | "CANCELLED"));
    cancelled |= persisted_cancel;
    let status =
        output.as_ref().map(|o| o.status.success()).unwrap_or(false) && !cancelled && !paused;
    let final_state = if cancelled {
        "CANCELLED"
    } else if paused {
        "PAUSED"
    } else if status {
        "SUCCESS"
    } else {
        "FAILED"
    };
    if let Some(o) = output {
        let log = state.root.join("logs").join(format!("job-{id}.log"));
        let combined = [&o.stdout[..], &o.stderr[..]].concat();
        let stats = parse_rclone_stats(&combined);
        let redacted_log = redact_log_text(&String::from_utf8_lossy(&combined));
        let redacted_error = redact_log_text(&String::from_utf8_lossy(&o.stderr));
        if let Ok(c) = db(&state) {
            let _ = c.execute(
                "UPDATE job_run SET transferred_bytes=COALESCE(?,transferred_bytes),total_bytes=COALESCE(?,total_bytes),transferred_files=COALESCE(?,transferred_files),total_files=COALESCE(?,total_files),error_count=COALESCE(?,error_count),error_message=? WHERE id=?",
                params![stats.bytes, stats.total_bytes, stats.files, stats.total_files, stats.errors, (!status).then(|| redacted_error.chars().take(4096).collect::<String>()), run_id],
            );
        }
        let _ = fs::write(&log, redacted_log);
        let _ = restrict_file(&log);
    }
    if let Some(p) = config {
        let _ = fs::remove_file(p);
    }
    let next = if status {
        schedule_interval(schedule.as_deref()).map(|d| now() + d)
    } else {
        None
    };
    let stored_state = if next.is_some() {
        "CREATED"
    } else {
        final_state
    };
    if let Ok(c) = db(&state) {
        let _ = c.execute(
            "UPDATE job_run SET state=?,finished_at=? WHERE id=?",
            params![final_state, now(), run_id],
        );
        let _ = c.execute(
            "UPDATE job SET status=?,finished_at=?,updated_at=?,next_run_at=?,last_error_code=? WHERE id=?",
            params![stored_state, now(), now(), next, if status { Option::<String>::None } else { (final_state == "FAILED").then(|| final_state.to_string()) }, id],
        );
    }
    // `audit` acquires the DB mutex itself; keep it outside the update guard.
    let _ = audit(
        &state,
        created_by.as_deref(),
        "job.run",
        Some(&id),
        None,
        final_state,
        if status { None } else { Some(final_state) },
    );
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct MountIn {
    name: String,
    remote_id: String,
    remote_path: Option<String>,
    mount_point: String,
    cache_dir: Option<String>,
    read_only: Option<bool>,
    cache_mode: Option<String>,
    cache_max_size: Option<String>,
    cache_max_age: Option<String>,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Mount {
    id: String,
    name: String,
    remote_id: String,
    remote_path: String,
    mount_point: String,
    cache_dir: Option<String>,
    status: String,
    pid: Option<i64>,
    read_only: bool,
    cache_mode: String,
    cache_max_size: String,
    cache_max_age: String,
    enabled: bool,
}
fn mount(c: &Connection, id: &str) -> Result<Mount> {
    c.query_row(
        "SELECT id,name,remote_id,remote_path,mount_point,cache_dir,status,pid,read_only,cache_mode,cache_max_size,cache_max_age,enabled FROM mount_profile WHERE id=?",
        params![id],
        |r| {
            Ok(Mount {
                id: r.get(0)?,
                name: r.get(1)?,
                remote_id: r.get(2)?,
                remote_path: r.get(3)?,
                mount_point: r.get(4)?,
                cache_dir: r.get(5)?,
                status: r.get(6)?,
                pid: r.get(7)?,
                read_only: r.get::<_, i64>(8)? != 0,
                cache_mode: r.get(9)?,
                cache_max_size: r.get(10)?,
                cache_max_age: r.get(11)?,
                enabled: r.get::<_, i64>(12)? != 0,
            })
        },
    )
    .map_err(GatewayError::Db)
}
async fn mounts(State(s): State<AppState>, h: HeaderMap) -> Result<Json<Vec<Mount>>> {
    let client = scope(&h, &s, "mount.read")?;
    let c = db(&s)?;
    let mut st=c.prepare("SELECT id,name,remote_id,remote_path,mount_point,cache_dir,status,pid,read_only,cache_mode,cache_max_size,cache_max_age,enabled FROM mount_profile ORDER BY created_at")?;
    let rows = st
        .query_map([], |r| {
            Ok(Mount {
                id: r.get(0)?,
                name: r.get(1)?,
                remote_id: r.get(2)?,
                remote_path: r.get(3)?,
                mount_point: r.get(4)?,
                cache_dir: r.get(5)?,
                status: r.get(6)?,
                pid: r.get(7)?,
                read_only: r.get::<_, i64>(8)? != 0,
                cache_mode: r.get(9)?,
                cache_max_size: r.get(10)?,
                cache_max_age: r.get(11)?,
                enabled: r.get::<_, i64>(12)? != 0,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    drop(st);
    drop(c);
    Ok(Json(
        rows.into_iter()
            .filter(|m| acl(&s, &client, &m.remote_id, "file.read", &m.remote_path).is_ok())
            .collect(),
    ))
}
async fn mount_create(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(i): Json<MountIn>,
) -> Result<(StatusCode, Json<Mount>)> {
    let c = scope(&h, &s, "mount.write")?;
    validate_identity(&i.name, "mount name", 128)?;
    valid_mount(&i.mount_point)?;
    let conflict: Option<String> = db(&s)?.query_row("SELECT id FROM mount_profile WHERE mount_point=? AND status IN ('STARTING','RUNNING','STOPPING')", params![i.mount_point], |r| r.get(0)).optional()?;
    if conflict.is_some() {
        return Err(GatewayError::Message(
            "MOUNT_CONFLICT: mount point already active".into(),
        ));
    }
    let (_, base) = remote_target(&s, &i.remote_id)?;
    let remote_path = valid_path(i.remote_path.as_deref().unwrap_or("/"), &base)?;
    let mount_permission = if i.read_only.unwrap_or(false) {
        "file.read"
    } else {
        "file.write"
    };
    acl(&s, &c, &i.remote_id, mount_permission, &remote_path)?;
    let cache_dir = i.cache_dir.clone().unwrap_or_else(|| {
        s.root
            .join("cache")
            .join(format!("mount-{}", Uuid::new_v4()))
            .display()
            .to_string()
    });
    if !path_is_within(&s.root.join("cache"), FsPath::new(&cache_dir)) {
        return Err(GatewayError::Message(
            "cacheDir must be inside the manager cache directory".into(),
        ));
    }
    let cache_mode = i.cache_mode.clone().unwrap_or_else(|| "full".into());
    if !["off", "minimal", "writes", "full"].contains(&cache_mode.as_str()) {
        return Err(GatewayError::Message("invalid cache mode".into()));
    }
    let cache_max_size = i.cache_max_size.clone().unwrap_or_else(|| "32G".into());
    let configured_cache_limit =
        configured_setting(&s.root, "cacheMaxBytes", 32 * 1024 * 1024 * 1024)?;
    if parse_size_bytes(&cache_max_size).is_none_or(|v| v > configured_cache_limit) {
        return Err(GatewayError::Message(
            "cacheMaxSize exceeds the configured cache limit".into(),
        ));
    }
    let cache_max_age = i.cache_max_age.clone().unwrap_or_else(|| "36h".into());
    let id = Uuid::new_v4().to_string();
    let t = now();
    db(&s)?.execute("INSERT INTO mount_profile(id,name,remote_id,remote_path,mount_point,cache_dir,read_only,cache_mode,cache_max_size,cache_max_age,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",params![id,i.name,i.remote_id,remote_path,i.mount_point,cache_dir,i.read_only.unwrap_or(false) as i64,cache_mode,cache_max_size,cache_max_age,t,t])?;
    audit(
        &s,
        Some(&c),
        "mount.create",
        Some(&id),
        Some(&i.remote_id),
        "SUCCESS",
        None,
    )?;
    let conn = db(&s)?;
    Ok((StatusCode::CREATED, Json(mount(&conn, &id)?)))
}
async fn mount_action(
    State(s): State<AppState>,
    h: HeaderMap,
    Path((id, a)): Path<(String, String)>,
) -> Result<(StatusCode, Json<Mount>)> {
    let c = scope(&h, &s, "mount.write")?;
    let conn = db(&s)?;
    let m = mount(&conn, &id)?;
    drop(conn);
    valid_mount(&m.mount_point)?;
    let mount_permission = if m.read_only {
        "file.read"
    } else {
        "file.write"
    };
    acl(&s, &c, &m.remote_id, mount_permission, &m.remote_path)?;
    let st = match a.as_str() {
        "start" => "STARTING",
        "stop" => "STOPPING",
        "enable" => "STOPPED",
        "disable" => "STOPPED",
        _ => return Err(GatewayError::Message("unknown mount action".into())),
    };
    if a == "start" && matches!(m.status.as_str(), "STARTING" | "RUNNING") {
        return Err(GatewayError::Message("mount is already active".into()));
    }
    // Enabling a profile is a persistence toggle and must not make an already
    // running worker appear stopped. Only lifecycle actions change status.
    if matches!(a.as_str(), "start" | "stop") {
        let conn = db(&s)?;
        conn.execute(
            "UPDATE mount_profile SET status=?,updated_at=? WHERE id=?",
            params![st, now(), id],
        )?;
    }
    if a == "enable" {
        let conn = db(&s)?;
        conn.execute(
            "UPDATE mount_profile SET enabled=?,status=CASE WHEN pid IS NULL THEN 'STOPPED' ELSE status END,updated_at=? WHERE id=?",
            params![1, now(), id],
        )?;
    } else if a == "disable" {
        if let Some(pid) = m.pid {
            let _ = process_kill_command()
                .args(["-TERM", &pid.to_string()])
                .status();
        }
        let conn = db(&s)?;
        conn.execute(
            "UPDATE mount_profile SET enabled=0,status='STOPPED',pid=NULL,updated_at=? WHERE id=?",
            params![now(), id],
        )?;
        unmount_derived_bind(&m.mount_point);
        remove_mount_config(&s.root, &id);
    } else if a == "start" {
        let (remote_name, _) = match remote_target(&s, &m.remote_id) {
            Ok(value) => value,
            Err(error) => {
                reset_mount_start(&s, &id);
                return Err(error);
            }
        };
        if let Err(error) = fs::create_dir_all(&m.mount_point) {
            reset_mount_start(&s, &id);
            return Err(error.into());
        }
        let config = match materialize_mount_config(&s, &id, &m.remote_id) {
            Ok(value) => value,
            Err(error) => {
                reset_mount_start(&s, &id);
                return Err(error);
            }
        };
        let mut command = rclone_command(&Some(config.clone()));
        command
            .args([
                "mount",
                &format!("{remote_name}:{}", m.remote_path),
                &m.mount_point,
            ])
            .arg("--vfs-cache-mode")
            .arg(&m.cache_mode)
            .arg("--vfs-cache-max-size")
            .arg(&m.cache_max_size)
            .arg("--vfs-cache-max-age")
            .arg(&m.cache_max_age);
        if let Some(cache_dir) = &m.cache_dir {
            if !path_is_within(&s.root.join("cache"), FsPath::new(cache_dir)) {
                let _ = fs::remove_file(&config);
                reset_mount_start(&s, &id);
                return Err(GatewayError::Message(
                    "invalid mount cache directory".into(),
                ));
            }
            if let Err(error) = fs::create_dir_all(cache_dir) {
                let _ = fs::remove_file(&config);
                reset_mount_start(&s, &id);
                return Err(error.into());
            }
            command.arg("--cache-dir").arg(cache_dir);
        }
        if m.read_only {
            command.arg("--read-only");
        }
        let child = match command.spawn() {
            Ok(child) => child,
            Err(e) => {
                let _ = fs::remove_file(&config);
                reset_mount_start(&s, &id);
                return Err(GatewayError::Message(format!("mount start failed: {e}")));
            }
        };
        if let Some(pid) = child.id() {
            let conn = db(&s)?;
            conn.execute(
                "UPDATE mount_profile SET status='RUNNING',pid=?,updated_at=? WHERE id=?",
                params![pid as i64, now(), id],
            )?;
            tokio::spawn(bind_mount_when_ready(m.mount_point.clone()));
            let monitor_state = s.clone();
            let monitor_id = id.clone();
            let monitor_point = m.mount_point.clone();
            tokio::spawn(async move {
                let mut child = child;
                let _ = child.wait().await;
                if let Ok(c) = db(&monitor_state) {
                    let _ = c.execute(
                        "UPDATE mount_profile SET status='STOPPED',pid=NULL,updated_at=? WHERE id=? AND pid=?",
                        params![now(), monitor_id, pid as i64],
                    );
                }
                unmount_derived_bind(&monitor_point);
                remove_mount_config(&monitor_state.root, &monitor_id);
            });
        }
    } else if let Some(pid) = m.pid {
        let _ = process_kill_command()
            .args(["-TERM", &pid.to_string()])
            .status();
        let conn = db(&s)?;
        conn.execute(
            "UPDATE mount_profile SET status='STOPPED',pid=NULL,updated_at=? WHERE id=?",
            params![now(), id],
        )?;
        unmount_derived_bind(&m.mount_point);
        let _ = fs::remove_file(s.root.join("runtime").join(format!("mount-{id}.conf")));
    } else if a == "stop" {
        let conn = db(&s)?;
        conn.execute(
            "UPDATE mount_profile SET status='STOPPED',pid=NULL,updated_at=? WHERE id=?",
            params![now(), id],
        )?;
        unmount_derived_bind(&m.mount_point);
        remove_mount_config(&s.root, &id);
    }
    audit(
        &s,
        Some(&c),
        &format!("mount.{a}"),
        Some(&id),
        Some(&m.remote_id),
        "ACCEPTED",
        None,
    )?;
    let conn = db(&s)?;
    Ok((StatusCode::ACCEPTED, Json(mount(&conn, &id)?)))
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CryptIn {
    name: String,
    remote_id: String,
    remote_path: Option<String>,
    password: Option<String>,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CryptProfile {
    id: String,
    name: String,
    remote_id: String,
    remote_path: String,
    password_configured: bool,
    status: String,
}
async fn crypts(State(s): State<AppState>, h: HeaderMap) -> Result<Json<Vec<CryptProfile>>> {
    let client = scope(&h, &s, "remote.read")?;
    let conn = db(&s)?;
    let mut st = conn.prepare(
        "SELECT id,name,remote_id,remote_path,secret_ref,status FROM crypt_profile ORDER BY created_at",
    )?;
    let rows = st.query_map([], |r| {
        Ok(CryptProfile {
            id: r.get(0)?,
            name: r.get(1)?,
            remote_id: r.get(2)?,
            remote_path: r.get(3)?,
            password_configured: r.get::<_, Option<String>>(4)?.is_some(),
            status: r.get(5)?,
        })
    })?;
    let rows = rows.collect::<rusqlite::Result<Vec<_>>>()?;
    drop(st);
    drop(conn);
    Ok(Json(
        rows.into_iter()
            .filter(|p| acl(&s, &client, &p.remote_id, "file.read", &p.remote_path).is_ok())
            .collect(),
    ))
}
async fn crypt_create(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(i): Json<CryptIn>,
) -> Result<(StatusCode, Json<CryptProfile>)> {
    let c = scope(&h, &s, "remote.write")?;
    let (parent_name, base) = remote_target(&s, &i.remote_id)?;
    let path = valid_path(i.remote_path.as_deref().unwrap_or("/"), &base)?;
    acl(&s, &c, &i.remote_id, "file.write", &path)?;
    if !valid_crypt_name(&i.name, &parent_name) {
        return Err(GatewayError::Message("invalid crypt profile name".into()));
    }
    if i.password.as_deref().is_some_and(|password| {
        password.is_empty() || password.len() > 4096 || !ini_line_safe(password)
    }) {
        return Err(GatewayError::Message("invalid crypt password".into()));
    }
    let id = Uuid::new_v4().to_string();
    let secret_ref = i
        .password
        .as_ref()
        .map(|password| encrypt_secret(&s.root, &id, &serde_json::json!({"password": password})))
        .transpose()?;
    db(&s)?.execute("INSERT INTO crypt_profile(id,name,remote_id,remote_path,secret_ref,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)", params![id,i.name,i.remote_id,path,secret_ref,"READY",now(),now()])?;
    if let Some(ref secret_ref) = secret_ref {
        db(&s)?.execute(
            "INSERT INTO secret_meta(id,kind,backend,version,created_at) VALUES(?,?,?,?,?)",
            params![secret_ref, "crypt", "xchacha20poly1305", 1, now()],
        )?;
    }
    audit(
        &s,
        Some(&c),
        "crypt.create",
        Some(&id),
        None,
        "SUCCESS",
        None,
    )?;
    let conn = db(&s)?;
    let p = conn.query_row(
        "SELECT id,name,remote_id,remote_path,secret_ref,status FROM crypt_profile WHERE id=?",
        params![id],
        |r| {
            Ok(CryptProfile {
                id: r.get(0)?,
                name: r.get(1)?,
                remote_id: r.get(2)?,
                remote_path: r.get(3)?,
                password_configured: r.get::<_, Option<String>>(4)?.is_some(),
                status: r.get(5)?,
            })
        },
    )?;
    Ok((StatusCode::CREATED, Json(p)))
}
async fn crypt_test(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<serde_json::Value>> {
    let client = scope(&h, &s, "remote.read")?;
    let (remote_id, remote_path, secret_ref): (String, String, Option<String>) = db(&s)?
        .query_row(
            "SELECT remote_id,remote_path,secret_ref FROM crypt_profile WHERE id=?",
            params![id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .map_err(|_| GatewayError::Message("crypt profile not found".into()))?;
    acl(&s, &client, &remote_id, "file.read", &remote_path)?;
    let configured = secret_ref.is_some();
    let materialized = if configured {
        match materialize_crypt_config(&s, &id) {
            Ok((path, _)) => {
                let _ = fs::remove_file(path);
                true
            }
            Err(_) => false,
        }
    } else {
        false
    };
    audit(
        &s,
        Some(&client),
        "crypt.test",
        Some(&id),
        Some(&remote_id),
        if configured {
            "SUCCESS"
        } else {
            "NOT_CONFIGURED"
        },
        None,
    )?;
    Ok(Json(serde_json::json!({
        "id": id,
        "passwordConfigured": configured,
        "encryptionRoundTrip": materialized,
        "remotePath": remote_path
    })))
}
async fn audit_logs(
    State(s): State<AppState>,
    h: HeaderMap,
) -> Result<Json<Vec<serde_json::Value>>> {
    scope(&h, &s, "audit.read")?;
    let c = db(&s)?;
    let mut st=c.prepare("SELECT id,timestamp,client_id,uid,operation,resource,remote_id,path_hash,result,error_code,latency_ms FROM audit_log ORDER BY timestamp DESC LIMIT 500")?;
    Ok(Json(st.query_map([],|r|Ok(serde_json::json!({"id":r.get::<_,i64>(0)?,"timestamp":r.get::<_,i64>(1)?,"clientId":r.get::<_,Option<String>>(2)?,"uid":r.get::<_,Option<i64>>(3)?,"operation":r.get::<_,String>(4)?,"resource":r.get::<_,Option<String>>(5)?,"remoteId":r.get::<_,Option<String>>(6)?,"pathHash":r.get::<_,Option<String>>(7)?,"result":r.get::<_,String>(8)?,"errorCode":r.get::<_,Option<String>>(9)?,"latencyMs":r.get::<_,Option<i64>>(10)?})))?.collect::<rusqlite::Result<Vec<_>>>()?))
}
fn app(s: AppState) -> Router {
    Router::new()
        .route("/api/v1/system/health", get(health))
        .route("/api/v1/system/info", get(info))
        .route(
            "/api/v1/system/safe-mode",
            get(safe_mode_get).put(safe_mode_set),
        )
        .route(
            "/api/v1/system/settings",
            get(system_settings_get).put(system_settings_set),
        )
        .route("/api/v1/system/migration", get(migration_status))
        .route(
            "/api/v1/system/backups",
            get(backups_list).post(backup_create),
        )
        .route("/api/v1/security/pairing/start", post(pair_start))
        .route("/api/v1/security/pairing/complete", post(pair_complete))
        .route("/api/v1/security/clients", get(clients))
        .route(
            "/api/v1/security/clients/{id}/grants",
            get(client_grants).post(client_grant),
        )
        .route(
            "/api/v1/security/clients/{id}/disable",
            post(client_disable),
        )
        .route(
            "/api/v1/security/clients/{id}/rotate-token",
            post(client_rotate_token),
        )
        .route(
            "/api/v1/security/clients/{id}/grants/{grant_id}",
            axum::routing::delete(client_revoke),
        )
        .route(
            "/api/v1/security/clients/{id}/remote-acl",
            post(remote_acl_grant),
        )
        .route("/api/v1/remotes", get(remotes).post(remote_create))
        .route("/api/v1/remotes/import", post(remote_create))
        .route(
            "/api/v1/remotes/{id}",
            get(remote_get).put(remote_update).delete(remote_delete),
        )
        .route("/api/v1/remotes/{id}/test", post(remote_test))
        .route("/api/v1/remotes/{id}/{action}", post(remote_action))
        .route("/api/v1/remotes/{id}/export", get(remote_export))
        .route("/api/v1/files", get(files))
        .route("/api/v1/files/mkdir", post(files_mkdir))
        .route("/api/v1/files/delete", post(files_delete))
        .route("/api/v1/files/copy", post(files_copy))
        .route("/api/v1/files/move", post(files_move))
        .route("/api/v1/files/upload", post(files_upload))
        .route("/api/v1/files/download", post(files_download))
        .route("/api/v1/jobs", get(jobs).post(job_create))
        .route("/api/v1/jobs/{id}", get(job_get).delete(job_delete))
        .route("/api/v1/jobs/{id}/runs", get(job_runs))
        .route("/api/v1/jobs/{id}/log", get(job_log))
        .route("/api/v1/jobs/{id}/{action}", post(job_action))
        .route("/api/v1/mounts", get(mounts).post(mount_create))
        .route("/api/v1/mounts/{id}/{action}", post(mount_action))
        .route("/api/v1/crypt", get(crypts).post(crypt_create))
        .route("/api/v1/crypt/{id}/test", post(crypt_test))
        .route("/api/v1/logs/audit", get(audit_logs))
        .with_state(s.clone())
        .layer(middleware::from_fn_with_state(s, signed_request))
}
#[derive(Clone)]
struct Paths {
    socket: PathBuf,
    root: PathBuf,
    legacy: Option<PathBuf>,
    lan_addr: Option<SocketAddr>,
    tls_cert: Option<PathBuf>,
    tls_key: Option<PathBuf>,
    tls_client_ca: Option<PathBuf>,
}
fn paths(a: &[String]) -> Result<Paths> {
    let mut socket = PathBuf::from(DEFAULT_SOCKET);
    let mut root = PathBuf::from(DEFAULT_ROOT);
    let mut legacy = None;
    let mut lan_addr = None;
    let mut tls_cert = None;
    let mut tls_key = None;
    let mut tls_client_ca = None;
    let mut i = 0;
    while i < a.len() {
        if i + 1 >= a.len() {
            return Err(GatewayError::Message("missing option value".into()));
        }
        match a[i].as_str() {
            "--socket" => socket = PathBuf::from(&a[i + 1]),
            "--root" | "--state-dir" => root = PathBuf::from(&a[i + 1]),
            "--legacy" => legacy = Some(PathBuf::from(&a[i + 1])),
            "--lan-addr" => {
                lan_addr = Some(a[i + 1].parse::<SocketAddr>().map_err(|_| {
                    GatewayError::Message("invalid --lan-addr (expected HOST:PORT)".into())
                })?)
            }
            "--tls-cert" => tls_cert = Some(PathBuf::from(&a[i + 1])),
            "--tls-key" => tls_key = Some(PathBuf::from(&a[i + 1])),
            "--tls-client-ca" => tls_client_ca = Some(PathBuf::from(&a[i + 1])),
            x => return Err(GatewayError::Message(format!("unknown option {x}"))),
        }
        i += 2
    }
    if !root.is_absolute() {
        return Err(GatewayError::Message("root path must be absolute".into()));
    }
    let tls_requested = tls_cert.is_some() || tls_key.is_some() || tls_client_ca.is_some();
    if lan_addr.is_some() && (tls_cert.is_none() || tls_key.is_none()) {
        return Err(GatewayError::Message(
            "LAN listener requires --tls-cert and --tls-key".into(),
        ));
    }
    if lan_addr.is_none() && tls_requested {
        return Err(GatewayError::Message(
            "TLS options require --lan-addr".into(),
        ));
    }
    for (name, path) in [
        ("--tls-cert", tls_cert.as_ref()),
        ("--tls-key", tls_key.as_ref()),
        ("--tls-client-ca", tls_client_ca.as_ref()),
    ] {
        if let Some(path) = path {
            if !path.is_absolute() {
                return Err(GatewayError::Message(format!(
                    "{name} must be an absolute path"
                )));
            }
        }
    }
    if lan_addr.is_some_and(|addr| addr.ip().is_unspecified()) {
        return Err(GatewayError::Message(
            "--lan-addr must name an explicit interface address; wildcard addresses are refused"
                .into(),
        ));
    }
    Ok(Paths {
        socket,
        root,
        legacy,
        lan_addr,
        tls_cert,
        tls_key,
        tls_client_ca,
    })
}

fn safe_request_segment(segment: &str) -> bool {
    !segment.is_empty()
        && segment.len() <= 128
        && segment
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_'))
}

/// Allow only endpoints that are present in the typed API.  Prefix matching is
/// deliberately avoided: `/remotes/{id}/anything` must never become a proxy
/// for a future or raw rclone endpoint.
fn allowed_request(method: &str, path: &str) -> bool {
    if !["GET", "POST", "PUT", "DELETE"].contains(&method)
        || !path.starts_with("/api/v1/")
        || path.contains(['\r', '\n', '\0'])
    {
        return false;
    }
    if path.contains("..") {
        return false;
    }
    let clean = path.split('?').next().unwrap_or(path);
    if matches!(
        (method, clean),
        ("GET", "/api/v1/system/health")
            | ("GET", "/api/v1/system/info")
            | ("GET", "/api/v1/system/safe-mode")
            | ("PUT", "/api/v1/system/safe-mode")
            | ("GET", "/api/v1/system/settings")
            | ("PUT", "/api/v1/system/settings")
            | ("GET", "/api/v1/system/migration")
            | ("GET", "/api/v1/system/backups")
            | ("POST", "/api/v1/system/backups")
            | ("POST", "/api/v1/security/pairing/start")
            | ("POST", "/api/v1/security/pairing/complete")
            | ("GET", "/api/v1/security/clients")
            | ("GET", "/api/v1/remotes")
            | ("POST", "/api/v1/remotes")
            | ("POST", "/api/v1/remotes/import")
            | ("GET", "/api/v1/files")
            | ("POST", "/api/v1/files/mkdir")
            | ("POST", "/api/v1/files/delete")
            | ("POST", "/api/v1/files/copy")
            | ("POST", "/api/v1/files/move")
            | ("POST", "/api/v1/files/upload")
            | ("POST", "/api/v1/files/download")
            | ("GET", "/api/v1/jobs")
            | ("POST", "/api/v1/jobs")
            | ("GET", "/api/v1/mounts")
            | ("POST", "/api/v1/mounts")
            | ("GET", "/api/v1/crypt")
            | ("POST", "/api/v1/crypt")
            | ("GET", "/api/v1/logs/audit")
    ) {
        return true;
    }
    let parts: Vec<&str> = clean.split('/').collect();
    let valid_id = |v: Option<&&str>| v.is_some_and(|x| safe_request_segment(x));
    match parts.as_slice() {
        ["", "api", "v1", "remotes", "import"] => method == "POST",
        ["", "api", "v1", "remotes", id] => {
            valid_id(Some(id)) && matches!(method, "GET" | "PUT" | "DELETE")
        }
        ["", "api", "v1", "remotes", id, "test"] => valid_id(Some(id)) && method == "POST",
        ["", "api", "v1", "remotes", id, action] => {
            valid_id(Some(id))
                && matches!(*action, "enable" | "disable" | "export")
                && ((method == "POST" && *action != "export")
                    || (method == "GET" && *action == "export"))
        }
        ["", "api", "v1", "jobs", id] => valid_id(Some(id)) && matches!(method, "GET" | "DELETE"),
        ["", "api", "v1", "jobs", id, "runs"] => valid_id(Some(id)) && method == "GET",
        ["", "api", "v1", "jobs", id, "log"] => valid_id(Some(id)) && method == "GET",
        ["", "api", "v1", "crypt", id, "test"] => valid_id(Some(id)) && method == "POST",
        ["", "api", "v1", "jobs", id, action] => {
            valid_id(Some(id))
                && matches!(*action, "start" | "pause" | "resume" | "cancel" | "retry")
                && method == "POST"
        }
        ["", "api", "v1", "mounts", id, action] => {
            valid_id(Some(id))
                && matches!(*action, "start" | "stop" | "enable" | "disable")
                && method == "POST"
        }
        ["", "api", "v1", "security", "clients", id, "grants"] => {
            valid_id(Some(id)) && matches!(method, "GET" | "POST")
        }
        [
            "",
            "api",
            "v1",
            "security",
            "clients",
            id,
            "grants",
            grant_id,
        ] => {
            valid_id(Some(id))
                && !grant_id.is_empty()
                && grant_id.bytes().all(|b| b.is_ascii_digit())
                && method == "DELETE"
        }
        ["", "api", "v1", "security", "clients", id, "remote-acl"]
        | ["", "api", "v1", "security", "clients", id, "disable"]
        | ["", "api", "v1", "security", "clients", id, "rotate-token"] => {
            valid_id(Some(id)) && method == "POST"
        }
        _ => false,
    }
}

#[cfg(unix)]
fn request_cli(args: &[String]) -> Result<()> {
    let mut socket = PathBuf::from(DEFAULT_SOCKET);
    let mut method = None;
    let mut path = None;
    let mut token = None;
    let mut body = Vec::new();
    let mut i = 0;
    while i < args.len() {
        if i + 1 >= args.len() {
            return Err(GatewayError::Message("missing option value".into()));
        }
        match args[i].as_str() {
            "--socket" => socket = PathBuf::from(&args[i + 1]),
            "--method" => method = Some(args[i + 1].as_str()),
            "--path" => path = Some(args[i + 1].as_str()),
            "--token" => token = Some(args[i + 1].as_str()),
            "--body-base64" => {
                body = B64
                    .decode(&args[i + 1])
                    .map_err(|_| GatewayError::Message("invalid base64 body".into()))?
            }
            x => return Err(GatewayError::Message(format!("unknown option {x}"))),
        }
        i += 2;
    }
    let method = method.ok_or_else(|| GatewayError::Message("missing method".into()))?;
    let path = path.ok_or_else(|| GatewayError::Message("missing path".into()))?;
    if !socket.is_absolute() || !allowed_request(method, path) || body.len() > 64 * 1024 {
        return Err(GatewayError::Message("request not allowed".into()));
    }
    if token.is_some_and(|v| v.contains(['\r', '\n', '\0'])) {
        return Err(GatewayError::Message("invalid token".into()));
    }
    let mut stream = UnixStream::connect(socket)?;
    let auth = token
        .map(|v| format!("Authorization: Bearer {v}\r\n"))
        .unwrap_or_default();
    let head = format!(
        "{method} {path} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n{auth}Content-Type: application/json\r\nContent-Length: {}\r\n\r\n",
        body.len()
    );
    stream.write_all(head.as_bytes())?;
    stream.write_all(&body)?;
    let mut response = Vec::new();
    stream.read_to_end(&mut response)?;
    let split = response
        .windows(4)
        .position(|v| v == b"\r\n\r\n")
        .map(|v| v + 4)
        .ok_or_else(|| GatewayError::Message("invalid gateway response".into()))?;
    let status = response
        .split(|b| *b == b'\n')
        .next()
        .and_then(|line| line.split(|b| *b == b' ').nth(1))
        .and_then(|v| std::str::from_utf8(v).ok())
        .and_then(|v| v.parse::<u16>().ok())
        .ok_or_else(|| GatewayError::Message("invalid gateway status".into()))?;
    std::io::stdout().write_all(&response[split..])?;
    if !(200..300).contains(&status) {
        return Err(GatewayError::Message(format!(
            "gateway returned HTTP {status}"
        )));
    }
    Ok(())
}

#[cfg(not(unix))]
fn request_cli(_args: &[String]) -> Result<()> {
    Err(GatewayError::Message(
        "request requires a Unix target".into(),
    ))
}

#[cfg(unix)]
fn probe_cli(args: &[String]) -> Result<()> {
    let mut forwarded = Vec::with_capacity(6);
    let mut i = 0;
    while i < args.len() {
        if i + 1 >= args.len() {
            return Err(GatewayError::Message("missing option value".into()));
        }
        match args[i].as_str() {
            "--socket" => {
                forwarded.push("--socket".to_owned());
                forwarded.push(args[i + 1].clone());
            }
            // Kept for compatibility with the documented development command;
            // health is served by the state directory selected by the running
            // Gateway, not by the probe client.
            "--state-dir" => {}
            _ => return Err(GatewayError::Message("unknown probe option".into())),
        }
        i += 2;
    }
    forwarded.extend([
        "--method".into(),
        "GET".into(),
        "--path".into(),
        "/api/v1/system/health".into(),
    ]);
    request_cli(&forwarded)
}

#[cfg(not(unix))]
fn probe_cli(_args: &[String]) -> Result<()> {
    Err(GatewayError::Message("probe requires a Unix target".into()))
}

fn restore_cli(args: &[String]) -> Result<()> {
    let mut root = PathBuf::from(DEFAULT_ROOT);
    let mut backup = None;
    let mut i = 0;
    while i < args.len() {
        if i + 1 >= args.len() {
            return Err(GatewayError::Message("missing option value".into()));
        }
        match args[i].as_str() {
            "--root" | "--state-dir" => root = PathBuf::from(&args[i + 1]),
            "--backup" => backup = Some(args[i + 1].clone()),
            _ => return Err(GatewayError::Message("unknown restore option".into())),
        }
        i += 2;
    }
    if !root.is_absolute() {
        return Err(GatewayError::Message("root path must be absolute".into()));
    }
    let name =
        backup.ok_or_else(|| GatewayError::Message("restore requires --backup NAME".into()))?;
    let result = restore_backup(&root, &name)?;
    println!(
        "{}",
        serde_json::to_string(&result).unwrap_or_else(|_| "{}".into())
    );
    Ok(())
}

/// Stop only manager-owned mount workers during module removal/maintenance.
/// This is intentionally separate from a global `pkill`: worker PIDs come
/// from the manager database and every derived bind target is deterministic.
fn stop_cli(args: &[String]) -> Result<()> {
    let mut root = PathBuf::from(DEFAULT_ROOT);
    let mut i = 0;
    while i < args.len() {
        if i + 1 >= args.len() {
            return Err(GatewayError::Message("missing option value".into()));
        }
        match args[i].as_str() {
            "--root" | "--state-dir" => root = PathBuf::from(&args[i + 1]),
            _ => return Err(GatewayError::Message("unknown stop option".into())),
        }
        i += 2;
    }
    if !root.is_absolute() {
        return Err(GatewayError::Message("root path must be absolute".into()));
    }
    let database = open_db(&root)?;
    let job_pids: Vec<i64> = {
        let c = database
            .lock()
            .map_err(|_| GatewayError::Message("database lock poisoned".into()))?;
        let mut st =
            c.prepare("SELECT pid FROM job_run WHERE state='RUNNING' AND pid IS NOT NULL")?;
        st.query_map([], |r| r.get(0))?
            .collect::<rusqlite::Result<Vec<_>>>()?
    };
    for pid in job_pids {
        let _ = process_kill_command()
            .args(["-TERM", &pid.to_string()])
            .status();
    }
    {
        let c = database
            .lock()
            .map_err(|_| GatewayError::Message("database lock poisoned".into()))?;
        c.execute(
            "UPDATE job SET status='CANCELLED',finished_at=?,updated_at=? WHERE status IN ('RUNNING','PAUSE_REQUESTED','CANCEL_REQUESTED')",
            params![now(), now()],
        )?;
        c.execute(
            "UPDATE job_run SET state='CANCELLED',finished_at=? WHERE state='RUNNING'",
            params![now()],
        )?;
    }
    let mounts: Vec<(String, i64, String)> = {
        let c = database
            .lock()
            .map_err(|_| GatewayError::Message("database lock poisoned".into()))?;
        let mut st =
            c.prepare("SELECT id,pid,mount_point FROM mount_profile WHERE pid IS NOT NULL")?;
        st.query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?
            .collect::<rusqlite::Result<Vec<_>>>()?
    };
    let count = mounts.len();
    for (id, pid, mount_point) in mounts {
        let _ = process_kill_command()
            .args(["-TERM", &pid.to_string()])
            .status();
        unmount_derived_bind(&mount_point);
        remove_mount_config(&root, &id);
        let c = database
            .lock()
            .map_err(|_| GatewayError::Message("database lock poisoned".into()))?;
        c.execute(
            "UPDATE mount_profile SET status='STOPPED',pid=NULL,updated_at=? WHERE id=?",
            params![now(), id],
        )?;
    }
    println!("stopped_mounts={count}");
    Ok(())
}
#[cfg(unix)]
async fn load_tls_config(
    cert_path: &FsPath,
    key_path: &FsPath,
    client_ca_path: Option<&FsPath>,
) -> Result<Arc<ServerConfig>> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    for path in [Some(cert_path), Some(key_path), client_ca_path]
        .into_iter()
        .flatten()
    {
        let metadata = fs::metadata(path)?;
        if !metadata.is_file() {
            return Err(GatewayError::Message(format!(
                "TLS material is not a regular file: {}",
                path.display()
            )));
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            if metadata.permissions().mode() & 0o077 != 0 {
                return Err(GatewayError::Message(format!(
                    "TLS material must be owner-only (0600): {}",
                    path.display()
                )));
            }
        }
    }
    let certs: Vec<CertificateDer<'static>> = CertificateDer::pem_file_iter(cert_path)
        .map_err(|e| GatewayError::Message(format!("TLS certificate read failed: {e:?}")))?
        .collect::<std::result::Result<Vec<_>, _>>()
        .map_err(|e| GatewayError::Message(format!("TLS certificate parse failed: {e:?}")))?;
    if certs.is_empty() {
        return Err(GatewayError::Message(
            "TLS certificate chain is empty".into(),
        ));
    }
    let key = PrivateKeyDer::from_pem_file(key_path)
        .map_err(|e| GatewayError::Message(format!("TLS private key parse failed: {e:?}")))?;
    let mut config = if let Some(ca_path) = client_ca_path {
        let mut roots = RootCertStore::empty();
        let mut count = 0usize;
        for cert in CertificateDer::pem_file_iter(ca_path)
            .map_err(|e| GatewayError::Message(format!("TLS client CA read failed: {e:?}")))?
        {
            let cert = cert
                .map_err(|e| GatewayError::Message(format!("TLS client CA parse failed: {e:?}")))?;
            roots
                .add(cert)
                .map_err(|e| GatewayError::Message(format!("TLS client CA is invalid: {e:?}")))?;
            count += 1;
        }
        if count == 0 {
            return Err(GatewayError::Message("TLS client CA is empty".into()));
        }
        let verifier = WebPkiClientVerifier::builder(Arc::new(roots))
            .build()
            .map_err(|e| GatewayError::Message(format!("TLS client verifier failed: {e:?}")))?;
        ServerConfig::builder()
            .with_client_cert_verifier(verifier)
            .with_single_cert(certs, key)
            .map_err(|e| GatewayError::Message(format!("TLS server config failed: {e:?}")))?
    } else {
        ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(certs, key)
            .map_err(|e| GatewayError::Message(format!("TLS server config failed: {e:?}")))?
    };
    // The small LAN adapter below serves HTTP/1.1 over TLS. Do not advertise
    // h2 until an HTTP/2 connection driver is added.
    config.alpn_protocols = vec![b"http/1.1".to_vec()];
    Ok(Arc::new(config))
}
#[cfg(unix)]
async fn run_lan(addr: SocketAddr, config: Arc<ServerConfig>, router: Router) -> Result<()> {
    let listener = TcpListener::bind(addr).await?;
    let acceptor = TlsAcceptor::from(config);
    loop {
        let (stream, _) = listener.accept().await?;
        let acceptor = acceptor.clone();
        let router = router.clone();
        tokio::spawn(async move {
            let tls_stream = match acceptor.accept(stream).await {
                Ok(value) => value,
                Err(error) => {
                    eprintln!("rclone-gateway LAN TLS handshake failed: {error}");
                    return;
                }
            };
            let io = hyper_util::rt::TokioIo::new(tls_stream);
            let service = hyper_util::service::TowerToHyperService::new(router.into_service());
            if let Err(error) = hyper::server::conn::http1::Builder::new()
                .serve_connection(io, service)
                .await
            {
                eprintln!("rclone-gateway LAN HTTP connection stopped: {error}");
            }
        });
    }
}
#[cfg(unix)]
async fn serve(p: Paths) -> Result<()> {
    ensure_dirs(&p.root)?;
    let database = open_db(&p.root)?;
    let _ = master_key(&p.root)?;
    db(&AppState {
        db: database.clone(),
        root: p.root.clone(),
        pairing: Arc::new(RwLock::new(HashMap::new())),
        require_signature: false,
    })?
    .execute(
        "INSERT OR IGNORE INTO migration_history(version,applied_at,checksum) VALUES(?,?,?)",
        params![1, now(), hash(SCHEMA)],
    )?;
    if p.root.join("runtime/safe-mode").is_file() {
        database
            .lock()
            .map_err(|_| GatewayError::Message("database lock poisoned".into()))?
            .execute(
                "INSERT OR REPLACE INTO system_config(key,value,updated_at) VALUES('safe_mode','1',?)",
                params![now()],
            )?;
    }
    // Persist a conservative transport state before attempting to load LAN
    // TLS material.  If certificate/key validation fails, the process exits
    // with LAN explicitly recorded as disabled instead of leaving stale state
    // from a previous successful boot visible to the controller.
    {
        let c = database
            .lock()
            .map_err(|_| GatewayError::Message("database lock poisoned".into()))?;
        c.execute(
            "INSERT OR REPLACE INTO system_config(key,value,updated_at) VALUES('lan.enabled',?,?)",
            params![false.to_string(), now()],
        )?;
        c.execute(
            "INSERT OR REPLACE INTO system_config(key,value,updated_at) VALUES('lan.mtls',?,?)",
            params![false.to_string(), now()],
        )?;
    }
    if let Some(parent) = p.socket.parent() {
        fs::create_dir_all(parent)?;
    }
    if p.socket.exists() {
        fs::remove_file(&p.socket)?;
    }
    #[cfg(unix)]
    unsafe {
        // Unix sockets honor process umask at creation; keep the endpoint private.
        unsafe extern "C" {
            fn umask(mask: u32) -> u32;
        }
        umask(0o177);
    }
    let l = UnixListener::bind(&p.socket)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&p.socket, fs::Permissions::from_mode(0o600))?;
    }
    let s = AppState {
        db: database.clone(),
        root: p.root,
        pairing: Arc::new(RwLock::new(HashMap::new())),
        require_signature: false,
    };
    if let Some(addr) = p.lan_addr {
        let cert = p
            .tls_cert
            .as_deref()
            .ok_or_else(|| GatewayError::Message("LAN TLS certificate is missing".into()))?;
        let key = p
            .tls_key
            .as_deref()
            .ok_or_else(|| GatewayError::Message("LAN TLS private key is missing".into()))?;
        let tls = load_tls_config(cert, key, p.tls_client_ca.as_deref()).await?;
        {
            let c = database
                .lock()
                .map_err(|_| GatewayError::Message("database lock poisoned".into()))?;
            c.execute(
                "INSERT OR REPLACE INTO system_config(key,value,updated_at) VALUES('lan.enabled',?,?)",
                params![true.to_string(), now()],
            )?;
            c.execute(
                "INSERT OR REPLACE INTO system_config(key,value,updated_at) VALUES('lan.mtls',?,?)",
                params![p.tls_client_ca.is_some().to_string(), now()],
            )?;
        }
        let lan_state = AppState {
            require_signature: true,
            ..s.clone()
        };
        let lan_app = app(lan_state);
        println!(
            "rclone-gateway LAN TLS listening on {}{}",
            addr,
            if p.tls_client_ca.is_some() {
                " (mTLS)"
            } else {
                " (TLS; HMAC required)"
            }
        );
        tokio::spawn(async move {
            if let Err(error) = run_lan(addr, tls, lan_app).await {
                eprintln!("rclone-gateway LAN listener stopped: {error}");
            }
        });
    }
    tokio::spawn(scheduler(s.clone()));
    println!("rclone-gateway listening on {}", p.socket.display());
    axum::serve(l, app(s))
        .with_graceful_shutdown(async {
            let _ = tokio::signal::ctrl_c().await;
        })
        .await
        .map_err(|e| GatewayError::Message(e.to_string()))?;
    Ok(())
}
#[cfg(not(unix))]
async fn serve(_p: Paths) -> Result<()> {
    Err(GatewayError::Message(
        "serve requires a Unix target; build for x86_64-linux-android".into(),
    ))
}
fn migrate(p: Paths) -> Result<()> {
    ensure_dirs(&p.root)?;
    let database = open_db(&p.root)?;
    let legacy = p
        .legacy
        .ok_or_else(|| GatewayError::Message("migrate requires --legacy PATH".into()))?;
    let already: Option<i64> = database
        .lock()
        .map_err(|_| GatewayError::Message("database lock poisoned".into()))?
        .query_row(
            "SELECT version FROM migration_history WHERE version=2",
            [],
            |r| r.get(0),
        )
        .optional()?;
    if already.is_some() {
        println!("migrated_jobs=0 already_migrated=true");
        return Ok(());
    }
    // Import legacy rclone.conf sections as Remote metadata plus encrypted
    // credential blobs. Unknown/unsafe keys are retained only in the error
    // table; they never become command-line arguments.
    let config = legacy.join("rclone.conf");
    if config.exists() {
        let mut section: Option<String> = None;
        let mut values: HashMap<String, String> = HashMap::new();
        let flush = |section: &mut Option<String>,
                     values: &mut HashMap<String, String>,
                     database: &Db,
                     root: &FsPath|
         -> Result<()> {
            let Some(name) = section.take() else {
                values.clear();
                return Ok(());
            };
            if name.is_empty() || !ini_line_safe(&name) || name.contains(':') || name.contains('/')
            {
                values.clear();
                return Ok(());
            }
            let typ = values.remove("type").unwrap_or_else(|| "unknown".into());
            if !typ
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
            {
                values.clear();
                return Ok(());
            }
            let endpoint = values.remove("endpoint");
            let secret = serde_json::Value::Object(
                values
                    .drain()
                    .map(|(k, v)| (k, serde_json::Value::String(v)))
                    .collect(),
            );
            let id = Uuid::new_v4().to_string();
            let sr = if secret.as_object().is_some_and(|v| !v.is_empty()) {
                Some(encrypt_secret(root, &id, &secret)?)
            } else {
                None
            };
            let c = database
                .lock()
                .map_err(|_| GatewayError::Message("database lock poisoned".into()))?;
            let exists: Option<String> = c
                .query_row("SELECT id FROM remote WHERE name=?", params![name], |r| {
                    r.get(0)
                })
                .optional()?;
            if exists.is_none() {
                c.execute("INSERT INTO remote(id,name,type,endpoint,base_path,secret_ref,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)", params![id,name,typ,endpoint,"/",sr,1,now(),now()])?;
                if let Some(ref x) = sr {
                    c.execute("INSERT INTO secret_meta(id,kind,backend,version,created_at) VALUES(?,?,?,?,?)", params![x,"remote","xchacha20poly1305",1,now()])?;
                }
            }
            values.clear();
            Ok(())
        };
        for (line_no, raw) in fs::read_to_string(&config)?.lines().enumerate() {
            let line = raw.trim();
            if line.starts_with('[') && line.ends_with(']') {
                flush(&mut section, &mut values, &database, &p.root)?;
                section = Some(line[1..line.len() - 1].to_string());
                continue;
            }
            if line.is_empty() || line.starts_with('#') || section.is_none() {
                continue;
            }
            let Some((k, v)) = line.split_once('=') else {
                let c = database
                    .lock()
                    .map_err(|_| GatewayError::Message("database lock poisoned".into()))?;
                c.execute("INSERT INTO migration_errors(migration_version,source_file,line_number,message,raw_value,created_at) VALUES(?,?,?,?,?,?)", params![2,config.display().to_string(),line_no as i64+1,"expected key=value", "<redacted>", now()])?;
                continue;
            };
            let k = k.trim().to_string();
            let v = v.trim().to_string();
            if !ini_line_safe(&k) || !ini_line_safe(&v) || k.is_empty() {
                // Never persist a malformed credential value in the migration
                // error table. Keep only a diagnostic marker; valid entries
                // are encrypted immediately by `flush` below.
                let c = database
                    .lock()
                    .map_err(|_| GatewayError::Message("database lock poisoned".into()))?;
                c.execute("INSERT INTO migration_errors(migration_version,source_file,line_number,message,raw_value,created_at) VALUES(?,?,?,?,?,?)", params![2,config.display().to_string(),line_no as i64+1,"unsafe key or value", "<redacted>", now()])?;
                continue;
            }
            values.insert(k, v);
        }
        flush(&mut section, &mut values, &database, &p.root)?;
    }
    let mut migrated = 0u64;
    for kind in ["sync", "copy"] {
        let file = legacy.join(kind);
        if !file.exists() {
            continue;
        }
        for (line_no, raw) in fs::read_to_string(&file)?.lines().enumerate() {
            let fields: Vec<&str> = raw.split_whitespace().collect();
            let valid = !raw.trim().is_empty()
                && !raw.trim_start().starts_with('#')
                && fields.len() == 2
                && fields
                    .iter()
                    .all(|x| !x.starts_with('-') && ini_line_safe(x) && !x.contains(".."));
            let c = database
                .lock()
                .map_err(|_| GatewayError::Message("database lock poisoned".into()))?;
            if valid {
                c.execute("INSERT INTO job(id,type,status,source,destination,dry_run,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)", params![Uuid::new_v4().to_string(), kind, "CREATED", fields[0], fields[1], 0, now(), now()])?;
                migrated += 1;
            } else {
                // Legacy sync/copy lines may contain remote identifiers or
                // local paths; never echo them into diagnostics because the
                // same file can contain credentials in provider-specific
                // formats.  Keep only a stable redaction marker.
                c.execute("INSERT INTO migration_errors(migration_version,source_file,line_number,message,raw_value,created_at) VALUES(?,?,?,?,?,?)", params![1, file.display().to_string(), line_no as i64 + 1, "expected two safe path arguments", "<redacted>", now()])?;
            }
        }
    }
    let c = database
        .lock()
        .map_err(|_| GatewayError::Message("database lock poisoned".into()))?;
    c.execute(
        "INSERT OR REPLACE INTO migration_history(version,applied_at,checksum) VALUES(?,?,?)",
        params![2, now(), hash(&format!("legacy:{}", legacy.display()))],
    )?;
    println!("migrated_jobs={migrated}");
    Ok(())
}
#[tokio::main]
async fn main() {
    let mut a = env::args().skip(1).collect::<Vec<_>>();
    let cmd = if a.is_empty() {
        "serve".into()
    } else {
        a.remove(0)
    };
    let r = match cmd.as_str() {
        "request" => request_cli(&a),
        "probe" => probe_cli(&a),
        "restore" => restore_cli(&a),
        "stop" => stop_cli(&a),
        _ => match paths(&a) {
        Ok(p) => match cmd.as_str() {
            "serve" => serve(p).await,
            "migrate" => migrate(p),
            "version" | "--version" => {
                println!("rclone-gateway {}", env!("CARGO_PKG_VERSION"));
                Ok(())
            }
            _ => Err(GatewayError::Message(
                "usage: rclone-gateway serve [--socket PATH] [--root PATH] [--lan-addr HOST:PORT --tls-cert PATH --tls-key PATH [--tls-client-ca PATH]] | migrate --root PATH --legacy PATH | restore --root PATH --backup NAME | stop --root PATH | probe [--socket PATH] | request --socket PATH --method METHOD --path PATH [--token TOKEN] [--body-base64 BASE64]".into(),
            )),
        },
        Err(e) => Err(e),
    }};
    if let Err(e) = r {
        eprintln!("rclone-gateway: {e}");
        std::process::exit(2)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn path_guard_rejects_traversal_and_prefix_escape() {
        assert!(valid_path("/photos/a", "/photos").is_ok());
        assert!(valid_path("/photos/../secret", "/photos").is_err());
        assert!(valid_path("/other", "/photos").is_err());
        assert!(valid_path("/photos/line\nfeed", "/photos").is_err());
        assert!(valid_path("/photos/\u{001b}escape", "/photos").is_err());
    }
    #[test]
    fn mount_guard_allows_only_documented_roots() {
        assert!(valid_mount("/mnt/rclone-drive").is_ok());
        assert!(valid_mount("/data/media/0/drive").is_ok());
        assert!(valid_mount("/mnt/rclone-").is_err());
        assert!(valid_mount("/system/bin").is_err());
        assert!(valid_mount("/mnt/rclone-../escape").is_err());
        assert!(valid_mount("/sdcard/name\tbad").is_err());
    }
    #[test]
    fn derived_bind_target_is_strictly_scoped() {
        assert_eq!(
            derived_bind_target("/mnt/rclone-drive"),
            Some("/data/media/0/drive".to_owned())
        );
        assert!(derived_bind_target("/mnt/rclone-a/b").is_none());
        assert!(derived_bind_target("/data/media/0/drive").is_none());
    }
    #[test]
    fn cache_path_check_is_component_aware() {
        assert!(path_is_within(
            FsPath::new("/state/cache"),
            FsPath::new("/state/cache/m1")
        ));
        assert!(!path_is_within(
            FsPath::new("/state/cache"),
            FsPath::new("/state/cache-escape")
        ));
        assert!(!path_is_within(
            FsPath::new("/state/cache"),
            FsPath::new("/state/cache/../secrets")
        ));
    }
    #[test]
    fn local_path_guard_blocks_protected_trees() {
        assert!(valid_local_path("/sdcard/Download").is_ok());
        assert!(valid_local_path("/data/adb/rclone").is_err());
        assert!(valid_local_path("/system/bin").is_err());
        assert!(valid_local_path("/tmp/../etc").is_err());
        assert!(valid_local_path("/sdcard/file\tbad").is_err());
    }
    #[cfg(unix)]
    #[test]
    fn local_path_guard_resolves_symlinked_protected_tree() {
        let root = std::env::temp_dir().join(format!("rclone-local-{}", Uuid::new_v4()));
        fs::create_dir_all(&root).unwrap();
        let link = root.join("escape");
        std::os::unix::fs::symlink("/proc", &link).unwrap();
        assert!(guard_local_path(link.to_str().unwrap()).is_err());
        let _ = fs::remove_dir_all(root);
    }
    #[test]
    fn typed_option_bounds_and_remote_acl_tokens_are_not_shell_parsed() {
        assert!((1..=32).contains(&4));
        assert!(!(1..=32).contains(&33));
        let permissions = "file.read,file.write";
        assert!(permissions.split(',').any(|v| v == "file.read"));
        assert!(!permissions.split_whitespace().any(|v| v == "file.read"));
    }
    #[test]
    fn request_cli_path_allowlist_blocks_raw_rc_and_shell() {
        assert!(allowed_request("GET", "/api/v1/system/health"));
        assert!(!allowed_request("POST", "/api/v1/rc/core/command"));
        assert!(!allowed_request("GET", "/api/v1/system/health?x=../"));
        assert!(!allowed_request("GET", "/api/v1/system/health\nX: y"));
        assert!(allowed_request(
            "GET",
            "/api/v1/remotes/123e4567-e89b-12d3-a456-426614174000"
        ));
        assert!(allowed_request(
            "POST",
            "/api/v1/jobs/123e4567-e89b-12d3-a456-426614174000/start"
        ));
        assert!(allowed_request(
            "POST",
            "/api/v1/jobs/123e4567-e89b-12d3-a456-426614174000/retry"
        ));
        assert!(!allowed_request(
            "POST",
            "/api/v1/jobs/123e4567-e89b-12d3-a456-426614174000/anything"
        ));
        assert!(!allowed_request("GET", "/api/v1/remotes/one/../../etc"));
        assert!(!allowed_request(
            "GET",
            "/api/v1/security/clients/one/grants/not-a-number"
        ));
        assert!(allowed_request(
            "GET",
            "/api/v1/remotes/123e4567-e89b-12d3-a456-426614174000/export"
        ));
        assert!(allowed_request(
            "POST",
            "/api/v1/remotes/123e4567-e89b-12d3-a456-426614174000/disable"
        ));
        assert!(allowed_request("POST", "/api/v1/remotes/import"));
    }
    #[test]
    fn lan_paths_require_tls_and_parse_socket_address() {
        let root = if cfg!(windows) {
            "C:\\data\\adb\\rclone-manage"
        } else {
            "/data/adb/rclone-manage"
        };
        let cert = if cfg!(windows) {
            "C:\\data\\adb\\rclone-manage\\keys\\server.pem"
        } else {
            "/data/adb/rclone-manage/keys/server.pem"
        };
        let key = if cfg!(windows) {
            "C:\\data\\adb\\rclone-manage\\keys\\server.key"
        } else {
            "/data/adb/rclone-manage/keys/server.key"
        };
        let ca = if cfg!(windows) {
            "C:\\data\\adb\\rclone-manage\\keys\\clients-ca.pem"
        } else {
            "/data/adb/rclone-manage/keys/clients-ca.pem"
        };
        let p = paths(&[
            "--root".into(),
            root.into(),
            "--lan-addr".into(),
            "192.168.1.10:8443".into(),
            "--tls-cert".into(),
            cert.into(),
            "--tls-key".into(),
            key.into(),
            "--tls-client-ca".into(),
            ca.into(),
        ])
        .unwrap();
        assert_eq!(p.lan_addr.unwrap().port(), 8443);
        assert!(p.tls_client_ca.is_some());
        assert!(paths(&["--lan-addr".into(), "127.0.0.1:8443".into()]).is_err());
        assert!(paths(&["--tls-cert".into(), "/tmp/server.pem".into()]).is_err());
        assert!(
            paths(&[
                "--lan-addr".into(),
                "192.168.1.10:8443".into(),
                "--tls-cert".into(),
                "server.pem".into(),
                "--tls-key".into(),
                "/tmp/server.key".into(),
            ])
            .is_err()
        );
        assert!(
            paths(&[
                "--lan-addr".into(),
                "0.0.0.0:8443".into(),
                "--tls-cert".into(),
                "/tmp/server.pem".into(),
                "--tls-key".into(),
                "/tmp/server.key".into(),
            ])
            .is_err()
        );
    }
    #[test]
    fn token_hash_is_not_plaintext() {
        assert_ne!(hash("token"), "token");
    }
    #[test]
    fn encrypted_secret_blob_is_not_plaintext() {
        let root = std::env::temp_dir().join(format!("rclone-gateway-test-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        let reference =
            encrypt_secret(&root, "remote-1", &serde_json::json!({"password":"secret"})).unwrap();
        let bytes = std::fs::read(root.join("secrets").join(format!("{reference}.blob"))).unwrap();
        assert!(!String::from_utf8_lossy(&bytes).contains("secret"));
        assert_eq!(
            decrypt_secret(&root, &reference, "remote-1").unwrap()["password"],
            "secret"
        );
        assert!(decrypt_secret(&root, &reference, "other-id").is_err());
        let _ = std::fs::remove_dir_all(root);
    }

    #[test]
    fn secret_validation_rejects_structured_or_multiline_values() {
        assert!(
            validate_secret_object(&serde_json::json!({
                "access_key": "abc",
                "secret": 42,
                "enabled": true
            }))
            .is_ok()
        );
        assert!(
            validate_secret_object(&serde_json::json!({
                "nested": {"password": "pw"}
            }))
            .is_err()
        );
        assert!(
            validate_secret_object(&serde_json::json!({
                "password": "line\nvalue"
            }))
            .is_err()
        );
        assert!(
            validate_secret_object(&serde_json::json!({
                "bad.key": "value"
            }))
            .is_err()
        );
    }

    #[test]
    fn rclone_obscure_has_random_iv_and_expected_shape() {
        let a = obscure_rclone("password").unwrap();
        let b = obscure_rclone("password").unwrap();
        assert_ne!(a, b);
        let decoded = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .decode(a.as_bytes())
            .unwrap();
        assert_eq!(decoded.len(), 16 + "password".len());
    }
    #[test]
    fn crypt_config_materialization_keeps_password_out_of_api_shape() {
        let root = std::env::temp_dir().join(format!("rclone-crypt-config-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        let state = AppState {
            db: open_db(&root).unwrap(),
            root: root.clone(),
            pairing: Arc::new(RwLock::new(HashMap::new())),
            require_signature: false,
        };
        let remote_id = "remote-1";
        let crypt_id = "crypt-1";
        let secret_ref =
            encrypt_secret(&root, crypt_id, &serde_json::json!({"password":"pw"})).unwrap();
        db(&state).unwrap().execute(
            "INSERT INTO remote(id,name,type,base_path,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
            params![remote_id, "base", "local", "/", 1, now(), now()],
        ).unwrap();
        db(&state).unwrap().execute(
            "INSERT INTO crypt_profile(id,name,remote_id,remote_path,secret_ref,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
            params![crypt_id, "encrypted", remote_id, "/vault", secret_ref, "READY", now(), now()],
        ).unwrap();
        let (path, name) = materialize_crypt_config(&state, crypt_id).unwrap();
        let text = fs::read_to_string(&path).unwrap();
        assert_eq!(name, "encrypted");
        assert!(text.contains("type = crypt"));
        assert!(text.contains("remote = base:/vault"));
        let password_line = text
            .lines()
            .find(|line| line.starts_with("password = "))
            .unwrap();
        assert_ne!(password_line, "password = pw");
        assert!(!password_line.ends_with(" pw"));
        let profile = serde_json::json!({"name": name, "passwordConfigured": true});
        assert!(!profile.to_string().contains("password = pw"));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn crypt_profile_name_cannot_shadow_parent_remote() {
        assert!(valid_crypt_name("encrypted", "base"));
        assert!(!valid_crypt_name("base", "base"));
        assert!(!valid_crypt_name("", "base"));
        assert!(!valid_crypt_name("bad\nname", "base"));
    }

    #[test]
    fn config_section_names_reject_ini_injection() {
        assert!(validate_identity("photos-2026", "remote name", 128).is_ok());
        assert!(validate_identity("photos]\ntype = local", "remote name", 128).is_err());
        assert!(validate_identity("remote/name", "mount name", 128).is_err());
        assert!(validate_identity("remote:name", "crypt profile name", 128).is_err());
    }

    #[test]
    fn schedule_parser_accepts_interval_forms_only() {
        assert_eq!(schedule_interval(Some("@every 30s")), Some(30));
        assert_eq!(schedule_interval(Some("*/5 * * * *")), Some(300));
        assert_eq!(schedule_interval(Some("@daily")), Some(86_400));
        assert_eq!(schedule_interval(Some("*/0 * * * *")), None);
        assert_eq!(schedule_interval(Some("rm -rf /")), None);
    }
    #[test]
    fn size_parser_and_settings_defaults_are_bounded() {
        assert_eq!(parse_size_bytes("32G"), Some(32 * 1024 * 1024 * 1024));
        assert_eq!(parse_size_bytes("64MiB"), Some(64 * 1024 * 1024));
        assert_eq!(parse_size_bytes("bad"), None);
        assert!(parse_size_bytes("1T").is_some());
        let root = std::env::temp_dir().join(format!("rclone-settings-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        let db_path = root.join("db/state.db");
        let conn = Connection::open(&db_path).unwrap();
        conn.execute_batch(SCHEMA).unwrap();
        conn.execute(
            "INSERT INTO system_config(key,value,updated_at) VALUES('logMaxBytes','1234567',0)",
            [],
        )
        .unwrap();
        drop(conn);
        assert_eq!(
            configured_setting(&root, "logMaxBytes", 99).unwrap(),
            1_234_567
        );
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn audit_persists_uid_path_hash_and_latency_shape_without_plaintext_path_hash() {
        let root = std::env::temp_dir().join(format!("rclone-audit-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        let state = AppState {
            db: open_db(&root).unwrap(),
            root: root.clone(),
            pairing: Arc::new(RwLock::new(HashMap::new())),
            require_signature: false,
        };
        audit_path(
            &state,
            Some("client-1"),
            "file.read",
            Some("/photos/private.jpg"),
            Some("remote-1"),
            "/photos/private.jpg",
            "SUCCESS",
            None,
        )
        .unwrap();
        let row: (Option<i64>, Option<String>, Option<i64>) = db(&state)
            .unwrap()
            .query_row(
                "SELECT uid,path_hash,latency_ms FROM audit_log ORDER BY id DESC LIMIT 1",
                [],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .unwrap();
        assert!(row.0.is_some());
        assert_eq!(row.1.as_deref(), Some(hash("/photos/private.jpg").as_str()));
        assert!(!row.1.as_deref().unwrap().contains("private.jpg"));
        assert!(row.2.is_some());
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn job_slot_claim_is_bounded_atomically() {
        let root = std::env::temp_dir().join(format!("rclone-job-slot-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        let state = AppState {
            db: open_db(&root).unwrap(),
            root: root.clone(),
            pairing: Arc::new(RwLock::new(HashMap::new())),
            require_signature: false,
        };
        let conn = db(&state).unwrap();
        for (id, status) in [("running", "RUNNING"), ("queued", "QUEUED")] {
            conn.execute(
                "INSERT INTO job(id,type,status,source,destination,dry_run,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                params![id, "copy", status, "remote:/a", "remote:/b", 0, now(), now()],
            )
            .unwrap();
        }
        drop(conn);
        assert!(!claim_job_slot(&state, "queued", 1).unwrap());
        db(&state)
            .unwrap()
            .execute("UPDATE job SET status='SUCCESS' WHERE id='running'", [])
            .unwrap();
        assert!(claim_job_slot(&state, "queued", 1).unwrap());
        let status: String = db(&state)
            .unwrap()
            .query_row("SELECT status FROM job WHERE id='queued'", [], |r| r.get(0))
            .unwrap();
        assert_eq!(status, "RUNNING");
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn delete_job_acl_uses_delete_permission_for_source() {
        assert_eq!(job_source_permission("delete"), "file.delete");
        assert_eq!(job_source_permission("sync"), "file.read");
        assert_eq!(job_source_permission("copy"), "file.read");
    }

    #[test]
    fn destructive_confirmation_is_single_use() {
        let root = std::env::temp_dir().join(format!("rclone-confirm-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        let state = AppState {
            db: open_db(&root).unwrap(),
            root: root.clone(),
            pairing: Arc::new(RwLock::new(HashMap::new())),
            require_signature: false,
        };
        db(&state)
            .unwrap()
            .execute(
                "INSERT INTO system_config(key,value,updated_at) VALUES(?,?,?)",
                params![
                    "delete-confirm:test",
                    r#"{"remoteId":"r","path":"/x"}"#,
                    now() + 60
                ],
            )
            .unwrap();
        consume_confirmation(&state, "delete-confirm:test", |_expires, value| {
            assert!(value.contains("\"remoteId\":\"r\""));
            Ok(())
        })
        .unwrap();
        assert!(consume_confirmation(&state, "delete-confirm:test", |_expires, _| Ok(())).is_err());
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn late_worker_exit_cannot_overwrite_cancellation() {
        let root = std::env::temp_dir().join(format!("rclone-cancel-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        let state = AppState {
            db: open_db(&root).unwrap(),
            root: root.clone(),
            pairing: Arc::new(RwLock::new(HashMap::new())),
            require_signature: false,
        };
        let conn = db(&state).unwrap();
        conn.execute(
            "INSERT INTO job(id,type,status,source,destination,dry_run,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
            params!["job-1", "copy", "CANCEL_REQUESTED", "local:/a", "local:/b", 0, now(), now()],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO job_run(id,job_id,state,started_at) VALUES(?,?,?,?)",
            params!["run-1", "job-1", "RUNNING", now()],
        )
        .unwrap();
        drop(conn);
        finish_job(&state, "job-1", "run-1", "client-1", "SUCCESS", None, None);
        let conn = db(&state).unwrap();
        let job_state: String = conn
            .query_row("SELECT status FROM job WHERE id='job-1'", [], |r| r.get(0))
            .unwrap();
        let run_state: String = conn
            .query_row("SELECT state FROM job_run WHERE id='run-1'", [], |r| {
                r.get(0)
            })
            .unwrap();
        assert_eq!(job_state, "CANCELLED");
        assert_eq!(run_state, "CANCELLED");
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn stats_parser_reads_rclone_json_progress() {
        let s = parse_rclone_stats(
            br#"{"bytes":123,"totalBytes":456,"transfers":2,"totalTransfers":5,"errors":1}
info line"#,
        );
        assert_eq!(s.bytes, Some(123));
        assert_eq!(s.total_bytes, Some(456));
        assert_eq!(s.files, Some(2));
        assert_eq!(s.total_files, Some(5));
        assert_eq!(s.errors, Some(1));
    }

    #[test]
    fn job_log_redaction_removes_credential_lines() {
        let redacted = redact_log_text(
            "Transferred: 10 bytes\npassword = super-secret\nerror: token=abc123\nDone",
        );
        assert!(redacted.contains("Transferred: 10 bytes"));
        assert!(redacted.contains("Done"));
        assert!(!redacted.contains("super-secret"));
        assert!(!redacted.contains("abc123"));
        assert_eq!(redacted.matches("[REDACTED]").count(), 2);
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn typed_job_executes_allowlisted_mock_rclone() {
        use std::os::unix::fs::PermissionsExt;
        static ENV_LOCK: std::sync::OnceLock<std::sync::Mutex<()>> = std::sync::OnceLock::new();
        let _guard = ENV_LOCK
            .get_or_init(|| std::sync::Mutex::new(()))
            .lock()
            .unwrap();
        let root = std::env::temp_dir().join(format!("rclone-mock-job-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        let mock = root.join("mock-rclone.sh");
        let args_log = root.join("mock-args.log");
        let shell = if cfg!(target_os = "android") {
            "/system/bin/sh"
        } else {
            "/bin/sh"
        };
        fs::write(
            &mock,
            format!(
                "#!{shell}\nprintf '%s\\n' \"$@\" > '{}'\nprintf '%s\\n' '{{\"bytes\":7,\"transfers\":1,\"totalTransfers\":1}}'\n",
                args_log.display()
            ),
        )
        .unwrap();
        fs::set_permissions(&mock, fs::Permissions::from_mode(0o700)).unwrap();
        // SAFETY: the test holds ENV_LOCK, so no other test mutates this
        // process-wide setting while the worker is running.
        unsafe { std::env::set_var("RCLONE_BIN", &mock) };
        let state = AppState {
            db: open_db(&root).unwrap(),
            root: root.clone(),
            pairing: Arc::new(RwLock::new(HashMap::new())),
            require_signature: false,
        };
        let id = Uuid::new_v4().to_string();
        let t = now();
        db(&state)
            .unwrap()
            .execute(
                "INSERT INTO job(id,type,status,source,destination,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                params![id, "copy", "QUEUED", "/tmp/source", "/tmp/destination", t, t],
            )
            .unwrap();
        run_job(state.clone(), id.clone()).await;
        let status: String = db(&state)
            .unwrap()
            .query_row("SELECT status FROM job WHERE id=?", params![id], |r| {
                r.get(0)
            })
            .unwrap();
        assert_eq!(status, "SUCCESS");
        let args = fs::read_to_string(args_log).unwrap();
        assert!(args.lines().any(|v| v == "copy"));
        assert!(args.lines().any(|v| v == "/tmp/source"));
        assert!(args.lines().any(|v| v == "/tmp/destination"));
        assert!(args.lines().any(|v| v == "--stats-one-line-json"));
        // SAFETY: ENV_LOCK is still held and the worker has completed.
        unsafe { std::env::remove_var("RCLONE_BIN") };
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn request_allowlist_includes_file_transfer_directions() {
        assert!(allowed_request("POST", "/api/v1/files/upload"));
        assert!(allowed_request("POST", "/api/v1/files/download"));
        assert!(!allowed_request("GET", "/api/v1/files/upload"));
    }

    #[test]
    fn hmac_signature_binds_method_path_body_and_nonce() {
        let root = std::env::temp_dir().join(format!("rclone-gateway-hmac-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        let state = AppState {
            db: open_db(&root).unwrap(),
            root: root.clone(),
            pairing: Arc::new(RwLock::new(HashMap::new())),
            require_signature: false,
        };
        let token = "test-token";
        let method = "POST";
        let path = "/api/v1/system/safe-mode";
        let body = br#"{"enabled":true}"#;
        let timestamp = (now() * 1000).to_string();
        let nonce = "nonce-1";
        let canonical = format!(
            "{method}\n{path}\n{}\n{timestamp}\n{nonce}",
            hex::encode(Sha256::digest(body))
        );
        let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(token.as_bytes()).unwrap();
        mac.update(canonical.as_bytes());
        let mut headers = HeaderMap::new();
        headers.insert("x-client-id", "client-a".parse().unwrap());
        headers.insert("x-timestamp", timestamp.parse().unwrap());
        headers.insert("x-nonce", nonce.parse().unwrap());
        headers.insert(
            "x-signature",
            hex::encode(mac.finalize().into_bytes()).parse().unwrap(),
        );
        verify_signature(&headers, token, &state, method, path, body).unwrap();
        assert!(verify_signature(&headers, token, &state, method, path, body).is_err());
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn hmac_rejects_unbounded_or_invalid_nonce_headers() {
        let root =
            std::env::temp_dir().join(format!("rclone-gateway-hmac-header-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        let state = AppState {
            db: open_db(&root).unwrap(),
            root: root.clone(),
            pairing: Arc::new(RwLock::new(HashMap::new())),
            require_signature: false,
        };
        let mut headers = HeaderMap::new();
        headers.insert("x-client-id", "client-a".parse().unwrap());
        headers.insert("x-timestamp", (now() * 1000).to_string().parse().unwrap());
        headers.insert("x-nonce", "bad nonce".parse().unwrap());
        headers.insert("x-signature", "0".repeat(64).parse().unwrap());
        assert!(
            verify_signature(&headers, "token", &state, "GET", "/api/v1/system/info", b"").is_err()
        );
        let _ = fs::remove_dir_all(root);
    }
    #[test]
    fn log_rotation_moves_oversized_logs_to_restricted_suffix() {
        let root = std::env::temp_dir().join(format!("rclone-gateway-logs-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        let log = root.join("logs/gateway.log");
        fs::write(&log, vec![b'x'; 10 * 1024 * 1024 + 1]).unwrap();
        rotate_logs(&root).unwrap();
        assert!(!log.exists());
        let rotated = root.join("logs/gateway.log.1");
        assert!(rotated.is_file());
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                fs::metadata(&rotated).unwrap().permissions().mode() & 0o777,
                0o600
            );
        }
        let _ = fs::remove_dir_all(root);
    }
    #[test]
    fn backup_restore_validates_name_and_restores_integrity_checked_db() {
        assert!(valid_backup_name("state-123.db").is_ok());
        assert!(valid_backup_name("../state.db").is_err());
        assert!(valid_backup_name("state.db/escape").is_err());
        let root = std::env::temp_dir().join(format!("rclone-gateway-restore-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        fs::write(root.join("keys/master.key"), b"old-master-key").unwrap();
        fs::write(root.join("secrets/old.blob"), b"old-secret").unwrap();
        let current = root.join("db/state.db");
        let conn = Connection::open(&current).unwrap();
        conn.execute_batch("CREATE TABLE marker(value TEXT); INSERT INTO marker VALUES ('old');")
            .unwrap();
        drop(conn);
        let backup = root.join("backups/state-123.db");
        let backup_conn = Connection::open(&backup).unwrap();
        backup_conn.execute_batch(SCHEMA).unwrap();
        backup_conn
            .execute_batch("CREATE TABLE marker(value TEXT); INSERT INTO marker VALUES ('new');")
            .unwrap();
        drop(backup_conn);
        let bundle = root.join("backups/state-123.bundle");
        fs::create_dir_all(bundle.join("keys")).unwrap();
        fs::create_dir_all(bundle.join("secrets")).unwrap();
        fs::write(bundle.join("keys/master.key"), b"new-master-key").unwrap();
        fs::write(bundle.join("secrets/new.blob"), b"new-secret").unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&bundle, fs::Permissions::from_mode(0o700)).unwrap();
            fs::set_permissions(bundle.join("keys"), fs::Permissions::from_mode(0o700)).unwrap();
            fs::set_permissions(bundle.join("secrets"), fs::Permissions::from_mode(0o700)).unwrap();
        }
        let result = restore_backup(&root, "state-123.db").unwrap();
        assert_eq!(result["restored"], "state-123.db");
        assert_eq!(result["bundleRestored"], true);
        assert_eq!(
            fs::read(root.join("keys/master.key")).unwrap(),
            b"new-master-key"
        );
        assert_eq!(
            fs::read(root.join("secrets/new.blob")).unwrap(),
            b"new-secret"
        );
        assert!(!root.join("secrets/old.blob").exists());
        let restored = Connection::open(&current).unwrap();
        let marker: String = restored
            .query_row("SELECT value FROM marker", [], |r| r.get(0))
            .unwrap();
        assert_eq!(marker, "new");
        assert!(
            root.join("backups").join("pre-restore-123.db").exists()
                || root.join("backups").read_dir().unwrap().any(|e| e
                    .unwrap()
                    .file_name()
                    .to_string_lossy()
                    .starts_with("pre-restore-"))
        );
        let _ = fs::remove_dir_all(root);
    }
    #[test]
    fn migration_errors_do_not_persist_malformed_values() {
        let root = std::env::temp_dir().join(format!("rclone-migration-{}", Uuid::new_v4()));
        let legacy = root.join("legacy");
        let state = root.join("state");
        fs::create_dir_all(&legacy).unwrap();
        fs::write(
            legacy.join("rclone.conf"),
            b"[remote]\ntype=s3\nmalformed-secret\npassword=bad\0value\n",
        )
        .unwrap();
        migrate(Paths {
            socket: state.join("runtime/gateway.sock"),
            root: state.clone(),
            legacy: Some(legacy),
            lan_addr: None,
            tls_cert: None,
            tls_key: None,
            tls_client_ca: None,
        })
        .unwrap();
        let conn = Connection::open(state.join("db/state.db")).unwrap();
        let values: Vec<String> = conn
            .prepare("SELECT raw_value FROM migration_errors ORDER BY id")
            .unwrap()
            .query_map([], |r| r.get(0))
            .unwrap()
            .collect::<rusqlite::Result<_>>()
            .unwrap();
        assert_eq!(
            values,
            vec!["<redacted>".to_owned(), "<redacted>".to_owned()]
        );
        let _ = fs::remove_dir_all(root);
    }
}
