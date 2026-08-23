package com.rk.terminal.backend.avf

import android.content.Context
import com.rk.settings.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object AvfBootResolver {
    data class BootFiles(
        val kernel: File,
        val initrd: File?,
        val cmdline: String,
        val title: String,
    )

    fun resolve(context: Context, disk: File, report: (String) -> Unit): BootFiles {
        val lbx = File(context.applicationInfo.nativeLibraryDir, "liblbx.so")
        check(lbx.canExecute()) { "Linux boot extractor is unavailable: ${lbx.absolutePath}" }

        report("Scanning Debian boot entries...")
        val entries = JSONArray(runLbx(lbx, "entries", disk.absolutePath, "--json"))
        check(entries.length() > 0) { "No Linux boot entries found in ${disk.name}" }
        val entry = defaultEntry(entries)
        val kernelUri = entry.optString("kernel").takeUnless { it.isBlank() || it == "null" }
            ?: error("The selected Debian boot entry has no kernel")
        val initrdUris = entry.optJSONArray("initrd") ?: JSONArray()
        val cacheDir = File(disk.parentFile, "boot-cache")
        check(cacheDir.isDirectory || cacheDir.mkdirs()) {
            "Unable to create boot cache: ${cacheDir.absolutePath}"
        }
        val kernel = cacheDir.resolve("Image")
        val initrd = cacheDir.resolve("initrd.img")
        val manifest = cacheDir.resolve("manifest.txt")
        val kernelCompressed = entry.optString("kernel_compression").isNotBlank()
        val cacheKey = buildString {
            append(disk.absolutePath).append('|').append(kernelUri).append('|')
            append(sourceMd5(lbx, disk, kernelUri, kernelCompressed)).append('|')
            for (index in 0 until initrdUris.length()) {
                val uri = initrdUris.getString(index)
                append(uri).append(':').append(sourceMd5(lbx, disk, uri, false)).append('|')
            }
        }

        if (!kernel.isFile || manifest.readTextOrNull() != cacheKey) {
            report("Preparing Debian kernel...")
            kernel.delete()
            initrd.delete()
            val kernelArgs = mutableListOf("cp", disk.absolutePath, kernelUri, kernel.absolutePath)
            if (kernelCompressed) kernelArgs += "--decompress"
            runLbx(lbx, *kernelArgs.toTypedArray())
            check(kernel.isFile && kernel.length() > 0L) { "Unable to prepare Debian kernel" }

            if (initrdUris.length() > 0) {
                initrd.outputStream().buffered().use { output ->
                    for (index in 0 until initrdUris.length()) {
                        val part = cacheDir.resolve("initrd.$index")
                        try {
                            runLbx(
                                lbx,
                                "cp",
                                disk.absolutePath,
                                initrdUris.getString(index),
                                part.absolutePath,
                            )
                            part.inputStream().buffered().use { it.copyTo(output) }
                        } finally {
                            part.delete()
                        }
                    }
                }
            }
            manifest.writeText(cacheKey)
        }

        val sourceCmdline = sequenceOf(entry)
            .plus((0 until entries.length()).asSequence().map { entries.getJSONObject(it) })
            .mapNotNull { candidate ->
                candidate.optString("cmdline_fixed")
                    .takeUnless { it.isBlank() || it == "null" }
                    ?: candidate.optString("cmdline").takeUnless { it.isBlank() || it == "null" }
            }
            .firstOrNull()
            ?: "root=/dev/vda1 ro"
        val storageParams = buildList {
            if (Settings.avfStorageAutoExpandEnabled) {
                add("reterminal.storage_expand=1")
            }
            if (Settings.avfStorageBalloonEnabled) {
                val availableBytes = (context.filesDir.usableSpace - HOST_STORAGE_RESERVE_BYTES)
                    .coerceAtLeast(0L)
                add("reterminal.storage_available_bytes=$availableBytes")
            }
        }
        val cmdline = sourceCmdline.split(Regex("\\s+"))
            .filterNot {
                it == "quiet" ||
                    it.startsWith("console=") ||
                    it.startsWith("hostname=") ||
                    it.startsWith("systemd.hostname=") ||
                    it.startsWith("cloud-init=") ||
                    it.startsWith("ds=") ||
                    it.startsWith("systemd.run=") ||
                    it.startsWith("systemd.run_success_action=") ||
                    it.startsWith("systemd.run_failure_action=") ||
                    it.startsWith("systemd.unit=") ||
                    it.startsWith("systemd.wants=") ||
                    it.startsWith("loglevel=") ||
                    it.startsWith("systemd.show_status=")
                    || it.startsWith("maxcpus=")
                    || it.startsWith("reterminal.storage_")
            }
            .plus(
                listOf(
                    "quiet",
                    "loglevel=3",
                    "systemd.show_status=false",
                    "rd.systemd.show_status=false",
                    "udev.log_level=3",
                    "vt.global_cursor_default=0",
                    "ds=nocloud",
                    "console=hvc0",
                    "maxcpus=${Settings.avfCpuCount.coerceIn(1, Runtime.getRuntime().availableProcessors())}",
                )
            )
            .plus(storageParams)
            .joinToString(" ")
        return BootFiles(
            kernel = kernel,
            initrd = initrd.takeIf { it.isFile && it.length() > 0L },
            cmdline = cmdline,
            title = entry.optString("title", "Debian GNU/Linux"),
        )
    }

    private const val HOST_STORAGE_RESERVE_BYTES = 1024L * 1024L * 1024L

    private fun defaultEntry(entries: JSONArray): JSONObject {
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            if (entry.optBoolean("default", false)) return entry
        }
        return entries.getJSONObject(0)
    }

    private fun sourceMd5(
        executable: File,
        disk: File,
        uri: String,
        decompress: Boolean,
    ): String {
        val arguments = mutableListOf("md5", disk.absolutePath, uri)
        if (decompress) arguments += "--decompress"
        return runLbx(executable, *arguments.toTypedArray()).trim().substringBefore(' ')
    }

    private fun runLbx(executable: File, vararg arguments: String): String {
        val process = ProcessBuilder(executable.absolutePath, *arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("Linux boot scan timed out")
        }
        check(process.exitValue() == 0) {
            output.trim().ifBlank { "Linux boot scan failed (${process.exitValue()})" }
        }
        return output
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()
}
