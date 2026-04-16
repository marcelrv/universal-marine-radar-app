//! JNI bridge that starts `mayara-server` (the marine radar server) inside an Android process.
//!
//! # Overview
//!
//! This crate exposes three JNI functions called from `RadarJni.kt`:
//!
//! | Kotlin method        | Rust function             | Description                          |
//! |----------------------|---------------------------|--------------------------------------|
//! | `nativeStart()`      | `Java_..._nativeStart`    | Create Tokio runtime, start server   |
//! | `nativeStop()`       | `Java_..._nativeStop`     | Graceful shutdown, drop runtime      |
//! | `nativeGetLogs()`    | `Java_..._nativeGetLogs`  | Return buffered log lines as String  |
//!
//! # Architecture
//!
//! The JNI layer starts an axum HTTP/WebSocket server on `127.0.0.1:{port}`.
//! The Android app connects to it over localhost — identically to how it connects to a
//! remote `mayara-server`. There is no special embedded-mode code path in the Android client.
//!
//! # Phase 1 Status
//!
//! `run_server()` calls `mayara::server::start_android()` which starts the full SignalK
//! REST + WebSocket server (radar detection, navigation, capability routes) on `127.0.0.1:{port}`.

#![allow(non_snake_case)]

use android_logger::Config;
use anyhow::Result;
use jni::objects::JClass;
use jni::sys::{jboolean, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use log::{error, info, LevelFilter};
use once_cell::sync::OnceCell;
use std::sync::Mutex;
use tokio::runtime::Runtime;
use tokio::sync::oneshot;

// ---------------------------------------------------------------------------
// Shared state
// ---------------------------------------------------------------------------

/// All mutable server state behind a single Mutex to guarantee thread-safety across JNI calls.
struct ServerState {
    /// Sender half of the shutdown channel. `take()`n on stop.
    shutdown_tx: Option<oneshot::Sender<()>>,
    /// The Tokio runtime. Dropping it blocks until all tasks finish.
    rt: Option<Runtime>,
}

static SERVER_STATE: OnceCell<Mutex<ServerState>> = OnceCell::new();
static LOG_BUFFER: OnceCell<Mutex<Vec<String>>> = OnceCell::new();

fn server_state() -> &'static Mutex<ServerState> {
    SERVER_STATE.get_or_init(|| {
        Mutex::new(ServerState {
            shutdown_tx: None,
            rt: None,
        })
    })
}

// ---------------------------------------------------------------------------
// Logging helpers
// ---------------------------------------------------------------------------

fn log_buf() -> &'static Mutex<Vec<String>> {
    LOG_BUFFER.get_or_init(|| Mutex::new(Vec::with_capacity(1024)))
}

/// Append a line to the in-memory log ring-buffer (visible via `nativeGetLogs()`).
/// Android logcat also receives the message via `android_logger`.
fn append_log(line: String) {
    if let Ok(mut buf) = log_buf().lock() {
        // Keep the ring-buffer bounded: drop the oldest 20% when over 2000 lines.
        if buf.len() >= 2000 {
            buf.drain(0..400);
        }
        buf.push(line);
    }
}

fn init_logging() {
    android_logger::init_once(
        Config::default()
            .with_max_level(LevelFilter::Debug)
            .with_tag("mayara-radar"),
    );
}

// ---------------------------------------------------------------------------
// JNI: nativeStart(port: Int, emulator: Boolean): Boolean
// ---------------------------------------------------------------------------

/// Start the mayara radar server on `127.0.0.1:{port}`.
///
/// Returns `JNI_TRUE` on success, `JNI_FALSE` if already running or on error.
///
/// # Safety
/// Called from the JVM. The JNIEnv pointer is valid for this call only.
#[no_mangle]
pub extern "system" fn Java_com_marineyachtradar_mayara_jni_RadarJni_nativeStart<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    port: jint,
    emulator: jboolean,
) -> jboolean {
    init_logging();

    let mut guard = match server_state().lock() {
        Ok(g) => g,
        Err(e) => {
            error!("Failed to lock server state: {e}");
            return JNI_FALSE;
        }
    };

    if guard.rt.is_some() {
        info!("nativeStart called but server is already running");
        return JNI_FALSE;
    }

    let (shutdown_tx, shutdown_rx) = oneshot::channel::<()>();

    let rt = match Runtime::new() {
        Ok(r) => r,
        Err(e) => {
            error!("Failed to create Tokio runtime: {e}");
            append_log(format!("[ERROR] Failed to create Tokio runtime: {e}"));
            return JNI_FALSE;
        }
    };

    let port_u16 = port as u16;
    let use_emulator = emulator == JNI_TRUE;

    rt.spawn(async move {
        if let Err(e) = run_server(port_u16, use_emulator, shutdown_rx).await {
            error!("Server exited with error: {e:#}");
            append_log(format!("[ERROR] Server exited: {e:#}"));
        }
    });

    guard.shutdown_tx = Some(shutdown_tx);
    guard.rt = Some(rt);

    let msg = format!("[INFO] mayara-server starting on port {port_u16} (emulator={use_emulator})");
    info!("{}", msg);
    append_log(msg);

    JNI_TRUE
}

// ---------------------------------------------------------------------------
// JNI: nativeStop()
// ---------------------------------------------------------------------------

