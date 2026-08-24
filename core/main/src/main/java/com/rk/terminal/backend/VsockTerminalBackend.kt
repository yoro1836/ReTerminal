package com.rk.terminal.backend

import android.content.Context
import android.os.ParcelFileDescriptor
import com.rk.terminal.backend.avf.AvfApi
import com.rk.terminal.backend.avf.AvfTerminalSize
import com.rk.terminal.backend.avf.AvfConsoleRelay
import com.rk.terminal.backend.avf.AvfUiState
import com.rk.terminal.backend.avf.AvfVmRegistry
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

private fun writeFrameHeader(output: OutputStream, length: Int) {
    val header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(length).array()
    output.write(header)
}

private fun writeFrame(output: OutputStream, buffer: ByteArray, length: Int) {
    synchronized(output) {
        writeFrameHeader(output, length)
        output.write(buffer, 0, length)
        output.flush()
    }
}

/**
 * A guest shell tab served over vsock (ChromiumOS vsh protocol) instead of SSH.
 *
 *   TerminalSession <-> avf-terminal-bridge <-> AvfConsoleRelay (LocalSocket)
 *        -> VsockTerminalBackend: vsh framing over AvfApi.connectVsock(6211)
 *        -> guest rvshd -> PTY/login shell
 *
 * Wire format (vm_tools/proto/vsh.proto): [u32 LE length][proto3 payload].
 * GuestMessage oneof: data_message=1, resize_message=3.
 * HostMessage oneof: data_message=1, status_message=2.
 */
