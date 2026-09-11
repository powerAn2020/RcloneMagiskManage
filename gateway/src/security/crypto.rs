use aes::Aes256;
use base64::Engine;
use chacha20poly1305::{
    XChaCha20Poly1305, XNonce,
    aead::{Aead, KeyInit},
};
use ctr::cipher::{KeyIvInit, StreamCipher};
use rand::RngCore;
use sha2::{Digest, Sha256};
use std::{
    fs,
    path::Path as FsPath,
    time::{SystemTime, UNIX_EPOCH},
};

use crate::error::{GatewayError, Result};

pub fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}

pub fn hash(v: &str) -> String {
    hex::encode(Sha256::digest(v.as_bytes()))
}

pub fn restrict_file(path: &FsPath) -> Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
    }
    #[cfg(not(unix))]
    let _ = path;
    Ok(())
}

pub fn master_key(root: &FsPath) -> Result<[u8; 32]> {
    let p = root.join("keys/master.key");
    if p.exists() {
        return fs::read(p)?.try_into().map_err(|_| GatewayError::Crypto);
    }
    let mut k = [0; 32];
    rand::rng().fill_bytes(&mut k);
    #[cfg(unix)]
    {
        use std::io::Write;
        use std::os::unix::fs::OpenOptionsExt;
        let mut file = fs::OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .mode(0o600)
            .open(&p)?;
        file.write_all(&k)?;
    }
    #[cfg(not(unix))]
    {
        fs::write(&p, k)?;
    }
    Ok(k)
}

pub fn encrypt_secret(root: &FsPath, id: &str, v: &serde_json::Value) -> Result<String> {
    let cipher = XChaCha20Poly1305::new((&master_key(root)?).into());
    let mut n = [0; 24];
    rand::rng().fill_bytes(&mut n);
    let body = serde_json::to_vec(v).map_err(|_| GatewayError::Crypto)?;
    let aad = format!("{id}:1");
    let ct = cipher
        .encrypt(
            XNonce::from_slice(&n),
            chacha20poly1305::aead::Payload {
                msg: &body,
                aad: aad.as_bytes(),
            },
        )
        .map_err(|_| GatewayError::Crypto)?;
    let r = uuid::Uuid::new_v4().to_string();
    let path = root.join("secrets").join(format!("{r}.blob"));
    fs::write(&path, [n.as_slice(), ct.as_slice()].concat())?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
    }
    Ok(r)
}

pub fn decrypt_secret(root: &FsPath, reference: &str, aad_id: &str) -> Result<serde_json::Value> {
    let bytes = fs::read(root.join("secrets").join(format!("{reference}.blob")))?;
    if bytes.len() < 24 {
        return Err(GatewayError::Crypto);
    }
    let cipher = XChaCha20Poly1305::new((&master_key(root)?).into());
    let plain = cipher
        .decrypt(
            XNonce::from_slice(&bytes[..24]),
            chacha20poly1305::aead::Payload {
                msg: &bytes[24..],
                aad: format!("{aad_id}:1").as_bytes(),
            },
        )
        .map_err(|_| GatewayError::Crypto)?;
    serde_json::from_slice(&plain).map_err(|_| GatewayError::Crypto)
}

pub fn ini_line_safe(v: &str) -> bool {
    !v.contains(['\r', '\n', '\0'])
}

pub fn validate_secret_object(value: &serde_json::Value) -> Result<()> {
    let object = value
        .as_object()
        .ok_or_else(|| GatewayError::Message("secret must be an object".into()))?;
    if object.len() > 64 {
        return Err(GatewayError::Message("secret has too many fields".into()));
    }
    for (key, value) in object {
        if key.is_empty()
            || key.len() > 128
            || !key
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-'))
        {
            return Err(GatewayError::Message("invalid secret key".into()));
        }
        let text = match value {
            serde_json::Value::String(v) => v.clone(),
            serde_json::Value::Bool(_) | serde_json::Value::Number(_) => value.to_string(),
            _ => {
                return Err(GatewayError::Message(
                    "secret values must be scalar strings or numbers".into(),
                ));
            }
        };
        if text.len() > 8192 || !ini_line_safe(&text) {
            return Err(GatewayError::Message("invalid secret value".into()));
        }
    }
    Ok(())
}

