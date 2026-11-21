package com.rk.terminal.vm

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Cores, memory, swap and disk of the Debian VM; applied on the next boot. */
@Composable
fun DebianVmSpec() {
    val ctx = LocalContext.current
    val cur = remember { DebianVm.spec(ctx) }
    val maxCpus = DebianVm.maxCpus
    val maxMem = remember { ((DebianVm.hostMemMb(ctx) * 3 / 4) / 256 * 256).toInt().coerceAtLeast(1024) }
    val minDisk = remember { DebianVm.minDiskGb(ctx) }
    val maxDisk = remember { maxOf(DebianVm.diskGb(ctx), minDisk + 4, 128) }
    var cpus by remember { mutableIntStateOf(cur.cpus) }
    var mem by remember { mutableIntStateOf(cur.memMb) }
    var swap by remember { mutableIntStateOf(cur.swapMb) }
    var disk by remember { mutableIntStateOf(maxOf(DebianVm.diskGb(ctx), minDisk)) }
    var expand by remember { mutableStateOf(DebianVm.autoExpand(ctx)) }
    fun save() = DebianVm.setSpec(ctx, DebianVm.Spec(cpus, mem, swap, disk))
    fun gb(mb: Int) = "${mb / 1024.0}".removeSuffix(".0") + " GB"

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Line("Cores", "$cpus")
        Slider(cpus.toFloat(), { cpus = it.roundToInt() }, Modifier.fillMaxWidth(),
            valueRange = 1f..maxCpus.toFloat(), steps = (maxCpus - 2).coerceAtLeast(0),
            onValueChangeFinished = ::save)
        Line("Memory", gb(mem))
        Slider(mem.toFloat(), { mem = (it / 512).roundToInt() * 512 }, Modifier.fillMaxWidth(),
            valueRange = 512f..maxMem.toFloat(), onValueChangeFinished = ::save)
        Line("Swap", if (swap == 0) "Off" else gb(swap))
        Slider(swap.toFloat(), { swap = (it / 512).roundToInt() * 512 }, Modifier.fillMaxWidth(),
            valueRange = 0f..8192f, onValueChangeFinished = ::save)
        Line("Disk", if (expand) "automatic (now ${DebianVm.diskGb(ctx)} GB)" else "$disk GB")
        Slider(disk.toFloat(), { disk = ((it / 4).roundToInt() * 4).coerceIn(minDisk, maxDisk) }, Modifier.fillMaxWidth(),
            enabled = !expand,
            valueRange = minDisk.toFloat()..maxDisk.toFloat(),
            onValueChangeFinished = ::save)
        Text(
            "Changes apply on the next boot (Restart VM is in the side drawer). Shrinking runs e2fsck + resize2fs before boot; the smallest size is what the disk holds now + 2 GB.",
            style = MaterialTheme.typography.bodySmall,
        )
        Toggle("Automatic storage expansion",
            "At each boot, grow the disk into the phone's free space (keeps 2 GB for Android). Turn off to set the size by hand.",
            expand) { expand = it; DebianVm.setAutoExpand(ctx, it) }
        var autoStart by remember { mutableStateOf(DebianVm.autoStart(ctx)) }
        Toggle("Start the VM with the app",
            "Boot Debian when the app opens. Off: Debian tabs show that the VM is off, with a Start button.",
            autoStart) { autoStart = it; DebianVm.setAutoStart(ctx, it) }
        var trimBoot by remember { mutableStateOf(DebianVm.trimAtBoot(ctx)) }
        Toggle("Trim at boot",
            "Run fstrim after each boot, so space freed inside Debian is given back to the phone.",
            trimBoot) { trimBoot = it; DebianVm.setTrimAtBoot(ctx, it) }
        var wake by remember { mutableStateOf(DebianVm.wakelock(ctx)) }
        Toggle("Wakelock while running", "Keep the CPU awake while the VM runs, so it keeps working with the screen off.",
            wake) { wake = it; DebianVm.setWakelock(ctx, it) }
        var access by remember { mutableStateOf(DebianVm.storageAccess()) }
        var share by remember { mutableStateOf(DebianVm.shareStorage(ctx)) }
        Toggle("Share phone storage as /mnt/media",
            if (access) "Mounted on the next Debian tab (hostfs)." else "Needs all-files access: tap to grant.",
            share && access) {
            if (!access) {
                runCatching {
                    ctx.startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:${ctx.packageName}")))
                }
                access = DebianVm.storageAccess()
            }
            share = it; DebianVm.setShareStorage(ctx, it)
        }
        if (DebianVm.running) Row {
            var trimmed by remember { mutableStateOf<String?>(null) }
            TextButton(onClick = {
                trimmed = "Trimming…"
                Thread { trimmed = DebianVm.trim(ctx) }.start()
            }) { Text(trimmed ?: "Free unused space") }
        }
    }
}

@Composable
private fun Toggle(label: String, desc: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(desc, style = MaterialTheme.typography.bodySmall)
        }
        Switch(on, set)
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
