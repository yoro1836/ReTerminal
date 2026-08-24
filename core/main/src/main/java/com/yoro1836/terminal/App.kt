package com.yoro1836.terminal

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.StrictMode
import com.github.anrwatchdog.ANRWatchDog
import com.yoro1836.libcommons.application
import com.yoro1836.resources.Res
import com.yoro1836.terminal.ui.screens.terminal.TerminalUtils
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

class App : Application() {

    companion object {
        fun getTempDir(context: Context): File {
            val tmp = File(context.cacheDir, "tmp")
            if (!tmp.exists()) {
                tmp.mkdir()
            }
            return tmp
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        application = this
        Res.application = this
        TerminalUtils.init(this)

        GlobalScope.launch(Dispatchers.IO) {
            getTempDir(this@App).apply {
                if (exists() && listFiles().isNullOrEmpty().not()) {
                    deleteRecursively()
                }
            }
        }

        ANRWatchDog().start()


        if (BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().apply {
                    detectAll()
                    penaltyLog()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        penaltyListener(Executors.newSingleThreadExecutor()) { violation ->
                            violation.printStackTrace()
                        }
                    }
                }.build()
            )
        }
    }
}
