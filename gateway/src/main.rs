//! Android Rclone Root Manager Gateway Entrypoint.

pub mod api;
pub mod db;
pub mod engine;
pub mod error;
pub mod security;
pub mod server;
pub mod state;
pub mod types;

#[cfg(test)]
mod tests;

use std::env;

use crate::db::migrate;
use crate::error::GatewayError;
use crate::server::{probe_cli, request_cli, restore_cli, serve, stop_cli};
use crate::state::parse_paths;

#[tokio::main]
async fn main() {
    let mut a = env::args().skip(1).collect::<Vec<_>>();
    let cmd = if a.is_empty() {
        "serve".into()
    } else {
        a.remove(0)
    };
    let r = match cmd.as_str() {
        "request" => request_cli(&a),
        "probe" => probe_cli(&a),
        "restore" => restore_cli(&a),
        "stop" => stop_cli(&a),
        _ => match parse_paths(&a) {
            Ok(p) => match cmd.as_str() {
                "serve" => serve(p).await,
                "migrate" => migrate(p),
                "version" | "--version" => {
                    println!("rclone-gateway {}", env!("CARGO_PKG_VERSION"));
                    Ok(())
                }
                _ => Err(GatewayError::Message(
                    "usage: rclone-gateway serve [--socket PATH] [--root PATH] [--lan-addr HOST:PORT --tls-cert PATH --tls-key PATH [--tls-client-ca PATH]] | migrate --root PATH --legacy PATH | restore --root PATH --backup NAME | stop --root PATH | probe [--socket PATH] | request --socket PATH --method METHOD --path PATH [--token TOKEN] [--body-base64 BASE64]".into(),
                )),
            },
            Err(e) => Err(e),
        },
    };
    if let Err(e) = r {
        eprintln!("rclone-gateway: {e}");
        std::process::exit(2)
    }
}
