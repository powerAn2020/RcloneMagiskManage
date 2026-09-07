use std::{
    env, fs,
    path::PathBuf,
};
use tokio::process::Command;
use uuid::Uuid;

use crate::db::db;
use crate::error::{GatewayError, Result};
use crate::security::crypto::{
    decrypt_secret, ini_line_safe, is_rclone_obscured, is_rclone_password_key, obscure_rclone,
    restrict_file,
};
use crate::state::AppState;
use crate::types::TransferStats;

pub struct TempConfig(pub Option<PathBuf>);

impl Drop for TempConfig {
    fn drop(&mut self) {
        if let Some(ref p) = self.0 {
            let _ = fs::remove_file(p);
        }
    }
}

pub fn rclone_command(config: &Option<PathBuf>) -> Command {
    let executable = env::var_os("RCLONE_BIN")
        .map(PathBuf::from)
        .filter(|p| p.is_absolute() && p.is_file())
        .or_else(|| {
            [
                "/data/adb/modules/rclone-manager/bin/rclone",
                "/data/adb/modules/rclone/bin/rclone",
                "/data/adb/modules/rclone/vendor/bin/rclone",
                "/data/adb/modules/rclone/system/vendor/bin/rclone",
                "/system/vendor/bin/rclone",
                "/vendor/bin/rclone",
                "/system/bin/rclone",
            ]
            .iter()
            .map(PathBuf::from)
            .find(|p| p.is_file())
        })
        .unwrap_or_else(|| PathBuf::from("/data/adb/modules/rclone-manager/bin/rclone"));
    let mut c = Command::new(executable);
    let module_bin = "/data/adb/modules/rclone-manager/bin";
    let new_path = match env::var("PATH") {
        Ok(p) if !p.is_empty() => format!("{module_bin}:{p}"),
        _ => module_bin.to_string(),
    };
    c.env("PATH", new_path);
    if let Some(path) = config {
        c.arg("--config").arg(path);
    }
    c
}

pub fn remote_id_by_name(s: &AppState, name: &str) -> Result<String> {
    db(s)?
        .query_row(
            "SELECT id FROM remote WHERE name=? AND enabled=1",
            rusqlite::params![name],
            |r| r.get(0),
        )
        .map_err(|_| GatewayError::Message("remote not found or disabled".into()))
}

static PROVIDERS_CACHE: std::sync::OnceLock<serde_json::Value> = std::sync::OnceLock::new();

pub async fn get_rclone_providers() -> Result<serde_json::Value> {
    if let Some(cached) = PROVIDERS_CACHE.get() {
        return Ok(cached.clone());
    }
    let mut cmd = rclone_command(&None);
    cmd.arg("config").arg("providers");
    let output = match cmd.output().await {
        Ok(o) if o.status.success() => o.stdout,
        _ => {
            Command::new("rclone")
                .args(["config", "providers"])
                .output()
                .await
                .map(|o| o.stdout)
                .unwrap_or_default()
        }
    };
    let val: serde_json::Value = serde_json::from_slice(&output)
        .map_err(|e| GatewayError::Message(format!("failed to parse rclone providers: {e}")))?;
    let _ = PROVIDERS_CACHE.set(val.clone());
    Ok(val)
}

pub fn materialize_rclone_config_at(
    s: &AppState,
    ids: &[String],
    destination: Option<PathBuf>,
) -> Result<Option<PathBuf>> {
    let mut sections = Vec::new();
    for id in ids {
        let (name, typ, endpoint, secret_ref): (String, String, Option<String>, Option<String>) =
            db(s)?
                .query_row(
                    "SELECT name,type,endpoint,secret_ref FROM remote WHERE id=? AND enabled=1",
                    rusqlite::params![id],
                    |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
                )
                .map_err(|_| GatewayError::Message("remote not found or disabled".into()))?;
        if !ini_line_safe(&name)
            || !ini_line_safe(&typ)
            || !typ
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
        {
            return Err(GatewayError::Message("invalid remote configuration".into()));
        }
        let mut text = format!("[{name}]\ntype = {typ}\n");
        let mut written_keys = std::collections::HashSet::new();

        if let Some(sr) = secret_ref {
            let secret = decrypt_secret(&s.root, &sr, id)?;
            let obj = secret
                .as_object()
                .ok_or_else(|| GatewayError::Message("secret must be an object".into()))?;
            for (k, v) in obj {
                if !k
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
                {
                    return Err(GatewayError::Message("invalid secret key".into()));
                }
                let value = v
                    .as_str()
                    .map(str::to_owned)
                    .unwrap_or_else(|| v.to_string());
                if !ini_line_safe(&value) {
                    return Err(GatewayError::Message("invalid secret value".into()));
                }
                let trimmed = value.trim();
                if trimmed.is_empty() || trimmed == "[]" || trimmed == "{}" {
                    continue;
                }
                let final_val = if is_rclone_password_key(k) && !is_rclone_obscured(trimmed) {
                    obscure_rclone(trimmed)?
                } else {
                    trimmed.to_string()
                };
                text.push_str(&format!("{k} = {final_val}\n"));
                written_keys.insert(k.clone());
            }
        }

        // Protocol adaptive fallbacks for common/legacy parameters
        if typ == "webdav" {
            if !written_keys.contains("url") {
                if let Some(ref e) = endpoint {
                    if ini_line_safe(e) {
                        text.push_str(&format!("url = {e}\n"));
                    }
                }
            }
            if !written_keys.contains("vendor") {
                text.push_str("vendor = other\n");
            }
            if !written_keys.contains("user") {
                if let Some(ref u) = written_keys.get("access_key") {
                    let _ = u;
                }
            }
        } else {
            if !written_keys.contains("endpoint") {
                if let Some(ref e) = endpoint {
                    if ini_line_safe(e) {
                        text.push_str(&format!("endpoint = {e}\n"));
                    }
                }
            }
        }

        sections.push(text);
    }
    if sections.is_empty() {
        return Ok(None);
    }
    let path = destination.unwrap_or_else(|| {
        s.root
            .join("runtime")
            .join(format!("rclone-{}.conf", Uuid::new_v4()))
    });
    fs::write(&path, sections.join("\n"))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&path, fs::Permissions::from_mode(0o600))?;
    }
    Ok(Some(path))
}

