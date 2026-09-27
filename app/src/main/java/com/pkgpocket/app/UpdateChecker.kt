package com.pkgpocket.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val PREFS = "pkg_pocket_updates"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_NOTIFIED_VERSION = "notified_version"

    private const val CHECK_INTERVAL_MS =
        6L * 60L * 60L * 1000L

    private const val CHANNEL_ID =
        "pkg_pocket_updates"

    private const val NOTIFICATION_ID = 1042

    private const val RELEASE_API =
        "https://api.github.com/repos/JoaoVictorCN/pkg-pocket/releases/latest"

    private val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )

    fun checkIfDue(context: Context) {
        val appContext = context.applicationContext

        val prefs =
            appContext.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

        val now = System.currentTimeMillis()

        val last =
            prefs.getLong(KEY_LAST_CHECK, 0L)

        if (now - last < CHECK_INTERVAL_MS) {
            return
        }

        /*
         * Marcamos antes da rede para impedir duas Activities
         * de dispararem a mesma consulta simultaneamente.
         */
        prefs.edit()
            .putLong(KEY_LAST_CHECK, now)
            .apply()

        scope.launch {
            runCatching {
                checkNow(appContext)
            }
        }
    }

    private fun checkNow(context: Context) {
        val connection =
            URL(RELEASE_API).openConnection()
                as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 7_000
            connection.setRequestProperty(
                "Accept",
                "application/vnd.github+json"
            )
            connection.setRequestProperty(
                "User-Agent",
                "PKG-Pocket/${BuildConfig.VERSION_NAME}"
            )

            if (connection.responseCode !in 200..299) {
                return
            }

            val body =
                connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

            val json = JSONObject(body)

            if (json.optBoolean("draft", false)) {
                return
            }

            val tag =
                json.optString("tag_name")
                    .trim()
                    .removePrefix("v")

            if (tag.isBlank()) {
                return
            }

            /*
             * RCs não devem avisar sobre outra RC.
             * Só queremos usar isso para chamar usuários
             * para a versão estável.
             */
            if (tag.contains("-", ignoreCase = true)) {
                return
            }

            if (!isRemoteNewer(
                    tag,
                    BuildConfig.VERSION_NAME
                )
            ) {
                return
            }

            val assets = json.optJSONArray("assets")

            var apkUrl: String? = null

            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset =
                        assets.optJSONObject(i)
                            ?: continue

                    val name =
                        asset.optString("name")
                            .lowercase()

                    if (name.endsWith(".apk")) {
                        apkUrl =
                            asset.optString(
                                "browser_download_url"
                            ).takeIf {
                                it.startsWith("https://")
                            }

                        if (apkUrl != null) break
                    }
                }
            }

            /*
             * Se a Release não tiver APK anexado,
             * não mostramos notificação quebrada.
             */
            if (apkUrl.isNullOrBlank()) {
                return
            }

            val prefs =
                context.getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                )

            if (
                prefs.getString(
                    KEY_NOTIFIED_VERSION,
                    ""
                ) == tag
            ) {
                return
            }

            showNotification(
                context,
                tag,
                apkUrl
            )

            prefs.edit()
                .putString(
                    KEY_NOTIFIED_VERSION,
                    tag
                )
                .apply()

        } finally {
            connection.disconnect()
        }
    }

    private fun showNotification(
        context: Context,
        version: String,
        apkUrl: String
    ) {
        val nm =
            context.getSystemService(
                NotificationManager::class.java
            )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(
                    R.string.update_channel_name
                ),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description =
                    context.getString(
                        R.string.update_channel_description
                    )
            }
        )

        val intent =
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(apkUrl)
            )

        val pi =
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val notification =
            NotificationCompat.Builder(
                context,
                CHANNEL_ID
            )
                .setSmallIcon(
                    android.R.drawable.stat_sys_download_done
                )
                .setContentTitle(
                    context.getString(
                        R.string.update_available_title
                    )
                )
                .setContentText(
                    context.getString(
                        R.string.update_available_text,
                        version
                    )
                )
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(
                            context.getString(
                                R.string.update_available_text,
                                version
                            )
                        )
                )
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()

        nm.notify(
            NOTIFICATION_ID,
            notification
        )
    }

    private fun isRemoteNewer(
        remote: String,
        local: String
    ): Boolean {
        val remoteParts =
            numericVersion(remote)

        val localParts =
            numericVersion(local)

        val count =
            maxOf(
                remoteParts.size,
                localParts.size
            )

        for (i in 0 until count) {
            val r =
                remoteParts.getOrElse(i) { 0 }

            val l =
                localParts.getOrElse(i) { 0 }

            if (r > l) return true
            if (r < l) return false
        }

        return false
    }

    private fun numericVersion(
        value: String
    ): List<Int> {
        return value
            .substringBefore("-")
            .split(".")
            .map {
                it.toIntOrNull() ?: 0
            }
    }
}
