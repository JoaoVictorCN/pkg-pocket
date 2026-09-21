package com.pkgpocket.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class InstallerService : Service() {
    companion object {
        const val ACTION_REFRESH = "com.pkgpocket.REFRESH"
        const val ACTION_INSTALL_ALL = "com.pkgpocket.INSTALL_ALL"
        const val ACTION_STATUS = "com.pkgpocket.STATUS"
        const val EXTRA_PS4_IP = "ps4_ip"
        const val EXTRA_STATUS = "status"
        const val EXTRA_PERCENT = "percent"
        const val EXTRA_LOG_ONLY = "log_only"
        private const val CHANNEL = "pkg_transfer"
        private const val NOTIF_ID = 41
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var installJob: Job? = null
    private var server: PkgHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Transferência de PKG", NotificationManager.IMPORTANCE_LOW)
        )

        // O serviço só é iniciado quando o usuário manda instalar.
        // A barra indeterminada aparece apenas enquanto preparamos a transferência.
        startForeground(
            NOTIF_ID,
            transferNotification(
                title = "PKG Pocket",
                text = "Preparando envio para o PS4…",
                percent = null
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REFRESH -> restartServer()
            ACTION_INSTALL_ALL -> {
                if (server == null) restartServer()
                val ip = intent.getStringExtra(EXTRA_PS4_IP).orEmpty()
                if (ip.isNotBlank()) installAll(ip)
            }
        }
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PkgPocket:Transfer"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    private fun restartServer() {
        server?.stop()
        server = PkgHttpServer(
            resolver = contentResolver,
            port = 8080,
            itemsProvider = { PkgRepository.items },
            onLog = ::logOnly
        ).also {
            runCatching { it.start() }
                .onFailure { e -> logOnly("Erro no servidor: ${e.message}") }
        }
    }

    private fun installAll(ps4Ip: String) {
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            acquireWakeLock()

            val localIp = NetworkUtils.localIpv4()
            if (localIp == null) {
                finishError("Celular sem IPv4 local")
                return@launch
            }

            if (!NetworkUtils.canConnect(ps4Ip)) {
                finishError("RPI não encontrado em $ps4Ip:12800")
                return@launch
            }

            val ordered = PkgRepository.items.sortedWith(
                compareBy<PkgItem>({ it.titleId }, { it.kind.order }, { it.fileName })
            )

            if (ordered.isEmpty()) {
                finishError("Nenhum PKG selecionado")
                return@launch
            }

            val queueStartedAt = android.os.SystemClock.elapsedRealtime()
            val totalSize = ordered.sumOf { it.size.coerceAtLeast(0L) }

            ordered.forEachIndexed { index, item ->
                val prefix = "${index + 1}/${ordered.size}"
                publishProgress(
                    title = item.title,
                    text = "$prefix • Preparando ${item.kind.label.lowercase()}…",
                    percent = null
                )

                try {
                    val activeServer = server ?: error("Servidor HTTP não iniciado")
                    val url = activeServer.urlFor(localIp, item)

                    logOnly("Enviando URL ao RPI: $url")
                    val result = RpiClient.install(ps4Ip, url)
                    logOnly("RPI respondeu: ${result.raw}")

                    val task = result.taskId
                    if (task == null) {
                        logOnly("RPI aceitou ${item.title}, mas não retornou task_id")
                        delay(2000)
                    } else {
                        var done = false
                        while (!done) {
                            delay(2000)
                            val p = RpiClient.progress(ps4Ip, task)
                            val pc = RpiClient.percent(p)

                            publishProgress(
                                title = item.title,
                                text = "$prefix • ${item.kind.label} • $pc%",
                                percent = pc
                            )
                            done = RpiClient.isFinished(p)
                        }
                    }
                } catch (e: Exception) {
                    finishError("Falha em ${item.title}: ${e.message ?: "erro desconhecido"}")
                    return@launch
                }
            }

            val elapsed = android.os.SystemClock.elapsedRealtime() - queueStartedAt
            val summary = if (ordered.size == 1) {
                "Instalação concluída • ${humanSize(totalSize)} • ${humanDuration(elapsed)}"
            } else {
                "Fila concluída • ${ordered.size} PKGs • ${humanSize(totalSize)} • ${humanDuration(elapsed)}"
            }

            finishSuccess(summary)
        }
    }

    private fun publishProgress(title: String, text: String, percent: Int?) {
        val shownPercent = percent ?: 0

        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            transferNotification(title, text, percent)
        )

        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, text)
                .putExtra(EXTRA_PERCENT, shownPercent)
                .putExtra(EXTRA_LOG_ONLY, false)
        )
    }

    private fun logOnly(text: String) {
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, text)
                .putExtra(EXTRA_LOG_ONLY, true)
        )
    }

    private fun finishSuccess(summary: String) {
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, summary)
                .putExtra(EXTRA_PERCENT, 100)
                .putExtra(EXTRA_LOG_ONLY, false)
        )

        stopForeground(STOP_FOREGROUND_REMOVE)

        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            finalNotification(
                title = "Instalação concluída",
                text = summary,
                success = true
            )
        )

        releaseWakeLock()
        stopSelf()
    }

    private fun finishError(message: String) {
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, message)
                .putExtra(EXTRA_PERCENT, 0)
                .putExtra(EXTRA_LOG_ONLY, false)
        )

        stopForeground(STOP_FOREGROUND_REMOVE)

        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            finalNotification(
                title = "Falha na instalação",
                text = message,
                success = false
            )
        )

        releaseWakeLock()
        stopSelf()
    }

    private fun transferNotification(
        title: String,
        text: String,
        percent: Int?
    ): android.app.Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (percent == null) {
                    setProgress(100, 0, true)
                } else {
                    setProgress(100, percent.coerceIn(0, 100), false)
                }
            }
            .build()
    }

    private fun finalNotification(
        title: String,
        text: String,
        success: Boolean
    ): android.app.Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(
                if (success) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, false)
            .build()
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        do {
            value /= 1024.0
            unit++
        } while (value >= 1024.0 && unit < units.lastIndex)
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    private fun humanDuration(ms: Long): String {
        val totalSeconds = (ms / 1000.0).coerceAtLeast(0.0)
        if (totalSeconds < 60.0) {
            return String.format(Locale.US, "%.1f s", totalSeconds)
        }
        val minutes = (totalSeconds / 60).toInt()
        val seconds = (totalSeconds % 60).toInt()
        return "${minutes}m ${seconds}s"
    }

    override fun onDestroy() {
        server?.stop()
        installJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
