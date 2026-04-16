package com.marineyachtradar.mayara.jni

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JNI bridge to libradar.so (compiled from mayara-jni/src/lib.rs).
 *
 * All `native*` calls are blocking and must be dispatched on [Dispatchers.IO].
 * The convenience suspend functions handle that automatically.
 *
 * JNI function names in Rust are derived from this class's fully-qualified name:
 *   Java_com_marineyachtradar_mayara_jni_RadarJni_nativeStart
 *   Java_com_marineyachtradar_mayara_jni_RadarJni_nativeStop
 *   Java_com_marineyachtradar_mayara_jni_RadarJni_nativeGetLogs
 */
object RadarJni {

    init {
        System.loadLibrary("radar")
    }

    // -----------------------------------------------------------------
    // Native declarations — implemented in mayara-jni/src/lib.rs
    // -----------------------------------------------------------------

    /**
     * Start the mayara HTTP/WebSocket server on `127.0.0.1:[port]`.
     *
     * @param port TCP port (default 6502)
     * @param emulator If true, uses the built-in radar emulator (no hardware needed)
     * @return true if the server started successfully; false if already running or on error
     */
    private external fun nativeStart(port: Int, emulator: Boolean): Boolean

    /**
     * Gracefully stop the server and release the Tokio runtime.
     * Idempotent — safe to call when no server is running.
     */
    private external fun nativeStop()

    /**
     * Return all buffered log lines as a single newline-separated String.
     * Lines are prefixed with [INFO], [WARN], or [ERROR].
     */
    private external fun nativeGetLogs(): String

    // -----------------------------------------------------------------
    // Kotlin convenience wrappers (dispatch to IO)
    // -----------------------------------------------------------------

    /**
     * Start the server on the given port. Suspending; switches to [Dispatchers.IO].
     */
    suspend fun startServer(port: Int = DEFAULT_PORT, emulator: Boolean = false): Boolean =
        withContext(Dispatchers.IO) { nativeStart(port, emulator) }

    /**
     * Stop the server. Suspending; switches to [Dispatchers.IO].
     */
    suspend fun stopServer(): Unit =
        withContext(Dispatchers.IO) { nativeStop() }

    /**
     * Retrieve buffered server logs. Suspending; switches to [Dispatchers.IO].
     */
    suspend fun getLogs(): String =
        withContext(Dispatchers.IO) { nativeGetLogs() }

    const val DEFAULT_PORT = 6502
}
