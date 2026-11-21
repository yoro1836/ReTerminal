package com.rk.terminal.ui.screens.terminal

import android.content.Context
import com.rk.libcommons.child
import com.rk.libcommons.createFileIfNot
import com.rk.libcommons.localBinDir
import com.rk.terminal.App.Companion.getTempDir
import com.rk.terminal.BuildConfig
import com.rk.terminal.ui.screens.settings.WorkingMode
import com.rk.terminal.vm.DebianVm
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

object MkSession {
    fun createSession(
        context: Context,
        sessionClient: TerminalSessionClient,
        sessionId: String,
        workingMode: Int,
        pendingCommand: PendingCommand? = null
    ): TerminalSession {
        with(context) {
            val workingDir = pendingCommand?.workingDir ?: filesDir.path

            val env = mutableListOf(
                "PATH=${System.getenv("PATH")}:/sbin:${localBinDir().absolutePath}",
                "HOME=/sdcard",
                "PUBLIC_HOME=${getExternalFilesDir(null)?.absolutePath}",
                "COLORTERM=truecolor",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "BIN=${localBinDir()}",
                "DEBUG=${BuildConfig.DEBUG}",
                "PREFIX=${filesDir.parentFile!!.path}",
                "NATIVE_LIB_DIR=${applicationInfo.nativeLibraryDir}",
                "PKG=${packageName}",
                "RISH_APPLICATION_ID=${packageName}",
                "PKG_PATH=${applicationInfo.sourceDir}",
                "TMPDIR=${getTempDir(this).absolutePath}",
            )
            listOf(
                "ANDROID_ART_ROOT", "ANDROID_DATA", "ANDROID_I18N_ROOT", "ANDROID_ROOT",
                "ANDROID_RUNTIME_ROOT", "ANDROID_TZDATA_ROOT", "BOOTCLASSPATH",
                "DEX2OATBOOTCLASSPATH", "EXTERNAL_STORAGE",
            ).forEach { env.add("$it=${System.getenv(it)}") }
            pendingCommand?.env?.let { env.addAll(it) }

            val vmLogin = localBinDir().child("vm-login")
            if (pendingCommand == null && workingMode == WorkingMode.DEBIAN_VM) {
                // Rewritten every time: it is small and follows the app version.
                vmLogin.createFileIfNot()
                vmLogin.writeText(assets.open("vm-login.sh").bufferedReader().use { it.readText() })
                env.add("VM_DIR=${DebianVm.dir(this).absolutePath}")
                DebianVm.sharedDir(this)?.let { env.add("VM_SHARE=$it") }
                if (DebianVm.trimAtBoot(this)) env.add("VM_TRIM=1")
                if (DebianVm.autoStart(this)) DebianVm.ensure(this)
            }

            val args: Array<String>
            val shell = if (pendingCommand == null) {
                // argv[0] first (termux execs args as the whole argv).
                args = if (workingMode == WorkingMode.DEBIAN_VM) arrayOf("sh", vmLogin.absolutePath) else arrayOf()
                "/system/bin/sh"
            } else {
                args = pendingCommand.args
                pendingCommand.shell
            }

            return TerminalSession(
                shell,
                workingDir,
                args,
                env.toTypedArray(),
                TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                sessionClient,
            )
        }
    }

    fun buildCustomPendingCommand(context: Context, custom: CustomSession): PendingCommand {
        val scriptFile = File(custom.shellPath)
        return PendingCommand(
            shell = "/system/bin/sh",
            args = arrayOf("sh", "-c", scriptFile.absolutePath),
            workingDir = scriptFile.parentFile?.absolutePath ?: "/sdcard/ReTerminal",
            env = null
        )
    }

    fun buildScriptPendingCommand(
        context: Context,
        script: File,
        workingMode: Int,
        custom: CustomSession? = null
    ): PendingCommand {
        val workingDir = script.parentFile?.absolutePath
        return PendingCommand(
            shell = "/system/bin/sh",
            args = if (custom != null) {
                arrayOf("sh", "-c", "'${custom.shellPath}' '${script.absolutePath}'")
            } else {
                arrayOf("sh", "-c", script.absolutePath)
            },
            workingDir = workingDir,
            env = null
        )
    }
}

data class PendingCommand(
    val shell: String,
    val args: Array<String>,
    val workingDir: String?,
    val env: List<String>?
)
