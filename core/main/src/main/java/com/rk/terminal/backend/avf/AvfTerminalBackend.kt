package com.rk.terminal.backend.avf

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import com.rk.settings.Settings
import com.rk.terminal.backend.TerminalSessionBackend
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AvfTerminalBackend(
    context: Context,
    private val sessionId: String,
) : TerminalSessionBackend {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val windowSizeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val windowSizeUpdates = ArrayBlockingQueue<AvfTerminalSize>(1)
    private val latestWindowSize = AtomicReference<AvfTerminalSize>()
    @Volatile private var windowSizeOutput: OutputStream? = null
    private val relay = AvfConsoleRelay("reterminal-avf-$sessionId", ::onWindowSizeChanged)
    private var vm: Any? = null
    private var session: TerminalSession? = null

    override fun createSession(client: TerminalSessionClient): TerminalSession {
        check(session == null) { "AVF terminal session already created" }
        AvfUiState.loading("Preparing Debian...")
        val bridge = File(appContext.applicationInfo.nativeLibraryDir, "libavf-terminal-bridge.so")
        check(bridge.canExecute()) { "AVF terminal bridge is missing: ${bridge.absolutePath}" }

        return TerminalSession(
            bridge.absolutePath,
            appContext.filesDir.absolutePath,
            arrayOf("avf-terminal-bridge", relay.socketName, relay.windowSizeSocketName),
            arrayOf("TERM=xterm-256color", "COLORTERM=truecolor"),
            2000,
            client,
        ).also {
            session = it
            executor.execute(::startVm)
        }
    }

    private fun startVm() {
        try {
            check(Build.VERSION.SDK_INT >= 36) { "Android 16 or newer is required" }
            check(
                appContext.packageManager.hasSystemFeature("android.software.virtualization_framework")
            ) { "This device does not expose Android Virtualization Framework" }

            HiddenApiBypass.addHiddenApiExemptions("Landroid/system/virtualmachine/")

            val images = AvfImageInstaller.ensureInstalled(appContext, relay::status)

            val manager = AvfApi.manager(appContext)
            val capabilities = AvfApi.capabilities(manager)
            check(capabilities == 0 || capabilities and 2 != 0) {
                "The device supports protected VMs only; Debian requires a non-protected VM"
            }

            val vmName = "debian"
            val boot = AvfBootResolver.resolve(appContext, images.disk, relay::status)
            val seed = AvfCloudInit.install(appContext, images.disk.parentFile!!)
            relay.status("Booting Debian GNU/Linux...")
            val sharedMediaPath = if (Settings.avfSharedMediaEnabled) {
                check(Environment.isExternalStorageManager()) {
                    "All files access is required for AVF /mnt/media"
                }
                Environment.getExternalStorageDirectory().absolutePath.also {
                    check(File(it).isDirectory) { "Host /sdcard is unavailable" }
                }
            } else {
                null
            }
            val custom = AvfApi.customConfig(
                name = vmName,
                kernelPath = boot.kernel.absolutePath,
                initrdPath = boot.initrd?.absolutePath,
                diskPath = images.disk.absolutePath,
                seedPath = seed.absolutePath,
                cmdline = boot.cmdline,
                networkEnabled = Settings.avfNetworkEnabled,
                memoryBalloonEnabled = Settings.avfMemoryBalloonEnabled,
                sharedMediaPath = sharedMediaPath,
            )
            val config = AvfApi.vmConfig(
                context = appContext,
                customConfig = custom,
                memoryBytes = Settings.avfMemoryMb.coerceIn(1024, 8192) * 1024L * 1024L,
                cpuTopology = if (Settings.avfCpuCount <= 1) 0 else 1,
            )
            val machine = AvfApi.getOrCreate(manager, vmName, config)
            vm = machine
            val callback = AvfApi.callback(
                onError = { code, message ->
                    relay.fail("VM error $code: ${message ?: "unknown error"}")
                    finishSession()
                },
                onStopped = { reason ->
                    if (!closed.get()) relay.fail("VM stopped (reason $reason)")
                    finishSession()
                },
            )
            AvfApi.setCallback(machine, executor, callback)
            relay.attach(
                output = AvfApi.consoleOutput(machine),
                input = AvfApi.consoleInput(machine),
            )
            windowSizeExecutor.execute { forwardWindowSizes(machine) }
            AvfApi.run(machine)
        } catch (error: Throwable) {
            stopWindowSizeForwarding()
            val cause = generateSequence(error) { it.cause }.last()
            relay.fail(cause.message ?: cause.javaClass.simpleName)
        }
    }


    private fun onWindowSizeChanged(size: AvfTerminalSize) {
        if (closed.get() || latestWindowSize.getAndSet(size) == size) return
        windowSizeUpdates.clear()
        windowSizeUpdates.offer(size)
    }

    private fun forwardWindowSizes(machine: Any) {
        while (!closed.get() && !Thread.currentThread().isInterrupted) {
            var output: OutputStream? = null
            try {
                output = ParcelFileDescriptor.AutoCloseOutputStream(
                    AvfApi.connectVsock(machine, WINDOW_SIZE_PORT),
                )
                windowSizeOutput = output
                var delivered: AvfTerminalSize? = null
                while (!closed.get() && !Thread.currentThread().isInterrupted) {
                    val latest = latestWindowSize.get()
                    val size = if (latest != null && latest != delivered) {
                        latest
                    } else {
                        val queued = windowSizeUpdates.take()
                        latestWindowSize.get() ?: queued
                    }
                    if (size == delivered) continue
                    output.write(
                        "${size.rows} ${size.columns} ${size.pixelWidth} ${size.pixelHeight}\n"
                            .toByteArray(Charsets.US_ASCII)
                    )
                    output.flush()
                    delivered = size
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (_: Throwable) {
                if (!closed.get()) {
                    try {
                        Thread.sleep(WINDOW_SIZE_RETRY_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            } finally {
                if (windowSizeOutput === output) windowSizeOutput = null
                runCatching { output?.close() }
            }
        }
    }

    private fun stopWindowSizeForwarding() {
        runCatching { windowSizeOutput?.close() }
        windowSizeOutput = null
        windowSizeExecutor.shutdownNow()
    }

    private fun finishSession() {
        stopWindowSizeForwarding()
        runCatching { session?.finishIfRunning() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopWindowSizeForwarding()
        vm?.let { machine -> runCatching { AvfApi.stop(machine) } }
        vm = null
        relay.close()
        finishSession()
        session = null
        executor.shutdownNow()
        AvfUiState.reset()
    }

    private companion object {
        const val WINDOW_SIZE_PORT = 6_210L
        const val WINDOW_SIZE_RETRY_MS = 250L
    }
}
