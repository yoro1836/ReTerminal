package com.rk.terminal.vm

import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * X11 display for the VM through the Termux:X11 app. Its X server
 * (com.termux.x11.CmdEntryPoint) runs in this app's uid via app_process with
 * the Termux:X11 APK on the classpath, and sends the Termux:X11 activity a
 * binder to draw into. The server's unix socket is relayed to 127.0.0.1:6000,
 * which the guest reaches as <gateway>:0 (passt maps the gateway to the
 * phone's loopback), so DISPLAY=<gateway>:0 in the guest.
 */
object X11 {
    const val PKG = "com.termux.x11"
    const val PORT = 6000

    @Volatile private var server: Process? = null
    @Volatile private var relay: ServerSocket? = null

    fun installed(ctx: Context) = runCatching { ctx.packageManager.getApplicationInfo(PKG, 0) }.isSuccess

    private fun dir(ctx: Context) = File(ctx.filesDir, "x11")
    fun socket(ctx: Context) = File(dir(ctx), "tmp/.X11-unix/X0")

    /** Start the X server and relay if needed, then show Termux:X11. Null on success, else an error. */
    fun open(context: Context): String? {
        val ctx = context.applicationContext
        val ai = runCatching { ctx.packageManager.getApplicationInfo(PKG, 0) }.getOrNull()
            ?: return "Termux:X11 is not installed"
        try {
            synchronized(this) {
                if (server?.isAlive != true) {
                    val tmp = File(dir(ctx), "tmp").apply { mkdirs() }
                    unpackXkb(ctx)
                    socket(ctx).delete()
                    val pb = ProcessBuilder("/system/bin/app_process", "/", "com.termux.x11.CmdEntryPoint", ":0")
                        .redirectErrorStream(true).redirectOutput(File(dir(ctx), "x11.log"))
                    pb.environment().apply {
                        put("CLASSPATH", ai.sourceDir)
                        put("TMPDIR", tmp.path)
                        put("XKB_CONFIG_ROOT", xkb(ctx).path)
                        remove("LD_PRELOAD")
                    }
                    server = pb.start()
                }
                if (relay == null) relay = startRelay(ctx)
            }
        } catch (e: Exception) {
            return "Could not start the X server: ${e.message ?: e}"
        }
        val launch = ctx.packageManager.getLaunchIntentForPackage(PKG)
            ?: return "Termux:X11 has no launcher activity"
        ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }

    val running get() = server?.isAlive == true

    fun stop() {
        synchronized(this) {
            runCatching { relay?.close() }
            relay = null
            server?.destroy()
            server = null
        }
    }

    /** Keyboard data the X server needs (assets/xkb.zip, from Debian's xkb-data). */
    private fun xkb(ctx: Context) = File(dir(ctx), "xkb")

    private fun unpackXkb(ctx: Context) {
        val stamp = File(xkb(ctx), ".version")
        val ver = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        if (stamp.isFile && stamp.readText() == ver) return
        xkb(ctx).deleteRecursively()
        java.util.zip.ZipInputStream(ctx.assets.open("xkb.zip")).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val f = File(xkb(ctx), e.name)
                if (!f.canonicalPath.startsWith(xkb(ctx).canonicalPath + "/")) continue
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
            }
        }
        stamp.writeText(ver)
    }

    private fun startRelay(ctx: Context): ServerSocket {
        val ss = ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!ss.isClosed) {
                val c = try { ss.accept() } catch (_: Exception) { break }
                Thread { bridge(ctx, c) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true; name = "x11-relay" }.start()
        return ss
    }

    private fun bridge(ctx: Context, tcp: Socket) {
        val unix = LocalSocket()
        try {
            // The server may still be starting: wait for its socket a little.
            val path = socket(ctx)
            for (i in 0 until 50) { if (path.exists()) break; Thread.sleep(100) }
            unix.connect(LocalSocketAddress(path.path, LocalSocketAddress.Namespace.FILESYSTEM))
            tcp.tcpNoDelay = true
            val t = Thread { pump(unix.inputStream, tcp.getOutputStream()); runCatching { tcp.shutdownOutput() } }
            t.isDaemon = true
            t.start()
            pump(tcp.getInputStream(), unix.outputStream)
            runCatching { unix.shutdownOutput() }
            t.join()
        } catch (_: Exception) {
        } finally {
            runCatching { tcp.close() }
            runCatching { unix.close() }
        }
    }

    private fun pump(i: InputStream, o: OutputStream) {
        val b = ByteArray(64 * 1024)
        try {
            while (true) {
                val n = i.read(b)
                if (n < 0) break
                o.write(b, 0, n)
                o.flush()
            }
        } catch (_: Exception) {}
    }
}
