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
use crate::engine::rclone::{
    TempConfig, clean_error_message, materialize_adhoc_remote_config, materialize_rclone_config,
    rclone_command, redact_log_text,
};
use crate::error::{GatewayError, Result};
use crate::security::auth::{acl, audit, consume_confirmation, scope};
use crate::security::crypto::{encrypt_secret, ini_line_safe, now, valid_path, validate_identity, validate_secret_object};
use crate::state::AppState;
use crate::types::{Remote, RemoteDeleteIn, RemoteIn};

pub async fn remotes(State(s): State<AppState>, h: HeaderMap) -> Result<Json<Vec<Remote>>> {
    let c = scope(&h, &s, "remote.read")?;
    let db = db(&s)?;
    let mut st = db.prepare("SELECT r.id,r.name,r.type,r.endpoint,r.enabled,r.secret_ref FROM remote r JOIN remote_acl a ON a.remote_id=r.id WHERE a.client_id=? GROUP BY r.id")?;
    let remotes_data: Vec<(String, String, String, Option<String>, bool, Option<String>)> = st
        .query_map(params![c], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, Option<String>>(3)?,
                r.get::<_, i64>(4)? != 0,
                r.get::<_, Option<String>>(5)?,
            ))
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;

    let mut out = Vec::with_capacity(remotes_data.len());
    for (id, name, remote_type, endpoint, enabled, secret_ref) in remotes_data {
        let mut options_map = serde_json::Map::new();
        let mut configured_secrets = Vec::new();
        if let Some(ref sr) = secret_ref {
            if let Ok(secret) = crate::security::crypto::decrypt_secret(&s.root, sr, &id) {
                if let Some(obj) = secret.as_object() {
                    for (k, v) in obj {
                        if crate::security::crypto::is_rclone_password_key(k) {
                            configured_secrets.push(k.clone());
                        } else {
                            options_map.insert(k.clone(), v.clone());
                        }
                    }
                }
            }
        }
        out.push(Remote {
            id,
            name,
            remote_type,
            endpoint,
            enabled,
            secret_ref,
            options: Some(serde_json::Value::Object(options_map)),
            configured_secrets: Some(configured_secrets),
        });
    }
    Ok(Json(out))
}

pub async fn remote_providers(
    State(s): State<AppState>,
    h: HeaderMap,
) -> Result<Json<serde_json::Value>> {
    let _ = scope(&h, &s, "remote.read")?;
    let providers = crate::engine::rclone::get_rclone_providers().await?;
    Ok(Json(providers))
}

pub async fn remote_import(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(i): Json<crate::types::RemoteImportIn>,
) -> Result<(StatusCode, Json<Vec<Remote>>)> {
    let c = scope(&h, &s, "remote.write")?;
    let mut imported = Vec::new();

    let mut current_name: Option<String> = None;
    let mut current_map: std::collections::HashMap<String, String> = std::collections::HashMap::new();
    let mut sections = Vec::new();

    for line in i.config.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') || trimmed.starts_with(';') {
            continue;
        }
        if trimmed.starts_with('[') && trimmed.ends_with(']') {
            if let Some(name) = current_name.take() {
                sections.push((name, std::mem::take(&mut current_map)));
            }
            let name = trimmed[1..trimmed.len() - 1].trim().to_string();
            current_name = Some(name);
        } else if let Some((k, v)) = trimmed.split_once('=') {
            let key = k.trim().to_lowercase();
            let val = v.trim().to_string();
            current_map.insert(key, val);
        }
    }
    if let Some(name) = current_name {
        sections.push((name, current_map));
    }

    if sections.is_empty() {
        return Err(GatewayError::Message("no valid remote configuration found in input".into()));
    }

    for (name, mut map) in sections {
        if validate_identity(&name, "remote name", 128).is_err() {
            continue;
        }
        let remote_type = match map.remove("type") {
            Some(t) if !t.is_empty() && t.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-') => t,
            _ => continue,
        };
        let endpoint = map.remove("url").or_else(|| map.remove("endpoint"));
        let base_path = map.remove("base_path").unwrap_or_else(|| "/".into());

        let secret_val = if !map.is_empty() {
            let obj: serde_json::Map<String, serde_json::Value> = map
                .into_iter()
                .map(|(k, v)| (k, serde_json::Value::String(v)))
                .collect();
            Some(serde_json::Value::Object(obj))
        } else {
            None
        };

        let id = Uuid::new_v4().to_string();
        let sr = secret_val
            .as_ref()
            .map(|v| encrypt_secret(&s.root, &id, v))
            .transpose()?;
        let t = now();

        db(&s)?.execute(
            "INSERT INTO remote(id,name,type,endpoint,base_path,secret_ref,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
            params![id, name, remote_type, endpoint, base_path, sr, 1, t, t],
        )?;
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
            "remote.import",
            Some(&id),
            Some(&id),
            "SUCCESS",
            None,
        )?;
        imported.push(Remote {
            id,
            name,
            remote_type,
            endpoint,
            enabled: true,
            secret_ref: sr,
            options: None,
            configured_secrets: None,
        });
    }

    if imported.is_empty() {
        return Err(GatewayError::Message("failed to import any valid remote".into()));
    }

    Ok((StatusCode::CREATED, Json(imported)))
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
            options: None,
            configured_secrets: None,
        }),
    ))
}

