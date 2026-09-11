use axum::{
    Json,
    extract::State,
    http::{HeaderMap, StatusCode},
};
use rusqlite::{OptionalExtension, params};
use sha2::{Digest, Sha256};
use std::fs;
use uuid::Uuid;

use crate::db::backup::copy_private_files;
use crate::db::db;
use crate::engine::mount::{remove_mount_config, unmount_derived_bind};
use crate::engine::rclone::rclone_command;
use crate::engine::scheduler::process_kill_command;
use crate::error::{GatewayError, Result};
use crate::security::auth::{audit, current_uid, scope};
use crate::security::crypto::{now, restrict_file};
use crate::state::{API_VERSION, AppState};
use crate::types::{Health, Info};

pub async fn health() -> Json<Health> {
    Json(Health {
        status: "ok",
        auth: "configured",
    })
}

pub async fn safe_mode_get(State(s): State<AppState>, h: HeaderMap) -> Result<Json<serde_json::Value>> {
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

pub async fn safe_mode_set(
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
        let job_pids: Vec<i64> = {
            let conn = db(&s)?;
            let mut st =
                conn.prepare("SELECT pid FROM job_run WHERE state='RUNNING' AND pid IS NOT NULL")?;
            st.query_map([], |r| r.get(0))?
                .collect::<rusqlite::Result<Vec<_>>>()?
        };
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

pub async fn system_settings_get(
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

pub async fn system_settings_set(
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

pub async fn migration_status(
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
    let already_migrated = applied
        .iter()
        .any(|v| v.get("version").and_then(|x| x.as_i64()) == Some(2));
    let errors_count: i64 = c
        .query_row("SELECT COUNT(*) FROM migration_errors", [], |r| r.get(0))
        .unwrap_or(0);

    let candidates = [
        "/data/adb/modules/rclone",
        "/data/adb/rclone",
        "/data/adb/modules_update/rclone",
    ];
    let detected_path = candidates.iter().find(|p| {
        let path = std::path::Path::new(p);
        path.join("rclone.conf").is_file()
            || path.join("sync").is_file()
            || path.join("copy").is_file()
    }).map(|p| p.to_string());

    let errors: Vec<serde_json::Value> = c
        .prepare("SELECT id, migration_version, source_file, line_number, message, created_at FROM migration_errors ORDER BY id DESC LIMIT 20")?
        .query_map([], |r| {
            Ok(serde_json::json!({
                "id": r.get::<_, i64>(0)?,
                "version": r.get::<_, i64>(1)?,
                "file": r.get::<_, String>(2)?,
                "line": r.get::<_, i64>(3)?,
                "message": r.get::<_, String>(4)?,
                "createdAt": r.get::<_, i64>(5)?
            }))
        })?
        .collect::<rusqlite::Result<_>>()?;

    Ok(Json(serde_json::json!({
        "applied": applied,
        "alreadyMigrated": already_migrated,
        "errorCount": errors_count,
        "detectedLegacyPath": detected_path,
        "errors": errors
    })))
}

pub async fn migration_run(
    State(s): State<AppState>,
    h: HeaderMap,
    body: Option<Json<serde_json::Value>>,
) -> Result<Json<serde_json::Value>> {
    let client = scope(&h, &s, "security.write")?;
    let legacy_path_str = body
        .and_then(|Json(b)| b.get("legacyPath").and_then(|v| v.as_str()).map(str::to_string))
        .or_else(|| {
            let candidates = [
                "/data/adb/modules/rclone",
                "/data/adb/rclone",
                "/data/adb/modules_update/rclone",
            ];
            candidates
                .iter()
                .find(|p| {
                    let path = std::path::Path::new(p);
                    path.join("rclone.conf").is_file()
                        || path.join("sync").is_file()
                        || path.join("copy").is_file()
                })
                .map(|p| p.to_string())
        })
        .ok_or_else(|| {
            GatewayError::Message("未指定且未自动检测到历史配置目录 (例如 /data/adb/modules/rclone)".into())
        })?;

    let legacy_buf = std::path::PathBuf::from(&legacy_path_str);
    if !legacy_buf.is_dir() {
        return Err(GatewayError::Message(format!(
            "历史配置目录不存在: {legacy_path_str}"
        )));
    }

    let p = crate::state::Paths {
        socket: s.root.join("runtime/gateway.sock"),
        root: s.root.clone(),
        legacy: Some(legacy_buf.clone()),
        lan_addr: None,
        tls_cert: None,
        tls_key: None,
        tls_client_ca: None,
    };

    {
        let conn = db(&s)?;
        conn.execute("DELETE FROM migration_history WHERE version=2", [])?;
    }
    crate::db::migrate(p)?;

    audit(
        &s,
        Some(&client),
        "system.migration.run",
        None,
        None,
        "SUCCESS",
        Some(&legacy_path_str),
    )?;

    let conn = db(&s)?;
    let error_count: i64 = conn
        .query_row("SELECT COUNT(*) FROM migration_errors", [], |r| r.get(0))
        .unwrap_or(0);
    let remotes_count: i64 = conn
        .query_row("SELECT COUNT(*) FROM remote", [], |r| r.get(0))
        .unwrap_or(0);
    let jobs_count: i64 = conn
        .query_row("SELECT COUNT(*) FROM job", [], |r| r.get(0))
        .unwrap_or(0);

    Ok(Json(serde_json::json!({
        "status": "success",
        "legacyPath": legacy_path_str,
        "remotesCount": remotes_count,
        "jobsCount": jobs_count,
        "errorCount": error_count
    })))
}

pub async fn backup_create(
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

pub async fn backup_restore(
    State(s): State<AppState>,
    axum::extract::Path(name): axum::extract::Path<String>,
    h: HeaderMap,
) -> Result<Json<serde_json::Value>> {
    let client = scope(&h, &s, "security.write")?;
    {
        let mut guard = s
            .db
            .lock()
            .map_err(|_| GatewayError::Message("database lock poisoned".into()))?;
        let _ = guard.execute_batch("PRAGMA wal_checkpoint(TRUNCATE);");
        *guard = rusqlite::Connection::open_in_memory()?;
    }
    let res = crate::db::backup::restore_backup(&s.root, &name);
    {
        let new_conn = rusqlite::Connection::open(s.root.join("db/state.db"))?;
        let _ = new_conn.execute_batch("PRAGMA journal_mode=WAL;");
        let mut guard = s
            .db
            .lock()
            .map_err(|_| GatewayError::Message("database lock poisoned".into()))?;
        *guard = new_conn;
    }
    let res = res?;
    audit(
        &s,
        Some(&client),
        "system.backup.restore",
        None,
        None,
        "SUCCESS",
        Some(&name),
    )?;
    Ok(Json(serde_json::json!({
        "status": "success",
        "restored": name,
        "detail": res
    })))
}

pub async fn backup_delete(
    State(s): State<AppState>,
    axum::extract::Path(name): axum::extract::Path<String>,
    h: HeaderMap,
) -> Result<Json<serde_json::Value>> {
    let client = scope(&h, &s, "security.write")?;
    crate::db::backup::valid_backup_name(&name)?;
    let backup_path = s.root.join("backups").join(&name);
    if !backup_path.is_file() {
        return Err(GatewayError::Message("备份文件不存在".into()));
    }
    fs::remove_file(&backup_path)?;
    let stem = name.trim_end_matches(".db");
    let bundle_path = s.root.join("backups").join(format!("{stem}.bundle"));
    if bundle_path.is_dir() {
        let _ = fs::remove_dir_all(&bundle_path);
    }
    audit(
        &s,
        Some(&client),
        "system.backup.delete",
        None,
        None,
        "SUCCESS",
        Some(&name),
    )?;
    Ok(Json(serde_json::json!({
        "status": "success",
        "deleted": name
    })))
}

pub async fn backups_list(
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
            "modified": meta.modified().ok().and_then(|v| v.duration_since(std::time::UNIX_EPOCH).ok()).map(|v| v.as_secs())
        }));
    }
    Ok(Json(out))
}

pub async fn info(State(s): State<AppState>, h: HeaderMap) -> Result<Json<Info>> {
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
        root: current_uid() == 0,
        lan_enabled,
        mtls_required,
    }))
}

