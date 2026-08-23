package com.rk.terminal.backend.avf

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AvfUiState {
    data class State(
        val loading: Boolean = false,
        val message: String = "",
        val error: String? = null,
    )

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    fun loading(message: String) {
        mutableState.value = State(loading = true, message = message)
    }

    fun ready() {
        mutableState.value = State()
    }

    fun failed(message: String) {
        mutableState.value = State(loading = false, message = message, error = message)
    }

    fun reset() {
        mutableState.value = State()
    }
}
