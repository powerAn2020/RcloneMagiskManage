use axum::{
    Json,
    extract::{Path, State},
    http::{HeaderMap, StatusCode},
};
use base64::{Engine, engine::general_purpose::STANDARD as B64};
use rusqlite::{OptionalExtension, params};
use uuid::Uuid;

use crate::db::db;
use crate::engine::mount::remote_target;
use crate::error::{GatewayError, Result};
use crate::security::auth::{audit, scope};
use crate::security::crypto::{hash, ini_line_safe, now, valid_path, validate_identity};
use crate::state::AppState;
use crate::types::{Client, GrantIn, Pair, PairResult, RemoteAclIn, TokenResult};

pub async fn pair_start(State(s): State<AppState>) -> Result<(StatusCode, Json<serde_json::Value>)> {
    let c = format!("{:06}", rand::random::<u32>() % 1_000_000);
    s.pairing.write().await.insert(c.clone(), now() + 300);
    Ok((
        StatusCode::CREATED,
        Json(serde_json::json!({"pairingCode":c,"expiresIn":300})),
    ))
}

pub async fn pair_complete(
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
    let token_expires = now() + 30 * 24 * 3600;
    c.execute("INSERT INTO client(id,name,package_name,public_key,token_hash,status,created_at,token_expires_at) VALUES(?,?,?,?,?,'ACTIVE',?,?)",params![id,i.client_name,i.package_name,i.public_key,hash(&tok),now(),token_expires])?;
    for initial in [
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
        "admin.*",
        "*",
    ] {
        c.execute(
            "INSERT INTO permission_grant(client_id,scope,resource) VALUES(?,?,?)",
            params![id, initial, "*"],
        )?;
    }
    let ids = {
        let mut remotes = c.prepare("SELECT id FROM remote")?;
        remotes
            .query_map([], |r| r.get::<_, String>(0))?
            .collect::<rusqlite::Result<Vec<_>>>()?
    };
    for rid in ids {
        c.execute("INSERT OR IGNORE INTO remote_acl(client_id,remote_id,permissions,allowed_prefix) VALUES(?,?,?,?)", params![id, rid, "*", "/"])?;
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

pub async fn client_grant(
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

pub async fn client_revoke(
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

pub async fn client_disable(
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

pub async fn client_enable(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<StatusCode> {
    let actor = scope(&h, &s, "security.write")?;
    let changed = db(&s)?.execute("UPDATE client SET status='ACTIVE' WHERE id=?", params![id])?;
    if changed == 0 {
        return Err(GatewayError::Message("client not found".into()));
    }
    audit(
        &s,
        Some(&actor),
        "security.client.enable",
        Some(&id),
        None,
        "SUCCESS",
        None,
    )?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn client_delete(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<StatusCode> {
    let actor = scope(&h, &s, "security.write")?;
    if id == actor {
        return Err(GatewayError::Message(
            "cannot delete current client".into(),
        ));
    }
    {
        let c = db(&s)?;
        c.execute("DELETE FROM permission_grant WHERE client_id=?", params![id])?;
        c.execute("DELETE FROM remote_acl WHERE client_id=?", params![id])?;
        let changed = c.execute("DELETE FROM client WHERE id=?", params![id])?;
        if changed == 0 {
            return Err(GatewayError::Message("client not found".into()));
        }
    }
    audit(
        &s,
        Some(&actor),
        "security.client.delete",
        Some(&id),
        None,
        "SUCCESS",
        None,
    )?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn client_grants(
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

pub async fn client_rotate_token(
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

pub async fn remote_acl_grant(
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

pub async fn clients(State(s): State<AppState>, h: HeaderMap) -> Result<Json<Vec<Client>>> {
    let caller_id = scope(&h, &s, "security.read")?;
    let c = db(&s)?;
    let mut st =
        c.prepare("SELECT id,name,status,created_at,last_seen_at FROM client ORDER BY created_at DESC")?;
    Ok(Json(
        st.query_map([], |r| {
            let id: String = r.get(0)?;
            let is_current = id == caller_id;
            Ok(Client {
                id,
                name: r.get(1)?,
                status: r.get(2)?,
                created_at: r.get(3)?,
                last_seen_at: r.get(4)?,
                is_current,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?,
    ))
}
