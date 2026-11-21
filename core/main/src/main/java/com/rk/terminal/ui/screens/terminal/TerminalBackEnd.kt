package com.rk.terminal.ui.screens.terminal

import android.content.res.Configuration
import android.content.res.Resources
import android.media.MediaPlayer
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.blankj.utilcode.util.ClipboardUtils
import com.blankj.utilcode.util.KeyboardUtils
import com.rk.libcommons.child
import com.rk.settings.Settings
import com.rk.terminal.ui.activities.terminal.MainActivity
import com.rk.terminal.ui.screens.terminal.virtualkeys.SpecialButton
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

class TerminalBackEnd(
    private val terminal: TerminalView,
    private val activity: MainActivity,
    private val coroutineScope: CoroutineScope = activity.lifecycleScope,
    /** Hidden EditText that takes IME input, so Hangul etc. compose properly. */
    private val imeTarget: () -> TerminalImeEditText? = { null },
) : TerminalViewClient, TerminalSessionClient {

    private val terminalViewModel by lazy { ViewModelProvider(activity)[TerminalViewModel::class.java] }

    override fun onTextChanged(changedSession: TerminalSession) {
        terminal.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        ClipboardUtils.copyText("Terminal", text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = ClipboardUtils.getText()?.toString() ?: return
        if (clip.trim().isNotEmpty() && terminal.mEmulator != null) {
            terminal.mEmulator.paste(clip)
        }
    }

    override fun onBell(session: TerminalSession) {
        if (!Settings.bell) return
        
        coroutineScope.launch {
            val bellFile = activity.cacheDir.child("bell.oga")
            if (!bellFile.exists()) {
                withContext(Dispatchers.IO) {
                    activity.assets.open("bell.oga").use { input ->
                        FileOutputStream(bellFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }

            MediaPlayer().apply {
                setOnCompletionListener { it?.release() }
                setDataSource(bellFile.absolutePath)
                prepare()
                start()
            }
        }
    }

    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String?, message: String?) { Log.e(tag ?: "Terminal", message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: "Terminal", message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: "Terminal", message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: "Terminal", message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: "Terminal", message ?: "") }

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: "Terminal", message ?: "", e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.e(tag ?: "Terminal", "Stack trace", e)
    }

    // TerminalView passes the pinch's cumulative factor (starting at 1). Step the
    // font size by 1 dp each time it passes +-10% and start over from 1, like
    // Termux; the size is saved so it survives leaving the app.
    override fun onScale(scale: Float): Float {
        if (scale in 0.9f..1.1f) return scale
        val cur = Settings.terminal_font_size
        val next = (cur + if (scale > 1f) 1 else -1).coerceIn(MIN_FONT_DP, MAX_FONT_DP)
        if (next != cur) {
            Settings.terminal_font_size = next
            terminal.setTextSize(com.rk.libcommons.dpToPx(next.toFloat(), activity))
        }
        return 1f
    }

    companion object {
        const val MIN_FONT_DP = 6
        const val MAX_FONT_DP = 40
    }

    private val isHardwareKeyboardConnected: Boolean
        get() = Resources.getSystem().configuration.keyboard != Configuration.KEYBOARD_NOKEYS

    override fun onSingleTapUp(e: MotionEvent) {
        if (!(isHardwareKeyboardConnected && Settings.hide_soft_keyboard_if_hwd)) {
            showSoftInput()
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = Settings.input_mode != 1
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        if (KeyShortcutHandler.handle(keyCode, e, activity)) return true
        
        if (keyCode == KeyEvent.KEYCODE_ENTER && !session.isRunning) {
            val binder = activity.viewModel.sessionBinder ?: return false
            val service = binder.getService()
            terminalViewModel.closeSession(activity, binder, service.currentSession.value.first)
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean =
        terminalViewModel.virtualKeysView?.readSpecialButton(SpecialButton.CTRL, true) == true

    override fun readAltKey(): Boolean =
        terminalViewModel.virtualKeysView?.readSpecialButton(SpecialButton.ALT, true) == true

    override fun readShiftKey(): Boolean =
        terminalViewModel.virtualKeysView?.readSpecialButton(SpecialButton.SHIFT, true) == true

    override fun readFnKey(): Boolean =
        terminalViewModel.virtualKeysView?.readSpecialButton(SpecialButton.FN, true) == true

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() {
        if (terminal.mEmulator != null) {
            terminal.setTerminalCursorBlinkerState(true, true)
        }
    }

    private fun showSoftInput() {
        val target = imeTarget()
        if (target != null) {
            target.showKeyboardAtCursor()
            KeyboardUtils.showSoftInput(target)
        } else {
            terminal.requestFocus()
            KeyboardUtils.showSoftInput(terminal)
        }
    }
}
