package com.jbd.bmsmonitor.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.jbd.bmsmonitor.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

data class UpdateRelease(
    val versionName: String,
    val downloadUrl: String,
    val releasePageUrl: String,
)

sealed interface UpdateCheckResult {
    data class Available(val release: UpdateRelease) : UpdateCheckResult
    data class UpToDate(val latestVersion: String) : UpdateCheckResult
    data class Failed(val message: String) : UpdateCheckResult
}

sealed interface UpdateDownloadResult {
    data class Ready(val file: File) : UpdateDownloadResult
    data class Failed(val message: String) : UpdateDownloadResult
}

class AppUpdateManager(private val context: Context) {
    fun checkForUpdate(): UpdateCheckResult {
        val connection = openConnection(BuildConfig.UPDATE_MANIFEST_URL)
            ?: return UpdateCheckResult.Failed("The update service could not be reached.")
        return try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> parseRelease(connection.inputStream.bufferedReader().use { it.readText() })
                HttpURLConnection.HTTP_NOT_FOUND -> UpdateCheckResult.Failed("No published update was found.")
                HTTP_RATE_LIMITED -> UpdateCheckResult.Failed("The update-check limit was reached. Try again later.")
                else -> UpdateCheckResult.Failed("The update service returned HTTP ${connection.responseCode}.")
            }
        } catch (_: Exception) {
            UpdateCheckResult.Failed("The update check failed. Check your internet connection.")
        } finally {
            connection.disconnect()
        }
    }

    fun downloadAndValidate(release: UpdateRelease): UpdateDownloadResult {
        val updateDirectory = File(context.cacheDir, UPDATE_DIRECTORY).apply { mkdirs() }
        val temporaryFile = File(updateDirectory, "update.tmp")
        val apkFile = File(updateDirectory, "update.apk")
        temporaryFile.delete()
        apkFile.delete()

        return try {
            val connection = openDownloadConnection(release.downloadUrl)
                ?: return UpdateDownloadResult.Failed("The APK download could not be started.")
            try {
                if (connection.responseCode !in 200..299) {
                    return UpdateDownloadResult.Failed("The APK download returned HTTP ${connection.responseCode}.")
                }
                if (connection.contentLengthLong > MAX_APK_BYTES) {
                    return UpdateDownloadResult.Failed("The APK is unexpectedly large.")
                }

                var totalBytes = 0L
                connection.inputStream.use { input ->
                    temporaryFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            totalBytes += count
                            if (totalBytes > MAX_APK_BYTES) {
                                throw IllegalStateException("APK exceeds size limit")
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }

            validateApk(temporaryFile)?.let {
                temporaryFile.delete()
                return UpdateDownloadResult.Failed(it)
            }
            if (!temporaryFile.renameTo(apkFile)) {
                temporaryFile.copyTo(apkFile, overwrite = true)
                temporaryFile.delete()
            }
            UpdateDownloadResult.Ready(apkFile)
        } catch (_: Exception) {
            temporaryFile.delete()
            UpdateDownloadResult.Failed("The APK could not be downloaded or verified.")
        }
    }

    fun createInstallIntent(apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apkFile)
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            clipData = ClipData.newRawUri("Battery Monitor update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun parseRelease(responseBody: String): UpdateCheckResult {
        val json = JSONObject(responseBody)
        val version = json.optString("versionName")
            .ifBlank { json.optString("tag_name") }
            .trim()
            .removePrefix("v")
            .removePrefix("V")
        val directDownloadUrl = json.optString("apkUrl")
        val downloadUrl = directDownloadUrl.ifBlank {
            val assets = json.optJSONArray("assets")
                ?: return UpdateCheckResult.Failed("The latest update does not contain an APK.")
            (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?.optString("browser_download_url")
                .orEmpty()
        }
        val releasePageUrl = json.optString("releasePageUrl")
            .ifBlank { json.optString("html_url") }
            .ifBlank { downloadUrl }
        if (version.isBlank() || !isHttpsUrl(downloadUrl) || !isHttpsUrl(releasePageUrl)) {
            return UpdateCheckResult.Failed("The latest release has invalid update information.")
        }
        return if (isNewerVersion(version, BuildConfig.VERSION_NAME)) {
            UpdateCheckResult.Available(UpdateRelease(version, downloadUrl, releasePageUrl))
        } else {
            UpdateCheckResult.UpToDate(version)
        }
    }

    private fun validateApk(file: File): String? {
        if (!file.isFile || file.length() == 0L) return "The downloaded APK is empty."
        val archive = packageInfo(file) ?: return "The downloaded file is not a valid APK."
        if (archive.packageName != context.packageName) return "The downloaded APK is for a different app."

        val installed = packageInfo(context.packageName)
            ?: return "The installed app identity could not be verified."
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) {
            return "The downloaded APK is not newer than the installed app."
        }
        val archiveSigners = signers(archive)
        val installedSigners = signers(installed)
        if (archiveSigners.isEmpty() || installedSigners.isEmpty() || archiveSigners != installedSigners) {
            return "The downloaded APK is not signed with this app's signing key."
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(file: File): PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        context.packageManager.getPackageArchiveInfo(file.absolutePath, packageInfoFlags())
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(packageName: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            context.packageManager.getPackageInfo(packageName, packageInfoFlags())
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun packageInfoFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }

    @Suppress("DEPRECATION")
    private fun signers(packageInfo: PackageInfo): Set<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.signingInfo?.apkContentsSigners.orEmpty().mapTo(mutableSetOf()) { it.toCharsString() }
    } else {
        packageInfo.signatures.orEmpty().mapTo(mutableSetOf()) { it.toCharsString() }
    }

    private fun openDownloadConnection(initialUrl: String): HttpURLConnection? {
        var url = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = openConnection(url) ?: return null
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            if (status !in REDIRECT_STATUS_CODES) return connection
            if (redirectCount == MAX_REDIRECTS) {
                connection.disconnect()
                return null
            }
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            url = runCatching { URI(url).resolve(location).toString() }.getOrNull()
                ?.takeIf(::isHttpsUrl)
                ?: return null
        }
        return null
    }

    private fun openConnection(url: String): HttpURLConnection? {
        if (!isHttpsUrl(url)) return null
        return (runCatching { URI(url).toURL().openConnection() }.getOrNull() as? HttpURLConnection)?.apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Battery-Monitor/${BuildConfig.VERSION_NAME}")
        }
    }

    private fun isHttpsUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private companion object {
        const val UPDATE_DIRECTORY = "updates"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
        const val HTTP_RATE_LIMITED = 403
        const val MAX_REDIRECTS = 5
        const val MAX_APK_BYTES = 200L * 1024L * 1024L
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }
}

internal fun isNewerVersion(candidate: String, current: String): Boolean {
    fun parts(value: String): List<Int> {
        return value
            .substringBefore('-')
            .trim()
            .removePrefix("v")
            .removePrefix("V")
            .split('.')
            .map { it.toIntOrNull() ?: return emptyList() }
    }

    val candidateParts = parts(candidate)
    val currentParts = parts(current)
    if (candidateParts.isEmpty() || currentParts.isEmpty()) return false
    val count = maxOf(candidateParts.size, currentParts.size)
    for (index in 0 until count) {
        val candidatePart = candidateParts.getOrElse(index) { 0 }
        val currentPart = currentParts.getOrElse(index) { 0 }
        if (candidatePart != currentPart) return candidatePart > currentPart
    }
    return false
}
