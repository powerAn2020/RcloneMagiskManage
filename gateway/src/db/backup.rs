use rusqlite::{Connection, params};
use sha2::{Digest, Sha256};
use std::{
    fs,
    path::Path as FsPath,
};
use uuid::Uuid;

use crate::error::{GatewayError, Result};
use crate::security::crypto::{now, restrict_file};

pub fn copy_private_files(source: &FsPath, destination: &FsPath) -> Result<()> {
    fs::create_dir_all(destination)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(destination, fs::Permissions::from_mode(0o700))?;
    }
    for entry in fs::read_dir(source)? {
        let entry = entry?;
        let metadata = fs::symlink_metadata(entry.path())?;
        if !metadata.file_type().is_file() {
            continue;
        }
        let target = destination.join(entry.file_name());
        fs::copy(entry.path(), &target)?;
        restrict_file(&target)?;
    }
    Ok(())
}

pub fn replace_private_files(source: &FsPath, destination: &FsPath) -> Result<()> {
    fs::create_dir_all(destination)?;
    for entry in fs::read_dir(destination)? {
        let entry = entry?;
        let metadata = fs::symlink_metadata(entry.path())?;
        if metadata.file_type().is_file() || metadata.file_type().is_symlink() {
            fs::remove_file(entry.path())?;
        }
    }
    copy_private_files(source, destination)
}

pub fn valid_backup_name(name: &str) -> Result<()> {
    if name.is_empty()
        || name.len() > 128
        || !name.ends_with(".db")
        || !name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'_'))
    {
        return Err(GatewayError::Message("invalid backup name".into()));
    }
    Ok(())
}

#[cfg(unix)]
fn process_kill_command() -> std::process::Command {
    if cfg!(target_os = "android") {
        std::process::Command::new("/system/bin/kill")
    } else {
        std::process::Command::new("kill")
    }
}


pub fn restore_backup(root: &FsPath, name: &str) -> Result<serde_json::Value> {
    valid_backup_name(name)?;
    crate::db::ensure_dirs(root)?;
    let pid_file = root.join("runtime/gateway.pid");
    let socket_file = root.join("runtime/gateway.sock");
    if socket_file.exists() && !pid_file.exists() {
        return Err(GatewayError::Message(
            "gateway socket exists without a PID; stop Gateway and remove the stale socket before restore".into(),
        ));
    }
    if let Ok(pid_text) = fs::read_to_string(&pid_file) {
        let pid = pid_text.trim();
        if !pid.is_empty() && pid.bytes().all(|b| b.is_ascii_digit()) {
            let current_pid = std::process::id().to_string();
            if pid != current_pid {
                #[cfg(unix)]
                {
                    let alive = process_kill_command()
                        .args(["-0", pid])
                        .status()
                        .is_ok_and(|v| v.success());
                    if alive {
                        return Err(GatewayError::Message(
                            "gateway must be stopped before database restore".into(),
                        ));
                    }
                }
            }
        }
    }
    let backup_dir = root.join("backups");
    let backup = backup_dir.join(name);
    if !backup.is_file() {
        return Err(GatewayError::Message("backup not found".into()));
    }
    let backup_root = fs::canonicalize(&backup_dir)?;
    let root_real = fs::canonicalize(root)?;
    if !backup_root.starts_with(&root_real) {
        return Err(GatewayError::Message(
            "backup directory escapes manager root".into(),
        ));
    }
    let backup_real = fs::canonicalize(&backup)?;
    if !backup_real.starts_with(&backup_root) {
        return Err(GatewayError::Message(
            "backup path escapes backup directory".into(),
        ));
    }
    let check = Connection::open(&backup_real)?;
    let integrity: String = check.query_row("PRAGMA integrity_check", [], |r| r.get(0))?;
    if integrity != "ok" {
        return Err(GatewayError::Message(
            "backup integrity check failed".into(),
        ));
    }
    let required_tables = [
        "system_config",
        "remote",
        "remote_acl",
        "client",
        "permission_grant",
        "job",
        "job_run",
        "mount_profile",
        "crypt_profile",
        "secret_meta",
        "audit_log",
        "migration_history",
    ];
    for table in required_tables {
        let present: i64 = check.query_row(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
            params![table],
            |r| r.get(0),
        )?;
        if present != 1 {
            return Err(GatewayError::Message(format!(
                "backup schema missing table {table}"
            )));
        }
    }
    drop(check);

    let bundle = backup_dir.join(format!("{}.bundle", name.trim_end_matches(".db")));
    let bundle_real = if bundle.is_dir() {
        let real = fs::canonicalize(&bundle)?;
        if !real.starts_with(&backup_root) {
            return Err(GatewayError::Message(
                "backup bundle escapes backup directory".into(),
            ));
        }
        let keys = real.join("keys");
        let secrets = real.join("secrets");
        let safe_dir = |path: &FsPath| {
            fs::symlink_metadata(path)
                .map(|m| m.is_dir() && !m.file_type().is_symlink())
                .unwrap_or(false)
        };
        if !safe_dir(&keys) || !safe_dir(&secrets) {
            return Err(GatewayError::Message("backup bundle is incomplete".into()));
        }
        Some(real)
    } else {
        None
    };

    let bundle_restored = bundle_real.is_some();
    let current = root.join("db/state.db");
    if current.is_file() {
        let current_conn = Connection::open(&current)?;
        let _ = current_conn.execute_batch("PRAGMA wal_checkpoint(TRUNCATE);");
        drop(current_conn);
        let safety = backup_dir.join(format!("pre-restore-{}-{}.db", now(), Uuid::new_v4()));
        fs::copy(&current, &safety)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&safety, fs::Permissions::from_mode(0o600))?;
        }
    }
    if bundle_real.is_some() {
        let safety_bundle = backup_dir.join(format!("pre-restore-{}.bundle", Uuid::new_v4()));
        fs::create_dir_all(safety_bundle.join("keys"))?;
        fs::create_dir_all(safety_bundle.join("secrets"))?;
        copy_private_files(&root.join("keys"), &safety_bundle.join("keys"))?;
        copy_private_files(&root.join("secrets"), &safety_bundle.join("secrets"))?;
    }
    let temp = root.join("db/state.db.restore.tmp");
    let _ = fs::remove_file(&temp);
    fs::copy(&backup_real, &temp)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&temp, fs::Permissions::from_mode(0o600))?;
    }
    fs::rename(&temp, &current)?;
    let _ = fs::remove_file(root.join("db/state.db-wal"));
    let _ = fs::remove_file(root.join("db/state.db-shm"));
    if let Some(bundle) = bundle_real {
        replace_private_files(&bundle.join("keys"), &root.join("keys"))?;
        replace_private_files(&bundle.join("secrets"), &root.join("secrets"))?;
    }
    let bytes = fs::read(&current)?;
    Ok(serde_json::json!({
        "restored": name,
        "sha256": hex::encode(Sha256::digest(&bytes)),
        "bytes": bytes.len(),
        "bundleRestored": bundle_restored
    }))
}