pub async fn logs_clear(
    State(s): State<AppState>,
    h: HeaderMap,
) -> Result<Json<serde_json::Value>> {
    let client = scope(&h, &s, "security.write")?;
    let dir = s.root.join("logs");
    let mut files_cleared = 0;
    let mut bytes_freed: u64 = 0;
    if let Ok(entries) = fs::read_dir(&dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            let name = path
                .file_name()
                .and_then(|x| x.to_str())
                .unwrap_or_default();
            let is_log = path.extension().and_then(|x| x.to_str()) == Some("log")
                || name.contains(".log");
            if is_log {
                if let Ok(meta) = fs::metadata(&path) {
                    bytes_freed = bytes_freed.saturating_add(meta.len());
                }
                if name == "gateway.log" {
                    let _ = fs::OpenOptions::new()
                        .write(true)
                        .truncate(true)
                        .open(&path);
                } else {
                    let _ = fs::remove_file(&path);
                }
                files_cleared += 1;
            }
        }
    }
    let audit_cleared = if crate::security::auth::has_scope(&h, &s, "admin.*") {
        let conn = db(&s)?;
        conn.execute("DELETE FROM audit_log", [])?
    } else {
        0
    };
    audit(
        &s,
        Some(&client),
        "system.logs.clear",
        None,
        None,
        "SUCCESS",
        None,
    )?;
    Ok(Json(serde_json::json!({
        "status": "ok",
        "filesCleared": files_cleared,
        "bytesFreed": bytes_freed,
        "auditRecordsCleared": audit_cleared
    })))
}

#[derive(serde::Deserialize)]
pub struct LogsCoreQuery {
    pub lines: Option<usize>,
}

pub async fn logs_core(
    State(s): State<AppState>,
    h: HeaderMap,
    axum::extract::Query(q): axum::extract::Query<LogsCoreQuery>,
) -> Result<Json<serde_json::Value>> {
    scope(&h, &s, "system.read")?;
    let log_path = s.root.join("logs/gateway.log");
    let lines_limit = q.lines.unwrap_or(500).clamp(1, 5000);
    let (content, total_lines, size_bytes) = if log_path.exists() {
        let size = fs::metadata(&log_path).map(|m| m.len()).unwrap_or(0);
        let file = fs::File::open(&log_path)?;
        let reader = std::io::BufReader::new(file);
        use std::io::BufRead;
        let all_lines: Vec<String> = reader.lines().filter_map(|l| l.ok()).collect();
        let total = all_lines.len();
        let start = total.saturating_sub(lines_limit);
        let slice = &all_lines[start..];
        (slice.join("\n"), total, size)
    } else {
        (String::new(), 0, 0)
    };
    Ok(Json(serde_json::json!({
        "status": "ok",
        "path": log_path.display().to_string(),
        "totalLines": total_lines,
        "returnedLines": lines_limit,
        "sizeBytes": size_bytes,
        "content": content
    })))
}

