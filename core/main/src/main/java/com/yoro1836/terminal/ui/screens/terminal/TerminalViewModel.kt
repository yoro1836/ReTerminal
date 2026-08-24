package com.yoro1836.terminal.ui.screens.terminal

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import com.google.android.material.R
import com.yoro1836.settings.Settings
import com.yoro1836.terminal.service.SessionService
import com.yoro1836.terminal.ui.activities.terminal.MainActivity
import com.yoro1836.terminal.ui.screens.terminal.virtualkeys.VirtualKeysListener
import com.yoro1836.terminal.ui.screens.terminal.virtualkeys.VirtualKeysView
import com.yoro1836.terminal.backend.avf.AvfUiState
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.lang.ref.WeakReference

class TerminalViewModel : ViewModel() {
    private var terminalViewRef = WeakReference<TerminalView>(null)
    private var virtualKeysViewRef = WeakReference<VirtualKeysView>(null)
    private var terminalImeViewRef = WeakReference<TerminalImeEditText>(null)

    val terminalView: TerminalView? get() = terminalViewRef.get()
    val virtualKeysView: VirtualKeysView? get() = virtualKeysViewRef.get()
    val terminalImeView: TerminalImeEditText? get() = terminalImeViewRef.get()

    fun setTerminalView(view: TerminalView?) { terminalViewRef = WeakReference(view) }
    fun setVirtualKeysView(view: VirtualKeysView?) { virtualKeysViewRef = WeakReference(view) }
    fun setTerminalImeView(view: TerminalImeEditText?) { terminalImeViewRef = WeakReference(view) }

    var bitmap by mutableStateOf<ImageBitmap?>(null)
    var wallAlpha by mutableFloatStateOf(Settings.wallTransparency)
    var backgroundBlur by mutableFloatStateOf(Settings.background_blur)

    var showToolbar by mutableStateOf(Settings.toolbar)
    var showVirtualKeys by mutableStateOf(Settings.virtualKeys)
    var showHorizontalToolbar by mutableStateOf(Settings.toolbar)

    fun setFont(typeface: Typeface) {
        TerminalUtils.typeface = typeface
        terminalView?.apply {
            setTypeface(typeface)
            onScreenUpdated()
        }
    }

    fun changeSession(context: Context, sessionBinder: SessionService.SessionBinder, sessionId: String) {
        val terminal = terminalView ?: return
        val activity = context as? MainActivity ?: return
        val client = TerminalBackEnd(terminal, activity) { terminalImeView }

        val session = sessionBinder.getSession(sessionId)
            ?: sessionBinder.createSession(sessionId, client)
        attachSession(terminal, client, session, sessionId, sessionBinder)
    }

    /** Creates a new vsock shell tab into the running VM and switches to it. */
    fun addTabSession(context: Context, sessionBinder: SessionService.SessionBinder): Boolean {
        val terminal = terminalView ?: return false
        val activity = context as? MainActivity ?: return false
        val id = nextTabSessionName(sessionBinder)
        val client = TerminalBackEnd(terminal, activity) { terminalImeView }
        val session = sessionBinder.createSession(id, client)
        attachSession(terminal, client, session, id, sessionBinder)
        return true
    }

    private fun nextTabSessionName(sessionBinder: SessionService.SessionBinder): String {
        var n = 1
        while (sessionBinder.getService().sessionList.containsKey("tab$n")) n++
        return "tab$n"
    }

    private fun attachSession(
        terminal: TerminalView,
        client: TerminalBackEnd,
        session: TerminalSession,
        sessionId: String,
        sessionBinder: SessionService.SessionBinder,
    ) {
        session.updateTerminalSessionClient(client)
        terminal.setBackgroundColor(TerminalUtils.getBackgroundColor())
        terminal.attachSession(session)
        terminal.setTerminalViewClient(client)

        terminal.post {
            val fgColor = TerminalUtils.getViewColor()
            val bgColor = TerminalUtils.getBackgroundColor()
            terminal.keepScreenOn = true
            terminal.requestFocus()
            terminal.isFocusableInTouchMode = true

            terminal.mEmulator?.mColors?.mCurrentColors?.apply {
                set(256, fgColor)
                set(257, bgColor)
                set(258, fgColor)
            }
            terminal.onScreenUpdated()
        }

        virtualKeysView?.apply {
            virtualKeysViewClient = terminal.mTermSession?.let { VirtualKeysListener(it) }
        }
        sessionBinder.getService().currentSession.value = Pair(sessionId, sessionBinder.getService().sessionList[sessionId] ?: Settings.working_Mode)
    }
}
