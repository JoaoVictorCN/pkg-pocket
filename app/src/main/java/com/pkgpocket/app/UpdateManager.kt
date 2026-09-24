package com.pkgpocket.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

object UpdateManager {
    private const val RELEASES_API =
        "https://api.github.com/repos/JoaoVictorCN/pkg-pocket/releases?per_page=20"

    private const val PREFS = "pkg_pocket_update"
    private const val KEY_PENDING_APK = "pending_apk"

    data class Release(
        val version: String,
        val apkUrl: String,
        val digest: String?
    )

    suspend fun findAvailableUpdate(
        activity: Activity
    ): Release? = withContext(Dispatchers.IO) {
        val connection = open(RELEASES_API)

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException(
                    activity.getString(
                        R.string.update_bad_response,
                        code
                    )
                )
            }

            val body = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            val releases = JSONArray(body)
            val allowPrerelease =
                BuildConfig.VERSION_NAME.contains("-")
            val candidates = mutableListOf<Release>()

            for (index in 0 until releases.length()) {
                val release = releases.getJSONObject(index)

                if (release.optBoolean("draft", false)) continue
                if (
                    release.optBoolean("prerelease", false) &&
                    !allowPrerelease
                ) {
                    continue
                }

                val version = release
                    .optString("tag_name")
                    .removePrefix("v")
                    .trim()

                if (version.isBlank()) continue

                val assets = release.optJSONArray("assets")
                    ?: continue

                var apkUrl: String? = null
                var digest: String? = null

                for (assetIndex in 0 until assets.length()) {
                    val asset = assets.getJSONObject(assetIndex)
                    val name = asset
                        .optString("name")
                        .lowercase(Locale.ROOT)

                    if (
                        name.endsWith(".apk") &&
                        !name.contains("debug")
                    ) {
                        apkUrl =
                            asset.optString("browser_download_url")
                        digest = asset
                            .optString("digest")
                            .takeIf { it.isNotBlank() }
                        break
                    }
                }

                if (apkUrl.isNullOrBlank()) continue

                candidates += Release(
                    version = version,
                    apkUrl = apkUrl,
                    digest = digest
                )
            }

            candidates
                .filter {
                    compareVersions(
                        it.version,
                        BuildConfig.VERSION_NAME
                    ) > 0
                }
                .maxWithOrNull { a, b ->
                    compareVersions(a.version, b.version)
                }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(
        activity: Activity,
        release: Release,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val base = activity.externalCacheDir
            ?: activity.cacheDir

        val dir = File(base, "updates")

        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException(
                "Could not create update cache directory"
            )
        }

        dir.listFiles()?.forEach {
            if (it.name.endsWith(".apk")) {
                runCatching { it.delete() }
            }
        }

        val target = File(
            dir,
            "PKG-Pocket-${sanitize(release.version)}.apk"
        )

