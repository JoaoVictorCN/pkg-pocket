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

class InstallerService : Service() {
    companion object {
        const val ACTION_REFRESH = "com.pkgpocket.REFRESH"
        const val ACTION_INSTALL_ALL = "com.pkgpocket.INSTALL_ALL"
        const val ACTION_STATUS = "com.pkgpocket.STATUS"
        const val EXTRA_PS4_IP = "ps4_ip"
        const val EXTRA_STATUS = "status"
        const val EXTRA_PERCENT = "percent"
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
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Transferência de PKG", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIF_ID, notification("Servidor pronto", 0))
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PkgPocket:Transfer").apply { setReferenceCounted(false); acquire() }
        restartServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REFRESH -> restartServer()
            ACTION_INSTALL_ALL -> {
                val ip = intent.getStringExtra(EXTRA_PS4_IP).orEmpty()
                if (ip.isNotBlank()) installAll(ip)
            }
        }
        return START_STICKY
    }

    private fun restartServer() {
        server?.stop()
        server = PkgHttpServer(
            resolver = contentResolver,
            port = 8080,
            itemsProvider = { PkgRepository.items },
            onLog = { message -> publish(message, 0) }
        ).also {
            runCatching { it.start() }.onFailure { e -> publish("Erro no servidor: ${e.message}", 0) }
        }
    }

    private fun installAll(ps4Ip: String) {
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            val localIp = NetworkUtils.localIpv4()
            if (localIp == null) { publish("Celular sem IPv4 local", 0); return@launch }
            if (!NetworkUtils.canConnect(ps4Ip)) { publish("RPI não encontrado em $ps4Ip:12800", 0); return@launch }
            val ordered = PkgRepository.items.sortedWith(compareBy<PkgItem>({ it.titleId }, { it.kind.order }, { it.fileName }))
            if (ordered.isEmpty()) { publish("Nenhum PKG selecionado", 0); return@launch }
            ordered.forEachIndexed { index, item ->
                publish("${index + 1}/${ordered.size}: enviando ${item.kind.label} — ${item.title}", 0)
                try {
                    val activeServer = server ?: error("Servidor HTTP não iniciado")
                    val url = activeServer.urlFor(localIp, item)
                    publish("Enviando URL ao RPI: $url", 0)
                    val result = RpiClient.install(ps4Ip, url)
                    publish("RPI respondeu: ${result.raw}", 0)
                    val task = result.taskId
                    if (task == null) {
                        publish("RPI aceitou ${item.title}, mas não retornou task_id", 0)
                        delay(2000)
                    } else {
                        var done = false
                        while (!done) {
                            delay(2000)
                            val p = RpiClient.progress(ps4Ip, task)
                            val pc = RpiClient.percent(p)
                            publish("${index + 1}/${ordered.size}: ${item.title} — $pc%", pc)
                            done = RpiClient.isFinished(p)
                        }
                    }
                } catch (e: Exception) {
                    publish("Falha em ${item.fileName}: ${e.message}", 0)
                    return@launch
                }
            }
            publish("Fila concluída", 100)
        }
    }

    private fun publish(text: String, percent: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text, percent))
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, text).putExtra(EXTRA_PERCENT, percent))
    }

    private fun notification(text: String, percent: Int): android.app.Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("PKG Pocket")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, percent <= 0)
            .build()
    }

    override fun onDestroy() {
        server?.stop()
        installJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
