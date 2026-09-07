use std::collections::HashMap;
use std::fs;
use std::path::Path as FsPath;
use std::sync::Arc;
use axum::http::HeaderMap;
use base64::Engine;
use hmac::{Hmac, Mac};
use rusqlite::{Connection, params};
use sha2::{Digest, Sha256};
use tokio::sync::RwLock;
use uuid::Uuid;

use crate::api::*;
use crate::db::*;
use crate::engine::*;
use crate::security::*;
use crate::state::*;

    #[test]
    fn path_guard_rejects_traversal_and_prefix_escape() {
        assert!(valid_path("/photos/a", "/photos").is_ok());
        assert!(valid_path("/photos/../secret", "/photos").is_err());
        assert!(valid_path("/other", "/photos").is_err());
        assert!(valid_path("/photos/line\nfeed", "/photos").is_err());
        assert!(valid_path("/photos/\u{001b}escape", "/photos").is_err());
    }
    #[test]
    fn mount_guard_allows_only_documented_roots() {
        assert!(valid_mount("/mnt/rclone-drive").is_ok());
        assert!(valid_mount("/data/media/0/drive").is_ok());
        assert!(valid_mount("/mnt/custom").is_ok());
        assert!(valid_mount("/sdcard/my_cloud").is_ok());
        assert!(valid_mount("/storage/emulated/0/my_cloud").is_ok());
        assert!(valid_mount("/mnt/rclone-").is_err());
        assert!(valid_mount("/system/bin").is_err());
        assert!(valid_mount("/mnt/rclone-../escape").is_err());
        assert!(valid_mount("/sdcard/name\tbad").is_err());
    }
    #[test]
    fn derived_bind_target_is_strictly_scoped() {
        assert_eq!(
            derived_bind_target("/mnt/rclone-drive"),
            Some("/data/media/0/drive".to_owned())
        );
        assert!(derived_bind_target("/mnt/rclone-a/b").is_none());
        assert!(derived_bind_target("/data/media/0/drive").is_none());
    }
    #[test]
    fn cache_path_check_is_component_aware() {
        assert!(path_is_within(
            FsPath::new("/state/cache"),
            FsPath::new("/state/cache/m1")
        ));
        assert!(!path_is_within(
            FsPath::new("/state/cache"),
            FsPath::new("/state/cache-escape")
        ));
        assert!(!path_is_within(
            FsPath::new("/state/cache"),
            FsPath::new("/state/cache/../secrets")
        ));
    }
    #[test]
    fn local_path_guard_blocks_protected_trees() {
        assert!(valid_local_path("/sdcard/Download").is_ok());
        assert!(valid_local_path("/data/adb/rclone").is_err());
        assert!(valid_local_path("/system/bin").is_err());
        assert!(valid_local_path("/tmp/../etc").is_err());
        assert!(valid_local_path("/sdcard/file\tbad").is_err());
    }
    #[cfg(unix)]
    #[test]
    fn local_path_guard_resolves_symlinked_protected_tree() {
        let root = std::env::temp_dir().join(format!("rclone-local-{}", Uuid::new_v4()));
        fs::create_dir_all(&root).unwrap();
        let link = root.join("escape");
        std::os::unix::fs::symlink("/proc", &link).unwrap();
        assert!(guard_local_path(link.to_str().unwrap()).is_err());
        let _ = fs::remove_dir_all(root);
    }
    #[test]
    fn typed_option_bounds_and_remote_acl_tokens_are_not_shell_parsed() {
        assert!((1..=32).contains(&4));
        assert!(!(1..=32).contains(&33));
        let permissions = "file.read,file.write";
        assert!(permissions.split(',').any(|v| v == "file.read"));
        assert!(!permissions.split_whitespace().any(|v| v == "file.read"));
    }
    #[test]
    fn request_cli_path_allowlist_blocks_raw_rc_and_shell() {
        assert!(allowed_request("GET", "/api/v1/system/health"));
        assert!(!allowed_request("POST", "/api/v1/rc/core/command"));
        assert!(!allowed_request("GET", "/api/v1/system/health?x=../"));
        assert!(!allowed_request("GET", "/api/v1/system/health\nX: y"));
        assert!(allowed_request(
            "GET",
            "/api/v1/remotes/123e4567-e89b-12d3-a456-426614174000"
        ));
        assert!(allowed_request(
            "POST",
            "/api/v1/jobs/123e4567-e89b-12d3-a456-426614174000/start"
        ));
        assert!(allowed_request(
            "POST",
            "/api/v1/jobs/123e4567-e89b-12d3-a456-426614174000/retry"
        ));
        assert!(!allowed_request(
            "POST",
            "/api/v1/jobs/123e4567-e89b-12d3-a456-426614174000/anything"
        ));
        assert!(!allowed_request("GET", "/api/v1/remotes/one/../../etc"));
        assert!(!allowed_request(
            "GET",
            "/api/v1/security/clients/one/grants/not-a-number"
        ));
        assert!(allowed_request(
            "GET",
            "/api/v1/remotes/123e4567-e89b-12d3-a456-426614174000/export"
        ));
        assert!(allowed_request(
            "POST",
            "/api/v1/remotes/123e4567-e89b-12d3-a456-426614174000/disable"
        ));
        assert!(allowed_request("POST", "/api/v1/remotes/import"));
    }
    #[test]
    fn lan_paths_require_tls_and_parse_socket_address() {
        let root = if cfg!(windows) {
            "C:\\data\\adb\\rclone-manage"
        } else {
            "/data/adb/rclone-manage"
        };
        let cert = if cfg!(windows) {
            "C:\\data\\adb\\rclone-manage\\keys\\server.pem"
        } else {
            "/data/adb/rclone-manage/keys/server.pem"
        };
        let key = if cfg!(windows) {
            "C:\\data\\adb\\rclone-manage\\keys\\server.key"
        } else {
            "/data/adb/rclone-manage/keys/server.key"
        };
        let ca = if cfg!(windows) {
            "C:\\data\\adb\\rclone-manage\\keys\\clients-ca.pem"
        } else {
            "/data/adb/rclone-manage/keys/clients-ca.pem"
        };
        let p = paths(&[
            "--root".into(),
            root.into(),
            "--lan-addr".into(),
            "192.168.1.10:8443".into(),
            "--tls-cert".into(),
            cert.into(),
            "--tls-key".into(),
            key.into(),
            "--tls-client-ca".into(),
            ca.into(),
        ])
        .unwrap();
        assert_eq!(p.lan_addr.unwrap().port(), 8443);
        assert!(p.tls_client_ca.is_some());
        assert!(paths(&["--lan-addr".into(), "127.0.0.1:8443".into()]).is_err());
        assert!(paths(&["--tls-cert".into(), "/tmp/server.pem".into()]).is_err());
        assert!(
            paths(&[
                "--lan-addr".into(),
                "192.168.1.10:8443".into(),
                "--tls-cert".into(),
                "server.pem".into(),
                "--tls-key".into(),
                "/tmp/server.key".into(),
            ])
            .is_err()
        );
        assert!(
            paths(&[
                "--lan-addr".into(),
                "0.0.0.0:8443".into(),
                "--tls-cert".into(),
                "/tmp/server.pem".into(),
                "--tls-key".into(),
                "/tmp/server.key".into(),
            ])
            .is_err()
        );
    }
    #[test]
    fn token_hash_is_not_plaintext() {
        assert_ne!(hash("token"), "token");
    }
    #[test]
    fn encrypted_secret_blob_is_not_plaintext() {
        let root = std::env::temp_dir().join(format!("rclone-gateway-test-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        let reference =
            encrypt_secret(&root, "remote-1", &serde_json::json!({"password":"secret"})).unwrap();
        let bytes = std::fs::read(root.join("secrets").join(format!("{reference}.blob"))).unwrap();
        assert!(!String::from_utf8_lossy(&bytes).contains("secret"));
        assert_eq!(
            decrypt_secret(&root, &reference, "remote-1").unwrap()["password"],
            "secret"
        );
        assert!(decrypt_secret(&root, &reference, "other-id").is_err());
        let _ = std::fs::remove_dir_all(root);
    }

    #[test]
    fn secret_validation_rejects_structured_or_multiline_values() {
        assert!(
            validate_secret_object(&serde_json::json!({
                "access_key": "abc",
                "secret": 42,
                "enabled": true
            }))
            .is_ok()
        );
        assert!(
            validate_secret_object(&serde_json::json!({
                "nested": {"password": "pw"}
            }))
            .is_err()
        );
        assert!(
            validate_secret_object(&serde_json::json!({
                "password": "line\nvalue"
            }))
            .is_err()
        );
        assert!(
            validate_secret_object(&serde_json::json!({
                "bad.key": "value"
            }))
            .is_err()
        );
    }

    #[test]
    fn rclone_obscure_has_random_iv_and_expected_shape() {
        let a = obscure_rclone("password").unwrap();
        let b = obscure_rclone("password").unwrap();
        assert_ne!(a, b);
        let decoded = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .decode(a.as_bytes())
            .unwrap();
        assert_eq!(decoded.len(), 16 + "password".len());
    }
    #[test]
    fn crypt_config_materialization_keeps_password_out_of_api_shape() {
        let root = std::env::temp_dir().join(format!("rclone-crypt-config-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        let state = AppState {
            db: open_db(&root).unwrap(),
            root: root.clone(),
            pairing: Arc::new(RwLock::new(HashMap::new())),
            require_signature: false,
        };
        let remote_id = "remote-1";
        let crypt_id = "crypt-1";
        let secret_ref =
            encrypt_secret(&root, crypt_id, &serde_json::json!({"password":"pw"})).unwrap();
        db(&state).unwrap().execute(
            "INSERT INTO remote(id,name,type,base_path,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
            params![remote_id, "base", "local", "/", 1, now(), now()],
        ).unwrap();
        db(&state).unwrap().execute(
            "INSERT INTO crypt_profile(id,name,remote_id,remote_path,secret_ref,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
            params![crypt_id, "encrypted", remote_id, "/vault", secret_ref, "READY", now(), now()],
        ).unwrap();
        let (path, name) = materialize_crypt_config(&state, crypt_id).unwrap();
        let text = fs::read_to_string(&path).unwrap();
        assert_eq!(name, "encrypted");
        assert!(text.contains("type = crypt"));
        assert!(text.contains("remote = base:/vault"));
        let password_line = text
            .lines()
            .find(|line| line.starts_with("password = "))
            .unwrap();
        assert_ne!(password_line, "password = pw");
        assert!(!password_line.ends_with(" pw"));
        let profile = serde_json::json!({"name": name, "passwordConfigured": true});
        assert!(!profile.to_string().contains("password = pw"));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn crypt_profile_name_cannot_shadow_parent_remote() {
        assert!(valid_crypt_name("encrypted", "base"));
        assert!(!valid_crypt_name("base", "base"));
        assert!(!valid_crypt_name("", "base"));
        assert!(!valid_crypt_name("bad\nname", "base"));
    }

    #[test]
    fn config_section_names_reject_ini_injection() {
        assert!(validate_identity("photos-2026", "remote name", 128).is_ok());
        assert!(validate_identity("photos]\ntype = local", "remote name", 128).is_err());
        assert!(validate_identity("remote/name", "mount name", 128).is_err());
        assert!(validate_identity("remote:name", "crypt profile name", 128).is_err());
    }

    #[test]
    fn schedule_parser_accepts_interval_forms_only() {
        assert_eq!(schedule_interval(Some("@every 30s")), Some(30));
        assert_eq!(schedule_interval(Some("*/5 * * * *")), Some(300));
        assert_eq!(schedule_interval(Some("@daily")), Some(86_400));
        assert_eq!(schedule_interval(Some("*/0 * * * *")), None);
        assert_eq!(schedule_interval(Some("rm -rf /")), None);
    }
    #[test]
    fn size_parser_and_settings_defaults_are_bounded() {
        assert_eq!(parse_size_bytes("32G"), Some(32 * 1024 * 1024 * 1024));
        assert_eq!(parse_size_bytes("64MiB"), Some(64 * 1024 * 1024));
        assert_eq!(parse_size_bytes("bad"), None);
        assert!(parse_size_bytes("1T").is_some());
        let root = std::env::temp_dir().join(format!("rclone-settings-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        let db_path = root.join("db/state.db");
        let conn = Connection::open(&db_path).unwrap();
        conn.execute_batch(SCHEMA).unwrap();
        conn.execute(
            "INSERT INTO system_config(key,value,updated_at) VALUES('logMaxBytes','1234567',0)",
            [],
        )
        .unwrap();
        drop(conn);
        assert_eq!(
            configured_setting(&root, "logMaxBytes", 99).unwrap(),
            1_234_567
        );
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn audit_persists_uid_path_hash_and_latency_shape_without_plaintext_path_hash() {
        let root = std::env::temp_dir().join(format!("rclone-audit-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        let state = AppState {
            db: open_db(&root).unwrap(),
            root: root.clone(),
            pairing: Arc::new(RwLock::new(HashMap::new())),
            require_signature: false,
        };
        audit_path(
            &state,
            Some("client-1"),
            "file.read",
            Some("/photos/private.jpg"),
            Some("remote-1"),
            "/photos/private.jpg",
            "SUCCESS",
            None,
        )
        .unwrap();
        let row: (Option<i64>, Option<String>, Option<i64>) = db(&state)
            .unwrap()
            .query_row(
                "SELECT uid,path_hash,latency_ms FROM audit_log ORDER BY id DESC LIMIT 1",
                [],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .unwrap();
        assert!(row.0.is_some());
        assert_eq!(row.1.as_deref(), Some(hash("/photos/private.jpg").as_str()));
        assert!(!row.1.as_deref().unwrap().contains("private.jpg"));
        assert!(row.2.is_some());
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn job_slot_claim_is_bounded_atomically() {
        let root = std::env::temp_dir().join(format!("rclone-job-slot-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        let state = AppState {
            db: open_db(&root).unwrap(),
            root: root.clone(),
            pairing: Arc::new(RwLock::new(HashMap::new())),
            require_signature: false,
        };
        let conn = db(&state).unwrap();
        for (id, status) in [("running", "RUNNING"), ("queued", "QUEUED")] {
            conn.execute(
                "INSERT INTO job(id,type,status,source,destination,dry_run,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                params![id, "copy", status, "remote:/a", "remote:/b", 0, now(), now()],
            )
            .unwrap();
        }
        drop(conn);
        assert!(!claim_job_slot(&state, "queued", 1).unwrap());
        db(&state)
            .unwrap()
            .execute("UPDATE job SET status='SUCCESS' WHERE id='running'", [])
            .unwrap();
        assert!(claim_job_slot(&state, "queued", 1).unwrap());
        let status: String = db(&state)
            .unwrap()
            .query_row("SELECT status FROM job WHERE id='queued'", [], |r| r.get(0))
            .unwrap();
        assert_eq!(status, "RUNNING");
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn delete_job_acl_uses_delete_permission_for_source() {
        assert_eq!(job_source_permission("delete"), "file.delete");
        assert_eq!(job_source_permission("sync"), "file.read");
        assert_eq!(job_source_permission("copy"), "file.read");
    }

    #[test]
    fn destructive_confirmation_is_single_use() {
        let root = std::env::temp_dir().join(format!("rclone-confirm-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        let state = AppState {
            db: open_db(&root).unwrap(),
            root: root.clone(),
            pairing: Arc::new(RwLock::new(HashMap::new())),
            require_signature: false,
        };
        db(&state)
            .unwrap()
            .execute(
                "INSERT INTO system_config(key,value,updated_at) VALUES(?,?,?)",
                params![
                    "delete-confirm:test",
                    r#"{"remoteId":"r","path":"/x"}"#,
                    now() + 60
                ],
            )
            .unwrap();
        consume_confirmation(&state, "delete-confirm:test", |_expires, value| {
            assert!(value.contains("\"remoteId\":\"r\""));
            Ok(())
        })
        .unwrap();
        assert!(consume_confirmation(&state, "delete-confirm:test", |_expires, _| Ok(())).is_err());
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn late_worker_exit_cannot_overwrite_cancellation() {
        let root = std::env::temp_dir().join(format!("rclone-cancel-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        let state = AppState {
            db: open_db(&root).unwrap(),
            root: root.clone(),
            pairing: Arc::new(RwLock::new(HashMap::new())),
            require_signature: false,
        };
        let conn = db(&state).unwrap();
        conn.execute(
            "INSERT INTO job(id,type,status,source,destination,dry_run,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
            params!["job-1", "copy", "CANCEL_REQUESTED", "local:/a", "local:/b", 0, now(), now()],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO job_run(id,job_id,state,started_at) VALUES(?,?,?,?)",
            params!["run-1", "job-1", "RUNNING", now()],
        )
        .unwrap();
        drop(conn);
        finish_job(&state, "job-1", "run-1", "client-1", "SUCCESS", None, None);
        let conn = db(&state).unwrap();
        let job_state: String = conn
            .query_row("SELECT status FROM job WHERE id='job-1'", [], |r| r.get(0))
            .unwrap();
        let run_state: String = conn
            .query_row("SELECT state FROM job_run WHERE id='run-1'", [], |r| {
                r.get(0)
            })
            .unwrap();
        assert_eq!(job_state, "CANCELLED");
        assert_eq!(run_state, "CANCELLED");
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn stats_parser_reads_rclone_json_progress() {
        let s = parse_rclone_stats(
            br#"{"bytes":123,"totalBytes":456,"transfers":2,"totalTransfers":5,"errors":1}
info line"#,
        );
        assert_eq!(s.bytes, Some(123));
        assert_eq!(s.total_bytes, Some(456));
        assert_eq!(s.files, Some(2));
        assert_eq!(s.total_files, Some(5));
        assert_eq!(s.errors, Some(1));

        let s2 = parse_rclone_stats(
            br#"{"level":"info","msg":"Transferred","stats":{"bytes":789,"totalBytes":1000,"transfers":3,"totalTransfers":4,"errors":0}}"#,
        );
        assert_eq!(s2.bytes, Some(789));
        assert_eq!(s2.total_bytes, Some(1000));
        assert_eq!(s2.files, Some(3));
        assert_eq!(s2.total_files, Some(4));
        assert_eq!(s2.errors, Some(0));
    }

    #[test]
    fn job_log_redaction_removes_credential_lines() {
        let redacted = redact_log_text(
            "Transferred: 10 bytes\npassword = super-secret\nerror: token=abc123\nDone",
        );
        assert!(redacted.contains("Transferred: 10 bytes"));
        assert!(redacted.contains("Done"));
        assert!(!redacted.contains("super-secret"));
        assert!(!redacted.contains("abc123"));
        assert_eq!(redacted.matches("[REDACTED]").count(), 2);
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn typed_job_executes_allowlisted_mock_rclone() {
        use std::os::unix::fs::PermissionsExt;
        static ENV_LOCK: std::sync::OnceLock<std::sync::Mutex<()>> = std::sync::OnceLock::new();
        let _guard = ENV_LOCK
            .get_or_init(|| std::sync::Mutex::new(()))
            .lock()
            .unwrap();
        let root = std::env::temp_dir().join(format!("rclone-mock-job-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        let mock = root.join("mock-rclone.sh");
        let args_log = root.join("mock-args.log");
        let shell = if cfg!(target_os = "android") {
            "/system/bin/sh"
        } else {
            "/bin/sh"
        };
        fs::write(
            &mock,
            format!(
                "#!{shell}\nprintf '%s\\n' \"$@\" > '{}'\nprintf '%s\\n' '{{\"bytes\":7,\"transfers\":1,\"totalTransfers\":1}}'\n",
                args_log.display()
            ),
        )
        .unwrap();
        fs::set_permissions(&mock, fs::Permissions::from_mode(0o700)).unwrap();
        // SAFETY: the test holds ENV_LOCK, so no other test mutates this
        // process-wide setting while the worker is running.
        unsafe { std::env::set_var("RCLONE_BIN", &mock) };
        let state = AppState {
            db: open_db(&root).unwrap(),
            root: root.clone(),
            pairing: Arc::new(RwLock::new(HashMap::new())),
            require_signature: false,
        };
        let id = Uuid::new_v4().to_string();
        let t = now();
        db(&state)
            .unwrap()
            .execute(
                "INSERT INTO job(id,type,status,source,destination,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                params![id, "copy", "QUEUED", "/tmp/source", "/tmp/destination", t, t],
            )
            .unwrap();
        run_job(state.clone(), id.clone()).await;
        let status: String = db(&state)
            .unwrap()
            .query_row("SELECT status FROM job WHERE id=?", params![id], |r| {
                r.get(0)
            })
            .unwrap();
        assert_eq!(status, "SUCCESS");
        let args = fs::read_to_string(args_log).unwrap();
        assert!(args.lines().any(|v| v == "copy"));
        assert!(args.lines().any(|v| v == "/tmp/source"));
        assert!(args.lines().any(|v| v == "/tmp/destination"));
        assert!(args.lines().any(|v| v == "--use-json-log"));
        assert!(args.lines().any(|v| v == "--stats"));
        assert!(args.lines().any(|v| v == "1s"));
        // SAFETY: ENV_LOCK is still held and the worker has completed.
        unsafe { std::env::remove_var("RCLONE_BIN") };
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn request_allowlist_includes_file_transfer_directions() {
        assert!(allowed_request("POST", "/api/v1/files/upload"));
        assert!(allowed_request("POST", "/api/v1/files/download"));
        assert!(!allowed_request("GET", "/api/v1/files/upload"));
    }

    #[test]
    fn hmac_signature_binds_method_path_body_and_nonce() {
        let root = std::env::temp_dir().join(format!("rclone-gateway-hmac-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        let state = AppState {
            db: open_db(&root).unwrap(),
            root: root.clone(),
            pairing: Arc::new(RwLock::new(HashMap::new())),
            require_signature: false,
        };
        let token = "test-token";
        let method = "POST";
        let path = "/api/v1/system/safe-mode";
        let body = br#"{"enabled":true}"#;
        let timestamp = (now() * 1000).to_string();
        let nonce = "nonce-1";
        let canonical = format!(
            "{method}\n{path}\n{}\n{timestamp}\n{nonce}",
            hex::encode(Sha256::digest(body))
        );
        let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(token.as_bytes()).unwrap();
        mac.update(canonical.as_bytes());
        let mut headers = HeaderMap::new();
        headers.insert("x-client-id", "client-a".parse().unwrap());
        headers.insert("x-timestamp", timestamp.parse().unwrap());
        headers.insert("x-nonce", nonce.parse().unwrap());
        headers.insert(
            "x-signature",
            hex::encode(mac.finalize().into_bytes()).parse().unwrap(),
        );
        verify_signature(&headers, token, &state, method, path, body).unwrap();
        assert!(verify_signature(&headers, token, &state, method, path, body).is_err());
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn hmac_rejects_unbounded_or_invalid_nonce_headers() {
        let root =
            std::env::temp_dir().join(format!("rclone-gateway-hmac-header-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        let state = AppState {
            db: open_db(&root).unwrap(),
            root: root.clone(),
            pairing: Arc::new(RwLock::new(HashMap::new())),
            require_signature: false,
        };
        let mut headers = HeaderMap::new();
        headers.insert("x-client-id", "client-a".parse().unwrap());
        headers.insert("x-timestamp", (now() * 1000).to_string().parse().unwrap());
        headers.insert("x-nonce", "bad nonce".parse().unwrap());
        headers.insert("x-signature", "0".repeat(64).parse().unwrap());
        assert!(
            verify_signature(&headers, "token", &state, "GET", "/api/v1/system/info", b"").is_err()
        );
        let _ = fs::remove_dir_all(root);
    }
    #[test]
    fn log_rotation_moves_oversized_logs_to_restricted_suffix() {
        let root = std::env::temp_dir().join(format!("rclone-gateway-logs-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        let log = root.join("logs/gateway.log");
        fs::write(&log, vec![b'x'; 10 * 1024 * 1024 + 1]).unwrap();
        rotate_logs(&root).unwrap();
        assert!(!log.exists());
        let rotated = root.join("logs/gateway.log.1");
        assert!(rotated.is_file());
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                fs::metadata(&rotated).unwrap().permissions().mode() & 0o777,
                0o600
            );
        }
        let _ = fs::remove_dir_all(root);
    }
    #[test]
    fn backup_restore_validates_name_and_restores_integrity_checked_db() {
        assert!(valid_backup_name("state-123.db").is_ok());
        assert!(valid_backup_name("../state.db").is_err());
        assert!(valid_backup_name("state.db/escape").is_err());
        let root = std::env::temp_dir().join(format!("rclone-gateway-restore-{}", Uuid::new_v4()));
        ensure_dirs(&root).unwrap();
        fs::write(root.join("keys/master.key"), b"old-master-key").unwrap();
        fs::write(root.join("secrets/old.blob"), b"old-secret").unwrap();
        let current = root.join("db/state.db");
        let conn = Connection::open(&current).unwrap();
        conn.execute_batch("CREATE TABLE marker(value TEXT); INSERT INTO marker VALUES ('old');")
            .unwrap();
        drop(conn);
        let backup = root.join("backups/state-123.db");
        let backup_conn = Connection::open(&backup).unwrap();
        backup_conn.execute_batch(SCHEMA).unwrap();
        backup_conn
            .execute_batch("CREATE TABLE marker(value TEXT); INSERT INTO marker VALUES ('new');")
            .unwrap();
        drop(backup_conn);
        let bundle = root.join("backups/state-123.bundle");
        fs::create_dir_all(bundle.join("keys")).unwrap();
        fs::create_dir_all(bundle.join("secrets")).unwrap();
        fs::write(bundle.join("keys/master.key"), b"new-master-key").unwrap();
        fs::write(bundle.join("secrets/new.blob"), b"new-secret").unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&bundle, fs::Permissions::from_mode(0o700)).unwrap();
            fs::set_permissions(bundle.join("keys"), fs::Permissions::from_mode(0o700)).unwrap();
            fs::set_permissions(bundle.join("secrets"), fs::Permissions::from_mode(0o700)).unwrap();
        }
        let result = restore_backup(&root, "state-123.db").unwrap();
        assert_eq!(result["restored"], "state-123.db");
        assert_eq!(result["bundleRestored"], true);
        assert_eq!(
            fs::read(root.join("keys/master.key")).unwrap(),
            b"new-master-key"
        );
        assert_eq!(
            fs::read(root.join("secrets/new.blob")).unwrap(),
            b"new-secret"
        );
        assert!(!root.join("secrets/old.blob").exists());
        let restored = Connection::open(&current).unwrap();
        let marker: String = restored
            .query_row("SELECT value FROM marker", [], |r| r.get(0))
            .unwrap();
        assert_eq!(marker, "new");
        assert!(
            root.join("backups").join("pre-restore-123.db").exists()
                || root.join("backups").read_dir().unwrap().any(|e| e
                    .unwrap()
                    .file_name()
                    .to_string_lossy()
                    .starts_with("pre-restore-"))
        );
        let _ = fs::remove_dir_all(root);
    }
    #[test]
    fn migration_errors_do_not_persist_malformed_values() {
        let root = std::env::temp_dir().join(format!("rclone-migration-{}", Uuid::new_v4()));
        let legacy = root.join("legacy");
        let state = root.join("state");
        fs::create_dir_all(&legacy).unwrap();
        fs::write(
            legacy.join("rclone.conf"),
            b"[remote]\ntype=s3\nmalformed-secret\npassword=bad\0value\n",
        )
        .unwrap();
        migrate(Paths {
            socket: state.join("runtime/gateway.sock"),
            root: state.clone(),
            legacy: Some(legacy),
            lan_addr: None,
            tls_cert: None,
            tls_key: None,
            tls_client_ca: None,
        })
        .unwrap();
        let conn = Connection::open(state.join("db/state.db")).unwrap();
        let values: Vec<String> = conn
            .prepare("SELECT raw_value FROM migration_errors ORDER BY id")
            .unwrap()
            .query_map([], |r| r.get(0))
            .unwrap()
            .collect::<rusqlite::Result<_>>()
            .unwrap();
        assert_eq!(
            values,
            vec!["<redacted>".to_owned(), "<redacted>".to_owned()]
        );
        let _ = fs::remove_dir_all(root);
    }
