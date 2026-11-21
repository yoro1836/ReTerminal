package com.rk.terminal.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rk.terminal.service.SessionService
import com.rk.terminal.ui.screens.settings.WorkingMode
import com.rk.terminal.vm.DebianVm

/**
 * Drawn over the terminal area only (the top bar and drawer stay usable):
 * "no tabs" after the last tab is closed, and for a Debian tab the VM being
 * off or booting. "Show log" shows the VM's console (files/vm/console.log)
 * here; the tab itself prints nothing until the login.
 */
@Composable
fun TerminalOverlay(service: SessionService, onNewTab: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val id = service.currentSession.value.first
    val empty = service.allClosed.value && service.sessionList.isEmpty()
    val mode = service.sessionList[id] ?: service.currentSession.value.second
    val vm by DebianVm.state
    val vmTab = !empty && mode == WorkingMode.DEBIAN_VM && vm != "ready"
    var peek by remember(id, DebianVm.isOff(vm)) { mutableStateOf(false) }
    if (!empty && !vmTab) return

    Box(modifier.fillMaxSize()) {
        if (vmTab && peek) {
            BootLog(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            FilledTonalButton(onClick = { peek = false }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Text("Hide log")
            }
            return@Box
        }
        Column(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // Swallow touches so the terminal underneath doesn't take them.
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val onBg = MaterialTheme.colorScheme.onBackground
            when {
                empty -> {
                    Icon(Icons.Outlined.Terminal, null, Modifier.size(48.dp), tint = onBg)
                    Spacer(Modifier.height(12.dp))
                    Text("No open tabs", style = MaterialTheme.typography.titleMedium, color = onBg)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onNewTab) { Text("New tab") }
                }
                DebianVm.isOff(vm) -> {
                    Icon(Icons.Outlined.PowerSettingsNew, null, Modifier.size(48.dp), tint = onBg)
                    Spacer(Modifier.height(12.dp))
                    Text("Debian VM is off", style = MaterialTheme.typography.titleMedium, color = onBg)
                    if (vm.startsWith("error")) {
                        Spacer(Modifier.height(8.dp))
                        Text(vm.removePrefix("error: "), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { DebianVm.ensure(ctx) }) { Text("Start") }
                    if (vm.startsWith("error")) TextButton(onClick = { peek = true }) { Text("Show log") }
                }
                else -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        when (vm) {
                            "starting" -> "Starting the VM…"
                            "booting", "booted" -> "Booting Debian…"
                            else -> vm
                        },
                        style = MaterialTheme.typography.titleMedium, color = onBg, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { peek = true }) { Text("Show log") }
                }
            }
        }
    }
}

private val ansi = Regex("\u001B(\\[[0-9;?>=]*[ -/]*[@-~]|\\][^\u0007\u001B]*(\u0007|\u001B\\\\)?|[()][0-9A-Za-z]|[=>c78])")

/** The tail of the VM console log, refreshed every second, scrolled to the end. */
@Composable
private fun BootLog(modifier: Modifier) {
    val ctx = LocalContext.current
    var lines by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(Unit) {
        val f = java.io.File(DebianVm.dir(ctx), "console.log")
        while (true) {
            lines = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    java.io.RandomAccessFile(f, "r").use { r ->
                        val n = minOf(r.length(), 64L * 1024)
                        r.seek(r.length() - n)
                        val b = ByteArray(n.toInt()); r.readFully(b)
                        String(b).replace(ansi, "").replace("\r", "").lines().takeLast(400)
                    }
                }.getOrDefault(emptyList())
            }
            kotlinx.coroutines.delay(1000)
        }
    }
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1) }
    androidx.compose.foundation.lazy.LazyColumn(modifier.padding(horizontal = 8.dp), state = state,
        contentPadding = PaddingValues(top = 56.dp, bottom = 8.dp)) {
        items(lines.size) { i ->
            Text(lines[i], color = MaterialTheme.colorScheme.onBackground,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
