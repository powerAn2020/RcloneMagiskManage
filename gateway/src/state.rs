use crate::error::{GatewayError, Result};
use rusqlite::Connection;
use std::{
    collections::HashMap,
    net::SocketAddr,
    path::PathBuf,
    sync::{Arc, Mutex},
    time::Instant,
};
use tokio::sync::RwLock;

pub const API_VERSION: &str = "1.1.0";
pub const DEFAULT_SOCKET: &str = "/data/adb/rclone-manage/runtime/gateway.sock";
pub const DEFAULT_ROOT: &str = "/data/adb/rclone-manage";
pub const SCHEMA: &str = include_str!("../../schema-v1.sql");

pub type Db = Arc<Mutex<Connection>>;

#[derive(Clone)]
pub struct AppState {
    pub db: Db,
    pub root: PathBuf,
    pub pairing: Arc<RwLock<HashMap<String, i64>>>,
    /// Track consecutive failed pairing attempts and lockout expiry epoch (failures, locked_until).
    pub pairing_failures: Arc<RwLock<(u32, i64)>>,
    /// LAN routers require HMAC request signing after pairing. Unix clients
    /// retain the local bearer-only flow because socket permissions provide
    /// the transport boundary there.
    pub require_signature: bool,
}

// Handlers execute inside this task-local request scope. Audit writes can then
// capture real wall-clock latency without putting a request ID or plaintext
// payload into the database. Background scheduler work falls back to zero
// because it has no inbound request boundary.
tokio::task_local! {
    pub static REQUEST_STARTED: Instant;
}

#[derive(Clone)]
pub struct Paths {
    pub socket: PathBuf,
    pub root: PathBuf,
    pub legacy: Option<PathBuf>,
    pub lan_addr: Option<SocketAddr>,
    pub tls_cert: Option<PathBuf>,
    pub tls_key: Option<PathBuf>,
    pub tls_client_ca: Option<PathBuf>,
}

pub fn parse_paths(a: &[String]) -> Result<Paths> {
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

pub use parse_paths as paths;

