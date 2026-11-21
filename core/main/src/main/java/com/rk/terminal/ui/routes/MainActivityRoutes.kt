package com.rk.terminal.ui.routes

sealed class MainActivityRoutes(val route: String) {
    data object Settings : MainActivityRoutes("settings")
    data object Customization : MainActivityRoutes("customization")
    data object PortForwarding : MainActivityRoutes("port_forwarding")
    data object CpuPinning : MainActivityRoutes("cpu_pinning")
    data object MainScreen : MainActivityRoutes("main")
}