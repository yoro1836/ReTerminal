package com.rk.terminal.backend.avf

import android.net.LocalServerSocket
import android.net.LocalSocket
import com.rk.settings.Settings
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class AvfTerminalSize(
    val rows: Int,
    val columns: Int,
    val pixelWidth: Int,
    val pixelHeight: Int,
)

internal class AvfConsoleRelay(
    val socketName: String,
    private val onWindowSizeChanged: (AvfTerminalSize) -> Unit,
    private val onReady: (vmIp: String?) -> Unit = {},
    /**
     * Raw mode: plain bidirectional byte pipe between the terminal bridge and
     * [consoleOutput]/[consoleInput]. Used by the vsock tab, whose protocol
     * framing lives in VsockTerminalBackend - no console markers or probes.
     */
    private val rawMode: Boolean = false,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(4)
    private val server = LocalServerSocket(socketName)
    val windowSizeSocketName = "$socketName-window-size"
    private val windowSizeServer = LocalServerSocket(windowSizeSocketName)
    private val lock = Any()
    private var client: LocalSocket? = null
    private var windowSizeClient: LocalSocket? = null
    private var consoleOutput: InputStream? = null
    private var consoleInput: OutputStream? = null
    private var failure: String? = null
    private var pumpsStarted = false

    init {
        executor.execute(::acceptTerminalClient)
        executor.execute(::acceptWindowSizeClient)
    }

    private fun acceptTerminalClient() {
        try {
            val accepted = server.accept()
            synchronized(lock) {
                client = accepted
                startPumpsLocked()
            }
        } catch (_: Throwable) {
            if (!closed.get()) close()
        }
    }

    private fun acceptWindowSizeClient() {
        try {
            val accepted = windowSizeServer.accept()
            synchronized(lock) {
                windowSizeClient = accepted
            }
            try {
                accepted.inputStream.bufferedReader(Charsets.US_ASCII).useLines { lines ->
                    lines.forEach { line ->
                        parseWindowSize(line)?.let(onWindowSizeChanged)
                    }
                }
            } finally {
                synchronized(lock) {
                    if (windowSizeClient === accepted) windowSizeClient = null
                }
                runCatching { accepted.close() }
            }
        } catch (_: Throwable) {
            // The terminal data channel remains usable if resize forwarding disconnects.
        }
    }

    private fun parseWindowSize(line: String): AvfTerminalSize? {
        val parts = line.split(' ')
        if (parts.size != 4) return null
        val values = parts.map { it.toIntOrNull() ?: return null }
        return AvfTerminalSize(
            rows = values[0],
            columns = values[1],
            pixelWidth = values[2],
            pixelHeight = values[3],
        ).takeIf {
            it.rows in 2..MAX_WINDOW_SIZE &&
                it.columns in 2..MAX_WINDOW_SIZE &&
                it.pixelWidth in 0..MAX_WINDOW_SIZE &&
                it.pixelHeight in 0..MAX_WINDOW_SIZE
        }
    }

    fun attach(output: InputStream, input: OutputStream) {
        synchronized(lock) {
            consoleOutput = output
            consoleInput = input
            startPumpsLocked()
        }
    }

    fun status(message: String) {
        AvfUiState.loading(message)
    }

    fun fail(message: String) {
        AvfUiState.failed(message)
        synchronized(lock) {
            failure = "\r\nReTerminal AVF: $message\r\n"
            if (pumpsStarted) {
                val socket = client
                executor.execute {
                    runCatching {
                        socket?.outputStream?.write(failure!!.toByteArray())
                        socket?.outputStream?.flush()
                    }
                    close()
                }
            } else {
                startPumpsLocked()
            }
        }
    }

    /** Writes a command to the guest console (used for SSH key bootstrap). */
    fun injectCommand(command: String) {
        runCatching {
            consoleInput?.let { out ->
                out.write(command.toByteArray())
                out.flush()
            }
        }
    }

    private fun startPumpsLocked() {
        if (pumpsStarted) return
        val socket = client ?: return
        failure?.let { message ->
            pumpsStarted = true
            executor.execute {
                runCatching {
                    socket.outputStream.write(message.toByteArray())
                    socket.outputStream.flush()
                }
                close()
            }
            return
        }
        val fromVm = consoleOutput ?: return
        val toVm = consoleInput ?: return
        pumpsStarted = true
        executor.execute { copyFromVm(fromVm, socket.outputStream) }
        executor.execute { copy(socket.inputStream, toVm) }
    }


    private fun copyFromVm(input: InputStream, output: OutputStream) {
        if (rawMode) {
            copy(input, output)
            return
        }
        val buffer = ByteArray(16 * 1024)
        val pending = StringBuilder()
        var markerSeen = false
        var probeSent = false
        var ready = false
        try {
            while (!closed.get()) {
                val count = input.read(buffer)
                if (count < 0) break
                if (ready) {
                    output.write(buffer, 0, count)
                    output.flush()
                    continue
                }

                pending.append(String(buffer, 0, count, Charsets.UTF_8))
                if (!markerSeen) {
                    val markerEnd = pending.indexOf(READY_MARKER)
                        .takeIf { it >= 0 }
                        ?.plus(READY_MARKER.length)
                    if (markerEnd != null) {
                        pending.delete(0, markerEnd)
                        markerSeen = true
                    }
                }
                if (!markerSeen) {
                    pending.keepTail()
                    continue
                }

                if (!probeSent && SHELL_PROMPT.containsMatchIn(ANSI_ESCAPE.replace(pending, ""))) {
                    pending.clear()
                    consoleInput?.write(GUEST_INFO_COMMAND.toByteArray())
                    consoleInput?.flush()
                    probeSent = true
                    continue
                }

                if (probeSent) {
                    val info = GUEST_INFO.find(ANSI_ESCAPE.replace(pending, ""))
                    if (info != null) {
                        Settings.avfGuestOsName = info.groupValues[1].trim()
                            .ifBlank { "Guest" }
                        ready = true
                        output.write("\u001b[2J\u001b[H".toByteArray())
                        info.groupValues[2].trimEnd().takeIf { it.isNotEmpty() }?.let { motd ->
                            output.write("$motd\r\n".toByteArray())
                        }
                        output.write(colorizePrompt(info.groupValues[3]).toByteArray())
                        output.flush()
                        AvfUiState.ready()
                        val vmIp = RETERMINAL_IP
                            .find(ANSI_ESCAPE.replace(pending, ""))
                            ?.groupValues?.get(1)
                            ?.takeIf { it.isNotBlank() }
                        onReady(vmIp)
                    }
                }
                pending.keepTail()
            }
        } catch (_: Throwable) {
            // Closing either side is the normal termination path.
        } finally {
            close()
        }
    }

    private fun StringBuilder.keepTail() {
        if (length > MAX_PENDING_CHARS) {
            delete(0, length - MAX_PENDING_CHARS)
        }
    }
    private fun colorizePrompt(prompt: String): String {
        val marker = prompt.takeLast(2).trim()
        val body = prompt.dropLast(2)
        val separator = body.lastIndexOf(':')
        if (marker.isEmpty() || separator <= 0) return prompt
        return "\u001b[01;32m${body.substring(0, separator)}\u001b[00m" +
            "\u001b[01;34m${body.substring(separator)}\u001b[00m$marker "
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (!closed.get()) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                output.flush()
            }
        } catch (_: Throwable) {
            // Closing either side is the normal termination path.
        } finally {
            close()
        }
    }

    private companion object {
        const val MAX_PENDING_CHARS = 16 * 1024
        const val MAX_WINDOW_SIZE = 65_535
        const val READY_MARKER = "RETERMINAL_READY"
        const val GUEST_INFO_COMMAND =
            ". /etc/os-release; printf '\\nRETERMINAL_OS:%s\\nRETERMINAL_MOTD_BEGIN\\n' \"\$PRETTY_NAME\"; cat /etc/motd 2>/dev/null; printf '\\nRETERMINAL_MOTD_END\\n'; printf 'RETERMINAL_IP:'; hostname -I 2>/dev/null | awk '{print \$1}'; printf '\\n'\n"
        val RETERMINAL_IP = Regex("""RETERMINAL_IP:([0-9.]+)""")
        val SHELL_PROMPT = Regex("""(?m)(?:^|\r?\n)[^\r\n]*[#$%>] $""")
        val GUEST_INFO = Regex(
            """\r?\nRETERMINAL_OS:([^\r\n]+)\r?\nRETERMINAL_MOTD_BEGIN\r?\n(.*?)\r?\nRETERMINAL_MOTD_END.*?(?:^|\r?\n)([^\r\n]*[#$%>] )""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE),
        )
        val ANSI_ESCAPE = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]""")
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        runCatching { windowSizeServer.close() }
        runCatching { client?.close() }
        runCatching { windowSizeClient?.close() }
        runCatching { consoleOutput?.close() }
        runCatching { consoleInput?.close() }
        executor.shutdownNow()
    }
}
