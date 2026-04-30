package com.example.ec2secureconnect

import android.content.Context
import com.google.gson.Gson
import kotlin.concurrent.thread

class GoTunnelSessionProcess(
    private val context: Context, private val profile: SsmProfile, private val callback: Callback
) {

    interface Callback {
        fun onConnected(message: String)
        fun onStopped(message: String)
        fun onError(message: String)
    }

    private val gson = Gson()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stopRequested = false

    fun start() {
        stopRequested = false
        val executable = GoBinaryManager.prepareExecutable(context)
        val builder =
            ProcessBuilder(executable.absolutePath, "--status-events").redirectErrorStream(true)
        builder.environment()["AWS_ACCESS_KEY"] = profile.accessKey
        builder.environment()["AWS_SECRET_KEY"] = profile.secretAccessKey
        builder.environment()["AWS_REGION"] = profile.region
        builder.environment()["EC2_INSTANCE_ID"] = profile.instanceId
        builder.environment()["REMOTE_PORT"] = profile.remotePort.toString()
        builder.environment()["LOCAL_PORT"] = profile.localPort.toString()
        builder.environment()["CONNECT_TIMEOUT"] = "20s"

        val startedProcess = builder.start()
        process = startedProcess
        thread(name = "ssm-client-${profile.id}", isDaemon = true) {
            monitor(startedProcess)
        }
    }

    fun stop() {
        stopRequested = true
        process?.destroy()
        process?.waitFor()
        process = null
    }

    private fun monitor(startedProcess: Process) {
        val output = StringBuilder()
        var connected = false
        var lastError: String? = null
        var stoppedMessage: String? = null

        try {
            startedProcess.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    output.appendLine(line)
                    val event = parseEvent(line) ?: return@forEach
                    when (event.event) {
                        "connected" -> {
                            connected = true
                            callback.onConnected(event.message ?: "Tunnel connected")
                        }

                        "stopped" -> {
                            stoppedMessage = event.message
                        }

                        "error" -> {
                            lastError = event.message ?: "ssm-client exited with an error"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (!stopRequested) {
                lastError = e.message ?: e.toString()
            }
        }

        val exitCode = try {
            startedProcess.waitFor()
        } catch (_: InterruptedException) {
            0
        }
        process = null
        if (stopRequested) {
            return
        }

        when {
            !lastError.isNullOrBlank() -> callback.onError(lastError)
            exitCode != 0 -> callback.onError(extractErrorMessage(output.toString(), exitCode))
            connected -> callback.onStopped(stoppedMessage ?: "Session ended")
            else -> callback.onError(extractErrorMessage(output.toString(), exitCode))
        }
    }

    private fun parseEvent(line: String): GoStatusEvent? {
        if (!line.startsWith(EVENT_PREFIX)) {
            return null
        }
        return runCatching {
            gson.fromJson(line.removePrefix(EVENT_PREFIX), GoStatusEvent::class.java)
        }.getOrNull()
    }

    private fun extractErrorMessage(output: String, exitCode: Int): String {
        val tail = output.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith(EVENT_PREFIX) }.toList().takeLast(6)
            .joinToString("\n")
        return tail.ifBlank {
            "ssm-client exited with code $exitCode"
        }
    }

    private data class GoStatusEvent(
        val event: String, val message: String?
    )

    private companion object {
        const val EVENT_PREFIX = "SSM_CLIENT_EVENT:"
    }
}
