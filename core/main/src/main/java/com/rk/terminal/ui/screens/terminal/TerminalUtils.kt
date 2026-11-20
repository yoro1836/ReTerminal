package com.rk.terminal.ui.screens.terminal

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import androidx.compose.runtime.mutableStateOf
import com.rk.libcommons.child
import com.rk.settings.Settings

object TerminalUtils {
    var darkText = mutableStateOf(Settings.blackTextColor)
    var typeface: Typeface = Typeface.MONOSPACE
    var hasCustomBackground = mutableStateOf(false)

    fun init(context: Context) {
        val fontFile = context.filesDir.child("font.ttf")
        if (fontFile.exists() && fontFile.canRead()) {
            typeface = Typeface.createFromFile(fontFile)
        }
        hasCustomBackground.value = context.filesDir.child("background").exists()
    }

    fun getViewColor(): Int =
        if (!hasCustomBackground.value || !darkText.value) Color.WHITE else Color.BLACK

    fun getBackgroundColor(): Int =
        if (hasCustomBackground.value) Color.TRANSPARENT else Color.BLACK

    fun getComposeColor(): androidx.compose.ui.graphics.Color =
        androidx.compose.ui.graphics.Color(getViewColor())

    fun getNameOfWorkingMode(workingMode: Int?): String = when (workingMode) {
        1 -> "android"
        2 -> "debian"
        else -> "unknown"
    }


}
