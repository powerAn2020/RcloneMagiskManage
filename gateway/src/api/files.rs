use axum::{
    Json,
    extract::{Query, State},
    http::{HeaderMap, StatusCode},
};
use base64::{Engine, engine::general_purpose::STANDARD as B64};
use rusqlite::params;
use serde::Deserialize;
use std::fs;

use crate::db::db;
use crate::engine::{create_job_row, materialize_rclone_config, rclone_command, remote_target, validate_transfer_endpoint};
use crate::error::{GatewayError, Result};
use crate::security::{acl, audit_path, consume_confirmation, now, safe_mode_enabled, scope, valid_path};
use crate::state::AppState;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FileQ {
    pub remote_id: String,
    pub path: Option<String>,
    pub page_size: Option<u32>,
}

pub async fn files(
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
            "--max-depth",
            "1",
        ])
        .output()
        .await?;
    if let Some(p) = config {
        let _ = fs::remove_file(p);
    }
    if !o.status.success() {
        return Err(GatewayError::Message("rclone listing failed".into()));
    }
    let mut v: serde_json::Value = serde_json::from_slice(&o.stdout)
        .map_err(|_| GatewayError::Message("invalid rclone response".into()))?;
    if let Some(arr) = v.as_array_mut() {
        let limit = page_size as usize;
        if arr.len() > limit {
            arr.truncate(limit);
        }
    }
    Ok(Json(
        serde_json::json!({"remoteId":q.remote_id,"path":path,"items":v}),
    ))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PathOp {
    pub remote_id: String,
    pub path: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeleteIn {
    pub remote_id: String,
    pub path: String,
    pub dry_run: Option<bool>,
    pub confirmation_token: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransferIn {
    pub source: String,
    pub destination: String,
    pub overwrite: Option<bool>,
}

pub async fn files_mkdir(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(i): Json<PathOp>,
) -> Result<Json<serde_json::Value>> {
    if safe_mode_enabled(&s) {
        return Err(GatewayError::Message(
            "SAFE_MODE_ENABLED: write operations are blocked in safe mode".into(),
        ));
    }
    let c = scope(&h, &s, "file.write")?;
    let (name, base) = remote_target(&s, &i.remote_id)?;
    let path = valid_path(&i.path, &base)?;
    acl(&s, &c, &i.remote_id, "file.write", &path)?;
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

pub async fn files_delete(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(i): Json<DeleteIn>,
) -> Result<(StatusCode, Json<serde_json::Value>)> {
    if safe_mode_enabled(&s) {
        return Err(GatewayError::Message(
            "SAFE_MODE_ENABLED: write operations are blocked in safe mode".into(),
        ));
    }
    let c = scope(&h, &s, "file.delete")?;
    acl(&s, &c, &i.remote_id, "file.delete", &i.path)?;
    if i.dry_run.unwrap_or(true) {
        let (name, base) = remote_target(&s, &i.remote_id)?;
        let path = valid_path(&i.path, &base)?;
        if path == "/" || path == base {
            return Err(GatewayError::Message("cannot delete root path".into()));
        }
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
    let _ = rclone_command(&config)
        .args(["rmdir", &format!("{name}:{path}")])
        .output()
        .await;
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

pub async fn files_transfer_kind(
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

pub async fn files_copy(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(i): Json<TransferIn>,
) -> Result<(StatusCode, Json<serde_json::Value>)> {
    if safe_mode_enabled(&s) {
        return Err(GatewayError::Message(
            "SAFE_MODE_ENABLED: write operations are blocked in safe mode".into(),
        ));
    }
    files_transfer_kind(State(s), h, Json(i), "copy").await
}

pub async fn files_move(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(i): Json<TransferIn>,
) -> Result<(StatusCode, Json<serde_json::Value>)> {
    if safe_mode_enabled(&s) {
        return Err(GatewayError::Message(
            "SAFE_MODE_ENABLED: write operations are blocked in safe mode".into(),
        ));
    }
    files_transfer_kind(State(s), h, Json(i), "move").await
}

pub async fn files_upload(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(i): Json<TransferIn>,
) -> Result<(StatusCode, Json<serde_json::Value>)> {
    if safe_mode_enabled(&s) {
        return Err(GatewayError::Message(
            "SAFE_MODE_ENABLED: write operations are blocked in safe mode".into(),
        ));
    }
    if i.source.contains(':') || !i.destination.contains(':') {
        return Err(GatewayError::Message(
            "upload requires local source and remote destination".into(),
        ));
    }
    files_transfer_kind(State(s), h, Json(i), "copy").await
}

pub async fn files_download(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(mut i): Json<TransferIn>,
) -> Result<(StatusCode, Json<serde_json::Value>)> {
    if !i.source.contains(':') || i.destination.contains(':') {
        return Err(GatewayError::Message(
            "download requires remote source and local destination".into(),
        ));
    }
    // If source is a single file and destination ends with that file name,
    // normalize destination to its parent directory because `rclone copy`
    // interprets the destination argument as a directory.
    let dest_clean = i.destination.trim_end_matches('/');
    if let Some(src_filename) = i.source.rsplit(['/', ':']).next().filter(|s| !s.is_empty()) {
        let suffix = format!("/{src_filename}");
        if dest_clean.ends_with(&suffix) {
            let trimmed = dest_clean.strip_suffix(&suffix).unwrap_or(dest_clean);
            i.destination = if trimmed.is_empty() { "/".to_string() } else { trimmed.to_string() };
        }
    }
    files_transfer_kind(State(s), h, Json(i), "copy").await
}
