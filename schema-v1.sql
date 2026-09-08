-- Android Rclone Root Manager Gateway - SQLite schema v1
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS system_config (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS remote (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  type TEXT NOT NULL,
  endpoint TEXT,
  base_path TEXT DEFAULT '/',
  secret_ref TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_remote_enabled ON remote(enabled);

CREATE TABLE IF NOT EXISTS remote_acl (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  client_id TEXT NOT NULL,
  remote_id TEXT NOT NULL,
  permissions TEXT NOT NULL,
  allowed_prefix TEXT NOT NULL DEFAULT '/',
  FOREIGN KEY(remote_id) REFERENCES remote(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_remote_acl_client ON remote_acl(client_id);

CREATE TABLE IF NOT EXISTS client (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  package_name TEXT,
  uid INTEGER,
  public_key TEXT,
  token_hash TEXT,
  status TEXT NOT NULL DEFAULT 'ACTIVE',
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER,
  token_expires_at INTEGER
);

CREATE TABLE IF NOT EXISTS permission_grant (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  client_id TEXT NOT NULL,
  scope TEXT NOT NULL,
  resource TEXT,
  expires_at INTEGER,
  FOREIGN KEY(client_id) REFERENCES client(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_permission_client ON permission_grant(client_id);

CREATE TABLE IF NOT EXISTS job (
  id TEXT PRIMARY KEY,
  type TEXT NOT NULL,
  status TEXT NOT NULL,
  source TEXT NOT NULL,
  destination TEXT NOT NULL,
  schedule TEXT,
  network_policy TEXT NOT NULL DEFAULT 'ANY',
  battery_policy TEXT NOT NULL DEFAULT 'ANY',
  options_json TEXT,
  dry_run INTEGER NOT NULL DEFAULT 0,
  confirm_required INTEGER NOT NULL DEFAULT 0,
  created_by TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  started_at INTEGER,
  finished_at INTEGER,
  next_run_at INTEGER,
  last_error_code TEXT,
  last_error_message TEXT
);
-- Added in v1.1.0: scheduler and boot-recovery metadata.
-- The gateway also applies these columns idempotently for existing databases.
CREATE TABLE IF NOT EXISTS migration_errors (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  migration_version INTEGER NOT NULL,
  source_file TEXT NOT NULL,
  line_number INTEGER,
  message TEXT NOT NULL,
  raw_value TEXT,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_job_status ON job(status);
CREATE INDEX IF NOT EXISTS idx_job_schedule ON job(schedule);

CREATE TABLE IF NOT EXISTS job_run (
  id TEXT PRIMARY KEY,
  job_id TEXT NOT NULL,
  state TEXT NOT NULL,
  rclone_job_id INTEGER,
  pid INTEGER,
  started_at INTEGER,
  finished_at INTEGER,
  transferred_bytes INTEGER NOT NULL DEFAULT 0,
  total_bytes INTEGER,
  transferred_files INTEGER NOT NULL DEFAULT 0,
  total_files INTEGER,
  error_count INTEGER NOT NULL DEFAULT 0,
  error_code TEXT,
  error_message TEXT,
  FOREIGN KEY(job_id) REFERENCES job(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_job_run_job ON job_run(job_id);

CREATE TABLE IF NOT EXISTS mount_profile (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  remote_id TEXT NOT NULL,
  remote_path TEXT NOT NULL DEFAULT '/',
  mount_point TEXT NOT NULL,
  read_only INTEGER NOT NULL DEFAULT 0,
  cache_mode TEXT NOT NULL DEFAULT 'full',
  cache_dir TEXT,
  cache_max_size TEXT DEFAULT '32G',
  cache_max_age TEXT DEFAULT '36h',
  enabled INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'STOPPED',
  pid INTEGER,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  target_package TEXT,
  isolated INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY(remote_id) REFERENCES remote(id) ON DELETE RESTRICT
);
CREATE INDEX IF NOT EXISTS idx_mount_enabled ON mount_profile(enabled);

CREATE TABLE IF NOT EXISTS crypt_profile (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  remote_id TEXT NOT NULL,
  remote_path TEXT NOT NULL DEFAULT '/',
  secret_ref TEXT,
  status TEXT NOT NULL DEFAULT 'READY',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  FOREIGN KEY(remote_id) REFERENCES remote(id) ON DELETE RESTRICT
);
CREATE INDEX IF NOT EXISTS idx_crypt_remote ON crypt_profile(remote_id);

CREATE TABLE IF NOT EXISTS secret_meta (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  backend TEXT NOT NULL,
  version INTEGER NOT NULL DEFAULT 1,
  created_at INTEGER NOT NULL,
  rotated_at INTEGER
);

CREATE TABLE IF NOT EXISTS audit_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  timestamp INTEGER NOT NULL,
  client_id TEXT,
  uid INTEGER,
  operation TEXT NOT NULL,
  resource TEXT,
  remote_id TEXT,
  path_hash TEXT,
  result TEXT NOT NULL,
  error_code TEXT,
  latency_ms INTEGER,
  metadata_json TEXT
);
CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_log(timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_audit_client ON audit_log(client_id);
CREATE INDEX IF NOT EXISTS idx_audit_operation ON audit_log(operation);

CREATE TABLE IF NOT EXISTS migration_history (
  version INTEGER PRIMARY KEY,
  applied_at INTEGER NOT NULL,
  checksum TEXT NOT NULL
);
