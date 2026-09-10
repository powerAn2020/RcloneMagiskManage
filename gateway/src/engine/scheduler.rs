use rusqlite::{Connection, TransactionBehavior, params};
use std::fs;
use uuid::Uuid;

use crate::db::{configured_setting, db, rotate_logs};
use crate::engine::mount::{guard_local_path, recover_mount, unmount_derived_bind};
use crate::engine::rclone::{
    materialize_rclone_config, parse_rclone_stats, rclone_command, redact_log_text, remote_id_by_name,
};
use crate::error::{GatewayError, Result};
use crate::security::auth::{acl, audit, safe_mode_enabled};
use crate::security::crypto::{now, restrict_file, valid_path};
use crate::state::AppState;
use crate::types::Job;

pub fn process_kill_command() -> std::process::Command {
    if cfg!(target_os = "android") {
        std::process::Command::new("/system/bin/kill")
    } else if cfg!(unix) {
        std::process::Command::new("kill")
    } else {
        let mut cmd = std::process::Command::new("cmd");
        cmd.args(["/c", "exit 0"]);
        cmd
    }
}

pub fn job(c: &Connection, id: &str) -> Result<Job> {
    c.query_row(
        "SELECT id,type,status,source,destination,dry_run,schedule,next_run_at FROM job WHERE id=?",
        params![id],
        |r| {
            Ok(Job {
                id: r.get(0)?,
                job_type: r.get(1)?,
                status: r.get(2)?,
                source: r.get(3)?,
                destination: r.get(4)?,
                dry_run: r.get::<_, i64>(5)? != 0,
                schedule: r.get(6)?,
                next_run_at: r.get(7)?,
            })
        },
    )
    .map_err(GatewayError::Db)
}

pub fn schedule_interval(schedule: Option<&str>) -> Option<i64> {
    let s = schedule?.trim();
    match s {
        "@hourly" => Some(3600),
        "@daily" => Some(86400),
        "@reboot" => None,
        _ if s.starts_with("@every ") => s[7..]
            .trim_end_matches('s')
            .parse()
            .ok()
            .filter(|v: &i64| *v > 0),
        _ if s.starts_with("*/") && s.ends_with(" * * * *") => s[2..s.len() - 8]
            .parse::<i64>()
            .ok()
            .filter(|v| *v > 0)
            .map(|v| v * 60),
        _ => None,
    }
}

pub fn power_status() -> Vec<String> {
    let mut out = Vec::new();
    let Ok(entries) = fs::read_dir("/sys/class/power_supply") else {
        return out;
    };
    for entry in entries.flatten() {
        let path = entry.path().join("status");
        if let Ok(v) = fs::read_to_string(path) {
            out.push(v.trim().to_ascii_lowercase());
        }
    }
    out
}

pub fn battery_capacity() -> Option<i64> {
    let entries = fs::read_dir("/sys/class/power_supply").ok()?;
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_ascii_lowercase();
        if name.starts_with("battery") {
            if let Ok(v) = fs::read_to_string(entry.path().join("capacity")) {
                if let Ok(n) = v.trim().parse::<i64>() {
                    return Some(n);
                }
            }
        }
    }
    None
}

pub fn interface_up(name: &str) -> bool {
    fs::read_to_string(format!("/sys/class/net/{name}/operstate"))
        .is_ok_and(|v| matches!(v.trim(), "up" | "unknown"))
}

pub fn policy_allows(network: &str, battery: &str) -> bool {
    let network_ok = match network {
        "ANY" => true,
        "WIFI" | "UNMETERED" => interface_up("wlan0"),
        "VPN" => interface_up("tun0") || interface_up("wg0"),
        _ => false,
    };
    let battery_ok = match battery {
        "ANY" => true,
        "CHARGING" => power_status()
            .iter()
            .any(|v| v == "charging" || v == "full"),
        "BATTERY_30" => battery_capacity().is_some_and(|v| v >= 30),
        _ => false,
    };
    network_ok && battery_ok
}

