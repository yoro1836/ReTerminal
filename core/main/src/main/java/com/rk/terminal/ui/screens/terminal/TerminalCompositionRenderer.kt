package com.rk.terminal.ui.screens.terminal

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.view.TerminalView

internal class TerminalCompositionRenderer {
    private var terminal: TerminalView? = null
    private var composition = ""
    private var previewEmulator: TerminalEmulator? = null
    private var previewColumns = 0
    private var previewRows = 0
    private var renderedComposition = ""
    private var renderedCursorColumn = -1
    private var renderedCursorRow = -1
    private var cursorEmulator: TerminalEmulator? = null

    private val overlay = object : Drawable() {
        override fun draw(canvas: Canvas) = drawComposition(canvas)
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSPARENT
    }

    private val layoutListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        overlay.setBounds(0, 0, view.width, view.height)
        if (composition.isNotEmpty()) hideTerminalCursor()
        overlay.invalidateSelf()
    }

    fun attach(view: TerminalView) {
        if (terminal === view) return
        detach()
        terminal = view
        overlay.setBounds(0, 0, view.width, view.height)
        view.overlay.add(overlay)
        view.addOnLayoutChangeListener(layoutListener)
        if (composition.isNotEmpty()) hideTerminalCursor()
        overlay.invalidateSelf()
    }

    fun setComposition(text: CharSequence?) {
        val next = text?.toString().orEmpty()
        if (composition == next) return

        composition = next
        if (composition.isEmpty()) restoreTerminalCursor() else hideTerminalCursor()
        terminal?.invalidate()
        overlay.invalidateSelf()
    }

    fun close() {
        composition = ""
        restoreTerminalCursor()
        detach()
    }

    private fun detach() {
        terminal?.let { view ->
            view.removeOnLayoutChangeListener(layoutListener)
            view.overlay.remove(overlay)
        }
        terminal = null
        previewEmulator = null
        previewColumns = 0
        previewRows = 0
        renderedComposition = ""
        renderedCursorColumn = -1
        renderedCursorRow = -1
    }

    private fun drawComposition(canvas: Canvas) {
        val view = terminal ?: return
        val emulator = view.mEmulator ?: return
        val renderer = view.mRenderer ?: return
        if (composition.isEmpty()) return

        hideTerminalCursor()
        val preview = preparePreview(view, emulator)
        emulator.mColors.mCurrentColors.copyInto(preview.mColors.mCurrentColors)
        renderer.render(preview, canvas, 0, -1, -1, -1, -1)
    }

    private fun preparePreview(
        view: TerminalView,
        emulator: TerminalEmulator,
    ): TerminalEmulator {
        val columns = emulator.mColumns
        val rows = emulator.mRows
        val cursorColumn = emulator.cursorCol
        val cursorRow = emulator.cursorRow

        var preview = previewEmulator
        if (preview == null || previewColumns != columns || previewRows != rows) {
            preview = TerminalEmulator(
                NO_OP_OUTPUT,
                columns,
                rows,
                view.mRenderer.fontWidth.toInt(),
                view.mRenderer.fontLineSpacing,
                null,
                null,
            )
            previewEmulator = preview
            previewColumns = columns
            previewRows = rows
            renderedComposition = ""
            renderedCursorColumn = -1
            renderedCursorRow = -1
        }

        if (
            renderedComposition != composition ||
            renderedCursorColumn != cursorColumn ||
            renderedCursorRow != cursorRow
        ) {
            preview.reset()
            val bytes = buildString {
                append("\u001B[2J\u001B[H\u001B[?25l")
                append("\u001B[")
                append(cursorRow + 1)
                append(';')
                append(cursorColumn + 1)
                append('H')
                append(composition)
            }.toByteArray()
            preview.append(bytes, bytes.size)
            renderedComposition = composition
            renderedCursorColumn = cursorColumn
            renderedCursorRow = cursorRow
        }

        return preview
    }

    private fun hideTerminalCursor() {
        val view = terminal ?: return
        val emulator = view.mEmulator ?: return
        if (cursorEmulator !== emulator) {
            view.setTerminalCursorBlinkerState(false, false)
            cursorEmulator = emulator
        }
        emulator.setCursorBlinkingEnabled(true)
        emulator.setCursorBlinkState(false)
    }

    private fun restoreTerminalCursor() {
        if (cursorEmulator == null) return
        terminal?.setTerminalCursorBlinkerState(true, true)
        cursorEmulator = null
    }

    private companion object {
        val NO_OP_OUTPUT = object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) = Unit
            override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
            override fun onCopyTextToClipboard(text: String?) = Unit
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() = Unit
        }
    }
}
