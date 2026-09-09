use axum::{
    Json,
    http::StatusCode,
    response::{IntoResponse, Response},
};
use serde::Serialize;
use thiserror::Error;
use uuid::Uuid;

#[derive(Debug, Error)]
pub enum GatewayError {
    #[error("{0}")]
    Message(String),
    #[error("database: {0}")]
    Db(#[from] rusqlite::Error),
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("crypto failure")]
    Crypto,
}

#[derive(Serialize)]
pub struct ErrorBody {
    pub code: String,
    pub message: String,
    pub request_id: String,
    pub details: serde_json::Value,
}

impl IntoResponse for GatewayError {
    fn into_response(self) -> Response {
        let message = self.to_string();
        let code = match self {
            GatewayError::Db(_) => "DB_ERROR",
            GatewayError::Io(_) => "IO_ERROR",
            GatewayError::Crypto => "SECRET_ERROR",
            GatewayError::Message(ref m) if m.contains("scope denied") => "SCOPE_DENIED",
            GatewayError::Message(ref m) if m.contains("remote ACL denied") => "REMOTE_DENIED",
            GatewayError::Message(ref m) if m.contains("remote is still referenced") => {
                "REMOTE_IN_USE"
            }
            GatewayError::Message(ref m) if m.contains("confirmation") => "CONFIRMATION_REQUIRED",
            GatewayError::Message(ref m) if m.contains("job not found") => "JOB_NOT_FOUND",
            GatewayError::Message(ref m) if m.contains("rclone") && m.contains("failed") => {
                "CORE_UNAVAILABLE"
            }
            GatewayError::Message(ref m) if m.contains("migration") => "MIGRATION_REQUIRED",
            GatewayError::Message(ref m) if m.contains("MOUNT_CONFLICT") => "MOUNT_CONFLICT",
            GatewayError::Message(ref m) if m.contains("missing bearer") => "AUTH_REQUIRED",
            GatewayError::Message(ref m)
                if m.starts_with("RATE_LIMITED")
                    || m.contains("too many failed")
                    || m.contains("locked out") =>
            {
                "RATE_LIMITED"
            }
            GatewayError::Message(ref m) if m.starts_with("AUTH") => "AUTH_INVALID",
            GatewayError::Message(ref m) if m.starts_with("PATH_DENIED") => "PATH_DENIED",
            GatewayError::Message(_) => "INVALID_REQUEST",
        };
        let status = match code {
            "RATE_LIMITED" => StatusCode::TOO_MANY_REQUESTS,
            "AUTH_REQUIRED" | "AUTH_INVALID" => StatusCode::UNAUTHORIZED,
            "SCOPE_DENIED" | "REMOTE_DENIED" => StatusCode::FORBIDDEN,
            "REMOTE_IN_USE" => StatusCode::CONFLICT,
            "CONFIRMATION_REQUIRED" => StatusCode::PRECONDITION_REQUIRED,
            "JOB_NOT_FOUND" => StatusCode::NOT_FOUND,
            "PATH_DENIED" => StatusCode::FORBIDDEN,
            "DB_ERROR" if self.to_string().contains("not found") => StatusCode::NOT_FOUND,
            _ => StatusCode::BAD_REQUEST,
        };
        (
            status,
            Json(ErrorBody {
                code: code.into(),
                message,
                request_id: Uuid::new_v4().to_string(),
                details: serde_json::json!({}),
            }),
        )
            .into_response()
    }
}

pub type Result<T> = std::result::Result<T, GatewayError>;