pub fn obscure_rclone(value: &str) -> Result<String> {
    type RcloneCtr = ctr::Ctr128BE<Aes256>;
    const KEY: [u8; 32] = [
        0x9c, 0x93, 0x5b, 0x48, 0x73, 0x0a, 0x55, 0x4d, 0x6b, 0xfd, 0x7c, 0x63, 0xc8, 0x86, 0xa9,
        0x2b, 0xd3, 0x90, 0x19, 0x8e, 0xb8, 0x12, 0x8a, 0xfb, 0xf4, 0xde, 0x16, 0x2b, 0x8b, 0x95,
        0xf6, 0x38,
    ];
    let mut iv = [0u8; 16];
    rand::rng().fill_bytes(&mut iv);
    let mut ciphertext = value.as_bytes().to_vec();
    let mut cipher = RcloneCtr::new((&KEY).into(), (&iv).into());
    cipher.apply_keystream(&mut ciphertext);
    let mut out = iv.to_vec();
    out.extend_from_slice(&ciphertext);
    Ok(base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(out))
}

pub fn deobscure_rclone(value: &str) -> Result<String> {
    type RcloneCtr = ctr::Ctr128BE<Aes256>;
    const KEY: [u8; 32] = [
        0x9c, 0x93, 0x5b, 0x48, 0x73, 0x0a, 0x55, 0x4d, 0x6b, 0xfd, 0x7c, 0x63, 0xc8, 0x86, 0xa9,
        0x2b, 0xd3, 0x90, 0x19, 0x8e, 0xb8, 0x12, 0x8a, 0xfb, 0xf4, 0xde, 0x16, 0x2b, 0x8b, 0x95,
        0xf6, 0x38,
    ];
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(value)
        .or_else(|_| base64::engine::general_purpose::URL_SAFE.decode(value))
        .map_err(|_| GatewayError::Crypto)?;
    if bytes.len() <= 16 {
        return Err(GatewayError::Crypto);
    }
    let iv = &bytes[..16];
    let mut ciphertext = bytes[16..].to_vec();
    let mut cipher = RcloneCtr::new((&KEY).into(), iv.into());
    cipher.apply_keystream(&mut ciphertext);
    let s = String::from_utf8(ciphertext).map_err(|_| GatewayError::Crypto)?;
    if s.chars().all(|c| !c.is_control() || c == '\t' || c == '\n' || c == '\r') {
        Ok(s)
    } else {
        Err(GatewayError::Crypto)
    }
}

pub fn is_rclone_obscured(value: &str) -> bool {
    deobscure_rclone(value).is_ok()
}

pub fn is_rclone_password_key(key: &str) -> bool {
    let lower = key.to_ascii_lowercase();
    matches!(
        lower.as_str(),
        "pass"
            | "password"
            | "password2"
            | "key_file_pass"
            | "api_password"
            | "library_key"
            | "mailbox_password"
            | "otp_secret_key"
            | "file_password"
            | "folder_password"
            | "client_certificate_password"
            | "plex_password"
            | "secret"
            | "secret_key"
            | "secret_access_key"
            | "token"
            | "auth_token"
    ) || lower.ends_with("_pass")
        || lower.ends_with("_password")
        || lower.ends_with("_secret")
        || lower.ends_with("_token")
}

pub fn is_sensitive_export_key(key: &str) -> bool {
    if is_rclone_password_key(key) {
        return true;
    }
    let lower = key.to_ascii_lowercase();
    matches!(
        lower.as_str(),
        "access_key_id"
            | "access_key"
            | "client_id"
            | "client_secret"
            | "account_id"
            | "account_key"
            | "api_key"
            | "key_id"
    ) || lower.ends_with("_client_id")
        || lower.ends_with("_key_id")
        || lower.ends_with("_account_id")
}

pub fn safe_request_segment(segment: &str) -> bool {
    !segment.is_empty()
        && segment.len() <= 128
        && segment
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_'))
}

pub fn validate_identity(value: &str, field: &str, max: usize) -> Result<()> {
    if value.is_empty()
        || value.len() > max
        || !value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-' | b'.' | b' '))
    {
        return Err(GatewayError::Message(format!("invalid {field}")));
    }
    Ok(())
}

