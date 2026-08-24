package com.yoro1836.terminal.backend.avf

import android.content.Context
import android.os.Build
import android.os.StatFs
import com.yoro1836.settings.Settings
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal object AvfImageInstaller {

    private const val DEBIAN_IMAGE_NAME = "debian-13-genericcloud-arm64.raw"
    private const val DEBIAN_IMAGE_URL =
        "https://cloud.debian.org/images/cloud/trixie/20260819-2575/debian-13-genericcloud-arm64-20260819-2575.raw"
    private const val DEBIAN_IMAGE_SHA512 =
        "ea00a768fc6201a4320b391e0bf1038e67c6d0800a074e9b6666b9329f1e6e1db8b05fc3421eeb05a38610ec64576bcf8e6a52dc2541ff23fb7d1342703cd169"

    data class Images(val disk: File)
    data class InstallationInfo(val installed: Boolean, val diskBytes: Long)

    fun installationInfo(context: Context): InstallationInfo {
        val imageDir = File(context.filesDir, "avf/debian")
        val disk = imageDir.resolve(DEBIAN_IMAGE_NAME)
        val diskReady = disk.isFile &&
            disk.resolveSibling("${disk.name}.SHA-512").readDigest() == DEBIAN_IMAGE_SHA512
        return InstallationInfo(
            installed = diskReady,
            diskBytes = if (disk.isFile) disk.length() else 0L,
        )
    }

    fun ensureInstalled(context: Context, report: (String) -> Unit): Images {
        check(Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            "The Debian AVF image requires an ARM64 device"
        }

        val imageDir = File(context.filesDir, "avf/debian")
        check(imageDir.exists() || imageDir.mkdirs()) {
            "Unable to create AVF image directory: ${imageDir.absolutePath}"
        }
        imageDir.resolve("debian-13-nocloud-arm64.qcow2").delete()
        imageDir.resolve("debian-13-nocloud-arm64.qcow2.SHA-512").delete()
        imageDir.resolve("debian-13-nocloud-arm64.raw").delete()
        imageDir.resolve("debian-13-nocloud-arm64.raw.SHA-512").delete()

        val disk = imageDir.resolve(DEBIAN_IMAGE_NAME)
        ensureDownload(
            target = disk,
            url = DEBIAN_IMAGE_URL,
            algorithm = "SHA-512",
            expectedDigest = DEBIAN_IMAGE_SHA512,
            label = "Debian 13 image",
            report = report,
        )
        expandSparseDisk(context, disk)
        return Images(disk = disk)
    }


    private fun expandSparseDisk(context: Context, disk: File) {
        if (!Settings.avfStorageAutoExpandEnabled) return
        val hostBytes = StatFs(context.filesDir.absolutePath).totalBytes
        val desiredBytes = hostBytes * 95L / 100L
        if (desiredBytes > disk.length()) {
            RandomAccessFile(disk, "rw").use { it.setLength(desiredBytes) }
        }
    }

    private fun ensureDownload(
        target: File,
        url: String,
        algorithm: String,
        expectedDigest: String,
        label: String,
        report: (String) -> Unit,
    ) {
        val marker = target.resolveSibling("${target.name}.$algorithm")
        if (target.isFile && target.length() > 0L && marker.readDigest() == expectedDigest) return

        val part = target.resolveSibling("${target.name}.part")
        part.delete()
        report("Downloading $label...")

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", "ReTerminal AVF image installer")
        }

        try {
            check(connection.responseCode in 200..299) {
                "$label download failed: HTTP ${connection.responseCode}"
            }
            val encodedLength = connection.contentLengthLong
            if (encodedLength > 0L) {
                val required = encodedLength + 64L * 1024L * 1024L
                check(target.parentFile?.usableSpace ?: 0L >= required) {
                    "Not enough free space for $label"
                }
            }

            val digest = MessageDigest.getInstance(algorithm)
            connection.inputStream.use { networkInput ->
                val input = CountingInputStream(networkInput)
                part.outputStream().buffered().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var lastPercent = -1
                    while (true) {
                        if (Thread.currentThread().isInterrupted) {
                            throw IOException("$label download cancelled")
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        if (encodedLength > 0L) {
                            val percent = (input.count * 100L / encodedLength).toInt()
                            if (percent > lastPercent) {
                                lastPercent = percent
                                report("Downloading $label: ${percent.coerceAtMost(100)}%")
                            }
                        }
                    }
                }
            }

            val actualDigest = digest.digest().toHex()
            check(actualDigest == expectedDigest) {
                "$label checksum mismatch: got $actualDigest (${part.length()} bytes)"
            }
            check(part.length() > 0L) { "$label download was empty" }
            if (target.exists()) check(target.delete()) { "Unable to replace ${target.name}" }
            check(part.renameTo(target)) { "Unable to install ${target.name}" }
            marker.writeText("$expectedDigest\n")
            report("Installed $label")
        } finally {
            connection.disconnect()
            part.delete()
        }
    }

    private fun File.readDigest(): String? =
        runCatching { takeIf(File::isFile)?.readText()?.trim() }.getOrNull()


    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count: Long = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) count += it }
    }
}
