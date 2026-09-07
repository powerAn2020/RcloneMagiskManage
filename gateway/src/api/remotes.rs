use axum::{
    Json,
    extract::{Path, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
};
use base64::{Engine, engine::general_purpose::STANDARD as B64};
use rusqlite::{OptionalExtension, params};
use std::fs;
use uuid::Uuid;

use crate::db::db;
use crate::engine::rclone::{TempConfig, materialize_rclone_config, rclone_command, redact_log_text};
use crate::error::{GatewayError, Result};
use crate::security::auth::{acl, audit, consume_confirmation, scope};
use crate::security::crypto::{encrypt_secret, ini_line_safe, now, valid_path, validate_identity, validate_secret_object};
use crate::state::AppState;
use crate::types::{Remote, RemoteDeleteIn, RemoteIn};

pub async fn remotes(State(s): State<AppState>, h: HeaderMap) -> Result<Json<Vec<Remote>>> {
    let c = scope(&h, &s, "remote.read")?;
    let db = db(&s)?;
    let mut st = db.prepare("SELECT r.id,r.name,r.type,r.endpoint,r.enabled,r.secret_ref FROM remote r JOIN remote_acl a ON a.remote_id=r.id WHERE a.client_id=? GROUP BY r.id")?;
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

pub async fn remote_create(
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

pub async fn remote_delete(
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

pub async fn remote_get(
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

pub async fn remote_update(
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

pub async fn remote_action(
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

pub async fn remote_export(
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

pub async fn remote_test(
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
    let config = TempConfig(materialize_rclone_config(&s, &[id.clone()])?);
    let o = rclone_command(&config.0)
        .args([
            "lsd",
            &format!("{name}:{base}"),
            "--max-depth",
            "1",
            "--contimeout",
            "5s",
            "--timeout",
            "8s",
            "--retries",
            "1",
            "--low-level-retries",
            "1",
        ])
        .output()
        .await;
    drop(config);
    let (ok, err_msg) = match o {
        Ok(ref output) if output.status.success() => (true, None),
        Ok(ref output) => {
            let err = String::from_utf8_lossy(&output.stderr).trim().to_string();
            let msg = if err.is_empty() {
                String::from_utf8_lossy(&output.stdout).trim().to_string()
            } else {
                err
            };
            (false, Some(redact_log_text(&msg)))
        }
        Err(ref e) => (false, Some(e.to_string())),
    };
    let _ = audit(
        &s,
        Some(&c),
        "remote.test",
        Some(&id),
        Some(&id),
        if ok { "SUCCESS" } else { "FAILED" },
        (!ok).then_some("RCLONE_TEST_FAILED"),
    );
    Ok(Json(serde_json::json!({
        "ok": ok,
        "remote": name,
        "error": err_msg
    })))
}
