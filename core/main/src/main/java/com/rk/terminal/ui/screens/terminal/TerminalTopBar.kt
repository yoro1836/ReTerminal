package com.rk.terminal.ui.screens.terminal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.rk.terminal.service.SessionService

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TerminalTopBar(
    sessionBinder: SessionService.SessionBinder?,
    onMenuClick: () -> Unit,
    onAddClick: () -> Unit,
    onAddLongClick: () -> Unit = {},
    color: Color
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent
        ),
        title = {
            Column {
                Text(text = "ReTerminal", color = color)
                sessionBinder?.getService()?.currentSession?.value?.let { (id, mode) ->
                    Text(
                        style = MaterialTheme.typography.bodySmall,
                        text = "$id (${TerminalUtils.getNameOfWorkingMode(mode)})",
                        color = color
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.Menu, null, tint = color)
            }
        },
        actions = {
            if (com.rk.terminal.vm.DebianVm.supported()) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                IconButton(onClick = {
                    com.rk.terminal.vm.X11.open(ctx)?.let {
                        android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show()
                    }
                }) {
                    Icon(Icons.Outlined.DesktopWindows, "Display (Termux:X11)", tint = color)
                }
            }
            Box(
                Modifier.size(48.dp).combinedClickable(onClick = onAddClick, onLongClick = onAddLongClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Add, "New tab (long press: choose kind)", tint = color)
            }
        }
    )
}
