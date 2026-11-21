package com.rk.terminal.vm

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.components.compose.preferences.base.PreferenceLayout
import com.rk.components.compose.preferences.base.PreferenceTemplate

/**
 * Settings > Debian VM > Port forwarding: phone 127.0.0.1:<phone> -> VM <vm>.
 * Rules are applied when the VM boots (passt -t), so there is a Restart button
 * when they differ from the running VM's. "Listening in the VM" lists the
 * guest's TCP listeners (ss) for one-tap adding.
 */
@Composable
fun PortForwardingScreen(navController: NavController) {
    val ctx = LocalContext.current
    var rules by remember { mutableStateOf(DebianVm.forwards(ctx)) }
    var adding by remember { mutableStateOf<Pair<String, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val vm by DebianVm.state

    fun save(list: List<String>): String? {
        val r = DebianVm.parseForwards(list.joinToString(","))
        r.exceptionOrNull()?.let { return it.message }
        DebianVm.setForwards(ctx, r.getOrThrow().joinToString(", "))
        rules = DebianVm.forwards(ctx)
        return null
    }

    PreferenceLayout(label = "Port forwarding", onBack = { navController.popBackStack() }) {
        Text(
            "Opens a VM port on the phone at 127.0.0.1 (localhost), for the browser and other apps. " +
                "The VM reaches the phone's localhost at its gateway address without any rule.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        if (vm == "ready" && rules != DebianVm.bootedForwards) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Changes apply when the VM restarts.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { DebianVm.restart(ctx) }) { Text("Restart VM") }
                }
            }
        }

        PreferenceGroup(heading = "Rules") {
            if (rules.isEmpty()) PreferenceTemplate(title = { Text("No rules") },
                description = { Text("Add one below, or pick a port the VM listens on.") })
            rules.forEach { r ->
                val phone = r.substringBefore(':')
                val guest = r.substringAfter(':', phone)
                PreferenceTemplate(
                    title = { Text("localhost:$phone  →  VM :$guest") },
                    endWidget = {
                        IconButton(onClick = { save(rules - r) }) {
                            Icon(Icons.Outlined.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
                        }
                    },
                )
            }
            PreferenceTemplate(
                modifier = Modifier.padding(vertical = 4.dp),
                title = { TextButton(onClick = { adding = "" to "" }) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Add rule")
                } },
            )
        }

        Listening(rules) { p -> adding = (p.toInt().let { if (it < 1024) "${it + 8000}" else p }) to p }
    }

    adding?.let { (p0, g0) ->
        var phone by remember(p0) { mutableStateOf(p0) }
        var guest by remember(g0) { mutableStateOf(g0) }
        AlertDialog(
            onDismissRequest = { adding = null; error = null },
            title = { Text("Forward a port") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(guest, { guest = it.filter(Char::isDigit).take(5) }, singleLine = true,
                        label = { Text("VM port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(phone, { phone = it.filter(Char::isDigit).take(5) }, singleLine = true,
                        label = { Text("Phone port") }, placeholder = { Text(guest.ifEmpty { "same as VM" }) },
                        supportingText = { Text("1024-65535; empty = same as the VM port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(enabled = guest.isNotEmpty(), onClick = {
                    val p = phone.ifEmpty { guest }
                    val rule = if (p == guest) p else "$p:$guest"
                    error = save(rules.filter { it.substringBefore(':') != p } + rule)
                    if (error == null) adding = null
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = null; error = null }) { Text("Cancel") } },
        )
    }
}

/** TCP ports the guest listens on (not 22), each with a button to forward it. */
@Composable
private fun Listening(rules: List<String>, onAdd: (String) -> Unit) {
    val ctx = LocalContext.current
    val vm by DebianVm.state
    var ports by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun scan() {
        busy = true
        Thread {
            val (rc, out) = DebianVm.rootSh(ctx, "ss -Hltn | awk '{print \$4}' | sed 's/.*://' | sort -un", 10)
            ports = if (rc == 0) out.lines().map { it.trim() }.filter { it.isNotEmpty() && it != "22" } else emptyList()
            busy = false
        }.start()
    }
    LaunchedEffect(vm) { if (vm == "ready") scan() else ports = null }
    if (vm != "ready") return
    val forwarded = rules.map { it.substringAfter(':', it.substringBefore(':')) }.toSet()
    PreferenceGroup(heading = "Listening in the VM") {
        PreferenceTemplate(
            title = { Text(if (busy) "Scanning…" else if (ports.isNullOrEmpty()) "No TCP listeners" else "Tap + to forward") },
            endWidget = { IconButton(enabled = !busy, onClick = { scan() }) { Icon(Icons.Outlined.Refresh, "Scan") } },
        )
        ports.orEmpty().forEach { p ->
            PreferenceTemplate(
                title = { Text("VM :$p") },
                description = { if (p in forwarded) Text("Forwarded") },
                endWidget = {
                    if (p !in forwarded) IconButton(onClick = { onAdd(p) }) { Icon(Icons.Filled.Add, "Forward") }
                },
            )
        }
    }
}
