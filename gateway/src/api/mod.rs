pub mod clients;
pub mod crypt;
pub mod files;
pub mod jobs;
pub mod mounts;
pub mod remotes;
pub mod system;

pub use clients::*;
pub use crypt::*;
pub use files::*;
pub use jobs::*;
pub use mounts::*;
pub use remotes::*;
pub use system::*;

use axum::{
    Router,
    middleware,
    routing::{get, post},
};

use crate::security::signed_request;
use crate::state::AppState;

pub fn app_router(s: AppState) -> Router {
    Router::new()
        .route("/api/v1/system/health", get(health))
        .route("/api/v1/system/info", get(info))
        .route(
            "/api/v1/system/safe-mode",
            get(safe_mode_get).put(safe_mode_set),
        )
        .route(
            "/api/v1/system/settings",
            get(system_settings_get).put(system_settings_set),
        )
        .route("/api/v1/system/migration", get(migration_status))
        .route(
            "/api/v1/system/backups",
            get(backups_list).post(backup_create),
        )
        .route("/api/v1/system/logs/clear", post(logs_clear))
        .route("/api/v1/security/pairing/start", post(pair_start))
        .route("/api/v1/security/pairing/complete", post(pair_complete))
        .route("/api/v1/security/clients", get(clients))
        .route(
            "/api/v1/security/clients/{id}",
            axum::routing::delete(client_delete),
        )
        .route(
            "/api/v1/security/clients/{id}/enable",
            post(client_enable),
        )
        .route(
            "/api/v1/security/clients/{id}/disable",
            post(client_disable),
        )
        .route(
            "/api/v1/security/clients/{id}/rotate-token",
            post(client_rotate_token),
        )
        .route(
            "/api/v1/security/clients/{id}/grants",
            get(client_grants).post(client_grant),
        )
        .route(
            "/api/v1/security/clients/{id}/grants/{grant_id}",
            axum::routing::delete(client_revoke),
        )
        .route(
            "/api/v1/security/clients/{id}/remote-acl",
            post(remote_acl_grant),
        )
        .route("/api/v1/remotes", get(remotes).post(remote_create))
        .route("/api/v1/remotes/providers", get(remote_providers))
        .route("/api/v1/remotes/import", post(remote_import))
        .route(
            "/api/v1/remotes/{id}",
            get(remote_get).put(remote_update).delete(remote_delete),
        )
        .route("/api/v1/remotes/{id}/test", post(remote_test))
        .route("/api/v1/remotes/{id}/{action}", post(remote_action))
        .route("/api/v1/remotes/{id}/export", get(remote_export))
        .route("/api/v1/files", get(files))
        .route("/api/v1/files/mkdir", post(files_mkdir))
        .route("/api/v1/files/delete", post(files_delete))
        .route("/api/v1/files/copy", post(files_copy))
        .route("/api/v1/files/move", post(files_move))
        .route("/api/v1/files/upload", post(files_upload))
        .route("/api/v1/files/download", post(files_download))
        .route("/api/v1/jobs", get(jobs).post(job_create))
        .route("/api/v1/jobs/{id}", get(job_get).delete(job_delete))
        .route("/api/v1/jobs/{id}/runs", get(job_runs))
        .route("/api/v1/jobs/{id}/log", get(job_log))
        .route("/api/v1/jobs/{id}/{action}", post(job_action))
        .route("/api/v1/mounts", get(mounts).post(mount_create))
        .route(
            "/api/v1/mounts/{id}",
            get(mount_get).put(mount_update).delete(mount_delete),
        )
        .route("/api/v1/mounts/{id}/{action}", post(mount_action))
        .route("/api/v1/crypt", get(crypts).post(crypt_create))
        .route("/api/v1/crypt/{id}/test", post(crypt_test))
        .route("/api/v1/logs/audit", get(audit_logs))
        .with_state(s.clone())
        .layer(middleware::from_fn_with_state(s, signed_request))
}
