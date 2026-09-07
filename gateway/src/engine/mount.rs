use rusqlite::{Connection, params};
use std::{
    fs,
    path::{Path as FsPath, PathBuf},
};

use crate::db::db;
use crate::engine::rclone::{materialize_mount_config, rclone_command};
use crate::error::{GatewayError, Result};
use crate::security::crypto::{now, valid_mount};
use crate::state::AppState;
use crate::types::Mount;

pub fn remove_mount_config(root: &FsPath, id: &str) {
    let _ = fs::remove_file(root.join("runtime").join(format!("mount-{id}.conf")));
}

pub fn reset_mount_start(state: &AppState, id: &str) {
    if let Ok(c) = db(state) {
        let _ = c.execute(
            "UPDATE mount_profile SET status='STOPPED',pid=NULL,updated_at=? WHERE id=?",
            params![now(), id],
        );
    }
    remove_mount_config(&state.root, id);
}

pub fn path_is_within(base: &FsPath, candidate: &FsPath) -> bool {
    use std::path::Component;
    if (!candidate.is_absolute() && !(cfg!(windows) && candidate.has_root()))
        || candidate
            .components()
            .any(|component| matches!(component, Component::ParentDir))
    {
        return false;
    }
    if !candidate.starts_with(base) {
        return false;
    }
    if !base.exists() {
        return true;
    }
    let base_canonical = match fs::canonicalize(base) {
        Ok(path) => path,
        Err(_) => return false,
    };
    let mut nearest = candidate;
    while !nearest.exists() {
        let Some(parent) = nearest.parent() else {
            return false;
        };
        if parent == nearest {
            break;
        }
        nearest = parent;
    }
    let nearest_canonical = match fs::canonicalize(nearest) {
        Ok(path) => path,
        Err(_) => return false,
    };
    nearest_canonical.starts_with(&base_canonical)
}

pub fn derived_bind_target(source: &str) -> Option<String> {
    let name = source.strip_prefix("/mnt/rclone-")?;
    if name.is_empty()
        || !name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.'))
    {
        return None;
    }
    Some(format!("/data/media/0/{name}"))
}

pub fn umount_command() -> std::process::Command {
    if cfg!(target_os = "android") {
        std::process::Command::new("/system/bin/umount")
    } else if cfg!(unix) {
        std::process::Command::new("umount")
    } else {
        let mut cmd = std::process::Command::new("cmd");
        cmd.args(["/c", "exit 0"]);
        cmd
    }
}

pub async fn bind_mount_when_ready(source: String) {
    let Some(target) = derived_bind_target(&source) else {
        return;
    };
    let _ = fs::create_dir_all(&target);
    for _ in 0..20 {
        let mut command = if cfg!(target_os = "android") {
            tokio::process::Command::new("/system/bin/mount")
        } else {
            tokio::process::Command::new("mount")
        };
        let ok = command
            .args(["--bind", &source, &target])
            .status()
            .await
            .is_ok_and(|s| s.success());
        if ok {
            return;
        }
        tokio::time::sleep(std::time::Duration::from_millis(500)).await;
    }
}

pub fn unmount_derived_bind(source: &str) {
    let Some(target) = derived_bind_target(source) else {
        return;
    };
    let _ = umount_command().arg(&target).status();
}

pub fn valid_local_path(path: &str) -> Result<()> {
    if path.is_empty()
        || !path.starts_with('/')
        || path.starts_with('-')
        || path.bytes().any(|b| b < 0x20)
        || path.split('/').any(|p| p == "..")
    {
        return Err(GatewayError::Message(
            "PATH_DENIED: invalid local path".into(),
        ));
    }
    for blocked in [
        "/system",
        "/vendor",
        "/product",
        "/proc",
        "/sys",
        "/dev",
        "/data/adb",
    ] {
        if path == blocked || path.starts_with(&(blocked.to_string() + "/")) {
            return Err(GatewayError::Message(
                "PATH_DENIED: protected local path".into(),
            ));
        }
    }
    Ok(())
}

pub fn guard_local_path(path: &str) -> Result<()> {
    valid_local_path(path)?;
    let mut candidate = FsPath::new(path);
    while !candidate.exists() {
        candidate = candidate.parent().ok_or_else(|| {
            GatewayError::Message("PATH_DENIED: local path has no existing ancestor".into())
        })?;
    }
    let canonical = fs::canonicalize(candidate)
        .map_err(|_| GatewayError::Message("PATH_DENIED: local path cannot be resolved".into()))?;
    for blocked in [
        "/system",
        "/vendor",
        "/product",
        "/proc",
        "/sys",
        "/dev",
        "/data/adb",
    ] {
        let blocked_path = FsPath::new(blocked);
        let blocked_canonical =
            fs::canonicalize(blocked_path).unwrap_or_else(|_| PathBuf::from(blocked));
        if canonical == blocked_canonical || canonical.starts_with(&blocked_canonical) {
            return Err(GatewayError::Message(
                "PATH_DENIED: local path resolves inside a protected tree".into(),
            ));
        }
    }
    Ok(())
}