pub fn valid_path(path: &str, prefix: &str) -> Result<String> {
    if path.bytes().any(|b| b < 0x20) || path.split('/').any(|p| p == "..") {
        return Err(GatewayError::Message(
            "PATH_DENIED: path traversal is not allowed".into(),
        ));
    }
    let n = format!("/{}", path.trim_matches('/'));
    let b = format!("/{}", prefix.trim_matches('/'));
    if b != "/" && n != b && !n.starts_with(&(b.clone() + "/")) {
        return Err(GatewayError::Message(
            "PATH_DENIED: outside allowed prefix".into(),
        ));
    }
    Ok(n)
}

pub fn is_valid_package_name(pkg: &str) -> bool {
    if pkg.is_empty() || pkg.len() > 128 {
        return false;
    }
    let parts: Vec<&str> = pkg.split('.').collect();
    if parts.len() < 2 {
        return false;
    }
    for part in parts {
        if part.is_empty() {
            return false;
        }
        let first = part.as_bytes()[0];
        if !first.is_ascii_alphabetic() && first != b'_' {
            return false;
        }
        if !part.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_') {
            return false;
        }
    }
    true
}

pub fn is_valid_app_private_mount(p: &str) -> bool {
    let sub = if let Some(sub) = p.strip_prefix("/data/data/") {
        sub
    } else if let Some(sub) = p.strip_prefix("/data/user/0/") {
        sub
    } else if let Some(sub) = p.strip_prefix("/storage/emulated/0/Android/data/") {
        sub
    } else if let Some(sub) = p.strip_prefix("/sdcard/Android/data/") {
        sub
    } else if let Some(sub) = p.strip_prefix("/data/media/0/Android/data/") {
        sub
    } else {
        return false;
    };
    let Some((pkg, rest)) = sub.split_once('/') else {
        return false;
    };
    if !is_valid_package_name(pkg) {
        return false;
    }
    // Must be within files/ or cache/ and have a sub-path
    if let Some(after_files) = rest.strip_prefix("files/") {
        !after_files.trim_matches('/').is_empty()
    } else if let Some(after_cache) = rest.strip_prefix("cache/") {
        !after_cache.trim_matches('/').is_empty()
    } else {
        false
    }
}

pub fn valid_mount(p: &str) -> Result<()> {
    if p.contains("..") || p.bytes().any(|b| b < 0x20) {
        return Err(GatewayError::Message(
            "PATH_DENIED: mount point is not allowed".into(),
        ));
    }
    if std::path::Path::new(p).is_file() {
        return Err(GatewayError::Message(
            "MOUNT_TARGET_IS_FILE: mount point must be a directory, not a regular file".into(),
        ));
    }
    let valid_root = if let Some(name) = p.strip_prefix("/mnt/rclone-") {
        !name.is_empty()
            && name
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.'))
    } else if let Some(name) = p.strip_prefix("/mnt/") {
        !name.is_empty()
    } else if let Some(name) = p.strip_prefix("/sdcard/") {
        !name.is_empty()
    } else if let Some(name) = p.strip_prefix("/storage/emulated/0/") {
        !name.is_empty()
    } else if let Some(name) = p.strip_prefix("/storage/") {
        !name.is_empty()
    } else if let Some(name) = p.strip_prefix("/data/media/0/") {
        !name.is_empty()
    } else {
        false
    };
    if !valid_root && !is_valid_app_private_mount(p) {
        return Err(GatewayError::Message(
            "PATH_DENIED: mount destination must reside under /mnt/rclone-*, /mnt/*, /sdcard/*, /storage/*, /data/media/0/*, /data/data/<pkg>/files/*, or Android/data/<pkg>/files/*"
                .into(),
        ));
    }
    Ok(())
}

