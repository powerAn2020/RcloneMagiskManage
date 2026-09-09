use std::path::PathBuf;

#[cfg(unix)]
use std::{
    collections::HashMap,
    fs,
    io::{Read, Write},
    net::SocketAddr,
    os::unix::net::UnixStream,
    path::Path as FsPath,
    sync::Arc,
};
#[cfg(unix)]
use tokio::sync::RwLock;
#[cfg(unix)]
use rustls::{RootCertStore, ServerConfig, server::WebPkiClientVerifier};
#[cfg(unix)]
use rustls_pki_types::{CertificateDer, PrivateKeyDer, pem::PemObject};
#[cfg(unix)]
use tokio::net::{TcpListener, UnixListener};
#[cfg(unix)]
use tokio_rustls::TlsAcceptor;
#[cfg(unix)]
use base64::{Engine, engine::general_purpose::STANDARD as B64};
#[cfg(unix)]
use axum::Router;
#[cfg(unix)]
use crate::api::app_router;
#[cfg(unix)]
use crate::db::{db, ensure_dirs};
#[cfg(unix)]
use crate::engine::scheduler;
#[cfg(unix)]
use crate::security::{allowed_request, hash, master_key};
#[cfg(unix)]
use crate::state::{AppState, DEFAULT_SOCKET, SCHEMA};

use rusqlite::params;
use crate::db::backup::restore_backup;
use crate::db::open_db;
use crate::engine::{process_kill_command, remove_mount_config, unmount_derived_bind};
use crate::error::{GatewayError, Result};
use crate::security::now;
use crate::state::{DEFAULT_ROOT, Paths};

#[cfg(unix)]
pub async fn load_tls_config(
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
    config.alpn_protocols = vec![b"http/1.1".to_vec()];
    Ok(Arc::new(config))
}

#[cfg(unix)]
pub async fn run_lan(addr: SocketAddr, config: Arc<ServerConfig>, router: Router) -> Result<()> {
    let listener = TcpListener::bind(addr).await?;
    let acceptor = TlsAcceptor::from(config);
    loop {
        let (stream, peer_addr) = listener.accept().await?;
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
            let peer_ip = peer_addr.ip().to_string();
            let conn_router = router.layer(axum::middleware::from_fn(
                move |mut req: axum::extract::Request, next: axum::middleware::Next| {
                    let peer_ip = peer_ip.clone();
                    async move {
                        if let Ok(val) = axum::http::HeaderValue::from_str(&peer_ip) {
                            req.headers_mut().insert("x-gateway-peer-ip", val);
                        }
                        next.run(req).await
                    }
                },
            ));
            let service = hyper_util::service::TowerToHyperService::new(conn_router.into_service());
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
pub async fn serve(p: Paths) -> Result<()> {
    ensure_dirs(&p.root)?;
    let database = open_db(&p.root)?;
    let _ = master_key(&p.root)?;
    db(&AppState {
        db: database.clone(),
        root: p.root.clone(),
        pairing: Arc::new(RwLock::new(HashMap::new())),
        pairing_failures: Arc::new(RwLock::new(HashMap::new())),
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
    unsafe {
        unsafe extern "C" {
            fn umask(mask: u32) -> u32;
        }
        umask(0o177);
    }
    let l = UnixListener::bind(&p.socket)?;
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&p.socket, fs::Permissions::from_mode(0o600))?;
    }
    let s = AppState {
        db: database.clone(),
        root: p.root,
        pairing: Arc::new(RwLock::new(HashMap::new())),
        pairing_failures: Arc::new(RwLock::new(HashMap::new())),
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
        let lan_app = app_router(lan_state);
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
    axum::serve(l, app_router(s))
        .with_graceful_shutdown(async {
            let _ = tokio::signal::ctrl_c().await;
        })
        .await
        .map_err(|e| GatewayError::Message(e.to_string()))?;
    Ok(())
}

#[cfg(not(unix))]
pub async fn serve(_p: Paths) -> Result<()> {
    Err(GatewayError::Message(
        "serve requires a Unix target; build for x86_64-linux-android".into(),
    ))
}

#[cfg(unix)]
pub fn request_cli(args: &[String]) -> Result<()> {
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
    let content_type = if !body.is_empty() {
        "Content-Type: application/json\r\n"
    } else {
        ""
    };
    let head = format!(
        "{method} {path} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n{auth}{content_type}Content-Length: {}\r\n\r\n",
        body.len()
    );
    stream.write_all(head.as_bytes())?;
    stream.write_all(&body)?;
    let _ = stream.set_read_timeout(Some(std::time::Duration::from_secs(35)));
    let mut response = Vec::new();
    let mut buf = [0u8; 4096];
    let mut expected_total_len: Option<usize> = None;
    loop {
        match stream.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => {
                response.extend_from_slice(&buf[..n]);
                if expected_total_len.is_none() {
                    if let Some(split) = response.windows(4).position(|v| v == b"\r\n\r\n").map(|v| v + 4) {
                        let status = response
                            .split(|b| *b == b'\n')
                            .next()
                            .and_then(|line| line.split(|b| *b == b' ').nth(1))
                            .and_then(|v| std::str::from_utf8(v).ok())
                            .and_then(|v| v.parse::<u16>().ok())
                            .unwrap_or(200);
                        if status == 204 || status == 304 {
                            expected_total_len = Some(split);
                        } else {
                            let header_str = String::from_utf8_lossy(&response[..split]);
                            for line in header_str.lines() {
                                if let Some(val) = line.to_ascii_lowercase().strip_prefix("content-length:") {
                                    if let Ok(cl) = val.trim().parse::<usize>() {
                                        expected_total_len = Some(split + cl);
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
                if let Some(target) = expected_total_len {
                    if response.len() >= target {
                        break;
                    }
                }
            }
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock || e.kind() == std::io::ErrorKind::TimedOut => {
                if let Some(target) = expected_total_len {
                    if response.len() >= target {
                        break;
                    }
                }
                return Err(GatewayError::Message("gateway request timed out".into()));
            }
            Err(e) => return Err(GatewayError::Io(e)),
        }
    }
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
pub fn request_cli(_args: &[String]) -> Result<()> {
    Err(GatewayError::Message(
        "request requires a Unix target".into(),
    ))
}

#[cfg(unix)]
pub fn probe_cli(args: &[String]) -> Result<()> {
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
pub fn probe_cli(_args: &[String]) -> Result<()> {
    Err(GatewayError::Message("probe requires a Unix target".into()))
}

pub fn restore_cli(args: &[String]) -> Result<()> {
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

pub fn stop_cli(args: &[String]) -> Result<()> {
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
