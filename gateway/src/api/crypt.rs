use axum::{
    Json,
    extract::{Path, State},
    http::{HeaderMap, StatusCode},
};
use rusqlite::params;
use std::fs;
use uuid::Uuid;

use crate::db::db;
use crate::engine::{materialize_crypt_config, remote_target};
use crate::error::{GatewayError, Result};
use crate::security::{acl, audit, encrypt_secret, ini_line_safe, now, scope, valid_path, validate_identity};
use crate::state::AppState;
use crate::types::{CryptIn, CryptProfile};

pub fn valid_crypt_name(name: &str, parent_name: &str) -> bool {
    name != parent_name && validate_identity(name, "crypt profile name", 128).is_ok()
}

pub async fn crypts(State(s): State<AppState>, h: HeaderMap) -> Result<Json<Vec<CryptProfile>>> {
    let client = scope(&h, &s, "remote.read")?;
    let conn = db(&s)?;
    let mut st = conn.prepare(
        "SELECT cp.id,cp.name,cp.remote_id,r.name,cp.remote_path,cp.secret_ref,cp.status FROM crypt_profile cp LEFT JOIN remote r ON cp.remote_id=r.id ORDER BY cp.created_at",
    )?;
    let rows = st.query_map([], |r| {
        Ok(CryptProfile {
            id: r.get(0)?,
            name: r.get(1)?,
            remote_id: r.get(2)?,
            remote_name: r.get(3)?,
            remote_path: r.get(4)?,
            password_configured: r.get::<_, Option<String>>(5)?.is_some(),
            status: r.get(6)?,
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

pub async fn crypt_create(
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
    db(&s)?.execute(
        "INSERT INTO crypt_profile(id,name,remote_id,remote_path,secret_ref,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
        params![id, i.name, i.remote_id, path, secret_ref, "READY", now(), now()],
    )?;
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
        "SELECT cp.id,cp.name,cp.remote_id,r.name,cp.remote_path,cp.secret_ref,cp.status FROM crypt_profile cp LEFT JOIN remote r ON cp.remote_id=r.id WHERE cp.id=?",
        params![id],
        |r| {
            Ok(CryptProfile {
                id: r.get(0)?,
                name: r.get(1)?,
                remote_id: r.get(2)?,
                remote_name: r.get(3)?,
                remote_path: r.get(4)?,
                password_configured: r.get::<_, Option<String>>(5)?.is_some(),
                status: r.get(6)?,
            })
        },
    )?;
    Ok((StatusCode::CREATED, Json(p)))
}

pub async fn crypt_test(
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

pub async fn audit_logs(
    State(s): State<AppState>,
    h: HeaderMap,
) -> Result<Json<Vec<serde_json::Value>>> {
    scope(&h, &s, "audit.read")?;
    let c = db(&s)?;
    let mut st = c.prepare(
        "SELECT id,timestamp,client_id,uid,operation,resource,remote_id,path_hash,result,error_code,latency_ms FROM audit_log ORDER BY timestamp DESC LIMIT 500",
    )?;
    Ok(Json(
        st.query_map([], |r| {
            Ok(serde_json::json!({
                "id": r.get::<_, i64>(0)?,
                "timestamp": r.get::<_, i64>(1)?,
                "clientId": r.get::<_, Option<String>>(2)?,
                "uid": r.get::<_, Option<i64>>(3)?,
                "operation": r.get::<_, String>(4)?,
                "resource": r.get::<_, Option<String>>(5)?,
                "remoteId": r.get::<_, Option<String>>(6)?,
                "pathHash": r.get::<_, Option<String>>(7)?,
                "result": r.get::<_, String>(8)?,
                "errorCode": r.get::<_, Option<String>>(9)?,
                "latencyMs": r.get::<_, Option<i64>>(10)?
            }))
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?,
    ))
}
