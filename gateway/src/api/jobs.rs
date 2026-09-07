use axum::{
    Json,
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode},
};
use rusqlite::params;
use std::fs;
use uuid::Uuid;

use crate::db::db;
use crate::engine::{job, job_accessible, run_job, schedule_interval, validate_transfer_endpoint};
use crate::error::{GatewayError, Result};
use crate::security::{audit, ini_line_safe, now, scope};
use crate::state::AppState;
use crate::types::{Job, JobIn, JobQuery};

pub async fn jobs(
    State(s): State<AppState>,
    h: HeaderMap,
    Query(q): Query<JobQuery>,
) -> Result<Json<Vec<Job>>> {
    let client = scope(&h, &s, "job.read")?;
    let c = db(&s)?;
    let mut st = c.prepare("SELECT id,type,status,source,destination,dry_run,schedule,next_run_at FROM job WHERE (?1 IS NULL OR status=?1) AND (?2 IS NULL OR type=?2) ORDER BY created_at DESC")?;
    let rows = st
        .query_map(params![q.status, q.job_type], |r| {
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
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    drop(st);
    drop(c);
    Ok(Json(
        rows.into_iter()
            .filter(|v| job_accessible(&s, &client, &v.id).is_ok())
            .collect(),
    ))
}

pub async fn job_create(
    State(s): State<AppState>,
    h: HeaderMap,
    Json(i): Json<JobIn>,
) -> Result<(StatusCode, Json<Job>)> {
    let c = scope(&h, &s, "job.execute")?;
    if !["copy", "sync", "move", "bisync", "delete"].contains(&i.job_type.as_str()) {
        return Err(GatewayError::Message("unsupported job type".into()));
    }
    if i.schedule.is_some()
        && schedule_interval(i.schedule.as_deref()).is_none()
        && i.schedule.as_deref() != Some("@reboot")
    {
        return Err(GatewayError::Message(
            "unsupported schedule; use @hourly, @daily, @every Ns, */N * * * * or @reboot".into(),
        ));
    }
    if let Some(policy) = i.network_policy.as_deref() {
        if !["ANY", "WIFI", "UNMETERED", "VPN"].contains(&policy) {
            return Err(GatewayError::Message("invalid network policy".into()));
        }
    }
    if let Some(policy) = i.battery_policy.as_deref() {
        if !["ANY", "CHARGING", "BATTERY_30"].contains(&policy) {
            return Err(GatewayError::Message("invalid battery policy".into()));
        }
    }
    if let Some(opts) = i.options.as_ref() {
        let obj = opts
            .as_object()
            .ok_or_else(|| GatewayError::Message("options must be an object".into()))?;
        for key in obj.keys() {
            if ![
                "transfers",
                "checkers",
                "bwLimit",
                "overwrite",
                "deleteExcluded",
            ]
            .contains(&key.as_str())
            {
                return Err(GatewayError::Message(format!(
                    "unsupported job option: {key}"
                )));
            }
        }
        if let Some(v) = obj.get("transfers").and_then(|v| v.as_i64()) {
            if !(1..=32).contains(&v) {
                return Err(GatewayError::Message("transfers must be 1..32".into()));
            }
        }
        if let Some(v) = obj.get("checkers").and_then(|v| v.as_i64()) {
            if !(1..=64).contains(&v) {
                return Err(GatewayError::Message("checkers must be 1..64".into()));
            }
        }
        if let Some(v) = obj.get("bwLimit") {
            if !v.is_string()
                || !ini_line_safe(v.as_str().unwrap_or(""))
                || v.as_str().unwrap_or("").len() > 64
            {
                return Err(GatewayError::Message("invalid bwLimit".into()));
            }
        }
        for key in ["overwrite", "deleteExcluded"] {
            if let Some(v) = obj.get(key) {
                if !v.is_boolean() {
                    return Err(GatewayError::Message(format!("{key} must be boolean")));
                }
                if key == "deleteExcluded" && v.as_bool() == Some(true) && i.job_type != "sync" {
                    return Err(GatewayError::Message(
                        "deleteExcluded is supported only for sync jobs".into(),
                    ));
                }
            }
        }
    }
    if i.source.contains("..") || i.destination.contains("..") {
        return Err(GatewayError::Message("PATH_DENIED: traversal".into()));
    }
    validate_transfer_endpoint(
        &s,
        &c,
        &i.source,
        if i.job_type == "delete" {
            "file.delete"
        } else {
            "file.read"
        },
    )?;
    if i.job_type == "delete" && !i.destination.is_empty() {
        return Err(GatewayError::Message(
            "delete jobs require an empty destination".into(),
        ));
    }
    if !i.destination.is_empty() {
        validate_transfer_endpoint(&s, &c, &i.destination, "file.write")?;
    }
    let id = Uuid::new_v4().to_string();
    let t = now();
    let interval = schedule_interval(i.schedule.as_deref());
    let next = interval.map(|d| now() + d);
    db(&s)?.execute(
        "INSERT INTO job(id,type,status,source,destination,schedule,network_policy,battery_policy,options_json,dry_run,created_by,created_at,updated_at,next_run_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        params![
            id,
            i.job_type,
            "CREATED",
            i.source,
            i.destination,
            i.schedule,
            i.network_policy.unwrap_or_else(|| "ANY".into()),
            i.battery_policy.unwrap_or_else(|| "ANY".into()),
            i.options.map(|v| v.to_string()),
            i.dry_run.unwrap_or(false) as i64,
            c,
            t,
            t,
            next
        ],
    )?;
    audit(&s, Some(&c), "job.create", Some(&id), None, "SUCCESS", None)?;
    let conn = db(&s)?;
    Ok((StatusCode::ACCEPTED, Json(job(&conn, &id)?)))
}

pub async fn job_get(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<Job>> {
    let client = scope(&h, &s, "job.read")?;
    job_accessible(&s, &client, &id)?;
    let conn = db(&s)?;
    Ok(Json(job(&conn, &id)?))
}

pub async fn job_delete(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<StatusCode> {
    let c = scope(&h, &s, "job.control")?;
    job_accessible(&s, &c, &id)?;
    db(&s)?.execute(
        "DELETE FROM job WHERE id=? AND status IN ('CREATED','SUCCESS','FAILED','CANCELLED')",
        params![id],
    )?;
    audit(&s, Some(&c), "job.delete", Some(&id), None, "SUCCESS", None)?;
    Ok(StatusCode::NO_CONTENT)
}

pub async fn job_action(
    State(s): State<AppState>,
    h: HeaderMap,
    Path((id, action)): Path<(String, String)>,
) -> Result<(StatusCode, Json<Job>)> {
    let c = scope(&h, &s, "job.control")?;
    job_accessible(&s, &c, &id)?;
    let current: String = {
        let conn = db(&s)?;
        conn.query_row("SELECT status FROM job WHERE id=?", params![id], |r| {
            r.get(0)
        })
        .map_err(|_| GatewayError::Message("job not found".into()))?
    };
    let st = match (action.as_str(), current.as_str()) {
        ("start", "CREATED")
        | ("start", "PAUSED")
        | ("start", "STOPPED")
        | ("start", "SUCCESS")
        | ("start", "FAILED")
        | ("start", "CANCELLED") => "QUEUED",
        ("pause", "RUNNING") => "PAUSE_REQUESTED",
        ("resume", "PAUSED") => "QUEUED",
        ("cancel", "QUEUED") => "CANCELLED",
        ("cancel", "RUNNING") | ("cancel", "PAUSE_REQUESTED") => "CANCEL_REQUESTED",
        ("retry", "FAILED") | ("retry", "CANCELLED") | ("retry", "SUCCESS") => "QUEUED",
        (_, _) => return Err(GatewayError::Message("invalid job state transition".into())),
    };
    db(&s)?.execute(
        "UPDATE job SET status=?,updated_at=?,finished_at=NULL,last_error_code=NULL,last_error_message=NULL WHERE id=?",
        params![st, now(), id],
    )?;
    audit(
        &s,
        Some(&c),
        &format!("job.{action}"),
        Some(&id),
        None,
        "ACCEPTED",
        None,
    )?;
    let conn = db(&s)?;
    let response = job(&conn, &id)?;
    drop(conn);
    if action == "start" || action == "retry" {
        let state = s.clone();
        let job_id = id.clone();
        tokio::spawn(async move {
            run_job(state, job_id).await;
        });
    }
    Ok((StatusCode::ACCEPTED, Json(response)))
}

pub async fn job_runs(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<Vec<serde_json::Value>>> {
    let client = scope(&h, &s, "job.read")?;
    job_accessible(&s, &client, &id)?;
    let conn = db(&s)?;
    let mut st = conn.prepare("SELECT id,state,rclone_job_id,pid,started_at,finished_at,transferred_bytes,total_bytes,transferred_files,total_files,error_count,error_code,error_message FROM job_run WHERE job_id=? ORDER BY started_at DESC")?;
    let rows = st.query_map(params![id], |r| {
        Ok(serde_json::json!({
            "id": r.get::<_, String>(0)?,
            "state": r.get::<_, String>(1)?,
            "rcloneJobId": r.get::<_, Option<i64>>(2)?,
            "pid": r.get::<_, Option<i64>>(3)?,
            "startedAt": r.get::<_, Option<i64>>(4)?,
            "finishedAt": r.get::<_, Option<i64>>(5)?,
            "transferredBytes": r.get::<_, i64>(6)?,
            "totalBytes": r.get::<_, Option<i64>>(7)?,
            "transferredFiles": r.get::<_, i64>(8)?,
            "totalFiles": r.get::<_, Option<i64>>(9)?,
            "errorCount": r.get::<_, i64>(10)?,
            "errorCode": r.get::<_, Option<String>>(11)?,
            "errorMessage": r.get::<_, Option<String>>(12)?
        }))
    })?;
    Ok(Json(rows.collect::<rusqlite::Result<Vec<_>>>()?))
}

pub async fn job_log(
    State(s): State<AppState>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<serde_json::Value>> {
    let client = scope(&h, &s, "job.read")?;
    job_accessible(&s, &client, &id)?;
    let path = s.root.join("logs").join(format!("job-{id}.log"));
    let log = fs::read_to_string(&path).unwrap_or_default();
    let truncated: String = log.chars().take(64 * 1024).collect();
    let was_truncated = truncated.chars().count() < log.chars().count();
    Ok(Json(serde_json::json!({
        "jobId": id,
        "log": truncated,
        "truncated": was_truncated
    })))
}
