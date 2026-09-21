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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToLong

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

        const val EXTRA_OVERALL_STATUS = "overall_status"
        const val EXTRA_ITEM_TOKEN = "item_token"
        const val EXTRA_ITEM_STATUS = "item_status"
        const val EXTRA_ITEM_DETAIL = "item_detail"
        const val EXTRA_ITEM_PERCENT = "item_percent"

        private const val CHANNEL = "pkg_transfer"
        private const val NOTIF_ID = 41
        private const val SPEED_WINDOW_MS = 30_000L
    }

    private data class TransferSample(
        val timeMs: Long,
        val bytes: Long
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var installJob: Job? = null
    private var server: PkgHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var currentTaskId: Int? = null
    @Volatile private var currentPs4Ip: String? = null
    @Volatile private var currentItemToken: String? = null
    @Volatile private var currentItemPercent = 0
    @Volatile private var currentItemDetail = ""
    @Volatile private var lastOverallPercent = 0
    @Volatile private var cancelRequested = false

    override fun onCreate() {
        super.onCreate()

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )

        startForeground(
            NOTIF_ID,
            transferNotification(
                title = getString(R.string.app_name),
                text = getString(R.string.notification_preparing),
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
                .onFailure { e -> logOnly(getString(R.string.log_server_error, e.message ?: "?")) }
        }
    }

    private fun installAll(ps4Ip: String) {
        if (installJob?.isActive == true) return

        cancelRequested = false
        currentPs4Ip = ps4Ip
        currentItemToken = null
        currentItemPercent = 0
        currentItemDetail = ""
        lastOverallPercent = 0
        restartServer()

        installJob = scope.launch {
            acquirePerformanceLocks()

            val localIp = NetworkUtils.localIpv4()
            if (localIp == null) {
                finishError(getString(R.string.error_no_ipv4))
                return@launch
            }

            if (!NetworkUtils.canConnect(ps4Ip)) {
                finishError(getString(R.string.error_rpi_not_found, ps4Ip))
                return@launch
            }

            val ordered = PkgRepository.items.sortedWith(
                compareBy<PkgItem>({ it.titleId }, { it.kind.order }, { it.fileName })
            )

            if (ordered.isEmpty()) {
                finishError(getString(R.string.error_no_pkgs))
                return@launch
            }

            val queueStartedAt = SystemClock.elapsedRealtime()
            val totalSize = ordered.sumOf { it.size.coerceAtLeast(0L) }
            var completedBytes = 0L
            var activeItem: PkgItem? = null

            try {
                ordered.forEachIndexed { index, item ->
                    if (cancelRequested) return@launch

                    activeItem = item
                    currentItemToken = item.token
                    currentItemPercent = 0
                    currentItemDetail = getString(R.string.calculating_time)

                    val position = index + 1
                    val kind = kindLabel(item.kind)
                    val baseOverall = overallPercent(completedBytes, totalSize)

                    publishTransfer(
                        item = item,
                        title = item.title,
                        topText = getString(R.string.top_preparing, position, ordered.size, kind),
                        itemStatus = getString(R.string.state_preparing),
                        itemDetail = getString(R.string.calculating_time),
                        itemPercent = -1,
                        overallPercent = baseOverall,
                        overallText = getString(
                            R.string.overall_preparing,
                            position,
                            ordered.size,
                            baseOverall
                        )
                    )

                    val activeServer = server ?: error(getString(R.string.error_server_not_started))
                    val url = activeServer.urlFor(localIp, item)

                    logOnly(getString(R.string.log_install_request, kind, item.title))

                    val installAttempt = runCatching { RpiClient.install(ps4Ip, url) }
                    var task = installAttempt.getOrNull()?.taskId

                    if (task == null) {
                        val originalError = installAttempt.exceptionOrNull()

                        publishTransfer(
                            item = item,
                            title = item.title,
                            topText = getString(R.string.top_registering, position, ordered.size),
                            itemStatus = getString(R.string.state_registering),
                            itemDetail = getString(R.string.calculating_time),
                            itemPercent = -1,
                            overallPercent = baseOverall,
                            overallText = getString(
                                R.string.overall_preparing,
                                position,
                                ordered.size,
                                baseOverall
                            )
                        )

                        if (originalError != null) {
                            logOnly(getString(R.string.log_install_response_lost))
                        } else {
                            logOnly(getString(R.string.log_no_task_id))
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
                            error(getString(R.string.error_no_task))
                        }

                        logOnly(getString(R.string.log_task_recovered, task!!))
                    } else {
                        logOnly(getString(R.string.log_task_started, task!!, item.title))
                    }

                    val activeTask = task ?: error(getString(R.string.error_no_task))
                    currentTaskId = activeTask

                    var done = false
                    var statusFailures = 0
                    var pollDelayMs = 5000L
                    var lastPc = 0
                    var lastMeta = getString(R.string.calculating_time)
                    var lastOverall = baseOverall
                    var lastOverallText = getString(
                        R.string.overall_preparing,
                        position,
                        ordered.size,
                        baseOverall
                    )
                    var smoothedEtaSeconds: Double? = null
                    val samples = mutableListOf<TransferSample>()
                    val itemStartedAt = SystemClock.elapsedRealtime()

                    publishTransfer(
                        item = item,
                        title = item.title,
                        topText = getString(R.string.top_sending_initial, position, ordered.size, kind),
                        itemStatus = getString(R.string.state_sending_percent, 0),
                        itemDetail = lastMeta,
                        itemPercent = 0,
                        overallPercent = baseOverall,
                        overallText = lastOverallText
                    )

                    while (!done && !cancelRequested) {
                        delay(pollDelayMs)

                        val progress = runCatching { RpiClient.progress(ps4Ip, activeTask) }
                            .onFailure {
                                statusFailures++
                                pollDelayMs = (pollDelayMs + 5000L).coerceAtMost(20_000L)

                                publishTransfer(
                                    item = item,
                                    title = item.title,
                                    topText = getString(
                                        R.string.top_waiting_ps4,
                                        position,
                                        ordered.size
                                    ),
                                    itemStatus = getString(R.string.state_waiting_ps4),
                                    itemDetail = lastMeta,
                                    itemPercent = lastPc,
                                    overallPercent = lastOverall,
                                    overallText = lastOverallText
                                )

                                if (statusFailures == 1 || statusFailures % 6 == 0) {
                                    logOnly(getString(R.string.log_status_flaky, statusFailures))
                                }
                            }
                            .getOrNull()
                            ?: continue

                        statusFailures = 0
                        pollDelayMs = 5000L

                        val pc = RpiClient.percent(progress)
                        val transferred = RpiClient.bytesDone(progress).coerceAtLeast(0L)
                        val transferTotal = RpiClient.bytesTotal(progress)
                            .takeIf { it > 0L }
                            ?: item.size.coerceAtLeast(0L)

                        val now = SystemClock.elapsedRealtime()
                        val speed = updateSpeed(samples, now, transferred)
                        val remainingBytes = (transferTotal - transferred).coerceAtLeast(0L)

                        val computedEta = if (speed > 1.0 && remainingBytes > 0L) {
                            remainingBytes / speed
                        } else {
                            -1.0
                        }

                        val rpiEta = RpiClient.restSeconds(progress)
                            .takeIf { it in 1..604_800 }
                            ?.toDouble()
                            ?: -1.0

                        val etaCandidate = when {
                            computedEta > 0 -> computedEta
                            rpiEta > 0 -> rpiEta
                            else -> -1.0
                        }

                        if (etaCandidate > 0) {
                            smoothedEtaSeconds = if (smoothedEtaSeconds == null) {
                                etaCandidate
                            } else {
                                (smoothedEtaSeconds!! * 0.70) + (etaCandidate * 0.30)
                            }
                        }

                        val itemEta = smoothedEtaSeconds?.roundToLong() ?: -1L
                        val itemMeta = transferDetail(
                            transferred = transferred,
                            total = transferTotal,
                            speed = speed,
                            etaSeconds = itemEta
                        )

                        val contribution = if (item.size > 0L) {
                            transferred.coerceAtMost(item.size)
                        } else {
                            transferred
                        }

                        val overallDone = completedBytes + contribution
                        val overallPc = overallPercent(overallDone, totalSize)
                        val queueRemaining = (totalSize - overallDone).coerceAtLeast(0L)
                        val queueEta = if (speed > 1.0 && queueRemaining > 0L) {
                            (queueRemaining / speed).roundToLong()
                        } else {
                            -1L
                        }

                        val overallInfo = overallDetail(
                            position = position,
                            count = ordered.size,
                            percent = overallPc,
                            speed = speed,
                            etaSeconds = queueEta
                        )

                        lastPc = pc
                        lastMeta = itemMeta
                        lastOverall = overallPc
                        lastOverallText = overallInfo

                        publishTransfer(
                            item = item,
                            title = item.title,
                            topText = getString(
                                R.string.top_progress,
                                position,
                                ordered.size,
                                kind,
                                pc,
                                itemMeta
                            ),
                            itemStatus = getString(R.string.state_sending_percent, pc),
                            itemDetail = itemMeta,
                            itemPercent = pc,
                            overallPercent = overallPc,
                            overallText = overallInfo
                        )

                        done = RpiClient.isFinished(progress)
                    }

                    if (cancelRequested) return@launch

                    completedBytes += item.size.coerceAtLeast(0L)
                    val completedOverall = overallPercent(completedBytes, totalSize)
                    val itemDuration = SystemClock.elapsedRealtime() - itemStartedAt
                    val completedDetail = getString(
                        R.string.card_completed_detail,
                        humanSize(item.size),
                        humanDuration(itemDuration)
                    )

                    publishTransfer(
                        item = item,
                        title = item.title,
                        topText = getString(
                            R.string.top_completed,
                            position,
                            ordered.size,
                            item.title
                        ),
                        itemStatus = getString(R.string.state_completed),
                        itemDetail = completedDetail,
                        itemPercent = 100,
                        overallPercent = completedOverall,
                        overallText = getString(
                            R.string.overall_position_percent,
                            position,
                            ordered.size,
                            completedOverall
                        )
                    )

                    logOnly(getString(R.string.log_completed, kind, item.title))
                    currentTaskId = null
                    currentItemToken = null
                    currentItemPercent = 100
                    currentItemDetail = completedDetail
                    activeItem = null
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (e: Exception) {
                if (!cancelRequested) {
                    activeItem?.let {
                        publishItemOnly(
                            token = it.token,
                            status = getString(R.string.state_failed),
                            detail = e.message ?: getString(R.string.unknown_error),
                            percent = currentItemPercent.coerceAtLeast(0)
                        )
                    }

                    finishError(
                        getString(
                            R.string.error_in_pkg,
                            currentTitle(),
                            e.message ?: getString(R.string.unknown_error)
                        )
                    )
                }
                return@launch
            }

            if (cancelRequested) return@launch

            val elapsed = SystemClock.elapsedRealtime() - queueStartedAt
            val summary = if (ordered.size == 1) {
                getString(
                    R.string.installation_completed_summary,
                    humanSize(totalSize),
                    humanDuration(elapsed)
                )
            } else {
                getString(
                    R.string.queue_completed_summary,
                    ordered.size,
                    humanSize(totalSize),
                    humanDuration(elapsed)
                )
            }

            lastOverallPercent = 100
            finishSuccess(summary)
        }
    }

    private fun updateSpeed(
        samples: MutableList<TransferSample>,
        now: Long,
        bytes: Long
    ): Double {
        if (samples.isNotEmpty() && bytes < samples.last().bytes) {
            samples.clear()
        }

        samples += TransferSample(now, bytes)

        while (
            samples.size > 2 &&
            samples.first().timeMs < now - SPEED_WINDOW_MS
        ) {
            samples.removeAt(0)
        }

        if (samples.size < 2) return 0.0

        val first = samples.first()
        val last = samples.last()
        val elapsedSeconds = (last.timeMs - first.timeMs) / 1000.0
        val deltaBytes = last.bytes - first.bytes

        return if (elapsedSeconds > 0.0 && deltaBytes > 0L) {
            deltaBytes / elapsedSeconds
        } else {
            0.0
        }
    }

    private fun transferDetail(
        transferred: Long,
        total: Long,
        speed: Double,
        etaSeconds: Long
    ): String {
        val amount = getString(
            R.string.transfer_amount,
            humanSize(transferred),
            humanSize(total)
        )

        return when {
            speed > 1.0 && etaSeconds > 0L -> getString(
                R.string.transfer_detail_full,
                amount,
                humanSpeed(speed),
                remainingText(etaSeconds)
            )

            speed > 1.0 -> getString(
                R.string.transfer_detail_speed,
                amount,
                humanSpeed(speed)
            )

            else -> getString(
                R.string.transfer_detail_wait,
                amount,
                getString(R.string.calculating_time)
            )
        }
    }

    private fun overallDetail(
        position: Int,
        count: Int,
        percent: Int,
        speed: Double,
        etaSeconds: Long
    ): String {
        return when {
            speed > 1.0 && etaSeconds > 0L -> getString(
                R.string.overall_transfer_full,
                position,
                count,
                percent,
                humanSpeed(speed),
                remainingText(etaSeconds)
            )

            speed > 1.0 -> getString(
                R.string.overall_transfer_speed,
                position,
                count,
                percent,
                humanSpeed(speed)
            )

            else -> getString(
                R.string.overall_transfer_wait,
                position,
                count,
                percent,
                getString(R.string.calculating_time)
            )
        }
    }

    private fun overallPercent(done: Long, total: Long): Int {
        if (total <= 0L) return 0
        return ((done.coerceIn(0L, total) * 100L) / total)
            .toInt()
            .coerceIn(0, 100)
    }

    private fun remainingText(seconds: Long): String {
        return getString(R.string.remaining_time, humanEta(seconds))
    }

    private fun humanEta(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)

        return when {
            safe < 60L -> getString(R.string.time_seconds, safe)
            safe < 3600L -> getString(R.string.time_minutes, (safe + 59L) / 60L)
            else -> {
                val hours = safe / 3600L
                val minutes = (safe % 3600L) / 60L
                getString(R.string.time_hours_minutes, hours, minutes)
            }
        }
    }

    private fun humanSpeed(bytesPerSecond: Double): String {
        return getString(
            R.string.speed_format,
            humanSize(bytesPerSecond.coerceAtLeast(0.0).roundToLong())
        )
    }

    private fun rpiSubType(item: PkgItem): Int? {
        return when (item.category.lowercase()) {
            "gd" -> 6
            "ac" -> 7
            "gp" -> 8
            "al" -> 9
            else -> when (item.kind) {
                PkgKind.GAME -> 6
                PkgKind.UPDATE -> 8
                PkgKind.DLC -> 7
                PkgKind.OTHER -> null
            }
        }
    }

    private fun kindLabel(kind: PkgKind): String {
        return getString(
            when (kind) {
                PkgKind.GAME -> R.string.kind_game
                PkgKind.UPDATE -> R.string.kind_update
                PkgKind.DLC -> R.string.kind_dlc
                PkgKind.OTHER -> R.string.kind_pkg
            }
        )
    }

    private fun currentTitle(): String {
        val task = currentTaskId
        return if (task != null) {
            getString(R.string.task_label, task)
        } else {
            getString(R.string.kind_pkg)
        }
    }

    private fun cancelInstall() {
        if (cancelRequested) return
        cancelRequested = true

        val task = currentTaskId
        val ip = currentPs4Ip

        publishGlobal(
            title = getString(R.string.app_name),
            text = getString(R.string.cancelling_transfer),
            percent = lastOverallPercent,
            overallText = getString(R.string.cancelling_transfer),
            active = true
        )

        currentItemToken?.let {
            publishItemOnly(
                token = it,
                status = getString(R.string.state_cancelled),
                detail = getString(R.string.cancelled_by_user),
                percent = currentItemPercent.coerceAtLeast(0)
            )
        }

        server?.stop()

        scope.launch {
            if (task != null && !ip.isNullOrBlank()) {
                runCatching { RpiClient.stop(ip, task) }
                runCatching { RpiClient.unregister(ip, task) }
            }

            installJob?.cancel()
            currentTaskId = null
            finishCancelled(getString(R.string.cancelled_by_user))
        }
    }

    private fun publishTransfer(
        item: PkgItem,
        title: String,
        topText: String,
        itemStatus: String,
        itemDetail: String,
        itemPercent: Int,
        overallPercent: Int,
        overallText: String
    ) {
        lastOverallPercent = overallPercent.coerceIn(0, 100)
        currentItemPercent = itemPercent
        currentItemDetail = itemDetail

        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            transferNotification(title, topText, lastOverallPercent)
        )

        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, topText)
                .putExtra(EXTRA_PERCENT, lastOverallPercent)
                .putExtra(EXTRA_OVERALL_STATUS, overallText)
                .putExtra(EXTRA_ITEM_TOKEN, item.token)
                .putExtra(EXTRA_ITEM_STATUS, itemStatus)
                .putExtra(EXTRA_ITEM_DETAIL, itemDetail)
                .putExtra(EXTRA_ITEM_PERCENT, itemPercent)
                .putExtra(EXTRA_LOG_ONLY, false)
                .putExtra(EXTRA_LIVE_UPDATE, true)
                .putExtra(EXTRA_ACTIVE, true)
        )
    }

    private fun publishItemOnly(
        token: String,
        status: String,
        detail: String,
        percent: Int
    ) {
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_ITEM_TOKEN, token)
                .putExtra(EXTRA_ITEM_STATUS, status)
                .putExtra(EXTRA_ITEM_DETAIL, detail)
                .putExtra(EXTRA_ITEM_PERCENT, percent)
                .putExtra(EXTRA_LOG_ONLY, true)
                .putExtra(EXTRA_LIVE_UPDATE, false)
                .putExtra(EXTRA_ACTIVE, true)
        )
    }

    private fun publishGlobal(
        title: String,
        text: String,
        percent: Int,
        overallText: String,
        active: Boolean
    ) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            transferNotification(title, text, percent)
        )

        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, text)
                .putExtra(EXTRA_PERCENT, percent.coerceIn(0, 100))
                .putExtra(EXTRA_OVERALL_STATUS, overallText)
                .putExtra(EXTRA_LOG_ONLY, false)
                .putExtra(EXTRA_LIVE_UPDATE, true)
                .putExtra(EXTRA_ACTIVE, active)
        )
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
                title = getString(R.string.notification_install_complete),
                text = summary,
                success = true
            )
        )

        cleanupAndStop()
    }

    private fun finishError(message: String) {
        broadcastFinal(message, lastOverallPercent)
        stopForeground(STOP_FOREGROUND_REMOVE)

        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            finalNotification(
                title = getString(R.string.notification_install_failed),
                text = message,
                success = false
            )
        )

        cleanupAndStop()
    }

    private fun finishCancelled(message: String) {
        broadcastFinal(message, lastOverallPercent)
        stopForeground(STOP_FOREGROUND_REMOVE)

        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            finalNotification(
                title = getString(R.string.notification_transfer_cancelled),
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
                .putExtra(EXTRA_PERCENT, percent.coerceIn(0, 100))
                .putExtra(EXTRA_OVERALL_STATUS, text)
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
        currentItemToken = null
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

        return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
    }

    private fun humanDuration(ms: Long): String {
        val totalSeconds = (ms / 1000.0).coerceAtLeast(0.0)

        if (totalSeconds < 60.0) {
            return getString(
                R.string.duration_seconds,
                String.format(Locale.getDefault(), "%.1f", totalSeconds)
            )
        }

        val minutes = (totalSeconds / 60).toInt()
        val seconds = (totalSeconds % 60).toInt()
        return getString(R.string.duration_minutes_seconds, minutes, seconds)
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
