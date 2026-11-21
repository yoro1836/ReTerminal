package com.rk.terminal.vm

import android.app.ActivityManager
import android.content.Context
import android.util.Base64
import com.rk.libcommons.application
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Debian as a real Linux kernel (User-Mode Linux, arm64) running as plain app
 * processes: no root, no proot. Every terminal tab is an SSH session
 * (dropbear's dbclient) to the guest's sshd, which passt forwards to
 * a free 127.0.0.1 port (files/vm/port).
 *
 * Binaries ship as lib*.so (an app may only exec from nativeLibraryDir):
 *   libuml.so      the kernel        libumstub.so  its per-process stub
 *   libumnet.so    fd transport <-> passt framing
 *   libpasst.so    userspace TCP/IP (the guest's network)
 *   libdropbear.so dropbearmulti: dbclient + dropbearkey
 *
 * filesDir/vm: debian.ext4 (sparse disk), .ready, id_dropbear, port, console.log,
 * status (one line the tab's login script shows while it waits).
 */
object DebianVm {
    /** Bump with the image; an older disk is then reinstalled. */
    private const val IMAGE = 4
    private const val IMAGE_URL = "https://github.com/yoro1836/ReTerminal/releases/download/rt-debian-$IMAGE/rt-debian-$IMAGE.ext4.gz"

    internal fun lib(ctx: Context, n: String) = File(ctx.applicationInfo.nativeLibraryDir, n)
    fun supported(ctx: Context = application!!) = lib(ctx, "libuml.so").exists()

    fun dir(ctx: Context) = File(ctx.filesDir, "vm")

    private fun disk(ctx: Context) = File(dir(ctx), "debian.ext4")
    private fun log(ctx: Context) = File(dir(ctx), "console.log")
    fun key(ctx: Context) = File(dir(ctx), "id_dropbear")
    private fun statusFile(ctx: Context) = File(dir(ctx), "status")
    private fun portFile(ctx: Context) = File(dir(ctx), "port")
    fun port(ctx: Context) = try { portFile(ctx).readText().trim().toInt() } catch (_: Exception) { 0 }

    /** Another app (or a stale VM) may hold any fixed port, so take a free one. */
    internal fun freePort() = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
    fun installed(ctx: Context) = try {
        // Any rt-N disk: a newer image is only for new installs, never re-downloaded over one.
        File(dir(ctx), ".ready").readText().trim().startsWith("rt-")
    } catch (_: Exception) { false }

    // ---- spec (applied on the next boot)

    /** All of the phone's cores, counting offline ones (availableProcessors can miss those). */
    val hostCpus: Int get() = try {
        File("/sys/devices/system/cpu/possible").readText().trim().split(",").sumOf { r ->
            r.split("-").let { if (it.size == 2) it[1].toInt() - it[0].toInt() + 1 else 1 }
        }
    } catch (_: Exception) { Runtime.getRuntime().availableProcessors() }
    /** Each host CPU's max frequency in MHz (0 if unreadable), for the pinning chooser. */
    fun cpuMaxMhz(): List<Int> = (0 until hostCpus.coerceAtMost(64)).map { i ->
        runCatching {
            File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toInt() / 1000
        }.getOrDefault(0)
    }

    /**
     * Host CPUs the whole VM (UML kernel, vCPU threads, stubs, passt) is pinned
     * to, as a bit mask; 0 = no pinning. Applied at the next boot via taskset.
     */
    fun cpuMask(ctx: Context) = prefs(ctx).getLong("cpu_mask", 0L)
    fun setCpuMask(ctx: Context, m: Long) = prefs(ctx).edit().putLong("cpu_mask", m).apply()

    /** The kernel is built with NR_CPUS=16. */
    val maxCpus get() = hostCpus.coerceIn(1, 16)
    fun hostMemMb(ctx: Context) = ActivityManager.MemoryInfo().also {
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
    }.totalMem shr 20

    data class Spec(val cpus: Int, val memMb: Int, val swapMb: Int, val diskGb: Int)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("debian_vm", Context.MODE_PRIVATE)
    fun spec(ctx: Context) = prefs(ctx).let {
        Spec(
            it.getInt("cpus", minOf(4, hostCpus)).coerceIn(1, maxCpus),
            it.getInt("mem", 2048),
            it.getInt("swap", 1024),
            it.getInt("disk", 16),
        )
    }
    fun setSpec(ctx: Context, s: Spec) {
        prefs(ctx).edit().putInt("cpus", s.cpus).putInt("mem", s.memMb)
            .putInt("swap", s.swapMb).putInt("disk", maxOf(s.diskGb, minDiskGb(ctx))).apply()
        // Shrinking is explicit and done once, before the next boot; a smaller disk
        // would only be grown back, so automatic expansion goes off.
        if (s.diskGb < diskGb(ctx)) {
            prefs(ctx).edit().putInt("shrink", maxOf(s.diskGb, minDiskGb(ctx))).apply()
            setAutoExpand(ctx, false)
        } else prefs(ctx).edit().remove("shrink").apply()
    }
    fun diskGb(ctx: Context) = (disk(ctx).length() shr 30).toInt()
    /** Bytes the sparse disk really holds on the phone. */
    fun diskUsed(ctx: Context) = try { android.system.Os.stat(disk(ctx).path).st_blocks * 512 } catch (_: Exception) { 0L }
    /** Smallest size the disk may be shrunk to: what it holds plus 2 GB. */
    fun minDiskGb(ctx: Context) = maxOf(4, ((diskUsed(ctx) + (1L shl 30) - 1) shr 30).toInt() + 2)

    // ---- options (from the AVF build's settings)

    /** Hold a partial wakelock while the VM runs, so it keeps going with the screen off. */
    fun wakelock(ctx: Context) = prefs(ctx).getBoolean("wakelock", true)
    fun setWakelock(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("wakelock", on).apply()
    /** fstrim once after each boot (the tab script does it). */
    fun batteryUnrestricted(ctx: Context) =
        (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)

    @android.annotation.SuppressLint("BatteryLife")
    fun requestBatteryUnrestricted(ctx: Context) {
        runCatching {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${ctx.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Android 12+ kills an app's child processes past 32; Developer options can turn it off. */
    fun childProcessLimit(ctx: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 31) return false
        val v = android.provider.Settings.Global.getString(ctx.contentResolver, "settings_enable_monitor_phantom_procs")
        return v != "false"
    }

    /** Asked once per install, at app start, so the VM survives in the background. */
    fun askBatteryOnce(ctx: Context) {
        if (!supported(ctx) || batteryUnrestricted(ctx) || prefs(ctx).getBoolean("asked_battery", false)) return
        prefs(ctx).edit().putBoolean("asked_battery", true).apply()
        requestBatteryUnrestricted(ctx)
    }

    /** Boot the VM when a Debian tab opens (e.g. the first tab at app start). */
    fun autoStart(ctx: Context) = prefs(ctx).getBoolean("auto_start", true)
    fun setAutoStart(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto_start", on).apply()

    fun trimAtBoot(ctx: Context) = prefs(ctx).getBoolean("trim_boot", true)
    fun setTrimAtBoot(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("trim_boot", on).apply()
    /** Grow the disk to the phone's free space at each boot (never shrinks). */
    fun autoExpand(ctx: Context) = prefs(ctx).getBoolean("auto_expand", true)
    fun setAutoExpand(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto_expand", on).apply()
    /** Mount the phone's shared storage at /mnt/media (hostfs); needs all-files access. */
    /**
     * Ports forwarded from the phone's 127.0.0.1 to the guest, as "8080" or
     * "8080:80" (phone:guest), comma separated; applied at the next boot.
     */
    fun forwardsText(ctx: Context) = prefs(ctx).getString("ports", "") ?: ""
    fun forwards(ctx: Context) = parseForwards(forwardsText(ctx)).getOrDefault(emptyList())

    /** Normalised list, or an error to show; phone ports must be 1024..65535 (no root). */
    fun parseForwards(text: String): Result<List<String>> = runCatching {
        text.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.map { t ->
            val m = Regex("^(\\d+)(?::(\\d+))?$").matchEntire(t) ?: error("\"$t\" is not a port or phone:guest pair")
            val host = m.groupValues[1].toInt()
            val guest = m.groupValues[2].ifEmpty { m.groupValues[1] }.toInt()
            require(host in 1024..65535) { "Phone port $host must be 1024-65535" }
            require(guest in 1..65535) { "VM port $guest must be 1-65535" }
            require(host != X11.PORT) { "Phone port ${X11.PORT} is used for the X11 display" }
            if (host == guest) "$host" else "$host:$guest"
        }.distinctBy { it.substringBefore(':') }
    }

    fun setForwards(ctx: Context, text: String) = prefs(ctx).edit().putString("ports", text).apply()

    fun shareStorage(ctx: Context) = prefs(ctx).getBoolean("share_storage", false)
    fun setShareStorage(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("share_storage", on).apply()
    fun storageAccess() = android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()
    /** Host directory the tab script mounts, or null. */
    fun sharedDir(ctx: Context): String? =
        if (shareStorage(ctx) && storageAccess()) android.os.Environment.getExternalStorageDirectory().path else null

    /** Space the disk may take: its current use plus the phone's free space, less 2 GB for Android. */
    private fun expandTarget(ctx: Context): Long {
        return ((diskUsed(ctx) + dir(ctx).usableSpace - (2L shl 30)) shr 30) shl 30
    }

    @Volatile private var wake: android.os.PowerManager.WakeLock? = null
    private fun hold(ctx: Context, on: Boolean) {
        if (on && wakelock(ctx)) {
            val w = wake ?: ctx.getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "reterminal:vm")
                .apply { setReferenceCounted(false) }.also { wake = it }
            if (!w.isHeld) w.acquire()
        } else {
            wake?.let { if (it.isHeld) runCatching { it.release() } }
        }
    }

    @Volatile var bootedWith: Spec? = null
    /** Forward rules the running VM was booted with. */
    @Volatile var bootedForwards: List<String> = emptyList()
        private set

    // ---- lifecycle

    @Volatile private var proc: Process? = null
    @Volatile private var starting = false
    val running get() = proc?.isAlive == true

    /**
     * The VM's state for the UI: "stopped", "starting", "booting", "booted" (kernel
     * up), "ready" (sshd answers), "error: ...", or an install step's text.
     */
    val state = androidx.compose.runtime.mutableStateOf("stopped")
    fun isOff(s: String) = s == "stopped" || s.startsWith("error")
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun status(ctx: Context, s: String) {
        main.post { state.value = s }
        dir(ctx).mkdirs()
        val f = statusFile(ctx)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(s + "\n")
        tmp.renameTo(f)
    }

    /** Install and boot in the background if needed; the tab script follows [status]. */
    fun ensure(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(this) {
            if (running || starting) return
            starting = true
            status(app, "starting")
        }
        Thread {
            try {
                if (!installed(app)) install(app)
                if (!key(app).exists()) makeKey(app)
                boot(app)
            } catch (e: Exception) {
                status(app, "error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                starting = false
            }
        }.start()
    }

    private fun makeKey(ctx: Context) {
        val p = ProcessBuilder(lib(ctx, "libdropbear.so").path, "dropbearkey", "-t", "ed25519", "-f", key(ctx).path)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0 || !key(ctx).exists()) throw IllegalStateException("Could not create the SSH key: $out")
    }

    private fun publicKey(ctx: Context): String {
        val p = ProcessBuilder(lib(ctx, "libdropbear.so").path, "dropbearkey", "-y", "-f", key(ctx).path)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out.lines().firstOrNull { it.startsWith("ssh-") }?.trim()
            ?: throw IllegalStateException("Could not read the public key: $out")
    }

    private fun boot(app: Context) {
        status(app, "booting")
        val pub = Base64.encodeToString(publicKey(app).toByteArray(), Base64.NO_WRAP)
        val sp = spec(app)
        val port = freePort()
        portFile(app).writeText("$port\n")
        File(dir(app), "trimmed").delete()
        var want = sp.diskGb.toLong() shl 30
        if (autoExpand(app)) want = maxOf(want, expandTarget(app))
        val shrinkTo = prefs(app).getInt("shrink", 0).toLong() shl 30
        if (shrinkTo > 0 && shrinkTo < disk(app).length()) {
            prefs(app).edit().remove("shrink").apply()
            shrink(app, shrinkTo)
        } else if (disk(app).length() < want) RandomAccessFile(disk(app), "rw").use { it.setLength(want) }
        val cmd = listOf(
            lib(app, "libumnet.so").path, "--passt", lib(app, "libpasst.so").path,
            "--tcp", "127.0.0.1/" + (listOf("$port:22") + forwards(app)).joinToString(","), "--",
            lib(app, "libuml.so").path, "mem=${sp.memMb}M", "ncpus=${sp.cpus}",
            "con=null", "con0=fd:0,fd:1",
            "stub_exe=" + lib(app, "libumstub.so").path,
            "ubd0=" + disk(app).path, "root=/dev/ubda", "rw",
            "rtkey=$pub", "rtswap=${sp.swapMb}",
        ) + lib(app, "libumlinitrd.so").let { if (it.isFile) listOf("initrd=" + it.path) else emptyList() }
        val mask = cpuMask(app)
        val pinned = if (mask != 0L && File("/system/bin/taskset").exists())
            listOf("/system/bin/taskset", java.lang.Long.toHexString(mask)) + cmd else cmd
        val pb = ProcessBuilder(pinned).directory(dir(app)).redirectErrorStream(true)
            .redirectOutput(log(app))
        pb.environment().apply {
            put("TMPDIR", app.cacheDir.path)
            put("HOME", dir(app).path) // ~/.uml/<id>/mconsole
        }
        val p = pb.start()
        proc = p
        bootedWith = sp
        bootedForwards = forwards(app)
        hold(app, true)
        status(app, "booted")
        // "ready" once the guest's sshd answers, so tabs (and the UI) know it is up.
        Thread {
            while (proc === p && p.isAlive) {
                if (sshTrue(app, port)) {
                    if (proc === p) status(app, "ready")
                    break
                }
                Thread.sleep(1000)
            }
        }.start()
        Thread {
            p.waitFor()
            hold(app, false)
            UsbPassthrough.dropAll()
            val code = p.exitValue()
            // Why it stopped, for Show log (128+N = killed by signal N, e.g. 137 = SIGKILL).
            runCatching { log(app).appendText("\n[ReTerminal] VM process exited with code $code\n") }
            if (proc === p) {
                proc = null
                status(app, "error: the VM stopped (exit $code)")
            }
        }.start()
    }

    /** Shrink the (unmounted) ext4 disk to [size] bytes: e2fsck, resize2fs, truncate. */
    private fun shrink(app: Context, size: Long) {
        status(app, "Shrinking the disk to ${size shr 30} GB…")
        fun run(vararg a: String): Pair<Int, String> {
            val p = ProcessBuilder(*a).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            return p.waitFor() to out.lines().filter { it.isNotBlank() }.takeLast(4).joinToString("\n")
        }
        val (fc, fout) = run(lib(app, "libe2fsck.so").path, "-fy", disk(app).path)
        if (fc >= 4) throw IllegalStateException("e2fsck failed ($fc):\n$fout")
        val (rc, rout) = run(lib(app, "libresize2fs.so").path, disk(app).path, "${size shr 20}M")
        if (rc != 0) throw IllegalStateException("resize2fs failed, the disk is unchanged:\n$rout")
        RandomAccessFile(disk(app), "rw").use { it.setLength(size) }
        status(app, "booting")
    }

    /** fstrim in the guest: hands unused blocks back to the phone. */
    fun trim(ctx: Context): String {
        val app = ctx.applicationContext
        if (!running) return "The VM is not running"
        val before = diskUsed(app)
        val p = ProcessBuilder(
            lib(app, "libdropbear.so").path, "dbclient", "-y", "-i", key(app).path,
            "-p", "${port(app)}", "-T", "root@127.0.0.1", "fstrim -a",
        ).also { it.environment()["HOME"] = dir(app).path }.redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) return "fstrim failed: ${out.lines().lastOrNull { it.isNotBlank() } ?: ""}"
        val freed = (before - diskUsed(app)).coerceAtLeast(0)
        return "Freed ${freed shr 20} MB"
    }

    /** Run [cmd] as root in the guest; returns (exit code, output). */
    fun rootSh(ctx: Context, cmd: String, timeoutSec: Long = 20): Pair<Int, String> {
        val app = ctx.applicationContext
        if (!running) return -1 to "The VM is not running"
        val p = ProcessBuilder(
            lib(app, "libdropbear.so").path, "dbclient", "-y", "-i", key(app).path,
            "-p", "${port(app)}", "-T", "root@127.0.0.1", cmd,
        ).also { it.environment()["HOME"] = dir(app).path }.redirectErrorStream(true).start()
        p.outputStream.close()
        val out = StringBuilder()
        val t = Thread { runCatching { out.append(p.inputStream.bufferedReader().readText()) } }.also { it.start() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroy(); return -1 to "timed out" }
        t.join(1000)
        return p.exitValue() to out.toString().trim()
    }

    /** Power the guest off (sessions die with it). */
    fun stop(ctx: Context, then: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        val p = proc ?: run { then?.let { Thread(it).start() }; return }
        Thread {
            proc = null
            status(app, "stopped")
            try {
                ProcessBuilder(
                    lib(app, "libdropbear.so").path, "dbclient", "-y", "-i", key(app).path,
                    "-p", "${port(app)}", "root@127.0.0.1", "systemctl poweroff",
                ).also { it.environment()["HOME"] = dir(app).path }
                    .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS)
            } catch (_: Exception) {}
            if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroy()
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
            then?.invoke()
        }.start()
    }

    private fun sshTrue(app: Context, port: Int) = try {
        val p = ProcessBuilder(
            lib(app, "libdropbear.so").path, "dbclient", "-y", "-i", key(app).path,
            "-p", "$port", "-T", "root@127.0.0.1", "true",
        ).also { it.environment()["HOME"] = dir(app).path }
            .redirectErrorStream(true).redirectOutput(File("/dev/null")).start()
        if (!p.waitFor(8, TimeUnit.SECONDS)) { p.destroyForcibly(); false } else p.exitValue() == 0
    } catch (_: Exception) { false }

    fun restart(ctx: Context) = stop(ctx) { ensure(ctx) }

    // ---- install

    private fun install(ctx: Context) {
        val d = dir(ctx)
        d.listFiles()?.forEach { if (it.name != "status") it.deleteRecursively() }
        d.mkdirs()
        val gz = File(ctx.cacheDir, "rt-debian-$IMAGE.ext4.gz")
        download(ctx, gz)
        status(ctx, "Creating the disk…")
        val tmp = File(d, "debian.ext4.part")
        RandomAccessFile(tmp, "rw").use { out ->
            GZIPInputStream(gz.inputStream().buffered(1 shl 20), 1 shl 16).use { input ->
                val buf = ByteArray(1 shl 16)
                var pos = 0L
                while (true) {
                    var n = 0
                    while (n < buf.size) {
                        val r = input.read(buf, n, buf.size - n)
                        if (r < 0) break
                        n += r
                    }
                    if (n == 0) break
                    // Skip zero blocks so the disk stays sparse.
                    if (buf.any(n) { it != 0.toByte() }) {
                        out.seek(pos)
                        out.write(buf, 0, n)
                    }
                    pos += n
                    if (n < buf.size) break
                }
                out.setLength(pos)
            }
        }
        gz.delete()
        if (!tmp.renameTo(disk(ctx))) throw IllegalStateException("Could not create the disk")
        File(d, ".ready").writeText("rt-$IMAGE\n")
    }

    private inline fun ByteArray.any(n: Int, p: (Byte) -> Boolean): Boolean {
        for (i in 0 until n) if (p(this[i])) return true
        return false
    }

    private fun download(ctx: Context, to: File) {
        val part = File(to.path + ".part")
        var have = if (part.exists()) part.length() else 0L
        val c = URL(IMAGE_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        val code = c.responseCode
        if (code == 200) have = 0 else if (code != 206) throw IllegalStateException("Download failed: HTTP $code")
        val total = have + c.contentLengthLong.coerceAtLeast(0)
        var last = 0L
        c.inputStream.use { input ->
            java.io.FileOutputStream(part, have > 0).use { out ->
                val buf = ByteArray(1 shl 16)
                var got = have
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    got += r
                    val now = System.currentTimeMillis()
                    if (now - last > 500) {
                        last = now
                        val pct = if (total > 0) " ${got * 100 / total}%" else ""
                        status(ctx, "Downloading Debian… ${got shr 20} MB$pct")
                    }
                }
            }
        }
        if (!part.renameTo(to)) throw IllegalStateException("Could not move the download")
    }
}
