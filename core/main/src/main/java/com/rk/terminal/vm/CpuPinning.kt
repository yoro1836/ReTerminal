package com.rk.terminal.vm

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.components.compose.preferences.base.PreferenceLayout
import com.rk.components.compose.preferences.base.PreferenceTemplate

/**
 * Settings > Debian VM > CPU pinning: the phone cores the whole VM runs on,
 * grouped by core type (max frequency). Applied at the next boot (taskset).
 */
@Composable
fun CpuPinningScreen(navController: NavController) {
    val ctx = LocalContext.current
    val mhz = remember { DebianVm.cpuMaxMhz() }
    var on by remember { mutableStateOf(DebianVm.cpuPinOn(ctx)) }
    var sel by remember { mutableStateOf(DebianVm.cpuSelection(ctx)) }
    val vm by DebianVm.state
    fun setSel(m: Long) { sel = m; DebianVm.setCpuSelection(ctx, m) }
    fun setOn(v: Boolean) {
        on = v; DebianVm.setCpuPinOn(ctx, v)
        // First switch-on with nothing chosen: start from the fastest cores.
        if (v && sel == 0L) setSel(groups(mhz).lastOrNull()?.second?.fold(0L) { a, c -> a or (1L shl c) } ?: 0L)
    }
    val effective = if (on) sel else 0L

    PreferenceLayout(label = "CPU pinning", onBack = { navController.popBackStack() }) {
        Text(
            "Keep the VM (kernel, virtual CPUs and its helper processes) on the chosen phone cores, " +
                "e.g. only the fast ones. Applies at the next boot. When the app is in the background, " +
                "Android can still hold it to the little cores.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        if (vm == "ready" && effective != DebianVm.bootedCpuMask) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Changes apply when the VM restarts.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { DebianVm.restart(ctx) }) { Text("Restart VM") }
                }
            }
        }

        PreferenceGroup {
            PreferenceTemplate(
                title = { Text("Pin the VM") },
                description = { Text(if (on && sel != 0L) "Cores ${DebianVm.cpuList(sel)}" else "Off: any core") },
                endWidget = { Switch(on, ::setOn) },
            )
        }

        groups(mhz).forEach { (name, cpus) ->
            val gm = cpus.fold(0L) { a, c -> a or (1L shl c) }
            val all = sel and gm == gm
            PreferenceGroup(heading = name) {
                PreferenceTemplate(
                    enabled = on,
                    title = { Text("${cpus.size} ${if (cpus.size == 1) "core" else "cores"}") },
                    endWidget = {
                        TextButton(enabled = on, onClick = { setSel(if (all) sel and gm.inv() else sel or gm) }) {
                            Text(if (all) "Clear" else "Select all")
                        }
                    },
                )
                cpus.forEach { c ->
                    val bit = 1L shl c
                    PreferenceTemplate(
                        enabled = on,
                        title = { Text("Core $c") },
                        endWidget = {
                            Checkbox(checked = sel and bit != 0L, enabled = on, onCheckedChange = { setSel(sel xor bit) })
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Cores grouped by max frequency, slowest first, named Little / Mid / Big. */
private fun groups(mhz: List<Int>): List<Pair<String, List<Int>>> {
    val known = mhz.indices.filter { mhz[it] > 0 }
    if (known.isEmpty()) return listOf("Cores" to mhz.indices.toList())
    val byFreq = known.groupBy { mhz[it] }.toSortedMap().entries.toList()
    return byFreq.mapIndexed { i, (f, cpus) ->
        val kind = when {
            byFreq.size == 1 -> "Cores"
            i == 0 -> "Little"
            i == byFreq.size - 1 -> "Big"
            else -> "Mid"
        }
        "$kind · %.2f GHz".format(f / 1000f) to cpus
    } + (mhz.indices.filter { mhz[it] <= 0 }.takeIf { it.isNotEmpty() }?.let { listOf("Other" to it) } ?: emptyList())
}
