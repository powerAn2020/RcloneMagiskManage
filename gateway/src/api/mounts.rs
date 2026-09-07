use axum::{
    Json,
    extract::{Path, State},
    http::{HeaderMap, StatusCode},
};
use rusqlite::{OptionalExtension, params};
use std::{fs, path::Path as FsPath};
use uuid::Uuid;

use crate::db::{configured_setting, db, parse_size_bytes};
use crate::engine::{
    bind_mount_when_ready, materialize_mount_config, mount, path_is_within, process_kill_command,
    rclone_command, remove_mount_config, remote_target, reset_mount_start, unmount_derived_bind,
};
use crate::error::{GatewayError, Result};
use crate::security::{acl, audit, now, scope, valid_mount, valid_path, validate_identity};
use crate::state::AppState;
use crate::types::{Mount, MountIn};

pub async fn mounts(State(s): State<AppState>, h: HeaderMap) -> Result<Json<Vec<Mount>>> {
    let client = scope(&h, &s, "mount.read")?;
    let c = db(&s)?;
    let mut st = c.prepare(
        "SELECT mp.id,mp.name,mp.remote_id,r.name,mp.remote_path,mp.mount_point,mp.cache_dir,mp.status,mp.pid,mp.read_only,mp.cache_mode,mp.cache_max_size,mp.cache_max_age,mp.enabled FROM mount_profile mp LEFT JOIN remote r ON mp.remote_id=r.id ORDER BY mp.created_at",
    )?;
    let rows = st
        .query_map([], |r| {
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
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    drop(st);
    drop(c);
    Ok(Json(
        rows.into_iter()
            .filter(|m| acl(&s, &client, &m.remote_id, "file.read", &m.remote_path).is_ok())
            .collect(),
    ))
}

pub async fn mount_create(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(i): Json<MountIn>,
) -> Result<(StatusCode, Json<Mount>)> {
    let c = scope(&h, &s, "mount.write")?;
    validate_identity(&i.name, "mount name", 128)?;
    valid_mount(&i.mount_point)?;
    let conflict: Option<String> = db(&s)?.query_row(
        "SELECT id FROM mount_profile WHERE mount_point=? AND status IN ('STARTING','RUNNING','STOPPING')",
        params![i.mount_point],
        |r| r.get(0),
    ).optional()?;
    if conflict.is_some() {
        return Err(GatewayError::Message(
            "MOUNT_CONFLICT: mount point already active".into(),
        ));
    }
    let (_, base) = remote_target(&s, &i.remote_id)?;
    let remote_path = valid_path(i.remote_path.as_deref().unwrap_or("/"), &base)?;
    let mount_permission = if i.read_only.unwrap_or(false) {
        "file.read"
    } else {
        "file.write"
    };
    acl(&s, &c, &i.remote_id, mount_permission, &remote_path)?;
    let cache_dir = i.cache_dir.clone().unwrap_or_else(|| {
        s.root
            .join("cache")
            .join(format!("mount-{}", Uuid::new_v4()))
            .display()
            .to_string()
    });
    if !path_is_within(&s.root.join("cache"), FsPath::new(&cache_dir)) {
        return Err(GatewayError::Message(
            "cacheDir must be inside the manager cache directory".into(),
        ));
    }
    let cache_mode = i.cache_mode.clone().unwrap_or_else(|| "full".into());
    if !["off", "minimal", "writes", "full"].contains(&cache_mode.as_str()) {
        return Err(GatewayError::Message("invalid cache mode".into()));
    }
    let cache_max_size = i.cache_max_size.clone().unwrap_or_else(|| "32G".into());
    let configured_cache_limit =
        configured_setting(&s.root, "cacheMaxBytes", 32 * 1024 * 1024 * 1024)?;
    if parse_size_bytes(&cache_max_size).is_none_or(|v| v > configured_cache_limit) {
        return Err(GatewayError::Message(
            "cacheMaxSize exceeds the configured cache limit".into(),
        ));
    }
    let cache_max_age = i.cache_max_age.clone().unwrap_or_else(|| "36h".into());
    let id = Uuid::new_v4().to_string();
    let t = now();
    db(&s)?.execute(
        "INSERT INTO mount_profile(id,name,remote_id,remote_path,mount_point,cache_dir,read_only,cache_mode,cache_max_size,cache_max_age,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        params![id, i.name, i.remote_id, remote_path, i.mount_point, cache_dir, i.read_only.unwrap_or(false) as i64, cache_mode, cache_max_size, cache_max_age, t, t],
    )?;
    audit(
        &s,
        Some(&c),
        "mount.create",
        Some(&id),
        Some(&i.remote_id),
        "SUCCESS",
        None,
    )?;
    let conn = db(&s)?;
    Ok((StatusCode::CREATED, Json(mount(&conn, &id)?)))
}

pub async fn mount_action(
    State(s): State<AppState>,
    h: HeaderMap,
    Path((id, a)): Path<(String, String)>,
) -> Result<(StatusCode, Json<Mount>)> {
    let c = scope(&h, &s, "mount.write")?;
    let conn = db(&s)?;
    let m = mount(&conn, &id)?;
    drop(conn);
    valid_mount(&m.mount_point)?;
    let mount_permission = if m.read_only {
        "file.read"
    } else {
        "file.write"
    };
    acl(&s, &c, &m.remote_id, mount_permission, &m.remote_path)?;
    let st = match a.as_str() {
        "start" => "STARTING",
        "stop" => "STOPPING",
        "enable" => "STOPPED",
        "disable" => "STOPPED",
        _ => return Err(GatewayError::Message("unknown mount action".into())),
    };
    if a == "start" && matches!(m.status.as_str(), "STARTING" | "RUNNING") {
        return Err(GatewayError::Message("mount is already active".into()));
    }
    if matches!(a.as_str(), "start" | "stop") {
        let conn = db(&s)?;
        conn.execute(
            "UPDATE mount_profile SET status=?,updated_at=? WHERE id=?",
            params![st, now(), id],
        )?;
    }
    if a == "enable" {
        let conn = db(&s)?;
        conn.execute(
            "UPDATE mount_profile SET enabled=?,status=CASE WHEN pid IS NULL THEN 'STOPPED' ELSE status END,updated_at=? WHERE id=?",
            params![1, now(), id],
        )?;
    } else if a == "disable" {
        if let Some(pid) = m.pid {
            let _ = process_kill_command()
                .args(["-TERM", &pid.to_string()])
                .status();
        }
        let conn = db(&s)?;
        conn.execute(
            "UPDATE mount_profile SET enabled=0,status='STOPPED',pid=NULL,updated_at=? WHERE id=?",
            params![now(), id],
        )?;
        unmount_derived_bind(&m.mount_point);
        remove_mount_config(&s.root, &id);
    } else if a == "start" {
        let (remote_name, _) = match remote_target(&s, &m.remote_id) {
            Ok(value) => value,
            Err(error) => {
                reset_mount_start(&s, &id);
                return Err(error);
            }
        };
        if let Err(error) = fs::create_dir_all(&m.mount_point) {
            reset_mount_start(&s, &id);
            return Err(error.into());
        }
        let config = match materialize_mount_config(&s, &id, &m.remote_id) {
            Ok(value) => value,
            Err(error) => {
                reset_mount_start(&s, &id);
                return Err(error);
            }
        };
        let mut command = rclone_command(&Some(config.clone()));
        command
            .args([
                "mount",
                &format!("{remote_name}:{}", m.remote_path),
                &m.mount_point,
            ])
            .arg("--vfs-cache-mode")
            .arg(&m.cache_mode)
            .arg("--vfs-cache-max-size")
            .arg(&m.cache_max_size)
            .arg("--vfs-cache-max-age")
            .arg(&m.cache_max_age);
        if let Some(cache_dir) = &m.cache_dir {
            if !path_is_within(&s.root.join("cache"), FsPath::new(cache_dir)) {
                let _ = fs::remove_file(&config);
                reset_mount_start(&s, &id);
                return Err(GatewayError::Message(
                    "invalid mount cache directory".into(),
                ));
            }
            if let Err(error) = fs::create_dir_all(cache_dir) {
                let _ = fs::remove_file(&config);
                reset_mount_start(&s, &id);
                return Err(error.into());
            }
            command.arg("--cache-dir").arg(cache_dir);
        }
        if m.read_only {
            command.arg("--read-only");
        }
        #[cfg(unix)]
        {
            command.arg("--allow-non-empty");
            command.arg("--allow-other");
        }
        let child = match command.spawn() {
            Ok(child) => child,
            Err(e) => {
                let _ = fs::remove_file(&config);
                reset_mount_start(&s, &id);
                return Err(GatewayError::Message(format!("mount start failed: {e}")));
            }
        };
        if let Some(pid) = child.id() {
            let conn = db(&s)?;
            conn.execute(
                "UPDATE mount_profile SET status='RUNNING',pid=?,updated_at=? WHERE id=?",
                params![pid as i64, now(), id],
            )?;
            tokio::spawn(bind_mount_when_ready(m.mount_point.clone()));
            let monitor_state = s.clone();
            let monitor_id = id.clone();
            let monitor_point = m.mount_point.clone();
            tokio::spawn(async move {
                let mut child = child;
                let _ = child.wait().await;
                if let Ok(c) = db(&monitor_state) {
                    let _ = c.execute(
                        "UPDATE mount_profile SET status='STOPPED',pid=NULL,updated_at=? WHERE id=? AND pid=?",
                        params![now(), monitor_id, pid as i64],
                    );
                }
                unmount_derived_bind(&monitor_point);
                remove_mount_config(&monitor_state.root, &monitor_id);
            });
        }
    } else if let Some(pid) = m.pid {
        let _ = process_kill_command()
            .args(["-TERM", &pid.to_string()])
            .status();
        let conn = db(&s)?;
        conn.execute(
            "UPDATE mount_profile SET status='STOPPED',pid=NULL,updated_at=? WHERE id=?",
            params![now(), id],
        )?;
        unmount_derived_bind(&m.mount_point);
        let _ = fs::remove_file(s.root.join("runtime").join(format!("mount-{id}.conf")));
    } else if a == "stop" {
        let conn = db(&s)?;
        conn.execute(
            "UPDATE mount_profile SET status='STOPPED',pid=NULL,updated_at=? WHERE id=?",
            params![now(), id],
        )?;
        unmount_derived_bind(&m.mount_point);
        remove_mount_config(&s.root, &id);
    }
    audit(
        &s,
        Some(&c),
        &format!("mount.{a}"),
        Some(&id),
        Some(&m.remote_id),
        "ACCEPTED",
        None,
    )?;
    let conn = db(&s)?;
    Ok((StatusCode::ACCEPTED, Json(mount(&conn, &id)?)))
}