pub fn claim_job_slot(state: &AppState, id: &str, max_concurrent: i64) -> Result<bool> {
    let mut conn = db(state)?;
    let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
    let running: i64 =
        tx.query_row("SELECT COUNT(*) FROM job WHERE status='RUNNING'", [], |r| {
            r.get(0)
        })?;
    if running >= max_concurrent {
        tx.rollback()?;
        return Ok(false);
    }
    let changed = tx.execute(
        "UPDATE job SET status='RUNNING',started_at=?,updated_at=? WHERE id=? AND status IN ('QUEUED','CREATED')",
        params![now(), now(), id],
    )?;
    tx.commit()?;
    Ok(changed > 0)
}

pub fn job_source_permission(kind: &str) -> &'static str {
    if kind == "delete" {
        "file.delete"
    } else {
        "file.read"
    }
}

pub fn validate_transfer_endpoint(
    s: &AppState,
    client: &str,
    target: &str,
    permission: &str,
) -> Result<()> {
    if let Some((remote, path)) = target.split_once(':') {
        if remote.is_empty() || path.is_empty() {
            return Err(GatewayError::Message(
                "PATH_DENIED: invalid remote target".into(),
            ));
        }
        let (id, prefix): (String, String) = db(s)?
            .query_row(
                "SELECT id,base_path FROM remote WHERE name=? AND enabled=1",
                params![remote],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .map_err(|_| GatewayError::Message("remote not found".into()))?;
        acl(s, client, &id, permission, path).and_then(|_| valid_path(path, &prefix).map(|_| ()))
    } else {
        guard_local_path(target)
    }
}

pub fn job_accessible(s: &AppState, client: &str, id: &str) -> Result<()> {
    let (kind, source, destination): (String, String, String) = db(s)?
        .query_row(
            "SELECT type,source,destination FROM job WHERE id=?",
            params![id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .map_err(|_| GatewayError::Message("job not found".into()))?;
    let source_permission = job_source_permission(&kind);
    validate_transfer_endpoint(s, client, &source, source_permission)?;
    if !destination.is_empty() {
        let dest_perm = if matches!(kind.as_str(), "sync" | "copy" | "move" | "bisync") {
            "file.write"
        } else {
            "file.read"
        };
        validate_transfer_endpoint(s, client, &destination, dest_perm)?;
    }
    Ok(())
}

pub fn create_job_row(
    s: &AppState,
    client: &str,
    kind: &str,
    source: &str,
    destination: &str,
    dry_run: bool,
) -> Result<String> {
    let id = Uuid::new_v4().to_string();
    let t = now();
    db(s)?.execute(
        "INSERT INTO job(id,type,status,source,destination,dry_run,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
        params![id, kind, "QUEUED", source, destination, dry_run as i64, client, t, t],
    )?;
    Ok(id)
}

pub fn finish_job(
    state: &AppState,
    id: &str,
    run_id: &str,
    client: &str,
    state_name: &str,
    next: Option<i64>,
    error: Option<&str>,
) {
    let effective_state = if let Ok(c) = db(state) {
        let effective_state = c
            .query_row("SELECT status FROM job WHERE id=?", params![id], |r| {
                r.get::<_, String>(0)
            })
            .ok()
            .filter(|current| {
                matches!(current.as_str(), "CANCEL_REQUESTED" | "CANCELLED")
                    && state_name != "CANCELLED"
            })
            .map(|_| "CANCELLED")
            .unwrap_or(state_name);
        let _ = c.execute(
            "UPDATE job_run SET state=?,finished_at=?,error_code=? WHERE id=?",
            params![effective_state, now(), error, run_id],
        );
        let _ = c.execute("UPDATE job SET status=?,finished_at=?,updated_at=?,next_run_at=?,last_error_code=? WHERE id=?", params![effective_state, now(), now(), next, error, id]);
        effective_state.to_owned()
    } else {
        state_name.to_owned()
    };
    let _ = audit(
        state,
        Some(client),
        "job.run",
        Some(id),
        None,
        &effective_state,
        error,
    );
}

pub async fn run_job(state: AppState, id: String) {
    let policy: Option<(String, String)> = db(&state).ok().and_then(|c| {
        c.query_row(
            "SELECT network_policy,battery_policy FROM job WHERE id=?",
            params![id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .ok()
    });
    if let Some((network, battery)) = policy {
        if !policy_allows(&network, &battery) {
            return;
        }
    } else {
        return;
    }
    let max_concurrent = configured_setting(&state.root, "maxConcurrentJobs", 2)
        .unwrap_or(2)
        .clamp(1, 4) as i64;
    let claimed = claim_job_slot(&state, &id, max_concurrent).unwrap_or(false);
    if !claimed {
        return;
    }
    let row: Result<(
        String,
        String,
        String,
        Option<String>,
        i64,
        Option<String>,
        Option<String>,
        String,
        String,
    )> = (|| {
        let c = db(&state)?;
        Ok(c.query_row(
            "SELECT type,source,destination,created_by,dry_run,options_json,schedule,network_policy,battery_policy FROM job WHERE id=?",
            params![id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?, r.get(5)?, r.get(6)?, r.get(7)?, r.get(8)?)),
        )?)
    })();
    let Ok((
        kind,
        source,
        destination,
        created_by,
        dry_run,
        options_json,
        schedule,
        _network,
        _battery,
    )) = row
    else {
        return;
    };
    if let Some(client) = created_by.as_deref() {
        let source_permission = job_source_permission(&kind);
        if validate_transfer_endpoint(&state, client, &source, source_permission).is_err()
            || (!destination.is_empty()
                && validate_transfer_endpoint(&state, client, &destination, "file.write").is_err())
        {
            let _ = db(&state).and_then(|c| {
                Ok(c.execute(
                    "UPDATE job SET status='FAILED',finished_at=?,updated_at=?,last_error_code='REMOTE_DENIED' WHERE id=?",
                    params![now(), now(), id],
                )?)
            });
            let _ = audit(
                &state,
                created_by.as_deref(),
                "job.run",
                Some(&id),
                None,
                "FAILED",
                Some("REMOTE_DENIED"),
            );
            return;
        }
    }
    if source.starts_with('-')
        || destination.starts_with('-')
        || source.contains('\0')
        || destination.contains('\0')
    {
        let _ = db(&state).and_then(|c| Ok(c.execute("UPDATE job SET status='FAILED',last_error_code='INVALID_ARGUMENT',updated_at=? WHERE id=?", params![now(),id])?));
        let _ = audit(
            &state,
            created_by.as_deref(),
            "job.run",
            Some(&id),
            None,
            "FAILED",
            Some("INVALID_ARGUMENT"),
        );
        return;
    }
    let run_id = Uuid::new_v4().to_string();
    if let Ok(c) = db(&state) {
        let _ = c.execute(
            "INSERT INTO job_run(id,job_id,state,started_at) VALUES(?,?,?,?)",
            params![run_id, id, "RUNNING", now()],
        );
    }
    let mut remote_ids = Vec::new();
    for target in [&source, &destination] {
        if let Some((name, _)) = target.split_once(':') {
            if let Ok(rid) = remote_id_by_name(&state, name) {
                remote_ids.push(rid);
            }
        }
    }
    remote_ids.sort();
    remote_ids.dedup();
    let config = match materialize_rclone_config(&state, &remote_ids) {
        Ok(v) => v,
        Err(e) => {
            finish_job(
                &state,
                &id,
                &run_id,
                created_by.as_deref().unwrap_or("system"),
                "FAILED",
                None,
                Some("SECRET_UNAVAILABLE"),
            );
            let _ = audit(
                &state,
                created_by.as_deref(),
                "job.run",
                Some(&id),
                None,
                "FAILED",
                Some(&e.to_string()),
            );
            return;
        }
    };
    let mut command = rclone_command(&config);
    command.arg(&kind).arg(&source);
    if kind != "delete" {
        command.arg(&destination);
    }
    command.args(["--use-json-log", "--stats", "1s", "--stats-log-level", "NOTICE"]);
    if dry_run != 0 {
        command.arg("--dry-run");
    }
    if let Some(raw) = options_json.and_then(|x| serde_json::from_str::<serde_json::Value>(&x).ok())
    {
        if raw.get("overwrite").and_then(|v| v.as_bool()) != Some(true)
            && (kind == "copy" || kind == "move")
        {
            command.arg("--ignore-existing");
        }
        if raw.get("deleteExcluded").and_then(|v| v.as_bool()) == Some(true) && kind == "sync" {
            command.arg("--delete-excluded");
        }
        if let Some(v) = raw
            .get("transfers")
            .and_then(|v| v.as_u64())
            .filter(|v| (1..=32).contains(v))
        {
            command.args(["--transfers", &v.to_string()]);
        }
        if let Some(v) = raw
            .get("checkers")
            .and_then(|v| v.as_u64())
            .filter(|v| (1..=64).contains(v))
        {
            command.args(["--checkers", &v.to_string()]);
        }
        if let Some(v) = raw
            .get("bwLimit")
            .and_then(|v| v.as_str())
            .filter(|v| {
                !v.is_empty()
                    && v.len() <= 64
                    && v.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, ':' | ',' | ' ' | '.'))
            })
        {
            command.args(["--bwlimit", v]);
        }
    }
    let mut child = match command
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .spawn()
    {
        Ok(c) => c,
        Err(_) => {
            if let Some(p) = config {
                let _ = fs::remove_file(p);
            }
            finish_job(
                &state,
                &id,
                &run_id,
                created_by.as_deref().unwrap_or("system"),
                "FAILED",
                None,
                Some("RCLONE_SPAWN_FAILED"),
            );
            return;
        }
    };
    if let Some(pid) = child.id() {
        if let Ok(c) = db(&state) {
            let _ = c.execute(
                "UPDATE job_run SET pid=? WHERE id=?",
                params![pid as i64, run_id],
            );
        }
    }
    let mut stdout = child.stdout.take();
    let mut stderr = child.stderr.take();
    let stdout_handle = tokio::spawn(async move {
        let mut buf = Vec::new();
        if let Some(mut stream) = stdout.as_mut() {
            let _ = tokio::io::AsyncReadExt::read_to_end(&mut stream, &mut buf).await;
        }
        buf
    });
    let stderr_handle = tokio::spawn(async move {
        let mut buf = Vec::new();
        if let Some(mut stream) = stderr.as_mut() {
            let _ = tokio::io::AsyncReadExt::read_to_end(&mut stream, &mut buf).await;
        }
        buf
    });
    let mut cancelled = false;
    let mut paused = false;
    loop {
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) => {
                let requested: Option<String> = db(&state).ok().and_then(|c| {
                    c.query_row("SELECT status FROM job WHERE id=?", params![id], |r| {
                        r.get(0)
                    })
                    .ok()
                });
                if requested.as_deref() == Some("CANCEL_REQUESTED")
                    || requested.as_deref() == Some("PAUSE_REQUESTED")
                {
                    cancelled = requested.as_deref() == Some("CANCEL_REQUESTED");
                    paused = !cancelled;
                    let _ = child.kill().await;
                    break;
                }
                tokio::time::sleep(std::time::Duration::from_millis(250)).await;
            }
            Err(_) => break,
        }
    }
    let exit_status = child.wait().await.ok();
    let stdout_bytes = stdout_handle.await.unwrap_or_default();
    let stderr_bytes = stderr_handle.await.unwrap_or_default();
    let persisted_cancel = db(&state)
        .ok()
        .and_then(|c| {
            c.query_row("SELECT status FROM job WHERE id=?", params![id], |r| {
                r.get::<_, String>(0)
            })
            .ok()
        })
        .is_some_and(|v| matches!(v.as_str(), "CANCEL_REQUESTED" | "CANCELLED"));
    cancelled |= persisted_cancel;
    let status =
        exit_status.as_ref().map(|o| o.success()).unwrap_or(false) && !cancelled && !paused;
    let final_state = if cancelled {
        "CANCELLED"
    } else if paused {
        "PAUSED"
    } else if status {
        "SUCCESS"
    } else {
        "FAILED"
    };
    let log = state.root.join("logs").join(format!("job-{id}.log"));
    let combined = [&stdout_bytes[..], &stderr_bytes[..]].concat();
    let stats = parse_rclone_stats(&combined);
    let redacted_log = redact_log_text(&String::from_utf8_lossy(&combined));
    let redacted_error = redact_log_text(&String::from_utf8_lossy(&stderr_bytes));
    if let Ok(c) = db(&state) {
        let _ = c.execute(
            "UPDATE job_run SET transferred_bytes=COALESCE(?,transferred_bytes),total_bytes=COALESCE(?,total_bytes),transferred_files=COALESCE(?,transferred_files),total_files=COALESCE(?,total_files),error_count=COALESCE(?,error_count),error_message=? WHERE id=?",
            params![stats.bytes, stats.total_bytes, stats.files, stats.total_files, stats.errors, (!status).then(|| redacted_error.chars().take(4096).collect::<String>()), run_id],
        );
    }
    let _ = fs::write(&log, redacted_log);
    let _ = restrict_file(&log);
    if let Some(p) = config {
        let _ = fs::remove_file(p);
    }
    let next = if status {
        schedule_interval(schedule.as_deref()).map(|d| now() + d)
    } else {
        None
    };
    let stored_state = if next.is_some() {
        "CREATED"
    } else {
        final_state
    };
    if let Ok(c) = db(&state) {
        let _ = c.execute(
            "UPDATE job_run SET state=?,finished_at=? WHERE id=?",
            params![final_state, now(), run_id],
        );
        let _ = c.execute(
            "UPDATE job SET status=?,finished_at=?,updated_at=?,next_run_at=?,last_error_code=? WHERE id=?",
            params![stored_state, now(), now(), next, if status { Option::<String>::None } else { (final_state == "FAILED").then(|| final_state.to_string()) }, id],
        );
    }
    let _ = audit(
        &state,
        created_by.as_deref(),
        "job.run",
        Some(&id),
        None,
        final_state,
        if status { None } else { Some(final_state) },
    );
}

