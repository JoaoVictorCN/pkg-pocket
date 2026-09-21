package com.pkgpocket.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.DynamicColors
import com.google.android.material.snackbar.Snackbar
import com.pkgpocket.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val logLines = mutableListOf<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var demoJob: Job? = null
    private var lastBackPressedAt = 0L
    private var multiSelectMode = false
    private val selectedTokens = linkedSetOf<String>()
    private val completedCards = mutableMapOf<String, String>()

    private data class PkgCardViews(
        val progress: ProgressBar,
        val status: TextView,
        val meta: TextView,
        val checkBox: CheckBox
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

            val normalized = normalizeKinds(parsed)
            clearCompletedCards()
            PkgRepository.items = normalized
            persistSelection(normalized)
            exitMultiSelectMode()
            render(normalized)

            normalized.forEach { item ->
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

            val ready = getString(R.string.pkgs_ready, normalized.size)
            updateSelectionSummary(normalized)
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
            val finalSuccess = intent.getBooleanExtra(
                InstallerService.EXTRA_FINAL_SUCCESS,
                false
            )
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
            b.historyButton.isEnabled = !active && demoJob?.isActive != true
            b.helpButton.isEnabled = !active && demoJob?.isActive != true
            b.smartLibraryButton.isEnabled = !active && demoJob?.isActive != true
            b.diagnosticsButton.isEnabled = demoJob?.isActive != true
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

                if (itemStatus == getString(R.string.state_completed)) {
                    PkgRepository.items
                        .firstOrNull { it.token == token }
                        ?.let { item ->
                            completedCards[itemStableKey(item)] = itemDetail
                            persistCompletedCards()
                            InstallHistoryStore.record(this@MainActivity, item)
                        }
                }
            }

            if (!liveUpdate && text.isNotBlank()) addLog(text)

            if (!active && finalSuccess) {
                clearQueueAfterSuccessfulInstall(text)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        playLaunchAnimation()
        setupExitGuard()

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

        if (BuildConfig.ENABLE_DEMO) {
            b.appTitle.setOnLongClickListener {
                if (demoJob?.isActive == true) {
                    stopDemo()
                } else {
                    startDemo()
                }
                true
            }
        } else {
            b.appTitle.isLongClickable = false
        }

        b.selectPkgs.setOnClickListener {
            addLog(getString(R.string.opening_picker))
            pickPkgs.launch(arrayOf("application/octet-stream", "application/x-pkg", "*/*"))
        }

        b.clearSelection.setOnClickListener {
            clearSelection()
        }

        b.deleteSelected.setOnClickListener {
            deleteSelectedPkgs()
        }

        b.cancelMultiSelect.setOnClickListener {
            exitMultiSelectMode()
        }

        b.copyLog.setOnClickListener {
            copyLogToClipboard()
        }

        b.shareLog.setOnClickListener {
            shareLog()
        }

        b.historyButton.setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }

        b.helpButton.setOnClickListener {
            showFaq()
        }

        b.smartLibraryButton.setOnClickListener {
            showSmartLibrary()
        }

        b.diagnosticsButton.setOnClickListener {
            runDiagnostics()
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

            preflightInstall(ip)
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

        if (savedInstanceState == null) {
            resetTransientInstallerSession()
        }

        loadCompletedCards()

        if (PkgRepository.items.isNotEmpty()) {
            render(PkgRepository.items)
            updateSelectionSummary(PkgRepository.items)
        } else {
            showEmptyQueue()
        }

        addLog(getString(R.string.app_started))
    }

    private fun itemStableKey(item: PkgItem): String = item.uri.toString()

    private fun loadCompletedCards() {
        completedCards.clear()
    }

    private fun persistCompletedCards() {
        // Estado de progresso/conclusão é apenas da sessão atual.
    }

    private fun clearCompletedCards() {
        completedCards.clear()
        getSharedPreferences("pkg_pocket", MODE_PRIVATE)
            .edit()
            .remove("completed_pkg_cards")
            .apply()
    }

    private fun normalizeKinds(items: List<PkgItem>): List<PkgItem> {
        val extraGameTokens = mutableSetOf<String>()

        items
            .filter { it.titleId.isNotBlank() }
            .groupBy { it.titleId.uppercase(Locale.ROOT) }
            .values
            .forEach { group ->
                val gameLike = group.filter { it.category.equals("gd", ignoreCase = true) }

                if (gameLike.size > 1) {
                    val base = gameLike.maxByOrNull { it.size }

                    gameLike
                        .filter { it.token != base?.token }
                        .forEach { extra ->
                            extraGameTokens += extra.token
                        }
                }
            }

        return items.map { item ->
            if (item.token in extraGameTokens) {
                item.copy(kind = PkgKind.DLC)
            } else {
                item
            }
        }
    }

    private fun migrateCompletedCardsToHistory(items: List<PkgItem>) {
        items.forEach { item ->
            if (
                completedCards.containsKey(itemStableKey(item)) &&
                !InstallHistoryStore.contains(this, item)
            ) {
                InstallHistoryStore.record(this, item)
            }
        }
    }

    private fun persistSelection(items: List<PkgItem>) {
        // A fila é temporária. Nunca persistimos os PKGs selecionados.
        getSharedPreferences("pkg_pocket", MODE_PRIVATE)
            .edit()
            .remove("selected_pkg_uris")
            .apply()
    }

    private fun restorePersistedSelection() {
        // Compatibilidade com versões antigas: apaga qualquer fila salva.
        getSharedPreferences("pkg_pocket", MODE_PRIVATE)
            .edit()
            .remove("selected_pkg_uris")
            .apply()

        PkgRepository.items = emptyList()
        showEmptyQueue()
    }

    private fun resetTransientInstallerSession() {
        PkgRepository.items = emptyList()
        completedCards.clear()
        selectedTokens.clear()
        multiSelectMode = false

        getSharedPreferences("pkg_pocket", MODE_PRIVATE)
            .edit()
            .remove("selected_pkg_uris")
            .remove("completed_pkg_cards")
            .apply()
    }

    private fun showEmptyQueue() {
        b.pkgList.removeAllViews()
        pkgCards.clear()
        selectedTokens.clear()
        multiSelectMode = false

        b.selectionActions.visibility = View.GONE
        b.clearSelection.visibility = View.GONE
        b.status.visibility = View.VISIBLE
        b.status.text = getString(R.string.select_pkg_prompt)
        b.overallProgressInfo.visibility = View.GONE
        b.progress.visibility = View.GONE
        b.progress.progress = 0
        b.liveLog.text = getString(R.string.no_active_transfer)
    }

    private fun clearQueueAfterSuccessfulInstall(summary: String) {
        PkgRepository.items = emptyList()
        clearCompletedCards()
        selectedTokens.clear()
        multiSelectMode = false

        b.pkgList.removeAllViews()
        pkgCards.clear()
        b.selectionActions.visibility = View.GONE
        b.clearSelection.visibility = View.GONE
        b.cancelInstall.visibility = View.GONE
        b.installAll.isEnabled = true
        b.selectPkgs.isEnabled = true
        b.status.visibility = View.VISIBLE
        b.status.text = summary.ifBlank { getString(R.string.select_pkg_prompt) }
        b.liveLog.text = summary.ifBlank { getString(R.string.no_active_transfer) }
        b.overallProgressInfo.visibility = View.GONE
        b.progress.visibility = View.GONE
        b.progress.progress = 0

        persistSelection(emptyList())
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

        exitMultiSelectMode()
        clearCompletedCards()
        PkgRepository.items = emptyList()
        persistSelection(emptyList())
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

    private fun enterMultiSelectMode(firstToken: String) {
        if (demoJob?.isActive == true || !b.installAll.isEnabled) return

        multiSelectMode = true
        selectedTokens.clear()
        selectedTokens += firstToken
        refreshMultiSelectUi()
    }

    private fun toggleMultiSelectToken(token: String) {
        if (!multiSelectMode) return

        if (!selectedTokens.add(token)) {
            selectedTokens.remove(token)
        }

        if (selectedTokens.isEmpty()) {
            exitMultiSelectMode()
        } else {
            refreshMultiSelectUi()
        }
    }

    private fun refreshMultiSelectUi() {
        b.selectionActions.visibility = if (multiSelectMode) View.VISIBLE else View.GONE
        b.selectionCount.text = if (selectedTokens.size == 1) {
            getString(R.string.selected_count_one)
        } else {
            getString(
                R.string.selected_count,
                selectedTokens.size
            )
        }

        pkgCards.forEach { (token, refs) ->
            refs.checkBox.visibility = if (multiSelectMode) View.VISIBLE else View.GONE
            refs.checkBox.isChecked = token in selectedTokens
        }

        if (multiSelectMode) {
            b.selectPkgs.isEnabled = false
            b.installAll.isEnabled = false
            b.detectPs4.isEnabled = false
            b.clearSelection.isEnabled = false
            b.historyButton.isEnabled = false
            b.helpButton.isEnabled = false
            b.smartLibraryButton.isEnabled = false
        }
    }

    private fun exitMultiSelectMode() {
        multiSelectMode = false
        selectedTokens.clear()
        b.selectionActions.visibility = View.GONE

        pkgCards.values.forEach { refs ->
            refs.checkBox.isChecked = false
            refs.checkBox.visibility = View.GONE
        }

        if (demoJob?.isActive != true) {
            b.selectPkgs.isEnabled = true
            b.installAll.isEnabled = true
            b.detectPs4.isEnabled = true
            b.clearSelection.isEnabled = PkgRepository.items.isNotEmpty()
            b.historyButton.isEnabled = true
            b.helpButton.isEnabled = true
            b.smartLibraryButton.isEnabled = true
        }
    }

    private fun deleteSelectedPkgs() {
        if (!multiSelectMode || selectedTokens.isEmpty()) return

        val tokens = selectedTokens.toSet()
        val count = tokens.size
        exitMultiSelectMode()

        removeTokensWithUndo(
            tokens,
            getString(R.string.pkgs_removed, count)
        )
    }

    private fun removeTokensWithUndo(
        tokens: Set<String>,
        message: String
    ) {
        if (tokens.isEmpty()) return

        if (multiSelectMode) {
            exitMultiSelectMode()
        } else {
            selectedTokens.removeAll(tokens)
        }

        val before = PkgRepository.items
        val updated = before.filterNot { it.token in tokens }
        if (updated.size == before.size) return

        PkgRepository.items = updated
        persistSelection(updated)
        render(updated)
        updateSelectionSummary(updated)
        addLog(message)

        Snackbar.make(
            b.root,
            message,
            Snackbar.LENGTH_LONG
        )
            .setAction(R.string.undo) {
                PkgRepository.items = before
                persistSelection(before)
                render(before)
                updateSelectionSummary(before)
                addLog(getString(R.string.removal_undone))
            }
            .show()
    }

    private fun buildLogText(): String {
        val events = if (logLines.isEmpty()) {
            getString(R.string.waiting_events)
        } else {
            logLines.joinToString("\n")
        }

        return buildString {
            append(getString(R.string.app_name))
            append(" v")
            append(BuildConfig.VERSION_NAME)
            append("\n\n")
            append(getString(R.string.current_transfer))
            append(": ")
            append(b.liveLog.text?.toString().orEmpty())
            append("\n\n")
            append(events)
        }
    }

    private fun copyLogToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                getString(R.string.log_clipboard_label),
                buildLogText()
            )
        )
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareLog() {
        val share = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_share_subject))
            .putExtra(Intent.EXTRA_TEXT, buildLogText())

        startActivity(
            Intent.createChooser(
                share,
                getString(R.string.share_log)
            )
        )
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
        if (!enabled) {
            exitMultiSelectMode()
        }

        b.selectPkgs.isEnabled = enabled
        b.installAll.isEnabled = enabled
        b.detectPs4.isEnabled = enabled
        b.clearSelection.isEnabled = enabled && PkgRepository.items.isNotEmpty()
        b.historyButton.isEnabled = enabled
        b.helpButton.isEnabled = enabled
        b.smartLibraryButton.isEnabled = enabled
        b.diagnosticsButton.isEnabled = enabled
    }

    private fun setupExitGuard() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val now = SystemClock.elapsedRealtime()
                    val activeTransfer = !b.installAll.isEnabled

                    if (now - lastBackPressedAt <= 2_000L) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        return
                    }

                    lastBackPressedAt = now

                    Toast.makeText(
                        this@MainActivity,
                        if (activeTransfer) {
                            R.string.press_back_again_active
                        } else {
                            R.string.press_back_again_to_exit
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun preflightInstall(ip: String) {
        val analysis = SmartLibraryAnalyzer.analyze(PkgRepository.items)

        if (analysis.importantWarnings.isEmpty()) {
            startInstallation(ip)
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.preflight_title)
            .setMessage(buildSmartIssues(analysis))
            .setNegativeButton(R.string.cancel_selection, null)
            .setNeutralButton(R.string.optimize_queue) { _, _ ->
                applyOptimizedQueue()
            }
            .setPositiveButton(R.string.install_anyway) { _, _ ->
                startInstallation(ip)
            }
            .show()
    }

    private fun startInstallation(ip: String) {
        clearCompletedCards()
        resetPkgCardsForQueue()

        PkgRepository.items
            .sortedWith(
                compareBy<PkgItem>({ it.titleId }, { it.kind.order }, { it.fileName })
            )
            .firstOrNull()
            ?.let { first ->
                updatePkgCard(
                    first.token,
                    null,
                    getString(R.string.state_preparing),
                    ""
                )
            }

        b.cancelInstall.visibility = View.VISIBLE
        b.cancelInstall.isEnabled = true
        b.installAll.isEnabled = false
        b.progress.progress = 0
        b.overallProgressInfo.text = getString(R.string.overall_idle)
        b.status.visibility = View.VISIBLE
        b.overallProgressInfo.visibility = View.GONE
        b.progress.visibility = View.GONE

        val rpiHint = if (PkgRepository.items.size > 1) {
            getString(R.string.keep_rpi_open_queue)
        } else {
            getString(R.string.keep_rpi_open_single)
        }

        b.status.text = rpiHint
        b.liveLog.text = rpiHint
        addLog(rpiHint)
        Toast.makeText(this, rpiHint, Toast.LENGTH_LONG).show()

        ActiveTransferQueue.set(PkgRepository.items)

        addLog(getString(R.string.starting_queue, PkgRepository.items.size, ip))
        ensureService(InstallerService.ACTION_INSTALL_ALL, ip)
    }

    private fun showSmartLibrary() {
        if (PkgRepository.items.isEmpty()) {
            Toast.makeText(this, R.string.select_pkgs_first, Toast.LENGTH_SHORT).show()
            return
        }

        val analysis = SmartLibraryAnalyzer.analyze(PkgRepository.items)
        val totalSize = PkgRepository.items.sumOf { it.size.coerceAtLeast(0L) }

        val message = buildString {
            append(
                getString(
                    R.string.smart_summary,
                    analysis.groups.size,
                    PkgRepository.items.size,
                    humanSize(totalSize)
                )
            )

            analysis.groups.forEach { group ->
                val installed = group.items.count {
                    InstallHistoryStore.contains(this@MainActivity, it)
                }

                append("\n\n")
                append(group.title)
                append("\n")
                append(
                    getString(
                        R.string.smart_group_line,
                        group.titleId.ifBlank { "—" },
                        group.games.size,
                        group.updates.size,
                        group.dlcs.size,
                        humanSize(group.totalSize)
                    )
                )

                if (installed > 0) {
                    append("\n")
                    append(
                        getString(
                            R.string.smart_installed_count,
                            installed,
                            group.items.size
                        )
                    )
                }

                val updateVersions = group.updates
                    .map { it.version }
                    .filter { it.isNotBlank() }
                    .distinct()

                if (updateVersions.isNotEmpty()) {
                    append("\n")
                    append(
                        getString(
                            R.string.smart_updates_versions,
                            updateVersions.joinToString(", ")
                        )
                    )
                }
            }

            append("\n\n")
            append(buildSmartIssues(analysis))
            append("\n\n")
            append(getString(R.string.smart_history_notice))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.smart_library)
            .setMessage(message)
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.only_missing) { _, _ ->
                filterInstalledFromQueue()
            }
            .setPositiveButton(R.string.optimize_queue) { _, _ ->
                applyOptimizedQueue()
            }
            .show()
    }

    private fun buildSmartIssues(analysis: SmartAnalysis): String {
        val lines = mutableListOf<String>()

        if (analysis.duplicateTokens.isNotEmpty()) {
            lines += getString(
                R.string.issue_duplicates,
                analysis.duplicateTokens.size
            )
        }

        if (analysis.olderUpdateTokens.isNotEmpty()) {
            lines += getString(
                R.string.issue_old_updates,
                analysis.olderUpdateTokens.size
            )
        }

        analysis.importantWarnings
            .filter { it.startsWith("MULTIPLE_BASES|") }
            .forEach { raw ->
                val parts = raw.split('|')
                lines += getString(
                    R.string.issue_multiple_bases,
                    parts.getOrNull(1).orEmpty(),
                    parts.getOrNull(2).orEmpty()
                )
            }

        analysis.notices.forEach { raw ->
            val parts = raw.split('|')
            when (parts.firstOrNull()) {
                "UPDATE_WITHOUT_BASE" -> lines += getString(
                    R.string.issue_update_without_base,
                    parts.getOrNull(1).orEmpty()
                )

                "DLC_WITHOUT_BASE" -> lines += getString(
                    R.string.issue_dlc_without_base,
                    parts.getOrNull(1).orEmpty()
                )
            }
        }

        return if (lines.isEmpty()) {
            getString(R.string.smart_no_issues)
        } else {
            getString(R.string.smart_issues_header) +
                "\n• " +
                lines.joinToString("\n• ")
        }
    }

    private fun applyOptimizedQueue() {
        val before = PkgRepository.items
        val optimized = SmartLibraryAnalyzer.optimize(before)

        if (optimized.size == before.size) {
            Toast.makeText(this, R.string.nothing_to_optimize, Toast.LENGTH_SHORT).show()
            return
        }

        PkgRepository.items = optimized
        persistSelection(optimized)
        render(optimized)
        updateSelectionSummary(optimized)

        Toast.makeText(
            this,
            getString(R.string.queue_optimized, before.size - optimized.size),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun filterInstalledFromQueue() {
        val before = PkgRepository.items
        val missing = before.filterNot {
            InstallHistoryStore.contains(this, it)
        }

        if (missing.size == before.size) {
            Toast.makeText(this, R.string.no_history_matches, Toast.LENGTH_SHORT).show()
            return
        }

        PkgRepository.items = missing
        persistSelection(missing)
        render(missing)
        updateSelectionSummary(missing)

        Toast.makeText(
            this,
            getString(R.string.history_filtered, before.size - missing.size),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun runDiagnostics() {
        lifecycleScope.launch {
            val ip = b.ps4Ip.text?.toString()?.trim().orEmpty()

            val localIp = withContext(Dispatchers.IO) {
                NetworkUtils.localIpv4()
            }

            val reachable = if (ip.isBlank()) {
                false
            } else {
                withContext(Dispatchers.IO) {
                    NetworkUtils.canConnect(ip)
                }
            }

            val analysis = SmartLibraryAnalyzer.analyze(PkgRepository.items)
            val warningCount =
                analysis.importantWarnings.size +
                    analysis.notices.size

            val message = buildString {
                append(
                    getString(
                        R.string.diag_phone_ip,
                        localIp ?: getString(R.string.diag_unavailable)
                    )
                )
                append("\n")
                append(
                    getString(
                        R.string.diag_ps4_ip,
                        ip.ifBlank { getString(R.string.diag_not_set) }
                    )
                )
                append("\n")
                append(
                    getString(
                        R.string.diag_rpi,
                        if (reachable) {
                            getString(R.string.diag_ok)
                        } else {
                            getString(R.string.diag_failed)
                        }
                    )
                )
                append("\n")
                append(
                    getString(
                        R.string.diag_queue,
                        PkgRepository.items.size,
                        warningCount
                    )
                )
                append("\n\n")
                append(
                    when {
                        ip.isBlank() -> getString(R.string.diag_tip_ip)
                        !reachable -> getString(R.string.diag_tip_rpi)
                        warningCount > 0 -> getString(R.string.diag_tip_smart)
                        else -> getString(R.string.diag_all_good)
                    }
                )
            }

            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.diagnostics)
                .setMessage(message)
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    private fun playLaunchAnimation() {
        val logo = b.splashLogo
        val title = b.splashTitle
        val tagline = b.splashTagline
        val overlay = b.splashOverlay
        val enter = android.view.animation.DecelerateInterpolator(1.6f)
        val exit = android.view.animation.AccelerateDecelerateInterpolator()

        overlay.visibility = View.VISIBLE
        overlay.alpha = 1f

        logo.alpha = 0f
        logo.scaleX = 0.90f
        logo.scaleY = 0.90f
        logo.translationY = 22f

        title.alpha = 0f
        title.translationY = 14f

        tagline.alpha = 0f
        tagline.translationY = 8f

        overlay.post {
            logo.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setInterpolator(enter)
                .setDuration(820L)
                .start()

            title.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(420L)
                .setInterpolator(enter)
                .setDuration(620L)
                .start()

            tagline.animate()
                .alpha(0.82f)
                .translationY(0f)
                .setStartDelay(720L)
                .setInterpolator(enter)
                .setDuration(620L)
                .start()

            overlay.animate()
                .alpha(0f)
                .setStartDelay(1850L)
                .setInterpolator(exit)
                .setDuration(520L)
                .withEndAction {
                    overlay.visibility = View.GONE
                    overlay.alpha = 1f
                }
                .start()
        }
    }

    private fun showInstallHistory() {
        val records = InstallHistoryStore.all(this)
        val dateFormat = DateFormat.getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT
        )

        val message = if (records.isEmpty()) {
            getString(R.string.history_empty)
        } else {
            records.joinToString("\n\n") { record ->
                val versionText = if (record.version.isBlank()) {
                    getString(R.string.history_no_version)
                } else {
                    getString(R.string.history_version, record.version)
                }

                val kind = runCatching {
                    PkgKind.valueOf(record.kind)
                }.getOrDefault(PkgKind.OTHER)

                getString(
                    R.string.history_item,
                    record.title.ifBlank { record.fileName },
                    kindLabel(kind),
                    versionText,
                    record.titleId.ifBlank { "—" },
                    dateFormat.format(Date(record.installedAt))
                )
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.install_history)
            .setMessage(message)
            .setPositiveButton(R.string.close, null)

        if (records.isNotEmpty()) {
            dialog.setNeutralButton(R.string.clear_history) { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(R.string.clear_history)
                    .setMessage(R.string.clear_history_confirm)
                    .setNegativeButton(R.string.cancel_selection, null)
                    .setPositiveButton(R.string.clear_history) { _, _ ->
                        InstallHistoryStore.clear(this)
                        render(PkgRepository.items)
                        updateSelectionSummary(PkgRepository.items)
                        Toast.makeText(
                            this,
                            R.string.history_cleared,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .show()
            }
        }

        dialog.show()
    }

    private fun showFaq() {
        AlertDialog.Builder(this)
            .setTitle(R.string.faq_title)
            .setMessage(R.string.faq_body)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.send_feedback) { _, _ ->
                openFeedback()
            }
            .show()
    }

    private fun openFeedback() {
        val body = buildString {
            append("PKG Pocket: ")
            append(BuildConfig.VERSION_NAME)
            append("\nAndroid: ")
            append(Build.VERSION.RELEASE)
            append(" (SDK ")
            append(Build.VERSION.SDK_INT)
            append(")\nDevice: ")
            append(Build.MANUFACTURER)
            append(" ")
            append(Build.MODEL)
            append("\n\nType: Bug / Feature request / Change\n\nMessage:\n")
        }

        val uri = Uri.parse(
            "https://github.com/VkctorC/pkg-pocket/issues/new"
        ).buildUpon()
            .appendQueryParameter("title", "[Feedback] ")
            .appendQueryParameter("body", body)
            .build()

        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.onFailure {
            Toast.makeText(
                this,
                R.string.feedback_open_failed,
                Toast.LENGTH_LONG
            ).show()
        }
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
            iv.tag = item.token

            val embeddedIcon = item.icon
            val sameTitleFallback = if (embeddedIcon == null && item.titleId.isNotBlank()) {
                sorted.firstOrNull {
                    it.token != item.token &&
                        it.titleId.equals(item.titleId, ignoreCase = true) &&
                        it.icon != null
                }?.icon
            } else {
                null
            }

            val immediateIcon = embeddedIcon ?: sameTitleFallback
            immediateIcon?.let { bytes ->
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                    .getOrNull()
                    ?.let(iv::setImageBitmap)
            }

            if (embeddedIcon == null) {
                lifecycleScope.launch {
                    val onlineBytes = withContext(Dispatchers.IO) {
                        CoverResolver.resolve(
                            context = this@MainActivity,
                            item = item,
                            allowTitleFallback = sameTitleFallback == null
                        )
                    }

                    if (
                        onlineBytes != null &&
                        iv.tag == item.token &&
                        iv.isAttachedToWindow
                    ) {
                        runCatching {
                            BitmapFactory.decodeByteArray(
                                onlineBytes,
                                0,
                                onlineBytes.size
                            )
                        }
                            .getOrNull()
                            ?.let(iv::setImageBitmap)
                    }
                }
            }

            val progress = row.findViewById<ProgressBar>(R.id.itemProgress)
            val status = row.findViewById<TextView>(R.id.progressStatus)
            val meta = row.findViewById<TextView>(R.id.progressMeta)
            val checkBox = row.findViewById<CheckBox>(R.id.selectionCheck)

            checkBox.isClickable = false
            checkBox.isFocusable = false
            checkBox.visibility = if (multiSelectMode) View.VISIBLE else View.GONE
            checkBox.isChecked = item.token in selectedTokens

            val completedDetail = completedCards[itemStableKey(item)]

            progress.isIndeterminate = false
            if (completedDetail != null) {
                progress.progress = 100
                progress.visibility = View.VISIBLE
                status.text = getString(R.string.state_completed)
                meta.text = completedDetail
                meta.visibility = if (completedDetail.isBlank()) View.GONE else View.VISIBLE
            } else {
                progress.progress = 0
                progress.visibility = View.GONE
                status.text = if (InstallHistoryStore.contains(this, item)) {
                    getString(R.string.state_installed_before)
                } else {
                    getString(R.string.state_waiting)
                }
                meta.text = ""
                meta.visibility = View.GONE
            }

            pkgCards[item.token] = PkgCardViews(progress, status, meta, checkBox)

            row.setOnLongClickListener {
                if (demoJob?.isActive == true || !b.installAll.isEnabled) {
                    false
                } else {
                    enterMultiSelectMode(item.token)
                    true
                }
            }

            row.setOnClickListener {
                if (multiSelectMode) {
                    toggleMultiSelectToken(item.token)
                }
            }

            attachSwipeToDelete(row, item)
            b.pkgList.addView(row)
        }
    }

    private fun attachSwipeToDelete(row: View, item: PkgItem) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var swiping = false

        row.setOnTouchListener { view, event ->
            // Durante envio real ou demo, a lista fica congelada.
            if (!b.installAll.isEnabled || demoJob?.isActive == true || multiSelectMode) {
                view.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(120)
                    .start()
                return@setOnTouchListener false
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    swiping = false
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY

                    if (!swiping &&
                        kotlin.math.abs(dx) > touchSlop &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy)
                    ) {
                        swiping = true
                        view.cancelLongPress()
                        view.isPressed = false
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }

                    if (swiping) {
                        view.translationX = dx
                        val fade = 1f - (
                            kotlin.math.abs(dx) /
                                (view.width.coerceAtLeast(1) * 1.35f)
                            ).coerceIn(0f, 0.55f)
                        view.alpha = fade
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (!swiping) return@setOnTouchListener false

                    view.parent?.requestDisallowInterceptTouchEvent(false)

                    val dx = event.rawX - downX
                    val threshold = maxOf(
                        view.width * 0.28f,
                        96f * resources.displayMetrics.density
                    )

                    if (kotlin.math.abs(dx) >= threshold) {
                        removePkgWithSwipe(
                            row = view,
                            item = item,
                            direction = if (dx >= 0f) 1f else -1f
                        )
                    } else {
                        view.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(160)
                            .start()
                    }

                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    view.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(160)
                        .start()
                    swiping
                }

                else -> false
            }
        }
    }

    private fun removePkgWithSwipe(
        row: View,
        item: PkgItem,
        direction: Float
    ) {
        if (!b.installAll.isEnabled || demoJob?.isActive == true || multiSelectMode) {
            row.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(120)
                .start()
            return
        }

        if (PkgRepository.items.none { it.token == item.token }) {
            row.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(120)
                .start()
            return
        }

        row.cancelLongPress()
        row.isPressed = false

        val distance = (row.width.coerceAtLeast(1) * 1.15f) * direction

        row.animate()
            .translationX(distance)
            .alpha(0f)
            .setDuration(170)
            .withEndAction {
                removeTokensWithUndo(
                    setOf(item.token),
                    getString(R.string.pkg_removed, item.title)
                )
            }
            .start()
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
        if (isFinishing) {
            PkgRepository.items = emptyList()
            completedCards.clear()
            selectedTokens.clear()

            getSharedPreferences("pkg_pocket", MODE_PRIVATE)
                .edit()
                .remove("selected_pkg_uris")
                .remove("completed_pkg_cards")
                .apply()
        }

        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }
}
