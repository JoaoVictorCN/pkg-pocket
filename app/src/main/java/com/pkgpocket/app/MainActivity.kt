package com.pkgpocket.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.pkgpocket.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val logLines = mutableListOf<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var demoJob: Job? = null

    private data class PkgCardViews(
        val progress: ProgressBar,
        val status: TextView,
        val meta: TextView
    )

    private val pkgCards = mutableMapOf<String, PkgCardViews>()

    private val pickPkgs = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult

        lifecycleScope.launch {
            val reading = getString(R.string.reading_metadata, uris.size)
            b.status.text = reading
            addLog(reading)

            val parsed = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        PkgParser.parse(contentResolver, uri)
                    }.onFailure { e ->
                        runOnUiThread {
                            val msg = "${uri.lastPathSegment}: ${e.message}"
                            addLog(getString(R.string.error_prefix, msg))
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                        }
                    }.getOrNull()
                }
            }

            PkgRepository.items = parsed
            render(parsed)

            parsed.forEach { item ->
                addLog(
                    getString(
                        R.string.log_pkg,
                        kindLabel(item.kind),
                        item.title,
                        item.titleId.ifBlank { getString(R.string.missing_title_id) },
                        item.version.ifBlank { "?" },
                        humanSize(item.size)
                    )
                )
            }

            val ready = getString(R.string.pkgs_ready, parsed.size)
            updateSelectionSummary(parsed)
            b.liveLog.text = getString(R.string.no_active_transfer)
            b.progress.progress = 0
            b.overallProgressInfo.text = getString(R.string.overall_idle)
            b.status.visibility = View.VISIBLE
            b.overallProgressInfo.visibility = View.GONE
            b.progress.visibility = View.GONE
            addLog(ready)
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            val text = intent.getStringExtra(InstallerService.EXTRA_STATUS).orEmpty()
            val logOnly = intent.getBooleanExtra(InstallerService.EXTRA_LOG_ONLY, false)
            val liveUpdate = intent.getBooleanExtra(InstallerService.EXTRA_LIVE_UPDATE, false)
            val active = intent.getBooleanExtra(InstallerService.EXTRA_ACTIVE, false)
            val overallPercent = intent.getIntExtra(InstallerService.EXTRA_PERCENT, 0)
            val overallText = intent.getStringExtra(InstallerService.EXTRA_OVERALL_STATUS).orEmpty()
            val token = intent.getStringExtra(InstallerService.EXTRA_ITEM_TOKEN).orEmpty()
            val rawItemStatus = intent.getStringExtra(InstallerService.EXTRA_ITEM_STATUS).orEmpty()
            val itemDetail = intent.getStringExtra(InstallerService.EXTRA_ITEM_DETAIL).orEmpty()
            val itemPercent = if (intent.hasExtra(InstallerService.EXTRA_ITEM_PERCENT)) {
                intent.getIntExtra(InstallerService.EXTRA_ITEM_PERCENT, 0)
            } else {
                null
            }

            val itemStatus = if (
                itemPercent == 0 &&
                rawItemStatus == getString(R.string.state_sending_percent, 0)
            ) {
                getString(R.string.state_starting_transfer)
            } else {
                rawItemStatus
            }

            val showOverallProgress = overallPercent > 0

            b.cancelInstall.visibility = if (active) View.VISIBLE else View.GONE
            b.installAll.isEnabled = !active
            b.selectPkgs.isEnabled = !active
            b.clearSelection.isEnabled = !active && demoJob?.isActive != true
            if (!active) b.cancelInstall.isEnabled = true

            if (liveUpdate || !logOnly) {
                val visibleStatus = if (!showOverallProgress && itemPercent == 0 && itemStatus.isNotBlank()) {
                    itemStatus
                } else {
                    text
                }

                if (visibleStatus.isNotBlank()) {
                    b.status.text = visibleStatus
                    b.liveLog.text = text
                }

                // Durante a transferência real, o topo mostra apenas o progresso da fila.
                // Percentual/velocidade/ETA do PKG ficam somente no card.
                b.status.visibility = if (showOverallProgress) View.GONE else View.VISIBLE
                b.overallProgressInfo.visibility = if (showOverallProgress) View.VISIBLE else View.GONE
                b.progress.visibility = if (showOverallProgress) View.VISIBLE else View.GONE
                b.progress.isIndeterminate = false
                b.progress.progress = overallPercent.coerceIn(0, 100)

                if (overallText.isNotBlank()) {
                    b.overallProgressInfo.text = overallText
                }
            }

            if (token.isNotBlank()) {
                updatePkgCard(token, itemPercent, itemStatus, itemDetail)
            }

            if (!liveUpdate && text.isNotBlank()) addLog(text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(b.root)

        requestRuntimePermissions()
        requestBatteryExemptionOnce()

        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(InstallerService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val prefs = getSharedPreferences("pkg_pocket", MODE_PRIVATE)
        val savedIp = prefs.getString("last_ps4_ip", "").orEmpty()
        if (savedIp.isNotBlank()) b.ps4Ip.setText(savedIp)
        b.ps4Ip.doAfterTextChanged { editable ->
            prefs.edit()
                .putString("last_ps4_ip", editable?.toString()?.trim().orEmpty())
                .apply()
        }

        b.appTitle.setOnLongClickListener {
            if (demoJob?.isActive == true) {
                stopDemo()
            } else {
                startDemo()
            }
            true
        }

        b.selectPkgs.setOnClickListener {
            addLog(getString(R.string.opening_picker))
            pickPkgs.launch(arrayOf("application/octet-stream", "application/x-pkg", "*/*"))
        }

        b.clearSelection.setOnClickListener {
            clearSelection()
        }

        b.detectPs4.setOnClickListener {
            lifecycleScope.launch {
                val searching = getString(R.string.searching_rpi)
                addLog(searching)

                val ip = withContext(Dispatchers.IO) { NetworkUtils.findRpi() }
                if (ip != null) {
                    b.ps4Ip.setText(ip)
                    val found = getString(R.string.rpi_found, ip)
                    addLog(found)
                    Toast.makeText(
                        this@MainActivity,
                        found,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val notFound = getString(R.string.rpi_not_found)
                    addLog(notFound)
                    Toast.makeText(
                        this@MainActivity,
                        notFound,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        b.installAll.setOnClickListener {
            val ip = b.ps4Ip.text?.toString()?.trim().orEmpty()

            if (PkgRepository.items.isEmpty()) {
                Toast.makeText(this, R.string.select_pkgs_first, Toast.LENGTH_SHORT).show()
                addLog(getString(R.string.install_cancelled_no_pkgs))
                return@setOnClickListener
            }

            if (ip.isBlank()) {
                Toast.makeText(this, R.string.enter_ps4_ip, Toast.LENGTH_SHORT).show()
                addLog(getString(R.string.install_cancelled_no_ip))
                return@setOnClickListener
            }

            resetPkgCardsForQueue()
            b.cancelInstall.visibility = View.VISIBLE
            b.cancelInstall.isEnabled = true
            b.installAll.isEnabled = false
            b.progress.progress = 0
            b.overallProgressInfo.text = getString(R.string.overall_idle)
            b.status.visibility = View.VISIBLE
            b.overallProgressInfo.visibility = View.GONE
            b.progress.visibility = View.GONE
            b.liveLog.text = getString(R.string.preparing_transfer)
            addLog(getString(R.string.starting_queue, PkgRepository.items.size, ip))
            ensureService(InstallerService.ACTION_INSTALL_ALL, ip)
        }

        b.cancelInstall.setOnClickListener {
            b.cancelInstall.isEnabled = false
            b.liveLog.text = getString(R.string.cancelling_transfer)
            ensureService(InstallerService.ACTION_CANCEL)
        }

        b.toggleLog.setOnClickListener {
            val show = b.logContainer.visibility != View.VISIBLE
            b.logContainer.visibility = if (show) View.VISIBLE else View.GONE
            b.toggleLog.text = getString(if (show) R.string.hide_log else R.string.view_log)
        }

        b.liveLog.text = getString(R.string.no_active_transfer)
        b.overallProgressInfo.text = getString(R.string.overall_idle)
        b.overallProgressInfo.visibility = View.GONE
        b.progress.visibility = View.GONE

        if (PkgRepository.items.isNotEmpty()) {
            render(PkgRepository.items)
            updateSelectionSummary(PkgRepository.items)
        } else {
            b.clearSelection.visibility = View.GONE
        }

        addLog(getString(R.string.app_started))
    }

    private fun updateSelectionSummary(items: List<PkgItem>) {
        if (items.isEmpty()) {
            b.status.text = getString(R.string.select_pkg_prompt)
            b.clearSelection.visibility = View.GONE
            return
        }

        val totalSize = items.sumOf { it.size.coerceAtLeast(0L) }
        val kinds = items
            .map { it.kind }
            .distinct()
            .sortedBy { it.order }
            .joinToString(" + ") { kindLabel(it) }

        b.status.text = getString(
            R.string.selection_summary,
            items.size,
            humanSize(totalSize),
            kinds
        )
        b.clearSelection.visibility = View.VISIBLE
    }

    private fun clearSelection() {
        if (demoJob?.isActive == true) demoJob?.cancel()
        demoJob = null

        PkgRepository.items = emptyList()
        b.pkgList.removeAllViews()
        pkgCards.clear()

        b.status.visibility = View.VISIBLE
        b.status.text = getString(R.string.select_pkg_prompt)
        b.overallProgressInfo.visibility = View.GONE
        b.progress.visibility = View.GONE
        b.progress.progress = 0
        b.clearSelection.visibility = View.GONE
        b.liveLog.text = getString(R.string.no_active_transfer)

        setDemoControlsEnabled(true)
        addLog(getString(R.string.selection_cleared))
    }

    private fun startDemo() {
        val ordered = PkgRepository.items.sortedWith(
            compareBy<PkgItem>({ it.titleId }, { it.kind.order }, { it.fileName })
        )

        if (ordered.isEmpty()) {
            Toast.makeText(this, R.string.demo_requires_pkg, Toast.LENGTH_SHORT).show()
            return
        }

        resetPkgCardsForQueue()
        setDemoControlsEnabled(false)
        b.cancelInstall.visibility = View.GONE
        b.status.visibility = View.VISIBLE
        b.status.text = getString(R.string.demo_preparing)
        b.overallProgressInfo.visibility = View.GONE
        b.progress.visibility = View.GONE
        b.progress.progress = 0

        addLog(getString(R.string.demo_started))
        Toast.makeText(this, R.string.demo_started, Toast.LENGTH_SHORT).show()

        demoJob = lifecycleScope.launch {
            val fakeSpeedBytes = (28.5 * 1024.0 * 1024.0).toLong()
            val totalSize = ordered.sumOf { it.size.coerceAtLeast(0L) }.coerceAtLeast(1L)
            var completedBytes = 0L

            try {
                ordered.forEachIndexed { index, item ->
                    val position = index + 1

                    b.status.visibility = View.VISIBLE
                    b.overallProgressInfo.visibility = View.GONE
                    b.progress.visibility = View.GONE
                    b.status.text = getString(
                        R.string.demo_stage,
                        position,
                        ordered.size,
                        getString(R.string.state_preparing)
                    )
                    updatePkgCard(
                        item.token,
                        null,
                        getString(R.string.state_preparing),
                        ""
                    )
                    delay(700)

                    b.status.text = getString(
                        R.string.demo_stage,
                        position,
                        ordered.size,
                        getString(R.string.state_registering)
                    )
                    updatePkgCard(
                        item.token,
                        null,
                        getString(R.string.state_registering),
                        ""
                    )
                    delay(700)

                    b.status.text = getString(
                        R.string.demo_stage,
                        position,
                        ordered.size,
                        getString(R.string.state_starting_transfer)
                    )
                    updatePkgCard(
                        item.token,
                        0,
                        getString(R.string.state_starting_transfer),
                        ""
                    )
                    delay(700)

                    val steps = intArrayOf(1, 8, 23, 47, 72, 100)

                    for (pc in steps) {
                        val itemSize = item.size.coerceAtLeast(0L)
                        val transferred = (itemSize * pc.toLong()) / 100L
                        val itemRemaining = (itemSize - transferred).coerceAtLeast(0L)
                        val itemEtaSeconds =
                            if (fakeSpeedBytes > 0L) itemRemaining / fakeSpeedBytes else 0L

                        val amount = getString(
                            R.string.transfer_amount,
                            humanSize(transferred),
                            humanSize(itemSize)
                        )
                        val speedText = getString(
                            R.string.speed_format,
                            humanSize(fakeSpeedBytes)
                        )
                        val itemEtaText = getString(
                            R.string.remaining_time,
                            humanEta(itemEtaSeconds)
                        )
                        val itemDetail = getString(
                            R.string.transfer_detail_full,
                            amount,
                            speedText,
                            itemEtaText
                        )

                        updatePkgCard(
                            item.token,
                            pc,
                            getString(R.string.state_sending_percent, pc),
                            itemDetail
                        )

                        val overallDone = completedBytes + transferred
                        val overallPercent =
                            ((overallDone * 100L) / totalSize).toInt().coerceIn(0, 100)
                        val queueRemaining = (totalSize - overallDone).coerceAtLeast(0L)
                        val queueEtaSeconds =
                            if (fakeSpeedBytes > 0L) queueRemaining / fakeSpeedBytes else 0L

                        b.status.visibility = View.GONE
                        b.overallProgressInfo.visibility = View.VISIBLE
                        b.progress.visibility = View.VISIBLE
                        b.progress.isIndeterminate = false
                        b.progress.progress = overallPercent
                        b.overallProgressInfo.text = getString(
                            R.string.overall_transfer_full,
                            position,
                            ordered.size,
                            overallPercent,
                            speedText,
                            getString(
                                R.string.remaining_time,
                                humanEta(queueEtaSeconds)
                            )
                        )

                        delay(550)
                    }

                    completedBytes += item.size.coerceAtLeast(0L)

                    updatePkgCard(
                        item.token,
                        100,
                        getString(R.string.state_completed),
                        getString(
                            R.string.card_completed_detail,
                            humanSize(item.size.coerceAtLeast(0L)),
                            getString(R.string.demo_label)
                        )
                    )

                    delay(500)
                }

                b.overallProgressInfo.visibility = View.GONE
                b.progress.visibility = View.GONE
                b.status.visibility = View.VISIBLE
                b.status.text = getString(R.string.demo_finished)

                addLog(getString(R.string.demo_finished))
                Toast.makeText(
                    this@MainActivity,
                    R.string.demo_finished,
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                setDemoControlsEnabled(true)
                demoJob = null
            }
        }
    }

    private fun stopDemo() {
        demoJob?.cancel()
        demoJob = null

        resetPkgCardsForQueue()
        b.overallProgressInfo.visibility = View.GONE
        b.progress.visibility = View.GONE
        b.progress.progress = 0
        b.status.visibility = View.VISIBLE
        updateSelectionSummary(PkgRepository.items)
        setDemoControlsEnabled(true)

        addLog(getString(R.string.demo_stopped))
        Toast.makeText(this, R.string.demo_stopped, Toast.LENGTH_SHORT).show()
    }

    private fun setDemoControlsEnabled(enabled: Boolean) {
        b.selectPkgs.isEnabled = enabled
        b.installAll.isEnabled = enabled
        b.detectPs4.isEnabled = enabled
        b.clearSelection.isEnabled = enabled
    }

    private fun humanEta(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)

        return when {
            safe < 60L -> getString(R.string.time_seconds, safe)
            safe < 3600L -> {
                val minutes = (safe + 59L) / 60L
                getString(R.string.time_minutes, minutes)
            }
            else -> {
                val hours = safe / 3600L
                val minutes = (safe % 3600L) / 60L
                getString(R.string.time_hours_minutes, hours, minutes)
            }
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf<String>()

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }

        if (
            Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), 9)
        }
    }

    private fun requestBatteryExemptionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val prefs = getSharedPreferences("pkg_pocket", MODE_PRIVATE)
        if (prefs.getBoolean("battery_prompted", false)) return

        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            prefs.edit().putBoolean("battery_prompted", true).apply()
            return
        }

        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
            prefs.edit().putBoolean("battery_prompted", true).apply()
        }
    }

    private fun addLog(message: String) {
        val clean = message.trim()
        if (clean.isBlank()) return

        logLines += "[${clock.format(Date())}] $clean"
        while (logLines.size > 80) logLines.removeAt(0)
        b.logText.text = logLines.joinToString("\n")
    }

    private fun ensureService(action: String, ip: String? = null) {
        val i = Intent(this, InstallerService::class.java).setAction(action)
        if (ip != null) i.putExtra(InstallerService.EXTRA_PS4_IP, ip)
        ContextCompat.startForegroundService(this, i)
    }

    private fun render(items: List<PkgItem>) {
        b.pkgList.removeAllViews()
        pkgCards.clear()

        val sorted = items.sortedWith(
            compareBy<PkgItem>({ it.titleId }, { it.kind.order }, { it.fileName })
        )

        sorted.forEach { item ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_pkg, b.pkgList, false)

            row.findViewById<TextView>(R.id.title).text = item.title
            row.findViewById<TextView>(R.id.kindBadge).text = kindLabel(item.kind)

            val details = if (item.version.isNotBlank()) {
                getString(R.string.pkg_details_version, item.version, humanSize(item.size))
            } else {
                getString(R.string.pkg_details_no_version, humanSize(item.size))
            }

            row.findViewById<TextView>(R.id.details).text = details
            row.findViewById<TextView>(R.id.titleId).text =
                getString(R.string.title_id_format, item.titleId.ifBlank { "—" })
            row.findViewById<TextView>(R.id.contentId).text =
                getString(R.string.content_id_format, item.contentId.ifBlank { "—" })
            row.findViewById<TextView>(R.id.fileName).text = item.fileName

            val iv = row.findViewById<ImageView>(R.id.icon)
            item.icon?.let { bytes ->
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                    .getOrNull()
                    ?.let(iv::setImageBitmap)
            }

            val progress = row.findViewById<ProgressBar>(R.id.itemProgress)
            val status = row.findViewById<TextView>(R.id.progressStatus)
            val meta = row.findViewById<TextView>(R.id.progressMeta)

            progress.isIndeterminate = false
            progress.progress = 0
            progress.visibility = View.GONE
            status.text = getString(R.string.state_waiting)
            meta.visibility = View.GONE

            pkgCards[item.token] = PkgCardViews(progress, status, meta)
            b.pkgList.addView(row)
        }
    }

    private fun resetPkgCardsForQueue() {
        pkgCards.values.forEach { refs ->
            refs.progress.isIndeterminate = false
            refs.progress.progress = 0
            refs.progress.visibility = View.GONE
            refs.status.text = getString(R.string.state_waiting)
            refs.meta.text = ""
            refs.meta.visibility = View.GONE
        }
    }

    private fun updatePkgCard(
        token: String,
        percent: Int?,
        statusText: String,
        detailText: String
    ) {
        val refs = pkgCards[token] ?: return

        val showProgress = percent != null && percent > 0

        if (percent != null) {
            if (showProgress) {
                refs.progress.visibility = View.VISIBLE
                refs.progress.isIndeterminate = false
                refs.progress.progress = percent.coerceIn(0, 100)
            } else {
                refs.progress.isIndeterminate = false
                refs.progress.progress = 0
                refs.progress.visibility = View.GONE
            }
        }

        if (statusText.isNotBlank()) refs.status.text = statusText

        val terminalState =
            statusText == getString(R.string.state_completed) ||
                statusText == getString(R.string.state_failed) ||
                statusText == getString(R.string.state_cancelled)

        if (detailText.isBlank() || (!showProgress && !terminalState)) {
            refs.meta.text = ""
            refs.meta.visibility = View.GONE
        } else {
            refs.meta.text = detailText
            refs.meta.visibility = View.VISIBLE
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

    private fun humanSize(n: Long): String {
        if (n < 1024) return "$n B"

        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = n.toDouble()
        var i = -1

        do {
            v /= 1024.0
            i++
        } while (v >= 1024 && i < units.lastIndex)

        return String.format(Locale.getDefault(), "%.1f %s", v, units[i])
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }
}
