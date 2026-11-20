package com.rk.terminal.vm

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Phone USB devices; tap Attach to pass one into the Debian VM. */
@Composable
fun UsbDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val devices = remember(refresh) { UsbPassthrough.devices(ctx) }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("USB devices") },
        text = {
            Column {
                if (devices.isEmpty()) Text("No USB device is connected to the phone.")
                devices.forEach { d ->
                    val on = UsbPassthrough.attached.containsKey(d.deviceName)
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(UsbPassthrough.label(d), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(enabled = busy[d.deviceName] != true, onClick = {
                            busy[d.deviceName] = true
                            error = null
                            Thread {
                                if (on) UsbPassthrough.detach(ctx, d.deviceName)
                                else UsbPassthrough.attach(ctx, d)?.let { error = it }
                                busy[d.deviceName] = false
                            }.start()
                        }) { Text(if (busy[d.deviceName] == true) "…" else if (on) "Detach" else "Attach") }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("An attached device leaves Android and appears in Debian (lsusb). Drivers are modules: modprobe usb-storage, ftdi_sio, cdc_acm, rtw88_8821au …",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = { refresh++ }) { Text("Refresh") } },
    )
}
