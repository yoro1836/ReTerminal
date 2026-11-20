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

    fun getViewColor(): Int = if (darkText.value) Color.BLACK else Color.WHITE

    fun getBackgroundColor(): Int {
        if (hasCustomBackground.value) {
            return Color.TRANSPARENT
        }
        val baseColor = if (darkText.value) Color.WHITE else Color.BLACK
        return androidx.core.graphics.ColorUtils.setAlphaComponent(baseColor, (255 * 0.3f).toInt())
    }

    fun getComposeColor(): androidx.compose.ui.graphics.Color =
        if (darkText.value) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White

    fun getNameOfWorkingMode(workingMode: Int?): String = when (workingMode) {
        1 -> "android"
        2 -> "debian"
        else -> "unknown"
    }


}