pub fn mount(c: &Connection, id: &str) -> Result<Mount> {
    c.query_row(
        "SELECT mp.id,mp.name,mp.remote_id,r.name,mp.remote_path,mp.mount_point,mp.cache_dir,mp.status,mp.pid,mp.read_only,mp.cache_mode,mp.cache_max_size,mp.cache_max_age,mp.enabled FROM mount_profile mp LEFT JOIN remote r ON mp.remote_id=r.id WHERE mp.id=?",
        params![id],
        |r| {
            Ok(Mount {
                id: r.get(0)?,
                name: r.get(1)?,
                remote_id: r.get(2)?,
                remote_name: r.get(3)?,
                remote_path: r.get(4)?,
                mount_point: r.get(5)?,
                cache_dir: r.get(6)?,
                status: r.get(7)?,
                pid: r.get(8)?,
                read_only: r.get::<_, i64>(9)? != 0,
                cache_mode: r.get(10)?,
                cache_max_size: r.get(11)?,
                cache_max_age: r.get(12)?,
                enabled: r.get::<_, i64>(13)? != 0,
            })
        },
    )
    .map_err(GatewayError::Db)
}

pub fn remote_target(s: &AppState, target: &str) -> Result<(String, String)> {
    if let Some((remote_id, subpath)) = target.split_once(':') {
        let (name, base): (String, String) = db(s)?
            .query_row(
                "SELECT name,base_path FROM remote WHERE (id=? OR name=?) AND enabled=1",
                params![remote_id, remote_id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .map_err(|_| GatewayError::Message("remote not found or disabled".into()))?;
        let sub = crate::security::crypto::valid_path(subpath, &base)?;
        Ok((name, sub))
    } else {
        let (name, base): (String, String) = db(s)?
            .query_row(
                "SELECT name,base_path FROM remote WHERE (id=? OR name=?) AND enabled=1",
                params![target, target],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .map_err(|_| GatewayError::Message("remote not found or disabled".into()))?;
        Ok((name, base))
    }
}

pub async fn recover_mount(state: AppState, id: String) {
    let row: Result<(
        String,
        String,
        String,
        String,
        Option<String>,
        bool,
        String,
        String,
        String,
    )> = (|| {
        let c = db(&state)?;
        Ok(c.query_row("SELECT name,remote_id,remote_path,mount_point,cache_dir,read_only,cache_mode,cache_max_size,cache_max_age FROM mount_profile WHERE id=? AND enabled=1", params![id], |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?,r.get(4)?,r.get::<_,i64>(5)? != 0,r.get(6)?,r.get(7)?,r.get(8)?)))?)
    })();
    let Ok((
        _,
        remote_id,
        remote_path,
        mount_point,
        cache_dir,
        read_only,
        cache_mode,
        max_size,
        max_age,
    )) = row
    else {
        return;
    };
    if valid_mount(&mount_point).is_err() {
        return;
    }
    let Ok((remote_name, _)) = remote_target(&state, &remote_id) else {
        return;
    };
    let _ = fs::create_dir_all(&mount_point);
    let Ok(config) = materialize_mount_config(&state, &id, &remote_id) else {
        remove_mount_config(&state.root, &id);
        return;
    };
    let mut command = rclone_command(&Some(config.clone()));
    command.args([
        "mount",
        &format!("{remote_name}:{remote_path}"),
        &mount_point,
        "--vfs-cache-mode",
        &cache_mode,
        "--vfs-cache-max-size",
        &max_size,
        "--vfs-cache-max-age",
        &max_age,
    ]);
    if let Some(cache_dir) = cache_dir {
        if path_is_within(&state.root.join("cache"), FsPath::new(&cache_dir)) {
            let _ = fs::create_dir_all(&cache_dir);
            command.args(["--cache-dir", &cache_dir]);
        }
    }
    if read_only {
        command.arg("--read-only");
    }
    let child = command.spawn();
    if let Ok(mut child) = child {
        if let Some(pid) = child.id() {
            if let Ok(c) = db(&state) {
                let _ = c.execute(
                    "UPDATE mount_profile SET status='RUNNING',pid=?,updated_at=? WHERE id=?",
                    params![pid as i64, now(), id],
                );
            }
            tokio::spawn(bind_mount_when_ready(mount_point.clone()));
            let monitor_state = state.clone();
            let monitor_id = id.clone();
            tokio::spawn(async move {
                let _ = child.wait().await;
                if let Ok(c) = db(&monitor_state) {
                    let _ = c.execute(
                        "UPDATE mount_profile SET status='STOPPED',pid=NULL,updated_at=? WHERE id=? AND pid=?",
                        params![now(), monitor_id, pid as i64],
                    );
                }
                unmount_derived_bind(&mount_point);
                remove_mount_config(&monitor_state.root, &monitor_id);
            });
        } else {
            remove_mount_config(&state.root, &id);
        }
    } else {
        remove_mount_config(&state.root, &id);
    }
}
