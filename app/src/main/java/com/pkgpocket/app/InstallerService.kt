package com.pkgpocket.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class InstallerService : Service() {
    companion object {
        const val ACTION_INSTALL_ALL = "com.pkgpocket.INSTALL_ALL"
        const val ACTION_CANCEL = "com.pkgpocket.CANCEL"
        const val ACTION_STATUS = "com.pkgpocket.STATUS"

        const val EXTRA_PS4_IP = "ps4_ip"
        const val EXTRA_STATUS = "status"
        const val EXTRA_PERCENT = "percent"
        const val EXTRA_LOG_ONLY = "log_only"
        const val EXTRA_LIVE_UPDATE = "live_update"
        const val EXTRA_ACTIVE = "active"

        private const val CHANNEL = "pkg_transfer"
        private const val NOTIF_ID = 41
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var installJob: Job? = null
    private var server: PkgHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var currentTaskId: Int? = null
    @Volatile private var currentPs4Ip: String? = null
    @Volatile private var cancelRequested = false

    override fun onCreate() {
        super.onCreate()

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Transferência de PKG", NotificationManager.IMPORTANCE_LOW)
        )

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
            ACTION_INSTALL_ALL -> {
                val ip = intent.getStringExtra(EXTRA_PS4_IP).orEmpty()
                if (ip.isNotBlank()) installAll(ip)
            }

            ACTION_CANCEL -> cancelInstall()
        }

        return START_NOT_STICKY
    }

    private fun acquirePerformanceLocks() {
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "PkgPocket:Transfer"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        if (wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "PkgPocket:HighPerfWifi"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releasePerformanceLocks() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
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

        cancelRequested = false
        currentPs4Ip = ps4Ip
        restartServer()

        installJob = scope.launch {
            acquirePerformanceLocks()

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

            try {
                ordered.forEachIndexed { index, item ->
                    if (cancelRequested) return@launch

                    val prefix = "${index + 1}/${ordered.size}"

                    publishProgress(
                        title = item.title,
                        text = "$prefix • Preparando ${item.kind.label.lowercase()}…",
                        percent = null
                    )

                    val activeServer = server ?: error("Servidor HTTP não iniciado")
                    val url = activeServer.urlFor(localIp, item)

                    logOnly("Solicitação enviada ao RPI: ${item.kind.label} • ${item.title}")

                    val installAttempt = runCatching { RpiClient.install(ps4Ip, url) }
                    var task = installAttempt.getOrNull()?.taskId

                    if (task == null) {
                        val originalError = installAttempt.exceptionOrNull()
                        if (originalError != null) {
                            logOnly(
                                "A resposta de /api/install se perdeu; verificando se o PS4 criou a task…"
                            )
                        } else {
                            logOnly(
                                "RPI não retornou task_id; verificando a task pelo Content ID…"
                            )
                        }

                        val subType = rpiSubType(item)
                        if (subType != null && item.contentId.isNotBlank()) {
                            repeat(4) { attempt ->
                                if (task != null || cancelRequested) return@repeat
                                delay(if (attempt == 0) 1200 else 1800)

                                task = runCatching {
                                    RpiClient.findTask(ps4Ip, item.contentId, subType)
                                }.getOrNull()
                            }
                        }

                        if (task == null) {
                            if (originalError != null) throw originalError
                            error("RPI não retornou task_id e nenhuma task foi encontrada")
                        }

                        logOnly("Task $task recuperada pelo Content ID")
                    } else {
                        logOnly("Task $task iniciada para ${item.title}")
                    }

                    currentTaskId = task

                    var done = false
                    var statusFailures = 0
                    var pollDelayMs = 5000L

                    while (!done && !cancelRequested) {
                        delay(pollDelayMs)

                        val progress = runCatching { RpiClient.progress(ps4Ip, task!!) }
                            .onFailure {
                                statusFailures++
                                publishLive(
                                    title = item.title,
                                    text = "$prefix • Envio continua • aguardando status do PS4…",
                                    percent = null
                                )
                                pollDelayMs = (pollDelayMs + 5000L).coerceAtMost(20_000L)
                                if (statusFailures == 1 || statusFailures % 6 == 0) {
                                    logOnly(
                                        "Status do RPI oscilou (${statusFailures}x); " +
                                            "o servidor continua enviando o PKG."
                                    )
                                }
                            }
                            .getOrNull()
                            ?: continue

                        statusFailures = 0
                        pollDelayMs = 5000L

                        val pc = RpiClient.percent(progress)
                        val transferred = RpiClient.bytesDone(progress)
                        val total = RpiClient.bytesTotal(progress).takeIf { it > 0L } ?: item.size

                        val amountText = if (transferred > 0L && total > 0L) {
                            "${humanSize(transferred)} / ${humanSize(total)}"
                        } else {
                            humanSize(item.size)
                        }

                        publishProgress(
                            title = item.title,
                            text = "$prefix • ${item.kind.label} • $pc% • $amountText",
                            percent = pc
                        )

                        done = RpiClient.isFinished(progress)
                    }

                    if (cancelRequested) return@launch

                    logOnly("Concluído: ${item.kind.label} • ${item.title}")
                    currentTaskId = null
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (e: Exception) {
                if (!cancelRequested) {
                    finishError("Falha em ${currentTitle()}: ${e.message ?: "erro desconhecido"}")
                }
                return@launch
            }

            if (cancelRequested) return@launch

            val elapsed = android.os.SystemClock.elapsedRealtime() - queueStartedAt
            val summary = if (ordered.size == 1) {
                "Instalação concluída • ${humanSize(totalSize)} • ${humanDuration(elapsed)}"
            } else {
                "Fila concluída • ${ordered.size} PKGs • ${humanSize(totalSize)} • ${humanDuration(elapsed)}"
            }

            finishSuccess(summary)
        }
    }

    private fun rpiSubType(item: PkgItem): Int? {
        return when (item.category.lowercase()) {
            "gd" -> 6  // Game
            "ac" -> 7  // Add-on content
            "gp" -> 8  // Patch/update
            "al" -> 9  // License/add-on license
            else -> when (item.kind) {
                PkgKind.GAME -> 6
                PkgKind.UPDATE -> 8
                PkgKind.DLC -> 7
                PkgKind.OTHER -> null
            }
        }
    }

    private fun currentTitle(): String {
        val task = currentTaskId
        return if (task != null) "task $task" else "PKG"
    }

    private fun cancelInstall() {
        if (cancelRequested) return
        cancelRequested = true

        val task = currentTaskId
        val ip = currentPs4Ip

        publishLive(
            title = "PKG Pocket",
            text = "Cancelando envio…",
            percent = 0
        )

        // Para imediatamente novas leituras do PS4.
        server?.stop()

        scope.launch {
            if (task != null && !ip.isNullOrBlank()) {
                runCatching { RpiClient.stop(ip, task) }
                runCatching { RpiClient.unregister(ip, task) }
            }

            installJob?.cancel()
            currentTaskId = null
            finishCancelled("Envio cancelado pelo usuário")
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
                .putExtra(EXTRA_LIVE_UPDATE, true)
                .putExtra(EXTRA_ACTIVE, true)
        )
    }

    private fun publishLive(title: String, text: String, percent: Int?) {
        publishProgress(title, text, percent)
    }

    private fun logOnly(text: String) {
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, text)
                .putExtra(EXTRA_LOG_ONLY, true)
                .putExtra(EXTRA_LIVE_UPDATE, false)
                .putExtra(EXTRA_ACTIVE, true)
        )
    }

    private fun finishSuccess(summary: String) {
        broadcastFinal(summary, 100)
        stopForeground(STOP_FOREGROUND_REMOVE)

        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            finalNotification(
                title = "Instalação concluída",
                text = summary,
                success = true
            )
        )

        cleanupAndStop()
    }

    private fun finishError(message: String) {
        broadcastFinal(message, 0)
        stopForeground(STOP_FOREGROUND_REMOVE)

        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            finalNotification(
                title = "Falha na instalação",
                text = message,
                success = false
            )
        )

        cleanupAndStop()
    }

    private fun finishCancelled(message: String) {
        broadcastFinal(message, 0)
        stopForeground(STOP_FOREGROUND_REMOVE)

        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            finalNotification(
                title = "Envio cancelado",
                text = message,
                success = false
            )
        )

        cleanupAndStop()
    }

    private fun broadcastFinal(text: String, percent: Int) {
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, text)
                .putExtra(EXTRA_PERCENT, percent)
                .putExtra(EXTRA_LOG_ONLY, false)
                .putExtra(EXTRA_LIVE_UPDATE, false)
                .putExtra(EXTRA_ACTIVE, false)
        )
    }

    private fun cleanupAndStop() {
        server?.stop()
        server = null
        currentTaskId = null
        currentPs4Ip = null
        releasePerformanceLocks()
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
        releasePerformanceLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
