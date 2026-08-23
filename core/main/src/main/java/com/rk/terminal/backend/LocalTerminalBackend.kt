package com.rk.terminal.backend

import android.content.Context
import com.rk.terminal.ui.screens.terminal.MkSession
import com.rk.terminal.ui.screens.terminal.PendingCommand
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

class LocalTerminalBackend(
    private val context: Context,
    private val sessionId: String,
    private val workingMode: Int,
    private val pendingCommand: PendingCommand?,
) : TerminalSessionBackend {
    private var session: TerminalSession? = null

    override fun createSession(client: TerminalSessionClient): TerminalSession {
        return MkSession.createSession(
            context = context,
            sessionClient = client,
            sessionId = sessionId,
            workingMode = workingMode,
            pendingCommand = pendingCommand,
        ).also { session = it }
    }

    override fun close() {
        session?.finishIfRunning()
        session = null
    }
}
