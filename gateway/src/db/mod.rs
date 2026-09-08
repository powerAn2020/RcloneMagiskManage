pub mod backup;
pub use backup::*;

use rusqlite::{Connection, OptionalExtension, params};
use std::{
    collections::HashMap,
    fs,
    path::Path as FsPath,
    sync::{Arc, Mutex},
    time::UNIX_EPOCH,
};
use uuid::Uuid;

use crate::error::{GatewayError, Result};
use crate::security::crypto::{encrypt_secret, hash, ini_line_safe, now, restrict_file};
use crate::state::{AppState, Db, Paths, SCHEMA};

pub fn db(s: &AppState) -> Result<std::sync::MutexGuard<'_, Connection>> {
    s.db.lock()
        .map_err(|_| GatewayError::Message("database lock poisoned".into()))
}

pub fn open_db(root: &FsPath) -> Result<Db> {
    let c = Connection::open(root.join("db/state.db"))?;
    c.execute_batch(SCHEMA)?;
    let _ = c.execute("ALTER TABLE job ADD COLUMN next_run_at INTEGER", []);
    let _ = c.execute("ALTER TABLE job ADD COLUMN max_runs INTEGER", []);
    let _ = c.execute("ALTER TABLE client ADD COLUMN token_expires_at INTEGER", []);
    let _ = c.execute("ALTER TABLE mount_profile ADD COLUMN target_package TEXT", []);
    let _ = c.execute("ALTER TABLE mount_profile ADD COLUMN isolated INTEGER NOT NULL DEFAULT 0", []);
    Ok(Arc::new(Mutex::new(c)))
}

pub fn ensure_dirs(root: &FsPath) -> Result<()> {
    fs::create_dir_all(root)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(root, fs::Permissions::from_mode(0o700))?;
    }
    for n in [
        "db",
        "keys",
        "secrets",
        "runtime",
        "logs",
        "backups",
        "migrations",
        "cache",
    ] {
        let p = root.join(n);
        fs::create_dir_all(&p)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&p, fs::Permissions::from_mode(0o700))?;
        }
    }
    Ok(())
}

pub fn rotate_logs(root: &FsPath) -> Result<()> {
    let dir = root.join("logs");
    let max = configured_setting(root, "logMaxBytes", 10 * 1024 * 1024)?;
    let retention_days = configured_setting(root, "logRetentionDays", 14)?.clamp(1, 365);
    let cutoff = now().saturating_sub(retention_days.saturating_mul(86_400) as i64);
    for entry in fs::read_dir(&dir)? {
        let path = entry?.path();
        let name = path
            .file_name()
            .and_then(|x| x.to_str())
            .unwrap_or_default();
        let is_log =
            path.extension().and_then(|x| x.to_str()) == Some("log") || name.contains(".log.");
        if !is_log {
            continue;
        }
        let metadata = fs::metadata(&path)?;
        if metadata
            .modified()
            .ok()
            .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
            .is_some_and(|age| (age.as_secs() as i64) < cutoff)
        {
            let _ = fs::remove_file(&path);
            continue;
        }
        if path.extension().and_then(|x| x.to_str()) == Some("log") && metadata.len() > max {
            let old = path.with_extension("log.1");
            let _ = fs::remove_file(&old);
            fs::rename(path, &old)?;
            restrict_file(&old)?;
        }
    }
    Ok(())
}

pub fn configured_setting(root: &FsPath, key: &str, default: u64) -> Result<u64> {
    let Ok(conn) = Connection::open(root.join("db/state.db")) else {
        return Ok(default);
    };
    let value: Option<String> = conn
        .query_row(
            "SELECT value FROM system_config WHERE key=?",
            params![key],
            |r| r.get(0),
        )
        .optional()
        .unwrap_or(None);
    Ok(value.and_then(|v| v.parse::<u64>().ok()).unwrap_or(default))
}

pub fn parse_size_bytes(value: &str) -> Option<u64> {
    let value = value.trim();
    let split = value
        .find(|c: char| !c.is_ascii_digit())
        .unwrap_or(value.len());
    let number = value[..split].parse::<u64>().ok()?;
    let multiplier = match value[split..].trim().to_ascii_lowercase().as_str() {
        "" | "b" => 1,
        "k" | "kb" | "kib" => 1024,
        "m" | "mb" | "mib" => 1024 * 1024,
        "g" | "gb" | "gib" => 1024 * 1024 * 1024,
        "t" | "tb" | "tib" => 1024 * 1024 * 1024 * 1024,
        _ => return None,
    };
    number.checked_mul(multiplier)
}

