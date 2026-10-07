package com.pkgpocket.app

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object AnalyticsTracker {
    private const val MIB = 1024L * 1024L

    fun setUserType(context: Context, isPro: Boolean) {
        runCatching {
            FirebaseAnalytics
                .getInstance(context.applicationContext)
                .setUserProperty(
                    "user_type",
                    if (isPro) "pro" else "free"
                )
        }
    }

    fun pkgSelected(
        context: Context,
        items: List<PkgItem>
    ) {
        if (items.isEmpty()) return

        val params = Bundle().apply {
            putQueueStats(items)
        }

        log(context, "pkg_selected", params)
    }

    fun ps4Detected(
        context: Context,
        rpiPort: Int
    ) {
        val params = Bundle().apply {
            putLong("rpi_port", rpiPort.toLong())
        }

        log(context, "ps4_detected", params)
    }

    fun installStarted(
        context: Context,
        items: List<PkgItem>
    ) {
        if (items.isEmpty()) return

        val params = Bundle().apply {
            putQueueStats(items)
        }

        log(context, "install_started", params)
    }

    fun installCompleted(
        context: Context,
        pkgCount: Int,
        totalBytes: Long,
        durationMs: Long
    ) {
        val params = Bundle().apply {
            putLong("pkg_count", pkgCount.toLong())
            putLong("total_mb", toMiB(totalBytes))
            putLong("duration_sec", (durationMs.coerceAtLeast(0L) / 1000L))
        }

        log(context, "install_completed", params)
    }

    fun installFailed(
        context: Context,
        reason: String,
        pkgCount: Int,
        totalBytes: Long,
        durationMs: Long,
        progressPercent: Int
    ) {
        val params = Bundle().apply {
            putString("failure_reason", reason.take(100))
            putLong("pkg_count", pkgCount.toLong())
            putLong("total_mb", toMiB(totalBytes))
            putLong("duration_sec", (durationMs.coerceAtLeast(0L) / 1000L))
            putLong(
                "progress_percent",
                progressPercent.coerceIn(0, 100).toLong()
            )
        }

        log(context, "install_failed", params)
    }

    fun installCancelled(
        context: Context,
        pkgCount: Int,
        totalBytes: Long,
        durationMs: Long,
        progressPercent: Int
    ) {
        val params = Bundle().apply {
            putLong("pkg_count", pkgCount.toLong())
            putLong("total_mb", toMiB(totalBytes))
            putLong("duration_sec", (durationMs.coerceAtLeast(0L) / 1000L))
            putLong(
                "progress_percent",
                progressPercent.coerceIn(0, 100).toLong()
            )
        }

        log(context, "install_cancelled", params)
    }

    fun failureReason(error: Throwable): String {
        return when (error) {
            is RpiClient.HttpException ->
                "rpi_http_${error.statusCode}"

            is SocketTimeoutException ->
                "rpi_timeout"

            is ConnectException ->
                "rpi_connect"

            is UnknownHostException ->
                "rpi_host"

            else ->
                error.javaClass.simpleName
                    .lowercase()
                    .replace(Regex("[^a-z0-9_]+"), "_")
                    .trim('_')
                    .take(100)
                    .ifBlank { "unknown" }
        }
    }

    private fun Bundle.putQueueStats(items: List<PkgItem>) {
        putLong("pkg_count", items.size.toLong())
        putLong(
            "total_mb",
            toMiB(
                items.sumOf {
                    it.size.coerceAtLeast(0L)
                }
            )
        )
        putLong(
            "game_count",
            items.count { it.kind == PkgKind.GAME }.toLong()
        )
        putLong(
            "update_count",
            items.count { it.kind == PkgKind.UPDATE }.toLong()
        )
        putLong(
            "dlc_count",
            items.count { it.kind == PkgKind.DLC }.toLong()
        )
        putLong(
            "other_count",
            items.count { it.kind == PkgKind.OTHER }.toLong()
        )
    }

    private fun log(
        context: Context,
        eventName: String,
        params: Bundle
    ) {
        runCatching {
            val appContext = context.applicationContext
            val isPro = ProManager.isProCached(appContext)

            params.putString(
                "user_type",
                if (isPro) "pro" else "free"
            )

            setUserType(appContext, isPro)

            FirebaseAnalytics
                .getInstance(appContext)
                .logEvent(eventName, params)
        }
    }

    private fun toMiB(bytes: Long): Long {
        return bytes
            .coerceAtLeast(0L)
            .div(MIB)
    }
}
