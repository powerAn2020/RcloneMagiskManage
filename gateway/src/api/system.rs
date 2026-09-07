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
        api_version: API_VERSION,
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
    let errors: i64 = c.query_row("SELECT COUNT(*) FROM migration_errors", [], |r| r.get(0))?;
    Ok(Json(
        serde_json::json!({"applied": applied, "errorCount": errors}),
    ))
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
