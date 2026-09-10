use serde::{Deserialize, Serialize};

#[derive(Serialize)]
pub struct Health {
    pub status: &'static str,
    pub auth: &'static str,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Info {
    pub service: &'static str,
    pub rclone_version: String,
    pub gateway_version: &'static str,
    pub api_version: &'static str,
    pub root: bool,
    pub lan_enabled: bool,
    pub mtls_required: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Pair {
    pub pairing_code: String,
    pub client_name: String,
    pub public_key: Option<String>,
    pub package_name: Option<String>,
    pub grant_admin: Option<bool>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairResult {
    pub client_id: String,
    pub token: String,
    pub expires_in: u64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GrantIn {
    pub scope: String,
    pub resource: Option<String>,
    pub expires_at: Option<i64>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TokenResult {
    pub token: String,
    pub expires_in: u64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RemoteAclIn {
    pub remote_id: String,
    pub permissions: Vec<String>,
    pub allowed_prefix: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Client {
    pub id: String,
    pub name: String,
    pub status: String,
    pub created_at: i64,
    pub last_seen_at: Option<i64>,
    pub is_current: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RemoteIn {
    pub name: String,
    #[serde(rename = "type")]
    pub remote_type: String,
    pub endpoint: Option<String>,
    pub base_path: Option<String>,
    pub enabled: Option<bool>,
    pub secret: Option<serde_json::Value>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Remote {
    pub id: String,
    pub name: String,
    #[serde(rename = "type")]
    pub remote_type: String,
    pub endpoint: Option<String>,
    pub enabled: bool,
    pub secret_ref: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub options: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub configured_secrets: Option<Vec<String>>,
}

#[derive(Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct RemoteDeleteIn {
    pub confirmation_token: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RemoteImportIn {
    pub config: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FileQ {
    pub remote_id: String,
    pub path: Option<String>,
    pub page_size: Option<u32>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PathOp {
    pub remote_id: String,
    pub path: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeleteIn {
    pub remote_id: String,
    pub path: String,
    pub dry_run: Option<bool>,
    pub confirmation_token: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TransferIn {
    pub source: String,
    pub destination: String,
    pub overwrite: Option<bool>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JobIn {
    #[serde(rename = "type")]
    pub job_type: String,
    pub source: String,
    pub destination: String,
    pub schedule: Option<String>,
    pub network_policy: Option<String>,
    pub battery_policy: Option<String>,
    pub options: Option<serde_json::Value>,
    pub dry_run: Option<bool>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Job {
    pub id: String,
    #[serde(rename = "type")]
    pub job_type: String,
    pub status: String,
    pub source: String,
    pub destination: String,
    pub dry_run: bool,
    pub schedule: Option<String>,
    pub next_run_at: Option<i64>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JobQuery {
    pub status: Option<String>,
    #[serde(rename = "type")]
    pub job_type: Option<String>,
}

#[derive(Default, Debug, Clone)]
pub struct TransferStats {
    pub bytes: Option<i64>,
    pub total_bytes: Option<i64>,
    pub files: Option<i64>,
    pub total_files: Option<i64>,
    pub errors: Option<i64>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MountIn {
    pub name: String,
    pub remote_id: String,
    pub remote_path: Option<String>,
    pub mount_point: String,
    pub cache_dir: Option<String>,
    pub read_only: Option<bool>,
    pub cache_mode: Option<String>,
    pub cache_max_size: Option<String>,
    pub cache_max_age: Option<String>,
    pub target_package: Option<String>,
    pub isolated: Option<bool>,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct Mount {
    pub id: String,
    pub name: String,
    pub remote_id: String,
    pub remote_name: Option<String>,
    pub remote_path: String,
    pub mount_point: String,
    pub cache_dir: Option<String>,
    pub status: String,
    pub pid: Option<i64>,
    pub read_only: bool,
    pub cache_mode: String,
    pub cache_max_size: String,
    pub cache_max_age: String,
    pub enabled: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub target_package: Option<String>,
    pub isolated: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CryptIn {
    pub name: String,
    pub remote_id: String,
    pub remote_path: Option<String>,
    pub password: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CryptProfile {
    pub id: String,
    pub name: String,
    pub remote_id: String,
    pub remote_name: Option<String>,
    pub remote_path: String,
    pub password_configured: bool,
    pub status: String,
}
