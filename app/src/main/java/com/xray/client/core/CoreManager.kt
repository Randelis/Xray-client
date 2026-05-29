package com.xray.client.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the full lifecycle of the native xray-core process.
 *
 * The binary is extracted from jniLibs (installed alongside APK) into the
 * app's private files directory on first use, then kept until the APK is
 * updated (version-gated re-extract).
 *
 * State machine:
 *   Idle → Starting → Running → Stopping → Idle
 *                                        ↘ Error (unexpected exit)
 */
@Singleton
class CoreManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    sealed interface State {
        data object Idle     : State
        data object Starting : State
        data class  Running(val pid: Long, val configPath: String) : State
        data object Stopping : State
        data class  Error(val cause: Throwable) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val isRunning: Boolean get() = _state.value is State.Running

    private var coreProcess: Process? = null
    private var monitorJob:  Job?     = null

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    suspend fun start(configFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        val current = _state.value
        if (current !is State.Idle && current !is State.Error) {
            return@withContext Result.failure(
                IllegalStateException("CoreManager.start() called while in state $current")
            )
        }
        _state.value = State.Starting
        runCatching {
            val binary = ensureBinaryReady()
            val process = ProcessBuilder(
                binary.absolutePath, "run", "-config", configFile.absolutePath
            )
                .directory(context.filesDir)
                .redirectErrorStream(true)   // merge stderr → stdout
                .start()

            coreProcess = process
            // Publish Running BEFORE launching the monitor so an immediate exit can
            // be reported as an error instead of being swallowed by the start race.
            _state.value = State.Running(pid = process.pid(), configPath = configFile.absolutePath)
            monitorJob  = scope.launch { monitorProcess(process, configFile.absolutePath) }
        }.onFailure { _state.value = State.Error(it) }
    }

    suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        val process = coreProcess ?: return@withContext
        _state.value = State.Stopping

        // Graceful SIGTERM, then hard SIGKILL after 5 s
        process.destroy()
        val cleanExit = process.waitFor(5, TimeUnit.SECONDS)
        if (!cleanExit) process.destroyForcibly()

        monitorJob?.cancelAndJoin()
        coreProcess = null
        monitorJob  = null
        _state.value = State.Idle
    }

    suspend fun restart(configFile: File): Result<Unit> {
        stop()
        return start(configFile)
    }

    // -------------------------------------------------------------------------
    // Process monitoring
    // -------------------------------------------------------------------------

    private suspend fun monitorProcess(process: Process, configPath: String) = withContext(Dispatchers.IO) {
        // Drain stdout to prevent the pipe buffer from blocking xray
        launch { process.inputStream.bufferedReader().forEachLine { /* forward to logcat */ } }

        val exitCode = process.waitFor()

        // Only signal error if we weren't the ones who stopped it AND this is still
        // the live process (a restart may have already swapped coreProcess).
        if (coreProcess === process && _state.value is State.Running) {
            _state.value = State.Error(
                RuntimeException("xray exited unexpectedly (code=$exitCode, config=$configPath)")
            )
        }
    }

    // -------------------------------------------------------------------------
    // Binary management  (Scoped Storage — stays inside filesDir, no MANAGE_EXTERNAL)
    // -------------------------------------------------------------------------

    private fun ensureBinaryReady(): File {
        val binDir = File(context.filesDir, "bin").also { it.mkdirs() }
        val binary = File(binDir, "xray")

        // Re-extract only when the APK has been updated or binary is missing
        val apkLastModified = File(context.packageCodePath).lastModified()
        val stampFile = File(binDir, ".extract_stamp")
        val stamp = stampFile.takeIf { it.exists() }?.readText()?.toLongOrNull() ?: 0L

        if (binary.exists() && stamp == apkLastModified) return binary

        // The xray binary is packaged as a native lib and installed by the OS
        // into /data/app/<pkg>/lib/<abi>/libxray.so; copy to private files dir
        // to gain executable permission under scoped storage.
        val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
        require(nativeLib.exists()) {
            "libxray.so not found in ${context.applicationInfo.nativeLibraryDir}. " +
            "Ensure the xray binary is placed in app/src/main/jniLibs/<abi>/libxray.so"
        }

        nativeLib.copyTo(binary, overwrite = true)

        Files.setPosixFilePermissions(
            binary.toPath(),
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
        )

        stampFile.writeText(apkLastModified.toString())
        return binary
    }
}
