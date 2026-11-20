package com.rk.terminal.vm

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.LocalServerSocket
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * USB passthrough over USB/IP. The app gets the device's usbfs fd from the
 * permission dialog and hands it to libumusb.so (a USB/IP server) over an
 * abstract unix socket; umusb listens on 127.0.0.1:<port>, which the guest
 * reaches at its gateway address (passt maps it to the phone's loopback). The
 * guest then connects a socket and writes it to vhci_hcd's attach file.
 */
object UsbPassthrough {
    private const val ACTION = "com.rk.terminal.USB_PERMISSION"
    private const val VHCI = "/sys/devices/platform/vhci_hcd.0"

    class Attached(val port: Int, val proc: Process, val conn: UsbDeviceConnection)

    /** deviceName -> attachment; observable from Compose. */
    val attached = mutableStateMapOf<String, Attached>()

    fun devices(ctx: Context): List<UsbDevice> =
        (ctx.getSystemService(Context.USB_SERVICE) as UsbManager).deviceList.values
            .sortedBy { it.deviceName }

    fun label(d: UsbDevice): String {
        val name = listOfNotNull(d.manufacturerName.takeIf { !it.isNullOrBlank() },
            d.productName.takeIf { !it.isNullOrBlank() }).joinToString(" ")
        val id = "%04x:%04x".format(d.vendorId, d.productId)
        return if (name.isEmpty()) id else "$name ($id)"
    }

    private fun permission(ctx: Context, um: UsbManager, d: UsbDevice): Boolean {
        if (um.hasPermission(d)) return true
        val done = CountDownLatch(1)
        var ok = false
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                done.countDown()
            }
        }
        ContextCompat.registerReceiver(ctx, r, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION).setPackage(ctx.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            um.requestPermission(d, pi)
            done.await(120, TimeUnit.SECONDS)
        } finally {
            runCatching { ctx.unregisterReceiver(r) }
        }
        return ok || um.hasPermission(d)
    }

    /** Blocking; returns null on success or an error message. */
    fun attach(context: Context, d: UsbDevice): String? {
        val ctx = context.applicationContext
        if (!DebianVm.running) return "The VM is not running"
        if (attached.containsKey(d.deviceName)) return null
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!permission(ctx, um, d)) return "USB permission denied"
        val conn = um.openDevice(d) ?: return "Could not open the device"
        var proc: Process? = null
        try {
            val sockName = "reterminal-usb-${d.deviceId}-${System.nanoTime()}"
            val server = LocalServerSocket(sockName)
            val tcp = DebianVm.freePort()
            proc = ProcessBuilder(DebianVm.lib(ctx, "libumusb.so").path,
                "--usbsock", sockName, "--listen", "127.0.0.1:$tcp")
                .redirectErrorStream(true).start()
            // Hand the usbfs fd over (SCM_RIGHTS); umusb connects to us.
            val pfd = ParcelFileDescriptor.fromFd(conn.fileDescriptor)
            val acceptor = Thread {
                runCatching {
                    server.accept().use { s ->
                        s.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                        s.outputStream.write(0)
                        s.outputStream.flush()
                        s.inputStream.read()
                    }
                }
            }.also { it.isDaemon = true; it.start() }
            // Wait for "attach with: echo "0 <sockfd> DEVID SPEED" > ..."
            val lines = proc.inputStream.bufferedReader()
            val log = StringBuilder()
            var devid = -1L
            var speed = -1
            val reader = Thread {
                while (true) {
                    val l = lines.readLine() ?: break
                    synchronized(log) { log.appendLine(l) }
                    Regex("<sockfd> (\\d+) (\\d+)").find(l)?.let {
                        devid = it.groupValues[1].toLong(); speed = it.groupValues[2].toInt()
                        break
                    }
                }
            }.also { it.start() }
            reader.join(15_000)
            acceptor.join(1000)
            runCatching { server.close() }
            pfd.close()
            if (devid < 0) {
                val out = synchronized(log) { log.toString().trim() }
                proc.destroy()
                return "umusb did not start: " + (out.lines().lastOrNull { it.isNotBlank() } ?: "no output")
            }
            // Keep draining umusb's output (to files/vm/usb.log) so it never blocks on a full pipe.
            val p = proc
            val logFile = java.io.File(DebianVm.dir(ctx), "usb.log")
            runCatching { logFile.writeText(synchronized(log) { log.toString() }) }
            Thread {
                runCatching {
                    logFile.appendText("")
                    java.io.FileWriter(logFile, true).use { w ->
                        while (true) { val l = lines.readLine() ?: break; w.write(l + "\n"); w.flush() }
                    }
                }
                gone(d.deviceName, p)
            }
                .also { it.isDaemon = true; it.start() }

            // vhci port: 0..7 high/full/low speed hub, 8..15 super speed hub.
            val used = attached.values.map { it.port }.toSet()
            val base = if (speed >= 5) 8 else 0
            val port = (base until base + 8).firstOrNull { it !in used } ?: run {
                proc.destroy(); return "No free USB port in the VM"
            }
            val (rc, out) = DebianVm.rootSh(ctx,
                "g=\$(ip route | awk '/default/{print \$3; exit}'); " +
                    "exec 3<>/dev/tcp/\$g/$tcp && echo '$port 3 $devid $speed' > $VHCI/attach")
            if (rc != 0) {
                proc.destroy()
                return "Attach failed in the VM: ${out.ifBlank { "exit $rc" }}"
            }
            attached[d.deviceName] = Attached(port, proc, conn)
            return null
        } catch (e: Exception) {
            proc?.destroy()
            conn.close()
            return e.message ?: e.toString()
        }
    }

    fun detach(context: Context, deviceName: String) {
        val a = attached.remove(deviceName) ?: return
        if (DebianVm.running) DebianVm.rootSh(context, "echo ${a.port} > $VHCI/detach", 5)
        a.proc.destroy()
        runCatching { a.conn.close() }
    }

    /** The VM went away: drop every attachment without talking to it. */
    fun dropAll() {
        attached.keys.toList().forEach { k ->
            attached.remove(k)?.let { it.proc.destroy(); runCatching { it.conn.close() } }
        }
    }

    private fun gone(name: String, p: Process) {
        val a = attached[name] ?: return
        if (a.proc !== p) return
        attached.remove(name)
        runCatching { a.conn.close() }
    }
}
