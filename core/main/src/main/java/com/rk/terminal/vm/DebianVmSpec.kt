package com.rk.terminal.vm

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    val maxCpus = minOf(8, DebianVm.hostCpus)
    val maxMem = remember { ((DebianVm.hostMemMb(ctx) * 3 / 4) / 256 * 256).toInt().coerceAtLeast(1024) }
    val minDisk = remember { maxOf(8, DebianVm.diskGb(ctx)) }
    var cpus by remember { mutableIntStateOf(cur.cpus) }
    var mem by remember { mutableIntStateOf(cur.memMb) }
    var swap by remember { mutableIntStateOf(cur.swapMb) }
    var disk by remember { mutableIntStateOf(maxOf(cur.diskGb, minDisk)) }
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
        Line("Disk", "$disk GB")
        Slider(disk.toFloat(), { disk = (it / 4).roundToInt() * 4 }, Modifier.fillMaxWidth(),
            valueRange = minDisk.toFloat()..maxOf(minDisk + 4, 128).toFloat(),
            onValueChangeFinished = ::save)
        Text(
            "The disk can only grow. Changes apply when the VM restarts (open Debian tabs disconnect).",
            style = MaterialTheme.typography.bodySmall,
        )
        if (DebianVm.running) TextButton(onClick = { save(); DebianVm.restart(ctx) }) { Text("Restart VM") }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
