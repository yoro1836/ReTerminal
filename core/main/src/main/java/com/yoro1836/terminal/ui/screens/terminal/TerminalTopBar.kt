package com.yoro1836.terminal.ui.screens.terminal

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.yoro1836.terminal.service.SessionService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalTopBar(
    sessionBinder: SessionService.SessionBinder?,
    onMenuClick: () -> Unit,
    onNewSession: () -> Unit,
    color: Color
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
            titleContentColor = color,
            navigationIconContentColor = color,
            actionIconContentColor = color,
        ),
        title = {
            Column {
                Text(text = "ReTerminal", color = color)
                sessionBinder?.getService()?.currentSession?.value?.let { (id, _) ->
                    Text(
                        style = MaterialTheme.typography.bodySmall,
                        text = id,
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
            IconButton(onClick = onNewSession) {
                Icon(Icons.Default.Add, contentDescription = "New SSH session", tint = color)
            }
        },
    )
}