pub fn migrate(p: Paths) -> Result<()> {
    ensure_dirs(&p.root)?;
    let database = open_db(&p.root)?;
    let legacy = p
        .legacy
        .ok_or_else(|| GatewayError::Message("migrate requires --legacy PATH".into()))?;
    let already: Option<i64> = database
        .lock()
        .map_err(|_| GatewayError::Message("database lock poisoned".into()))?
        .query_row(
            "SELECT version FROM migration_history WHERE version=2",
            [],
            |r| r.get(0),
        )
        .optional()?;
    if already.is_some() {
        println!("migrated_jobs=0 already_migrated=true");
        return Ok(());
    }
    let config = legacy.join("rclone.conf");
    if config.exists() {
        let mut section: Option<String> = None;
        let mut values: HashMap<String, String> = HashMap::new();
        let flush = |section: &mut Option<String>,
                     values: &mut HashMap<String, String>,
                     database: &Db,
                     root: &FsPath|
         -> Result<()> {
            let Some(name) = section.take() else {
                values.clear();
                return Ok(());
            };
            if name.is_empty() || !ini_line_safe(&name) || name.contains(':') || name.contains('/')
            {
                values.clear();
                return Ok(());
            }
            let typ = values.remove("type").unwrap_or_else(|| "unknown".into());
            if !typ
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
            {
                values.clear();
                return Ok(());
            }
            let endpoint = values.remove("endpoint");
            let secret = serde_json::Value::Object(
                values
                    .drain()
                    .map(|(k, v)| (k, serde_json::Value::String(v)))
                    .collect(),
            );
            let id = Uuid::new_v4().to_string();
            let sr = if secret.as_object().is_some_and(|v| !v.is_empty()) {
                Some(encrypt_secret(root, &id, &secret)?)
            } else {
                None
            };
            let c = database
                .lock()
                .map_err(|_| GatewayError::Message("database lock poisoned".into()))?;
            let exists: Option<String> = c
                .query_row("SELECT id FROM remote WHERE name=?", params![name], |r| {
                    r.get(0)
                })
                .optional()?;
            if exists.is_none() {
                c.execute("INSERT INTO remote(id,name,type,endpoint,base_path,secret_ref,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)", params![id,name,typ,endpoint,"/",sr,1,now(),now()])?;
                if let Some(ref x) = sr {
                    c.execute("INSERT INTO secret_meta(id,kind,backend,version,created_at) VALUES(?,?,?,?,?)", params![x,"remote","xchacha20poly1305",1,now()])?;
                }
            }
            values.clear();
            Ok(())
        };
        for (line_no, raw) in fs::read_to_string(&config)?.lines().enumerate() {
            let line = raw.trim();
            if line.starts_with('[') && line.ends_with(']') {
                flush(&mut section, &mut values, &database, &p.root)?;
                section = Some(line[1..line.len() - 1].to_string());
                continue;
            }
            if line.is_empty() || line.starts_with('#') || section.is_none() {
                continue;
            }
            let Some((k, v)) = line.split_once('=') else {
                let c = database
                    .lock()
                    .map_err(|_| GatewayError::Message("database lock poisoned".into()))?;
                c.execute("INSERT INTO migration_errors(migration_version,source_file,line_number,message,raw_value,created_at) VALUES(?,?,?,?,?,?)", params![2,config.display().to_string(),line_no as i64+1,"expected key=value", "<redacted>", now()])?;
                continue;
            };
            let k = k.trim().to_string();
            let v = v.trim().to_string();
            if !ini_line_safe(&k) || !ini_line_safe(&v) || k.is_empty() {
                let c = database
                    .lock()
                    .map_err(|_| GatewayError::Message("database lock poisoned".into()))?;
                c.execute("INSERT INTO migration_errors(migration_version,source_file,line_number,message,raw_value,created_at) VALUES(?,?,?,?,?,?)", params![2,config.display().to_string(),line_no as i64+1,"unsafe key or value", "<redacted>", now()])?;
                continue;
            }
            values.insert(k, v);
        }
        flush(&mut section, &mut values, &database, &p.root)?;
    }
    let mut migrated = 0u64;
    for kind in ["sync", "copy"] {
        let file = legacy.join(kind);
        if !file.exists() {
            continue;
        }
        for (line_no, raw) in fs::read_to_string(&file)?.lines().enumerate() {
            let fields: Vec<&str> = raw.split_whitespace().collect();
            let valid = !raw.trim().is_empty()
                && !raw.trim_start().starts_with('#')
                && fields.len() == 2
                && fields
                    .iter()
                    .all(|x| !x.starts_with('-') && ini_line_safe(x) && !x.contains(".."));
            let c = database
                .lock()
                .map_err(|_| GatewayError::Message("database lock poisoned".into()))?;
            if valid {
                c.execute("INSERT INTO job(id,type,status,source,destination,dry_run,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)", params![Uuid::new_v4().to_string(), kind, "CREATED", fields[0], fields[1], 0, now(), now()])?;
                migrated += 1;
            } else {
                c.execute("INSERT INTO migration_errors(migration_version,source_file,line_number,message,raw_value,created_at) VALUES(?,?,?,?,?,?)", params![1, file.display().to_string(), line_no as i64 + 1, "expected two safe path arguments", "<redacted>", now()])?;
            }
        }
    }
    let c = database
        .lock()
        .map_err(|_| GatewayError::Message("database lock poisoned".into()))?;
    c.execute(
        "INSERT OR REPLACE INTO migration_history(version,applied_at,checksum) VALUES(?,?,?)",
        params![2, now(), hash(&format!("legacy:{}", legacy.display()))],
    )?;
    println!("migrated_jobs={migrated}");
    Ok(())
}