        val connection = open(release.apkUrl)

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException(
                    activity.getString(
                        R.string.update_bad_response,
                        code
                    )
                )
            }

            val total = connection.contentLengthLong
            var readTotal = 0L
            var lastPercent = -1

            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(128 * 1024)

                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break

                        output.write(buffer, 0, count)
                        readTotal += count

                        val percent =
                            if (total > 0L) {
                                (
                                    readTotal * 100L / total
                                    ).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }

                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }

                    output.flush()
                }
            }
        } catch (e: Exception) {
            runCatching { target.delete() }
            throw e
        } finally {
            connection.disconnect()
        }

        if (!target.exists() || target.length() <= 0L) {
            throw IllegalStateException(
                activity.getString(
                    R.string.update_download_failed,
                    "empty APK"
                )
            )
        }

        verifyDigest(
            target,
            release.digest,
            activity
        )

        onProgress(100)
        target
    }

    fun requestInstall(
        activity: Activity,
        apk: File
    ) {
        if (!apk.exists()) return

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            activity.getSharedPreferences(
                PREFS,
                Activity.MODE_PRIVATE
            )
                .edit()
                .putString(KEY_PENDING_APK, apk.absolutePath)
                .apply()

            android.widget.Toast.makeText(
                activity,
                R.string.update_permission_needed,
                android.widget.Toast.LENGTH_LONG
            ).show()

            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )

            return
        }

        launchInstaller(activity, apk)
    }

    fun resumePendingInstall(
        activity: Activity
    ): Boolean {
        val prefs = activity.getSharedPreferences(
            PREFS,
            Activity.MODE_PRIVATE
        )

        val path = prefs
            .getString(KEY_PENDING_APK, "")
            .orEmpty()

        if (path.isBlank()) return false

        val apk = File(path)

        if (!apk.exists()) {
            prefs.edit().remove(KEY_PENDING_APK).apply()
            return false
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            return false
        }

        prefs.edit().remove(KEY_PENDING_APK).apply()
        launchInstaller(activity, apk)
        return true
    }

    private fun launchInstaller(
        activity: Activity,
        apk: File
    ) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apk
        )

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(
                uri,
                "application/vnd.android.package-archive"
            )
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        activity.startActivity(intent)
    }

    private fun open(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty(
                "User-Agent",
                "PKG-Pocket/${BuildConfig.VERSION_NAME}"
            )
            setRequestProperty(
                "Accept",
                "application/vnd.github+json, application/octet-stream"
            )
        }
    }

    private fun verifyDigest(
        apk: File,
        digestValue: String?,
        activity: Activity
    ) {
        val expected = digestValue
            ?.trim()
            ?.takeIf {
                it.startsWith(
                    "sha256:",
                    ignoreCase = true
                )
            }
            ?.substringAfter(":")
            ?.lowercase(Locale.ROOT)
            ?: return

        val digest = MessageDigest.getInstance("SHA-256")

        apk.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)

            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }

        val actual = digest.digest()
            .joinToString("") { "%02x".format(it) }

        if (!actual.equals(expected, ignoreCase = true)) {
            runCatching { apk.delete() }

            throw IllegalStateException(
                activity.getString(
                    R.string.update_bad_checksum
                )
            )
        }
    }

    private fun sanitize(value: String): String {
        return value.replace(
            Regex("[^A-Za-z0-9._-]"),
            "_"
        )
    }

    private data class ParsedVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val qualifierRank: Int,
        val qualifierNumber: Int
    )

    private fun parseVersion(value: String): ParsedVersion {
        val normalized = value
            .trim()
            .removePrefix("v")
            .lowercase(Locale.ROOT)

        val match = Regex(
            "^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([a-z]+)[.-]?(\\d+)?)?.*$"
        ).find(normalized)

        if (match == null) {
            return ParsedVersion(0, 0, 0, 0, 0)
        }

        val qualifier = match.groupValues[4]
        val qualifierNumber =
            match.groupValues[5].toIntOrNull() ?: 0

        val rank = when (qualifier) {
            "" -> 4
            "rc" -> 3
            "beta", "b" -> 2
            "alpha", "a" -> 1
            else -> 0
        }

        return ParsedVersion(
            major = match.groupValues[1].toInt(),
            minor = match.groupValues[2].toInt(),
            patch = match.groupValues[3].toInt(),
            qualifierRank = rank,
            qualifierNumber = qualifierNumber
        )
    }

    private fun compareVersions(
        left: String,
        right: String
    ): Int {
        val a = parseVersion(left)
        val b = parseVersion(right)

        val comparisons = listOf(
            a.major to b.major,
            a.minor to b.minor,
            a.patch to b.patch,
            a.qualifierRank to b.qualifierRank,
            a.qualifierNumber to b.qualifierNumber
        )

        comparisons.forEach { (x, y) ->
            if (x != y) return x.compareTo(y)
        }

        return 0
    }
}
