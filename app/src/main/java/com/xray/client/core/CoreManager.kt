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

    suspend fun start(configFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        check(_state.value is State.Idle || _state.value is State.Error) {
            "CoreManager.start() called while in state ${_state.value}"
        }
        _state.value = State.Starting
        runCatching {
            val binary = ensureBinaryReady()
            val process = ProcessBuilder(
                binary.absolutePath, "run", "-config", configFile.absolutePath
            )
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .start()

            coreProcess = process
            monitorJob  = scope.launch { monitorProcess(process, configFile.absolutePath) }
            _state.value = State.Running(pid = -1L, configPath = configFile.absolutePath)
        }.onFailure { _state.value = State.Error(it) }
    }

    suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        val process = coreProcess ?: return@withContext
        _state.value = State.Stopping
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

    private suspend fun monitorProcess(process: Process, configPath: String) = withContext(Dispatchers.IO) {
        launch { process.inputStream.bufferedReader().forEachLine { } }
        val exitCode = process.waitFor()
        if (_state.value is State.Running) {
            _state.value = State.Error(
                RuntimeException("xray exited unexpectedly (code=$exitCode, config=$configPath)")
            )
        }
    }

    private fun ensureBinaryReady(): File {
        val binDir = File(context.filesDir, "bin").also { it.mkdirs() }
        val binary = File(binDir, "xray")
        val apkLastModified = File(context.packageCodePath).lastModified()
        val stampFile = File(binDir, ".extract_stamp")
        val stamp = stampFile.takeIf { it.exists() }?.readText()?.toLongOrNull() ?: 0L
        if (binary.exists() && stamp == apkLastModified) return binary
        val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
        require(nativeLib.exists()) {
            "libxray.so not found in ${context.applicationInfo.nativeLibraryDir}"
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