class VsockTerminalBackend(
    context: Context,
    private val sessionId: String,
) : TerminalSessionBackend {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val relay = AvfConsoleRelay(
        "reterminal-vsock-$sessionId",
        ::onWindowSizeChanged,
        rawMode = true,
    )
    private var session: TerminalSession? = null

    override fun createSession(client: TerminalSessionClient): TerminalSession {
        check(session == null) { "vsock terminal session already created" }
        val bridge = File(appContext.applicationInfo.nativeLibraryDir, "libavf-terminal-bridge.so")
        check(bridge.canExecute()) { "AVF terminal bridge is missing: ${bridge.absolutePath}" }
        AvfUiState.loading("Connecting to VM shell...")
        return TerminalSession(
            bridge.absolutePath,
            appContext.filesDir.absolutePath,
            arrayOf("avf-terminal-bridge", relay.socketName, relay.windowSizeSocketName),
            arrayOf("TERM=xterm-256color", "COLORTERM=truecolor"),
            2000,
            client,
        ).also {
            session = it
            executor.execute(::runConnection)
        }
    }

    private fun runConnection() {
        var pfd: ParcelFileDescriptor? = null
        try {
            val machine = AvfVmRegistry.acquire()
                ?: error("VM is not running - boot the console tab first")
            pfd = AvfApi.connectVsock(machine, VSHD_PORT)
            livePfd = pfd
            val input = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            val rawOutput = ParcelFileDescriptor.AutoCloseOutputStream(pfd)

            // The relay pipes terminal bytes to us; we frame them toward the guest.
            val guestInput = GuestInputStream(input)
            val guestOutput = GuestOutputStream(rawOutput)
            liveGuestOutput = guestOutput
            guestInput.onReadyStatus = {
                // rvshd answered: session is live, drop the loading overlay.
                AvfUiState.ready()
            }
            guestInput.start(rawOutput)  // send SetupConnectionRequest first
            relay.attach(guestInput, guestOutput)
        } catch (error: Throwable) {
            // Never leak the vsock descriptor: repeated failed tabs would
            // exhaust fds and destabilize the system.
            runCatching { pfd?.close() }
            if (!closed.get()) relay.fail(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Adapts raw vsock bytes into the framed stream the relay expects:
     * strips the 4-byte length headers from rvshd's HostMessages and emits
     * only DataMessage(STDOUT) payload bytes to the terminal.
     */
    private class GuestInputStream(private val input: InputStream) : InputStream() {
        private var header = ByteArray(4)
        private var headerFilled = 0
        private var payload = ByteArray(0)
        private var payloadOffset = 0

        /** Sends SetupConnectionRequest{user="root", rows, cols}. */
        fun start(out: OutputStream) {
            val body = ByteBuffer.allocate(32)
            writeTag(body, FIELD_USER, WIRE_LEN); writeVarint(body, 5)
            body.put("droid".toByteArray(Charsets.US_ASCII))
            writeVarintField(body, FIELD_ROWS, DEFAULT_ROWS)
            writeVarintField(body, FIELD_COLS, DEFAULT_COLS)
            val bytes = body.array().copyOfRange(0, body.position())
            synchronized(out) {
                writeFrameHeader(out, bytes.size)
                out.write(bytes)
                out.flush()
            }
        }

        override fun read(): Int {
            while (payloadOffset >= payload.size) {
                if (!nextMessage()) return -1
            }
            return payload[payloadOffset++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (payloadOffset >= payload.size) {
                if (!nextMessage()) return -1
            }
            val count = minOf(len, payload.size - payloadOffset)
            System.arraycopy(payload, payloadOffset, b, off, count)
            payloadOffset += count
            return count
        }

        /** Reads one HostMessage; returns false on EOF. */
        private fun nextMessage(): Boolean {
            while (headerFilled < header.size) {
                val n = input.read(header, headerFilled, header.size - headerFilled)
                if (n < 0) return false
                headerFilled += n
            }
            val length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
            headerFilled = 0
            if (length <= 0 || length > MAX_MESSAGE) return false
            val message = ByteArray(length)
            var got = 0
            while (got < length) {
                val n = input.read(message, got, length - got)
                if (n < 0) return false
                got += n
            }
            payload = extractStdout(message)
            payloadOffset = 0
            return true
        }

        /**
         * Walks HostMessage{data_message=1{stream=1,data=2},status_message=2}.
         * Returns stdout payload bytes; [onReadyStatus] fires when the guest
         * confirms the session (SetupConnectionResponse READY / EXITED).
         */
        var onReadyStatus: (() -> Unit)? = null

        private fun extractStdout(message: ByteArray): ByteArray {
            var cursor = 0
            while (cursor < message.size) {
                val key = readVarint(message, cursor) ?: break
                cursor = key.second
                val field = (key.first shr 3).toInt()
                val wire = ((key.first and 7L).toInt())
                if (wire == 1 || wire == 5) break
                val len = readVarint(message, cursor) ?: break
                cursor = len.second
                val end = (cursor + len.first).toInt().coerceAtMost(message.size)
                if (field == FIELD_DATA_MESSAGE) {
                    val inner = parseDataMessage(message, cursor, end)
                    if (inner != null) return inner
                } else if (field == FIELD_STATUS_MESSAGE) {
                    onReadyStatus?.invoke()
                    onReadyStatus = null
                }
                cursor = end
            }
            return ByteArray(0)
        }

        private fun parseDataMessage(msg: ByteArray, start: Int, end: Int): ByteArray? {
            var c = start
            var stream = 0
            var dataStart = -1
            var dataEnd = -1
            while (c < end) {
                val key = readVarint(msg, c) ?: break
                c = key.second
                val f = (key.first shr 3).toInt()
                val w = (key.first and 7).toInt()
                if (f == 1 && w == 0) {
                    val v = readVarint(msg, c) ?: break
                    stream = v.first.toInt(); c = v.second
                } else if (f == 2 && w == 2) {
                    val l = readVarint(msg, c) ?: break
                    c = l.second
                    dataStart = c; dataEnd = (c + l.first).toInt().coerceAtMost(end); c = dataEnd
                } else break
            }
            return if (stream == STREAM_STDOUT && dataStart >= 0) {
                msg.copyOfRange(dataStart, dataEnd)
            } else null
        }

        private fun readVarint(data: ByteArray, offset: Int): Pair<Long, Int>? {
            var value = 0L
            var shift = 0
            var i = offset
            while (i < data.size && shift < 64) {
                val b = data[i].toInt() and 0xff
                i++
                value = value or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return value to i
                shift += 7
            }
            return null
        }
    }

    /**
     * Wraps the vsock output so everything the terminal types becomes a
     * GuestMessage DataMessage(STDIN) frame, with a 4-byte LE length prefix.
     */
    private inner class GuestOutputStream(private val output: OutputStream) : OutputStream() {
        override fun write(b: Int) = throw UnsupportedOperationException()

        override fun write(b: ByteArray, off: Int, len: Int) {
            // DataMessage{stream=1(STDIN), data=bytes} inside GuestMessage.
            val inner = ByteBuffer.allocate(len + 10)
            writeVarintField(inner, 1, STREAM_STDIN)
            writeTag(inner, 2, WIRE_LEN)
            writeVarint(inner, len.toLong())
            inner.put(b, off, len)
            val outer = ByteBuffer.allocate(inner.position() + 10)
            writeTag(outer, FIELD_DATA_MESSAGE, WIRE_LEN)
            writeVarint(outer, inner.position().toLong())
            outer.put(inner.array(), 0, inner.position())
            writeFrame(output, outer.array(), outer.position())
        }

        override fun flush() = output.flush()

        override fun close() = output.close()

        /** WindowResizeMessage{rows=1,cols=2} in GuestMessage{resize_message=3}. */
        fun sendResize(rows: Int, cols: Int) {
            val inner = ByteBuffer.allocate(16)
            writeVarintField(inner, 1, rows)
            writeVarintField(inner, 2, cols)
            val outer = ByteBuffer.allocate(inner.position() + 8)
            writeTag(outer, FIELD_RESIZE_MESSAGE, WIRE_LEN)
            writeVarint(outer, inner.position().toLong())
            outer.put(inner.array(), 0, inner.position())
            writeFrame(output, outer.array(), outer.position())
        }
    }

    @Volatile private var liveGuestOutput: GuestOutputStream? = null

    private fun onWindowSizeChanged(size: AvfTerminalSize) {
        runCatching { liveGuestOutput?.sendResize(size.rows, size.columns) }
    }

    /** The live vsock descriptor; closed with the session to release the fd. */
    @Volatile private var livePfd: ParcelFileDescriptor? = null

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        relay.close()
        executor.shutdownNow()
        runCatching { livePfd?.close() }
        livePfd = null
        liveGuestOutput = null
    }

    private companion object {
        const val VSHD_PORT = 6_211L
        const val STREAM_STDOUT = 2
        const val STREAM_STDIN = 1
        const val FIELD_DATA_MESSAGE = 1
        const val FIELD_STATUS_MESSAGE = 2
        const val FIELD_USER = 2
        const val FIELD_RESIZE_MESSAGE = 3
        const val FIELD_ROWS = 6
        const val FIELD_COLS = 7
        const val WIRE_VARINT = 0
        const val WIRE_LEN = 2
        const val MAX_MESSAGE = 64 * 1024
        const val DEFAULT_ROWS = 24
        const val DEFAULT_COLS = 80

        fun writeVarint(buffer: ByteBuffer, value: Long): ByteBuffer {
            var v = value
            while (true) {
                if (v and 0x7f.inv() == 0L) {
                    buffer.put(v.toByte())
                    return buffer
                }
                buffer.put(((v and 0x7f) or 0x80).toByte())
                v = v ushr 7
            }
        }

        fun writeTag(buffer: ByteBuffer, field: Int, wire: Int) {
            buffer.put(((field shl 3) or wire).toByte())
        }

        fun writeVarintField(buffer: ByteBuffer, field: Int, value: Int) {
            writeTag(buffer, field, WIRE_VARINT)
            writeVarint(buffer, value.toLong())
        }
    }
}