pub async fn remote_delete(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
    body: axum::body::Bytes,
) -> Result<Response> {
    let c = scope(&h, &s, "remote.delete")?;
    acl(&s, &c, &id, "*", "/")?;

    let remote_name: Option<String> = db(&s)?
        .query_row("SELECT name FROM remote WHERE id=?", params![id], |r| r.get(0))
        .optional()?;
    let r_name = remote_name.as_deref().unwrap_or(&id);

    let (mount_count, crypt_count): (i64, i64) = db(&s)?.query_row(
        "SELECT 
            (SELECT COUNT(*) FROM mount_profile WHERE remote_id=? OR remote_id=?),
            (SELECT COUNT(*) FROM crypt_profile WHERE remote_id=? OR remote_id=?)",
        params![id, r_name, id, r_name],
        |r| Ok((r.get(0)?, r.get(1)?)),
    )?;

    if mount_count > 0 {
        return Err(GatewayError::Message(
            "无法删除：该远端存在关联的挂载配置，请先停止并删除相关挂载配置后再试".into(),
        ));
    }
    if crypt_count > 0 {
        return Err(GatewayError::Message(
            "无法删除：该远端存在关联的加密档案，请先删除相关加密档案后再试".into(),
        ));
    }

    let token = if body.is_empty() {
        None
    } else {
        serde_json::from_slice::<RemoteDeleteIn>(&body)
            .ok()
            .and_then(|v| v.confirmation_token)
    };
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

    let mut options_map = serde_json::Map::new();
    let mut configured_secrets = Vec::new();
    if let Some(ref sr) = secret_ref {
        if let Ok(secret) = crate::security::crypto::decrypt_secret(&s.root, sr, &id) {
            if let Some(obj) = secret.as_object() {
                for (k, v) in obj {
                    if crate::security::crypto::is_rclone_password_key(k) {
                        configured_secrets.push(k.clone());
                    } else {
                        options_map.insert(k.clone(), v.clone());
                    }
                }
            }
        }
    }

    Ok(Json(Remote {
        id,
        name,
        remote_type,
        endpoint,
        enabled: enabled != 0,
        secret_ref,
        options: Some(serde_json::Value::Object(options_map)),
        configured_secrets: Some(configured_secrets),
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

    let old_secret_ref: Option<String> = db(&s)?
        .query_row(
            "SELECT secret_ref FROM remote WHERE id=?",
            params![id],
            |r| r.get(0),
        )
        .optional()?
        .flatten();

    // Merge existing secrets so blank password/secret fields retain their previous values
    let mut merged_obj = serde_json::Map::new();
    if let Some(ref old_sr) = old_secret_ref {
        if let Ok(old_sec) = crate::security::crypto::decrypt_secret(&s.root, old_sr, &id) {
            if let Some(old_map) = old_sec.as_object() {
                merged_obj = old_map.clone();
            }
        }
    }

    if let Some(new_map) = i.secret.as_ref().and_then(|v| v.as_object()) {
        for (k, v) in new_map {
            let val_str = v.as_str().unwrap_or("");
            // If user left password blank on update, preserve old password
            if val_str.trim().is_empty() && crate::security::crypto::is_rclone_password_key(k) {
                continue;
            }
            merged_obj.insert(k.clone(), v.clone());
        }
    }

    let secret_val = if !merged_obj.is_empty() {
        Some(serde_json::Value::Object(merged_obj))
    } else {
        None
    };

    let secret_ref = secret_val
        .as_ref()
        .map(|v| encrypt_secret(&s.root, &id, v))
        .transpose()?;

    let base_path = valid_path(i.base_path.as_deref().unwrap_or("/"), "/")?;
    db(&s)?.execute(
        "UPDATE remote SET name=?,type=?,endpoint=?,base_path=?,enabled=?,secret_ref=COALESCE(?,secret_ref),updated_at=? WHERE id=?",
        params![
            i.name,
            i.remote_type,
            i.endpoint,
            base_path,
            i.enabled.unwrap_or(true) as i64,
            secret_ref,
            now(),
            id
        ],
    )?;
    if let Some(ref secret_ref) = secret_ref {
        if let Some(ref old) = old_secret_ref {
            if old != secret_ref {
                let _ = fs::remove_file(s.root.join("secrets").join(format!("{old}.blob")));
                let _ = db(&s)?.execute("DELETE FROM secret_meta WHERE id=?", params![old]);
            }
        }
        db(&s)?.execute(
            "INSERT OR REPLACE INTO secret_meta(id,kind,backend,version,created_at) VALUES(?,?,?,?,?)",
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
    let row: (String, String, Option<String>, String, i64, Option<String>) = db(&s)?
        .query_row(
            "SELECT name,type,endpoint,base_path,enabled,secret_ref FROM remote WHERE id=?",
            params![id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?, r.get(5)?)),
        )
        .map_err(|_| GatewayError::Message("remote not found".into()))?;

    let name = row.0;
    let remote_type = row.1;
    let endpoint = row.2;
    let base_path = row.3;
    let enabled = row.4 != 0;
    let secret_ref = row.5;

    let mut full_ini_lines = Vec::new();
    let mut redacted_ini_lines = Vec::new();
    let mut full_json_map = serde_json::Map::new();
    let mut redacted_json_map = serde_json::Map::new();

    full_json_map.insert("type".to_string(), serde_json::Value::String(remote_type.clone()));
    redacted_json_map.insert("type".to_string(), serde_json::Value::String(remote_type.clone()));

    full_ini_lines.push(format!("[{}]", name));
    full_ini_lines.push(format!("type = {}", remote_type));

    redacted_ini_lines.push(format!("[{}]", name));
    redacted_ini_lines.push(format!("type = {}", remote_type));

    let mut written_keys = std::collections::HashSet::new();

    if let Some(ref sr) = secret_ref {
        if let Ok(secret) = crate::security::crypto::decrypt_secret(&s.root, sr, &id) {
            if let Some(obj) = secret.as_object() {
                for (k, v) in obj {
                    let val_str = match v {
                        serde_json::Value::String(s) => s.clone(),
                        serde_json::Value::Bool(_) | serde_json::Value::Number(_) => v.to_string(),
                        _ => continue,
                    };
                    let trimmed = val_str.trim();
                    if trimmed.is_empty() || trimmed == "[]" || trimmed == "{}" {
                        continue;
                    }
                    written_keys.insert(k.clone());

                    if crate::security::crypto::is_rclone_password_key(k) {
                        let obscured = if crate::security::crypto::is_rclone_obscured(trimmed) {
                            trimmed.to_string()
                        } else {
                            crate::security::crypto::obscure_rclone(trimmed).unwrap_or_else(|_| trimmed.to_string())
                        };
                        full_ini_lines.push(format!("{} = {}", k, obscured));
                        full_json_map.insert(k.clone(), serde_json::Value::String(obscured));

                        redacted_ini_lines.push(format!("{} = ***REDACTED***", k));
                        redacted_json_map.insert(k.clone(), serde_json::Value::String("***REDACTED***".to_string()));
                    } else if crate::security::crypto::is_sensitive_export_key(k) {
                        full_ini_lines.push(format!("{} = {}", k, trimmed));
                        full_json_map.insert(k.clone(), v.clone());

                        redacted_ini_lines.push(format!("{} = ***REDACTED***", k));
                        redacted_json_map.insert(k.clone(), serde_json::Value::String("***REDACTED***".to_string()));
                    } else {
                        full_ini_lines.push(format!("{} = {}", k, trimmed));
                        full_json_map.insert(k.clone(), v.clone());

                        redacted_ini_lines.push(format!("{} = {}", k, trimmed));
                        redacted_json_map.insert(k.clone(), v.clone());
                    }
                }
            }
        }
    }

    if remote_type == "webdav" {
        if !written_keys.contains("url") {
            if let Some(ref e) = endpoint {
                full_ini_lines.push(format!("url = {}", e));
                redacted_ini_lines.push(format!("url = {}", e));
                full_json_map.insert("url".to_string(), serde_json::Value::String(e.clone()));
                redacted_json_map.insert("url".to_string(), serde_json::Value::String(e.clone()));
            }
        }
        if !written_keys.contains("vendor") {
            full_ini_lines.push("vendor = other".to_string());
            redacted_ini_lines.push("vendor = other".to_string());
            full_json_map.insert("vendor".to_string(), serde_json::Value::String("other".to_string()));
            redacted_json_map.insert("vendor".to_string(), serde_json::Value::String("other".to_string()));
        }
    } else if let Some(ref e) = endpoint {
        let key = if remote_type == "s3" { "endpoint" } else { "url" };
        if !written_keys.contains(key) {
            full_ini_lines.push(format!("{} = {}", key, e));
            redacted_ini_lines.push(format!("{} = {}", key, e));
            full_json_map.insert(key.to_string(), serde_json::Value::String(e.clone()));
            redacted_json_map.insert(key.to_string(), serde_json::Value::String(e.clone()));
        }
    }

    let can_export_secrets = crate::security::auth::has_scope(&h, &s, "admin.*");

    let full_ini = full_ini_lines.join("\n") + "\n";
    let redacted_ini = redacted_ini_lines.join("\n") + "\n";

    let (export_ini, export_json, credentials_included) = if can_export_secrets {
        (full_ini, full_json_map, true)
    } else {
        (redacted_ini.clone(), redacted_json_map.clone(), false)
    };

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
        "name": name,
        "type": remote_type,
        "endpoint": endpoint,
        "basePath": base_path,
        "enabled": enabled,
        "credentialsIncluded": credentials_included,
        "ini": export_ini,
        "redactedIni": redacted_ini,
        "json": export_json,
        "redactedJson": redacted_json_map
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
            let redacted = redact_log_text(&msg);
            let display = if redacted.trim() == "[REDACTED]" && !msg.is_empty() {
                clean_error_message(&msg)
            } else {
                redacted
            };
            (false, Some(display))
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

#[derive(Debug, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RemoteTestConfigIn {
    pub name: String,
    #[serde(rename = "type")]
    pub remote_type: String,
    pub endpoint: Option<String>,
    pub secret: Option<serde_json::Value>,
}

pub async fn remote_test_config(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(i): Json<RemoteTestConfigIn>,
) -> Result<Json<serde_json::Value>> {
    let c = scope(&h, &s, "remote.read")?;
    validate_identity(&i.name, "remote name", 128)?;
    let path = materialize_adhoc_remote_config(
        &s,
        &i.name,
        &i.remote_type,
        i.endpoint.as_deref(),
        i.secret.as_ref(),
    )?;
    let config = TempConfig(Some(path));
    let o = rclone_command(&config.0)
        .args([
            "lsd",
            &format!("{}:/", i.name),
            "--max-depth",
            "1",
            "--contimeout",
            "3s",
            "--timeout",
            "5s",
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
            let redacted = redact_log_text(&msg);
            let display = if redacted.trim() == "[REDACTED]" && !msg.is_empty() {
                clean_error_message(&msg)
            } else {
                redacted
            };
            (false, Some(display))
        }
        Err(ref e) => (false, Some(e.to_string())),
    };
    let _ = audit(
        &s,
        Some(&c),
        "remote.test_config",
        None,
        Some(&i.name),
        if ok { "SUCCESS" } else { "FAILED" },
        (!ok).then_some("RCLONE_TEST_FAILED"),
    );
    Ok(Json(serde_json::json!({
        "ok": ok,
        "remote": i.name,
        "error": err_msg
    })))
}

