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
    private const val IMAGE = 1
    private const val IMAGE_URL = "https://github.com/yoro1836/ReTerminal/releases/download/rt-debian-$IMAGE/rt-debian-$IMAGE.ext4.gz"

    private fun lib(ctx: Context, n: String) = File(ctx.applicationInfo.nativeLibraryDir, n)
    fun supported(ctx: Context = application!!) = lib(ctx, "libuml.so").exists()

    fun dir(ctx: Context) = File(ctx.filesDir, "vm")
    private fun disk(ctx: Context) = File(dir(ctx), "debian.ext4")
    private fun log(ctx: Context) = File(dir(ctx), "console.log")
    fun key(ctx: Context) = File(dir(ctx), "id_dropbear")
    private fun statusFile(ctx: Context) = File(dir(ctx), "status")
    private fun portFile(ctx: Context) = File(dir(ctx), "port")
    fun port(ctx: Context) = try { portFile(ctx).readText().trim().toInt() } catch (_: Exception) { 0 }

    /** Another app (or a stale VM) may hold any fixed port, so take a free one. */
    private fun freePort() = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
    fun installed(ctx: Context) = try {
        File(dir(ctx), ".ready").readText().trim() == "rt-$IMAGE"
    } catch (_: Exception) { false }

    // ---- spec (applied on the next boot)

    val hostCpus get() = Runtime.getRuntime().availableProcessors()
    fun hostMemMb(ctx: Context) = ActivityManager.MemoryInfo().also {
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
    }.totalMem shr 20

    data class Spec(val cpus: Int, val memMb: Int, val swapMb: Int, val diskGb: Int)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("debian_vm", Context.MODE_PRIVATE)
    fun spec(ctx: Context) = prefs(ctx).let {
        Spec(
            it.getInt("cpus", minOf(4, hostCpus)).coerceIn(1, minOf(8, hostCpus)),
            it.getInt("mem", 2048),
            it.getInt("swap", 1024),
            it.getInt("disk", 16),
        )
    }
    fun setSpec(ctx: Context, s: Spec) {
        prefs(ctx).edit().putInt("cpus", s.cpus).putInt("mem", s.memMb)
            .putInt("swap", s.swapMb).putInt("disk", maxOf(s.diskGb, diskGb(ctx))).apply()
    }
    fun diskGb(ctx: Context) = (disk(ctx).length() shr 30).toInt()

    // ---- options (from the AVF build's settings)

    /** Hold a partial wakelock while the VM runs, so it keeps going with the screen off. */
    fun wakelock(ctx: Context) = prefs(ctx).getBoolean("wakelock", true)
    fun setWakelock(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("wakelock", on).apply()
    /** Grow the disk to the phone's free space at each boot (never shrinks). */
    fun autoExpand(ctx: Context) = prefs(ctx).getBoolean("auto_expand", true)
    fun setAutoExpand(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto_expand", on).apply()
    /** Mount the phone's shared storage at /mnt/media (hostfs); needs all-files access. */
    fun shareStorage(ctx: Context) = prefs(ctx).getBoolean("share_storage", false)
    fun setShareStorage(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("share_storage", on).apply()
    fun storageAccess() = android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()
    /** Host directory the tab script mounts, or null. */
    fun sharedDir(ctx: Context): String? =
        if (shareStorage(ctx) && storageAccess()) android.os.Environment.getExternalStorageDirectory().path else null

    /** Space the disk may take: its current use plus the phone's free space, less 2 GB for Android. */
    private fun expandTarget(ctx: Context): Long {
        val d = disk(ctx)
        val used = try { android.system.Os.stat(d.path).st_blocks * 512 } catch (_: Exception) { d.length() }
        return ((used + dir(ctx).usableSpace - (2L shl 30)) shr 30) shl 30
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
        private set

    // ---- lifecycle

    @Volatile private var proc: Process? = null
    @Volatile private var starting = false
    val running get() = proc?.isAlive == true

    private fun status(ctx: Context, s: String) {
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
        var want = sp.diskGb.toLong() shl 30
        if (autoExpand(app)) want = maxOf(want, expandTarget(app))
        if (disk(app).length() < want) RandomAccessFile(disk(app), "rw").use { it.setLength(want) }
        val cmd = listOf(
            lib(app, "libumnet.so").path, "--passt", lib(app, "libpasst.so").path,
            "--tcp", "127.0.0.1/$port:22", "--",
            lib(app, "libuml.so").path, "mem=${sp.memMb}M", "ncpus=${sp.cpus}",
            "con=null", "con0=fd:0,fd:1",
            "stub_exe=" + lib(app, "libumstub.so").path,
            "ubd0=" + disk(app).path, "root=/dev/ubda", "rw",
            "rtkey=$pub", "rtswap=${sp.swapMb}",
        )
        val pb = ProcessBuilder(cmd).directory(dir(app)).redirectErrorStream(true)
            .redirectOutput(log(app))
        pb.environment().apply {
            put("TMPDIR", app.cacheDir.path)
            put("HOME", dir(app).path) // ~/.uml/<id>/mconsole
        }
        val p = pb.start()
        proc = p
        bootedWith = sp
        hold(app, true)
        status(app, "booted")
        Thread {
            p.waitFor()
            hold(app, false)
            if (proc === p) {
                proc = null
                status(app, "error: the VM stopped (exit ${p.exitValue()})")
            }
        }.start()
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