pub async fn scheduler(state: AppState) {
    if let Ok(c) = db(&state) {
        let pids: Vec<i64> = c
            .prepare("SELECT pid FROM job_run WHERE state='RUNNING' AND pid IS NOT NULL")
            .and_then(|mut st| {
                st.query_map([], |r| r.get(0))?
                    .collect::<rusqlite::Result<Vec<_>>>()
            })
            .unwrap_or_default();
        for pid in pids {
            let _ = process_kill_command()
                .args(["-TERM", &pid.to_string()])
                .status();
        }
    }
    if let Ok(c) = db(&state) {
        let _ = c.execute("UPDATE job SET status='FAILED',last_error_code='GATEWAY_RESTARTED',updated_at=? WHERE status IN ('RUNNING','PAUSE_REQUESTED','CANCEL_REQUESTED')", params![now()]);
        let _ = c.execute("UPDATE mount_profile SET status='STOPPED',pid=NULL,updated_at=? WHERE status IN ('STARTING','RUNNING','STOPPING')", params![now()]);
    }
    if let Ok(entries) = fs::read_dir(state.root.join("runtime")) {
        for entry in entries.flatten() {
            let p = entry.path();
            if p.file_name()
                .and_then(|n| n.to_str())
                .is_some_and(|n| n.starts_with("mount-") && n.ends_with(".conf"))
            {
                let _ = fs::remove_file(p);
            }
        }
    }
    let safe_at_boot = safe_mode_enabled(&state);
    let boot_jobs: Vec<String> = db(&state)
        .and_then(|c| {
            let mut st =
                c.prepare("SELECT id FROM job WHERE status='CREATED' AND schedule='@reboot'")?;
            Ok(st
                .query_map([], |r| r.get(0))?
                .collect::<rusqlite::Result<Vec<_>>>()?)
        })
        .unwrap_or_default();
    for id in boot_jobs.into_iter().filter(|_| !safe_at_boot) {
        let s = state.clone();
        tokio::spawn(async move {
            run_job(s, id).await;
        });
    }
    let enabled_mounts: Vec<String> = db(&state)
        .and_then(|c| {
            let mut st = c.prepare("SELECT id FROM mount_profile WHERE enabled=1")?;
            Ok(st
                .query_map([], |r| r.get(0))?
                .collect::<rusqlite::Result<Vec<_>>>()?)
        })
        .unwrap_or_default();
    for id in enabled_mounts.into_iter().filter(|_| !safe_at_boot) {
        let s = state.clone();
        tokio::spawn(async move {
            recover_mount(s, id).await;
        });
    }
    let mut tick = tokio::time::interval(std::time::Duration::from_secs(5));
    loop {
        tick.tick().await;
        let _ = rotate_logs(&state.root);
        if let Ok(c) = db(&state) {
            let _ = c.execute("DELETE FROM system_config WHERE (key LIKE 'request-nonce:%' OR key LIKE 'delete-confirm:%' OR key LIKE 'remote-delete-confirm:%') AND updated_at<?", params![now()]);
        }
        let safe = safe_mode_enabled(&state);
        let running_mounts: Vec<(String, i64, String)> = db(&state)
            .and_then(|c| {
                let mut st = c.prepare(
                    "SELECT id,pid,mount_point FROM mount_profile WHERE status='RUNNING' AND pid IS NOT NULL",
                )?;
                Ok(st
                    .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?
                    .collect::<rusqlite::Result<Vec<_>>>()?)
            })
            .unwrap_or_default();
        for (id, pid, mount_point) in running_mounts {
            let alive = process_kill_command()
                .args(["-0", &pid.to_string()])
                .status()
                .is_ok_and(|v| v.success());
            if !alive {
                let _ = db(&state).and_then(|c| {
                    Ok(c.execute(
                        "UPDATE mount_profile SET status='STOPPED',pid=NULL,updated_at=? WHERE id=?",
                        params![now(), id],
                    )?)
                });
                unmount_derived_bind(&mount_point);
                crate::engine::mount::remove_mount_config(&state.root, &id);
            }
        }
        if safe {
            continue;
        }
        let due: Vec<String> = match db(&state).and_then(|c| {
            let mut st = c.prepare("SELECT id FROM job WHERE status='QUEUED' OR (status='CREATED' AND schedule IS NOT NULL AND schedule <> '@reboot' AND next_run_at IS NOT NULL AND next_run_at<=?)")?;
            Ok(st.query_map(params![now()], |r| r.get(0))?.collect::<rusqlite::Result<Vec<_>>>()?)
        }) { Ok(v) => v, Err(_) => continue };
        let max_concurrent = configured_setting(&state.root, "maxConcurrentJobs", 2)
            .unwrap_or(2)
            .clamp(1, 4) as usize;
        let running = db(&state)
            .ok()
            .and_then(|c| {
                c.query_row("SELECT COUNT(*) FROM job WHERE status='RUNNING'", [], |r| {
                    r.get::<_, i64>(0)
                })
                .ok()
            })
            .unwrap_or(0)
            .max(0) as usize;
        for id in due.into_iter().take(max_concurrent.saturating_sub(running)) {
            let s = state.clone();
            tokio::spawn(async move {
                run_job(s, id).await;
            });
        }
    }
}
