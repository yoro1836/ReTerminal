package com.yoro1836.terminal.ui.screens.terminal

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doOnTextChanged
import com.yoro1836.libcommons.child
import com.yoro1836.libcommons.dpToPx
import com.yoro1836.libcommons.localDir
import com.yoro1836.settings.Settings
import com.yoro1836.terminal.backend.avf.AvfUiState
import com.yoro1836.terminal.service.SessionService
import com.yoro1836.terminal.ui.activities.terminal.MainActivity
import com.yoro1836.terminal.ui.screens.terminal.virtualkeys.*
import com.termux.terminal.TerminalColors
import com.termux.view.TerminalView
import java.io.FileInputStream
import java.util.*

@Composable
fun TerminalViewLayout(
    viewModel: TerminalViewModel,
    mainActivity: MainActivity,
    sessionBinder: SessionService.SessionBinder,
    modifier: Modifier = Modifier
) {
    val avfState by AvfUiState.state.collectAsState()
    val compositionRenderer = remember { TerminalCompositionRenderer() }
    DisposableEffect(compositionRenderer) {
        onDispose(compositionRenderer::close)
    }
    Column(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
        AndroidView(
            factory = { ctx ->
                TerminalView(ctx, null).apply {
                    viewModel.setTerminalView(this)
                    setTextSize(dpToPx(Settings.terminal_font_size.toFloat(), ctx))
                    setBackgroundColor(TerminalUtils.getBackgroundColor())

                    val client = TerminalBackEnd(this, mainActivity) { viewModel.terminalImeView }
                    val service = sessionBinder.getService()

                    val session = sessionBinder.getSession(service.currentSession.value.first)
                        ?: sessionBinder.createSession(
                            service.currentSession.value.first,
                            client,
                        )

                    session.updateTerminalSessionClient(client)
                    attachSession(session)
                    setTerminalViewClient(client)
                    setTypeface(TerminalUtils.typeface)

                    post {
                        val color = TerminalUtils.getViewColor()
                        val bgColor = TerminalUtils.getBackgroundColor()
                        keepScreenOn = true
                        requestFocus()
                        isFocusableInTouchMode = true

                        mEmulator?.mColors?.mCurrentColors?.apply {
                            set(256, color)
                            set(257, bgColor)
                            set(258, color)
                        }
                        onScreenUpdated()

                        val colorsFile = ctx.localDir().child("colors.properties")
                        if (colorsFile.exists() && colorsFile.isFile) {
                            val props = Properties()
                            FileInputStream(colorsFile).use { props.load(it) }
                            TerminalColors.COLOR_SCHEME.updateWith(props)
                        }
                    }
                    compositionRenderer.attach(this)
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.setBackgroundColor(TerminalUtils.getBackgroundColor())
                val color = TerminalUtils.getViewColor()
                val bgColor = TerminalUtils.getBackgroundColor()
                view.mEmulator?.mColors?.mCurrentColors?.apply {
                    set(256, color)
                    set(257, bgColor)
                    set(258, color)
                }
                view.onScreenUpdated()
            }
        )
            AndroidView(
                factory = { ctx ->
                    TerminalImeEditText(ctx).apply {
                        terminalProvider = { viewModel.terminalView }
                        onCompositionChanged = compositionRenderer::setComposition
                        viewModel.setTerminalImeView(this)
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(1.dp),
            )
            if (avfState.loading) {
                Column(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(24.dp))
                    Text(text = avfState.message, color = Color.White)
                }
            } else if (avfState.message.isNotEmpty()) {
                Text(
                    text = avfState.message,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .background(Color(0xcc202020))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        if (viewModel.showVirtualKeys && !avfState.loading) {
            VirtualKeysPager(viewModel)
        }
    }
}

@Composable
private fun VirtualKeysPager(viewModel: TerminalViewModel) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val onSurfaceColor = TerminalUtils.getViewColor()
    val onSurfaceVariantColor = onSurfaceColor

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxWidth().height(75.dp)
    ) { page ->
        when (page) {
            0 -> {
                AndroidView(
                    factory = { ctx ->
                        VirtualKeysView(ctx, null).apply {
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            buttonActiveTextColor = onSurfaceColor
                            viewModel.setVirtualKeysView(this)
                            virtualKeysViewClient = viewModel.terminalView?.mTermSession?.let {
                                VirtualKeysListener(it)
                            }
                            buttonTextColor = onSurfaceColor
                            reload(VirtualKeysInfo(Settings.virtual_keys_string, "", VirtualKeysConstants.CONTROL_CHARS_ALIASES))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(75.dp),
                    update = { view ->
                        view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        view.buttonActiveTextColor = onSurfaceColor
                        view.buttonTextColor = onSurfaceColor
                    }
                )
            }
            1 -> {
                var text by rememberSaveable { mutableStateOf("") }
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(75.dp),
                    factory = { ctx ->
                        EditText(ctx).apply {
                            maxLines = 1
                            isSingleLine = true
                            imeOptions = EditorInfo.IME_ACTION_DONE
                            setTextColor(onSurfaceColor)
                            setHintTextColor(onSurfaceVariantColor)
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            doOnTextChanged { t, _, _, _ -> text = t.toString() }
                            setOnEditorActionListener { _, actionId, _ ->
                                if (actionId == EditorInfo.IME_ACTION_DONE) {
                                    val terminal = viewModel.terminalView
                                    if (text.isEmpty()) {
                                        terminal?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                                        terminal?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                                    } else {
                                        terminal?.currentSession?.write(text)
                                        setText("")
                                    }
                                    true
                                } else false
                            }
                        }
                    },
                    update = { editText ->
                        editText.setTextColor(onSurfaceColor)
                        editText.setHintTextColor(onSurfaceVariantColor)
                        if (editText.text.toString() != text) {
                            editText.setText(text)
                            editText.setSelection(text.length)
                        }
                    }
                )
            }
        }
    }
}
