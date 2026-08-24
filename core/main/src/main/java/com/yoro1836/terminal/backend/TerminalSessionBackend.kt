package com.yoro1836.terminal.backend

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

interface TerminalSessionBackend : AutoCloseable {
    fun createSession(client: TerminalSessionClient): TerminalSession
    override fun close()
}
