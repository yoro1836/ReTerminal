package com.yoro1836.terminal.backend.avf

import android.content.Context
import android.os.Build
import android.os.Environment
import com.yoro1836.settings.Settings
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the Debian VM independent of any terminal tab.
 *
 * Tabs come and go; every tab is a rvshd/vsh session over vsock. The VM is
 * booted on demand by [ensureRunning], shared by all tabs, and only stopped
 * through [powerOff] (drawer power button) or process death.
 */
internal object AvfVmManager {
    private val lock = Any()
    private val machineRef = AtomicReference<Any?>(null)

    /** The live machine handle, or null when the VM is not running. */
    fun acquire(): Any? = machineRef.get()

    /**
     * Boots the VM if needed and returns the machine handle. [onStatus]
     * receives human-readable progress (download, kernel prep, boot).
     */
    fun ensureRunning(context: Context, onStatus: (String) -> Unit): Any =
        machineRef.get() ?: synchronized(lock) {
            machineRef.get() ?: boot(context.applicationContext, onStatus).also { machineRef.set(it) }
        }

    /** Powers the VM off; open tabs observe their vsock sessions ending. */
    fun stopDebian() {
        val machine = synchronized(lock) { machineRef.getAndSet(null) } ?: return
        runCatching { AvfApi.stop(machine) }
    }

    private fun boot(context: Context, onStatus: (String) -> Unit): Any {
        check(Build.VERSION.SDK_INT >= 36) { "Android 16 or newer is required" }
        check(
            context.packageManager.hasSystemFeature("android.software.virtualization_framework")
        ) { "This device does not expose Android Virtualization Framework" }

        HiddenApiBypass.addHiddenApiExemptions("Landroid/system/virtualmachine/")

        val images = AvfImageInstaller.ensureInstalled(context, onStatus)

        val manager = AvfApi.manager(context)
        val capabilities = AvfApi.capabilities(manager)
        check(capabilities == 0 || capabilities and 2 != 0) {
            "The device supports protected VMs only; Debian requires a non-protected VM"
        }

        val vmName = "debian"
        val boot = AvfBootResolver.resolve(context, images.disk, onStatus)
        val seed = AvfCloudInit.install(context, images.disk.parentFile!!)
        onStatus("Booting Debian GNU/Linux...")
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
            context = context,
            customConfig = custom,
            memoryBytes = Settings.avfMemoryMb.coerceIn(1024, 8192) * 1024L * 1024L,
            cpuTopology = if (Settings.avfCpuCount <= 1) 0 else 1,
        )
        val machine = AvfApi.getOrCreate(manager, vmName, config)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        AvfApi.setCallback(
            machine,
            executor,
            AvfApi.callback(
                onError = { _, _ ->
                    // Tabs surface the failure through their dead vsock sessions.
                    machineRef.compareAndSet(machine, null)
                },
                onStopped = { _ ->
                    machineRef.compareAndSet(machine, null)
                },
            ),
        )
        AvfApi.run(machine)
        return machine
    }
}