pub fn allowed_request(method: &str, path: &str) -> bool {
    if !["GET", "POST", "PUT", "DELETE"].contains(&method)
        || !path.starts_with("/api/v1/")
        || path.contains(['\r', '\n', '\0'])
    {
        return false;
    }
    if path.contains("..") {
        return false;
    }
    let clean = path.split('?').next().unwrap_or(path);
    if matches!(
        (method, clean),
        ("GET", "/api/v1/system/health")
            | ("GET", "/api/v1/system/info")
            | ("GET", "/api/v1/system/safe-mode")
            | ("PUT", "/api/v1/system/safe-mode")
            | ("GET", "/api/v1/system/settings")
            | ("PUT", "/api/v1/system/settings")
            | ("GET", "/api/v1/system/migration")
            | ("POST", "/api/v1/system/migration")
            | ("GET", "/api/v1/system/backups")
            | ("POST", "/api/v1/system/backups")
            | ("GET", "/api/v1/system/logs/core")
            | ("POST", "/api/v1/system/logs/clear")
            | ("POST", "/api/v1/security/pairing/start")
            | ("POST", "/api/v1/security/pairing/complete")
            | ("POST", "/api/v1/security/pairing/cancel")
            | ("GET", "/api/v1/security/clients")
            | ("GET", "/api/v1/remotes")
            | ("POST", "/api/v1/remotes")
            | ("POST", "/api/v1/remotes/import")
            | ("POST", "/api/v1/remotes/test-config")
            | ("GET", "/api/v1/files")
            | ("POST", "/api/v1/files/mkdir")
            | ("POST", "/api/v1/files/delete")
            | ("POST", "/api/v1/files/copy")
            | ("POST", "/api/v1/files/move")
            | ("POST", "/api/v1/files/upload")
            | ("POST", "/api/v1/files/download")
            | ("GET", "/api/v1/jobs")
            | ("POST", "/api/v1/jobs")
            | ("GET", "/api/v1/mounts")
            | ("POST", "/api/v1/mounts")
            | ("GET", "/api/v1/crypt")
            | ("POST", "/api/v1/crypt")
            | ("GET", "/api/v1/logs/audit")
    ) {
        return true;
    }
    let parts: Vec<&str> = clean.split('/').collect();
    let valid_id = |v: Option<&&str>| v.is_some_and(|x| safe_request_segment(x));
    match parts.as_slice() {
        ["", "api", "v1", "system", "backups", name] => {
            crate::db::backup::valid_backup_name(name).is_ok() && method == "DELETE"
        }
        ["", "api", "v1", "system", "backups", name, "restore"] => {
            crate::db::backup::valid_backup_name(name).is_ok() && method == "POST"
        }
        ["", "api", "v1", "remotes", "import"] => method == "POST",
        ["", "api", "v1", "remotes", id] => {
            valid_id(Some(id)) && matches!(method, "GET" | "PUT" | "DELETE")
        }
        ["", "api", "v1", "remotes", id, "test"] => valid_id(Some(id)) && method == "POST",
        ["", "api", "v1", "remotes", id, action] => {
            valid_id(Some(id))
                && matches!(*action, "enable" | "disable" | "export")
                && ((method == "POST" && *action != "export")
                    || (method == "GET" && *action == "export"))
        }
        ["", "api", "v1", "jobs", id] => valid_id(Some(id)) && matches!(method, "GET" | "DELETE"),
        ["", "api", "v1", "jobs", id, "runs"] => valid_id(Some(id)) && method == "GET",
        ["", "api", "v1", "jobs", id, "log"] => valid_id(Some(id)) && method == "GET",
        ["", "api", "v1", "crypt", id, "test"] => valid_id(Some(id)) && method == "POST",
        ["", "api", "v1", "jobs", id, action] => {
            valid_id(Some(id))
                && matches!(*action, "start" | "pause" | "resume" | "cancel" | "retry")
                && method == "POST"
        }
        ["", "api", "v1", "mounts", id] => {
            valid_id(Some(id)) && matches!(method, "GET" | "PUT" | "DELETE")
        }
        ["", "api", "v1", "mounts", id, action] => {
            valid_id(Some(id))
                && matches!(*action, "start" | "stop" | "enable" | "disable")
                && method == "POST"
        }
        ["", "api", "v1", "security", "clients", id] => {
            valid_id(Some(id)) && method == "DELETE"
        }
        ["", "api", "v1", "security", "clients", id, "grants"] => {
            valid_id(Some(id)) && matches!(method, "GET" | "POST")
        }
        [
            "",
            "api",
            "v1",
            "security",
            "clients",
            id,
            "grants",
            grant_id,
        ] => {
            valid_id(Some(id))
                && !grant_id.is_empty()
                && grant_id.bytes().all(|b| b.is_ascii_digit())
                && method == "DELETE"
        }
        ["", "api", "v1", "security", "clients", id, "remote-acl"]
        | ["", "api", "v1", "security", "clients", id, "disable"]
        | ["", "api", "v1", "security", "clients", id, "enable"]
        | ["", "api", "v1", "security", "clients", id, "rotate-token"] => {
            valid_id(Some(id)) && method == "POST"
        }
        _ => false,
    }
}