/// Gracefully stop the server and drop the Tokio runtime.
/// Idempotent — safe to call when no server is running.
///
/// # Safety
/// Called from the JVM.
#[no_mangle]
pub extern "system" fn Java_com_marineyachtradar_mayara_jni_RadarJni_nativeStop<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    let mut guard = match server_state().lock() {
        Ok(g) => g,
        Err(e) => {
            error!("nativeStop: failed to lock server state: {e}");
            return;
        }
    };

    if let Some(tx) = guard.shutdown_tx.take() {
        // Ignore error: receiver may have already been dropped if server exited on its own.
        let _ = tx.send(());
    }

    // Dropping the runtime waits for all tasks to complete.
    drop(guard.rt.take());

    let msg = "[INFO] mayara-server stopped".to_string();
    info!("{}", msg);
    append_log(msg);
}

// ---------------------------------------------------------------------------
// JNI: nativeGetLogs(): String
// ---------------------------------------------------------------------------

/// Return all buffered log lines as a newline-separated Java String.
/// Returns an empty string if the buffer is empty or the lock cannot be acquired.
///
/// # Safety
/// Called from the JVM.
#[no_mangle]
pub extern "system" fn Java_com_marineyachtradar_mayara_jni_RadarJni_nativeGetLogs<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let text = match log_buf().lock() {
        Ok(buf) => buf.join("\n"),
        Err(_) => "[ERROR] Failed to read log buffer".to_string(),
    };

    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

// ---------------------------------------------------------------------------
// Server entry point
// ---------------------------------------------------------------------------

/// Start the mayara radar server and block until `shutdown_rx` fires or the server exits.
///
/// ## Phase 1 (current)
/// Calls `mayara::start_session()` for radar detection + navigation, then starts a
/// minimal health-check HTTP server on the given port.
///
/// ## Phase 1b (after fork restructuring)
/// Replace the body with:
/// ```rust
/// // TODO Phase-1b: after mayara fork moves Web into the library:
/// // mayara::server::start_android(build_cli(port, emulator), shutdown_rx).await
/// ```
/// The fork must add:
/// 1. `pub mod server` to `src/lib/mod.rs`
/// 2. `src/lib/server/mod.rs` — move `src/bin/mayara-server/web.rs` + sub-modules here
/// 3. `pub async fn start_android(cli: Cli, shutdown_rx: oneshot::Receiver<()>)` in that module
/// 4. Thin binary: import `mayara::server::Web` instead of local `web.rs`
async fn run_server(port: u16, emulator: bool, shutdown_rx: oneshot::Receiver<()>) -> Result<()> {
    // Build Cli args programmatically (clap::Parser lets us parse from an iterator).
    let port_str = port.to_string();
    let mut args: Vec<&str> = vec!["mayara-server", "--port", &port_str];
    if emulator {
        args.push("--emulator");
    }
    let cli = mayara::Cli::parse_from(args);

    append_log(format!("[INFO] Starting full mayara server (port={port}, emulator={emulator})"));

    // Delegate to the library's Android entry point:
    //   – binds to 127.0.0.1:{port} only (loopback, no external exposure)
    //   – starts radar session (locator, AIS, nav data, target tracker)
    //   – serves full SignalK REST + WebSocket routes
    //   – shuts down cleanly when shutdown_rx fires
    mayara::server::start_android(cli, shutdown_rx).await
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    /// Verify that start/stop lifecycle does not panic and is idempotent.
    /// These tests use raw Rust (no JVM needed) by calling the internal helpers directly.
    #[tokio::test]
    async fn test_log_buffer_appends() {
        append_log("[TEST] hello from test".to_string());
        let buf = log_buf().lock().unwrap();
        assert!(buf.iter().any(|l| l.contains("hello from test")));
    }

    #[tokio::test]
    async fn test_log_buffer_bounded() {
        // Fill buffer past the threshold, then verify it was trimmed.
        {
            let mut buf = log_buf().lock().unwrap();
            buf.clear();
            for i in 0..2100 {
                buf.push(format!("line {i}"));
            }
        }
        append_log("overflow trigger".to_string());
        let len = log_buf().lock().unwrap().len();
        // After trim (2100 - 400 + 1) = 1701 expected.
        assert!(len < 2000, "log buffer was not trimmed: len={len}");
    }

    /// nativeStop() is idempotent — calling it when no server is running must not panic.
    #[test]
    fn test_native_stop_idempotent() {
        // Drive the ServerState directly without touching the JVM.
        let state = server_state();

        // First stop: nothing is running — should be a no-op.
        {
            let mut guard = state.lock().unwrap();
            assert!(guard.rt.is_none(), "no runtime should be present initially");
            // Simulate what nativeStop does:
            if let Some(tx) = guard.shutdown_tx.take() {
                let _ = tx.send(());
            }
            drop(guard.rt.take());
        }

        // Second stop: still a no-op (idempotent).
        {
            let mut guard = state.lock().unwrap();
            if let Some(tx) = guard.shutdown_tx.take() {
                let _ = tx.send(());
            }
            drop(guard.rt.take());
        }
        // If we reach here without panicking, the test passes.
    }

    /// The server_state() singleton initialises on first access and is Mutex-protected.
    #[test]
    fn test_server_state_singleton() {
        let a = server_state() as *const _;
        let b = server_state() as *const _;
        assert_eq!(a, b, "server_state() must return the same Mutex every time");
    }
}
