package com.rk.terminal.ui.screens.terminal

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText
import com.termux.view.TerminalView

class TerminalImeEditText(context: Context) : AppCompatEditText(context) {
    var terminalProvider: () -> TerminalView? = { null }
    var onCompositionChanged: (CharSequence?) -> Unit = {}
    private var forwardingTerminal: TerminalView? = null
    private var forwardingConnection: InputConnection? = null

    init {
        maxLines = 1
        isSingleLine = true
        isCursorVisible = false
        includeFontPadding = false
        setPadding(0, 0, 0, 0)
        setBackgroundColor(Color.TRANSPARENT)
        setTextColor(Color.TRANSPARENT)
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
    }

    fun showKeyboardAtCursor() {
        requestFocus()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        val target = super.onCreateInputConnection(outAttrs) ?: BaseInputConnection(this, true)
        return object : InputConnectionWrapper(target, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val result = super.commitText(text, newCursorPosition)
                text?.takeIf { it.isNotEmpty() }?.let(::sendToTerminal)
                clearComposition()
                return result
            }

            override fun finishComposingText(): Boolean {
                val result = super.finishComposingText()
                editableText?.toString()?.takeIf { it.isNotEmpty() }?.let(::sendToTerminal)
                clearComposition()
                return result
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val result = super.setComposingText(text, newCursorPosition)
                updateCompositionPreview()
                return result
            }

            override fun deleteSurroundingText(leftLength: Int, rightLength: Int): Boolean {
                if (editableText.isNullOrEmpty()) {
                    repeat(leftLength.coerceAtLeast(1)) {
                        dispatchToTerminal(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                        dispatchToTerminal(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                    }
                    return true
                }
                val result = super.deleteSurroundingText(leftLength, rightLength)
                updateCompositionPreview()
                return result
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                editableText?.toString()?.takeIf { it.isNotEmpty() }?.let(::sendToTerminal)
                clearComposition()
                dispatchToTerminal(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                dispatchToTerminal(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean = dispatchToTerminal(event)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        dispatchToTerminal(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        dispatchToTerminal(event) || super.onKeyUp(keyCode, event)

    private fun sendToTerminal(text: CharSequence) {
        val terminal = terminalProvider() ?: return
        if (forwardingTerminal !== terminal) {
            forwardingTerminal = terminal
            forwardingConnection = terminal.onCreateInputConnection(EditorInfo())
        }
        forwardingConnection?.commitText(text, 1)
    }

    private fun dispatchToTerminal(event: KeyEvent): Boolean =
        terminalProvider()?.dispatchKeyEvent(event) ?: false

    private fun updateCompositionPreview() {
        onCompositionChanged(editableText)
    }

    private fun clearComposition() {
        editableText?.clear()
        onCompositionChanged(null)
    }

    override fun onDetachedFromWindow() {
        onCompositionChanged(null)
        super.onDetachedFromWindow()
    }
}
