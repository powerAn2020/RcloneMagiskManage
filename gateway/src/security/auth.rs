use axum::{
    body::{Body, to_bytes},
    extract::State,
    http::{HeaderMap, Request},
    middleware::Next,
    response::IntoResponse,
};
use hmac::{Hmac, Mac};
use rusqlite::{OptionalExtension, TransactionBehavior, params};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;

use crate::error::{GatewayError, Result};
use crate::security::crypto::{hash, now, valid_path};
use crate::state::{AppState, REQUEST_STARTED};

pub fn header<'a>(h: &'a HeaderMap, name: &str) -> Option<&'a str> {
    h.get(name).and_then(|v| v.to_str().ok())
}

#[cfg(unix)]
pub fn current_uid() -> u32 {
    unsafe {
        unsafe extern "C" {
            fn getuid() -> u32;
        }
        getuid()
    }
}

#[cfg(not(unix))]
pub fn current_uid() -> u32 {
    0
}

pub fn verify_signature(
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
    let mut conn = s.db.lock().map_err(|_| GatewayError::Message("database lock poisoned".into()))?;
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

pub async fn signed_request(
    State(s): State<AppState>,
    request: Request<Body>,
    next: Next,
) -> axum::response::Response {
    let started = std::time::Instant::now();
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

pub fn token_client(h: &HeaderMap, s: &AppState) -> Result<String> {
    let t = h
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .ok_or_else(|| GatewayError::Message("AUTH missing bearer token".into()))?;
    let d = hash(t);
    let c = s.db.lock().map_err(|_| GatewayError::Message("database lock poisoned".into()))?;
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

pub fn scope(h: &HeaderMap, s: &AppState, name: &str) -> Result<String> {
    let id = token_client(h, s)?;
    let ok: Option<i64> = s.db.lock().map_err(|_| GatewayError::Message("database lock poisoned".into()))?
        .query_row(
            "SELECT 1 FROM permission_grant WHERE client_id=? AND (scope=? OR scope='*' OR scope='admin.*') AND (expires_at IS NULL OR expires_at>?) LIMIT 1",
            params![id, name, now()],
            |r| r.get(0),
        )
        .optional()?;
    if ok.is_none() {
        return Err(GatewayError::Message(format!("AUTH scope denied: {name}")));
    }
    Ok(id)
}

pub fn acl(s: &AppState, c: &str, r: &str, p: &str, path: &str) -> Result<()> {
    let conn = s.db.lock().map_err(|_| GatewayError::Message("database lock poisoned".into()))?;
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

pub fn consume_confirmation<F>(s: &AppState, key: &str, validate: F) -> Result<()>
where
    F: FnOnce(i64, &str) -> Result<()>,
{
    let mut conn = s.db.lock().map_err(|_| GatewayError::Message("database lock poisoned".into()))?;
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

pub fn audit(
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

pub fn audit_path(
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

pub fn audit_inner(
    s: &AppState,
    c: Option<&str>,
    op: &str,
    res: Option<&str>,
    remote: Option<&str>,
    result: &str,
    err: Option<&str>,
    path: Option<&str>,
) -> Result<()> {
    let uid = current_uid() as i64;
    let path_hash = path.map(hash);
    let safe_resource =
        res.filter(|value| !value.starts_with('/') && !value.contains(['\\', '\r', '\n', '\0']));
    let latency_ms = REQUEST_STARTED
        .try_with(|started| started.elapsed().as_millis().min(i64::MAX as u128) as i64)
        .unwrap_or(0);
    s.db.lock()
        .map_err(|_| GatewayError::Message("database lock poisoned".into()))?
        .execute(
            "INSERT INTO audit_log(timestamp,client_id,uid,operation,resource,remote_id,path_hash,result,error_code,latency_ms) VALUES(?,?,?,?,?,?,?,?,?,?)",
            params![now(), c, uid, op, safe_resource, remote, path_hash, result, err, latency_ms],
        )?;
    Ok(())
}

pub fn safe_mode_enabled(s: &AppState) -> bool {
    s.db.lock()
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
