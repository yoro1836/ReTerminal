package com.yoro1836.terminal.backend.avf

import android.content.Context
import java.io.File

internal object AvfCloudInit {
    private const val ASSET_PATH = "avf/cidata.iso"
    private const val VERSION = "18"

    fun install(context: Context, imageDir: File): File {
        val target = imageDir.resolve("cidata.iso")
        val marker = imageDir.resolve("cidata.version")
        if (target.isFile && target.length() > 0L && marker.readTextOrNull() == VERSION) {
            return target
        }

        val temporary = imageDir.resolve("cidata.iso.part")
        context.assets.open(ASSET_PATH).use { input ->
            temporary.outputStream().buffered().use(input::copyTo)
        }
        check(temporary.renameTo(target)) { "Unable to install AVF cloud-init seed" }
        marker.writeText(VERSION)
        return target
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()
}