pub fn materialize_rclone_config(s: &AppState, ids: &[String]) -> Result<Option<PathBuf>> {
    materialize_rclone_config_at(s, ids, None)
}

pub fn materialize_mount_config(s: &AppState, id: &str, remote_id: &str) -> Result<PathBuf> {
    let path = s.root.join("runtime").join(format!("mount-{id}.conf"));
    let _ = fs::remove_file(&path);
    materialize_rclone_config_at(s, &[remote_id.to_owned()], Some(path))?
        .ok_or_else(|| GatewayError::Message("remote has no usable configuration".into()))
}

pub fn materialize_crypt_config(s: &AppState, id: &str) -> Result<(PathBuf, String)> {
    let (name, remote_id, remote_path, secret_ref, parent_name):
        (String, String, String, Option<String>, String) =
        db(s)?.query_row(
            "SELECT c.name,c.remote_id,c.remote_path,c.secret_ref,r.name FROM crypt_profile c JOIN remote r ON r.id=c.remote_id WHERE c.id=?",
            rusqlite::params![id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
        )?;
    let path = s
        .root
        .join("runtime")
        .join(format!("crypt-{}.conf", Uuid::new_v4()));
    let Some(base) = materialize_rclone_config_at(s, &[remote_id], Some(path.clone()))? else {
        return Err(GatewayError::Message(
            "crypt parent remote unavailable".into(),
        ));
    };
    let password = secret_ref
        .as_deref()
        .ok_or_else(|| GatewayError::Message("crypt password is not configured".into()))
        .and_then(|reference| decrypt_secret(&s.root, reference, id))?
        .get("password")
        .and_then(|v| v.as_str())
        .map(str::to_owned)
        .ok_or_else(|| GatewayError::Message("crypt password is invalid".into()))?;
    if !ini_line_safe(&password) || password.is_empty() || password.len() > 4096 {
        let _ = fs::remove_file(&base);
        return Err(GatewayError::Message("crypt password is invalid".into()));
    }
    let mut config = fs::OpenOptions::new().append(true).open(&base)?;
    use std::io::Write as _;
    writeln!(
        config,
        "\n[{name}]\ntype = crypt\nremote = {parent_name}:{remote_path}\npassword = {}",
        if is_rclone_obscured(&password) {
            password
        } else {
            obscure_rclone(&password)?
        }
    )?;
    restrict_file(&base)?;
    Ok((base, name))
}

pub fn parse_rclone_stats(output: &[u8]) -> TransferStats {
    let mut stats = TransferStats::default();
    let text = String::from_utf8_lossy(output);
    for line in text.lines().rev() {
        let Ok(v) = serde_json::from_str::<serde_json::Value>(line.trim()) else {
            continue;
        };
        let number = |name: &str| {
            v.get(name)
                .or_else(|| v.get("stats").and_then(|s| s.get(name)))
                .and_then(|x| x.as_i64())
        };
        stats.bytes = number("bytes").or(stats.bytes);
        stats.total_bytes = number("totalBytes").or(stats.total_bytes);
        stats.files = number("transfers").or(stats.files);
        stats.total_files = number("totalTransfers").or(stats.total_files);
        stats.errors = number("errors").or(stats.errors);
        if stats.bytes.is_some()
            || stats.total_bytes.is_some()
            || stats.files.is_some()
            || stats.total_files.is_some()
            || stats.errors.is_some()
        {
            break;
        }
    }
    stats
}

pub fn redact_log_text(input: &str) -> String {
    const SENSITIVE: &[&str] = &[
        "password",
        "secret",
        "token",
        "access_key",
        "access key",
        "client_secret",
        "client secret",
        "private_key",
        "private key",
        "bearer",
        "authorization",
    ];
    input
        .lines()
        .map(|line| {
            let lower = line.to_ascii_lowercase();
            if SENSITIVE.iter().any(|needle| lower.contains(needle)) {
                "[REDACTED]"
            } else {
                line
            }
        })
        .collect::<Vec<_>>()
        .join("\n")
}

pub fn clean_error_message(input: &str) -> String {
    let mut lines = Vec::new();
    for line in input.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        let mut cleaned = trimmed.to_string();
        for key in &[
            "pass",
            "password",
            "secret",
            "token",
            "access_key",
            "client_secret",
            "bearer",
        ] {
            if let Some(pos) = cleaned.to_ascii_lowercase().find(key) {
                let rest = &cleaned[pos + key.len()..];
                if rest.starts_with('=')
                    || rest.starts_with(':')
                    || rest.starts_with(" =")
                    || rest.starts_with(" :")
                {
                    cleaned = format!("{}: [REDACTED]", &cleaned[..pos + key.len()]);
                }
            }
        }
        lines.push(cleaned);
    }
    if lines.is_empty() {
        "remote connection test failed".into()
    } else {
        lines.join("\n")
    }
}
