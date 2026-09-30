package com.pkgpocket.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Patterns
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.pkgpocket.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class SettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding
    private var proJob: Job? = null
    private var updateJob: Job? = null
    private var lastProSyncElapsed = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(b.root)

        b.settingsBack.setOnClickListener { finish() }

        b.settingsUpdate.setOnClickListener {
            checkForAppUpdate()
        }

        b.settingsClearCache.setOnClickListener {
            runCatching { cacheDir.deleteRecursively() }
            runCatching { LibraryCoverStore.clear(this) }
            Toast.makeText(
                this,
                R.string.settings_cache_cleared,
                Toast.LENGTH_SHORT
            ).show()
        }

        b.settingsDiagnostics.setOnClickListener {
            showAppDiagnostics()
        }

        b.settingsRpiPort.setOnClickListener {
            showRpiPortDialog()
        }

        val prefs = getSharedPreferences("pkg_pocket", MODE_PRIVATE)

        b.settingsFaq.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.faq_title)
                .setMessage(R.string.faq_body)
                .setPositiveButton(R.string.close, null)
                .show()
        }

        b.settingsLogs.setOnClickListener {
            val logs = prefs
                .getString("last_log", "")
                .orEmpty()
                .ifBlank {
                    getString(R.string.settings_log_empty)
                }

            val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.view_log)
                .setMessage(logs)
                .setNeutralButton(
                    R.string.share_log,
                    null
                )
                .setNegativeButton(
                    R.string.copy_log,
                    null
                )
                .setPositiveButton(
                    R.string.close,
                    null
                )
                .create()

            dialog.setOnShowListener {
                dialog.getButton(
                    AlertDialog.BUTTON_NEUTRAL
                ).setOnClickListener {
                    val allLines = logs.lines()

                    // Snapshots do RPI são prioridade absoluta.
                    val snapshots = allLines.filter { line ->
                        line.contains(
                            "RPI SNAPSHOT",
                            ignoreCase = true
                        )
                    }

                    // Eventos de controle/recovery da instalação.
                    val rpiEvents = allLines.filter { line ->
                        line.contains(
                            "status query",
                            ignoreCase = true
                        ) ||
                        line.contains(
                            "RPI did not",
                            ignoreCase = true
                        ) ||
                        line.contains(
                            "RPI is responding",
                            ignoreCase = true
                        ) ||
                        line.contains(
                            "Task ",
                            ignoreCase = true
                        ) ||
                        line.contains(
                            "Queue paused",
                            ignoreCase = true
                        ) ||
                        line.contains(
                            "reconectar ao RPI",
                            ignoreCase = true
                        ) ||
                        line.contains(
                            "RPI status",
                            ignoreCase = true
                        )
                    }.takeLast(30)

                    // Não deixa milhares de Broken pipe engolirem
                    // o diagnóstico realmente importante.
                    val httpFailures = allLines.filter { line ->
                        line.contains("HTTP #") &&
                        (
                            line.contains("FALHOU") ||
                            line.contains("INTERROMPIDO") ||
                            line.contains("ÚLTIMO RANGE")
                        )
                    }.takeLast(25)

                    val terminalEvents = allLines.filter { line ->
                        line.contains(
                            "cancelada",
                            ignoreCase = true
                        ) ||
                        line.contains(
                            "erro",
                            ignoreCase = true
                        ) ||
                        line.contains(
                            "error",
                            ignoreCase = true
                        )
                    }.takeLast(20)

                    val diagnosticLogs = buildString {
                        appendLine("=== PKG POCKET DIAGNÓSTICO ===")
                        appendLine()

                        appendLine("=== RPI SNAPSHOTS ===")
                        if (snapshots.isEmpty()) {
                            appendLine("Nenhum snapshot RPI encontrado.")
                        } else {
                            snapshots.takeLast(20).forEach {
                                appendLine(it)
                            }
                        }

                        appendLine()
                        appendLine("=== EVENTOS RPI ===")
                        if (rpiEvents.isEmpty()) {
                            appendLine("Nenhum evento RPI encontrado.")
                        } else {
                            rpiEvents.forEach {
                                appendLine(it)
                            }
                        }

                        appendLine()
                        appendLine("=== ÚLTIMAS FALHAS HTTP ===")
                        if (httpFailures.isEmpty()) {
                            appendLine("Nenhuma falha HTTP encontrada.")
                        } else {
                            httpFailures.forEach {
                                appendLine(it)
                            }
                        }

                        appendLine()
                        appendLine("=== EVENTOS TERMINAIS ===")
                        if (terminalEvents.isEmpty()) {
                            appendLine("Nenhum evento terminal encontrado.")
                        } else {
                            terminalEvents.forEach {
                                appendLine(it)
                            }
                        }
                    }.takeLast(20_000)

                    val share = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(
                            Intent.EXTRA_SUBJECT,
                            getString(R.string.log_share_subject)
                        )
                        .putExtra(
                            Intent.EXTRA_TEXT,
                            diagnosticLogs
                        )

                    startActivity(
                        Intent.createChooser(
                            share,
                            getString(R.string.share_log)
                        )
                    )
                }

                dialog.getButton(
                    AlertDialog.BUTTON_NEGATIVE
                ).setOnClickListener {
                    val clipboard =
                        getSystemService(
                            android.content.Context.CLIPBOARD_SERVICE
                        ) as android.content.ClipboardManager

                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            getString(R.string.log_clipboard_label),
                            logs
                        )
                    )

                    Toast.makeText(
                        this,
                        R.string.log_copied,
                        Toast.LENGTH_SHORT
                    ).show()
                }

                // Garante que o diálogo abra no COMEÇO do log.
                val messageView =
                    dialog.findViewById<android.widget.TextView>(
                        android.R.id.message
                    )

                messageView?.setOnLongClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Limpar log?")
                        .setMessage(
                            "Todo o histórico do log será apagado."
                        )
                        .setNegativeButton(
                            android.R.string.cancel,
                            null
                        )
                        .setPositiveButton("Limpar") { _, _ ->
                            prefs.edit()
                                .remove("last_log")
                                .apply()

                            messageView.text =
                                getString(
                                    R.string.settings_log_empty
                                )

                            Toast.makeText(
                                this,
                                "Log limpo",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .show()

                    true
                }

                messageView?.let { textView ->
                    val parent = textView.parent

                    if (parent is android.widget.ScrollView) {
                        parent.post {
                            parent.scrollTo(0, 0)
                        }
                    }
                }
            }

            dialog.show()
        }

        b.settingsVersion.text = getString(
            R.string.settings_about_version,
            BuildConfig.VERSION_NAME
        )

        setupPro()
        handleCheckoutIntent(intent)
    }

    private fun showRpiPortDialog() {
        val prefs = getSharedPreferences(
            "pkg_pocket",
            MODE_PRIVATE
        )

        val currentPort = prefs.getInt(
            "rpi_port",
            12800
        )

        val input = EditText(this).apply {
            inputType =
                android.text.InputType.TYPE_CLASS_NUMBER
            setText(currentPort.toString())
            selectAll()
            setPadding(48, 20, 48, 8)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_rpi_port)
            .setMessage(
                R.string.settings_rpi_port_message
            )
            .setView(input)
            .setNegativeButton(
                android.R.string.cancel,
                null
            )
            .setNeutralButton(
                R.string.settings_rpi_port_default
            ) { _, _ ->

                prefs.edit()
                    .putInt("rpi_port", 12800)
                    .apply()

                RpiClient.setPort(12800)

                Toast.makeText(
                    this,
                    getString(
                        R.string.settings_rpi_port_saved,
                        12800
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setPositiveButton(
                android.R.string.ok,
                null
            )
            .create()

        dialog.setOnShowListener {
            dialog.getButton(
                AlertDialog.BUTTON_POSITIVE
            ).setOnClickListener {

                val port = input.text
                    ?.toString()
                    ?.trim()
                    ?.toIntOrNull()

                if (
                    port == null ||
                    port !in 1..65535
                ) {
                    input.error = getString(
                        R.string.settings_rpi_port_invalid
                    )
                    return@setOnClickListener
                }

                prefs.edit()
                    .putInt("rpi_port", port)
                    .apply()

                RpiClient.setPort(port)

                Toast.makeText(
                    this,
                    getString(
                        R.string.settings_rpi_port_saved,
                        port
                    ),
                    Toast.LENGTH_SHORT
                ).show()

                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun checkForAppUpdate() {
        if (updateJob?.isActive == true) return

        updateJob = lifecycleScope.launch {
            b.settingsUpdate.isEnabled = false
            b.settingsUpdate.text = getString(R.string.update_checking)

            try {
                val release = UpdateManager.findAvailableUpdate(
                    this@SettingsActivity
                )

                if (release == null) {
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.update_latest,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.update_found, release.version),
                    Toast.LENGTH_LONG
                ).show()

                val progress = android.widget.ProgressBar(
                    this@SettingsActivity,
                    null,
                    android.R.attr.progressBarStyleHorizontal
                ).apply {
                    max = 100
                    progress = 0
                    isIndeterminate = false
                    setPadding(28, 20, 28, 20)
                }

                val progressDialog = AlertDialog.Builder(
                    this@SettingsActivity
                )
                    .setTitle(R.string.update_download_title)
                    .setMessage(
                        getString(
                            R.string.update_downloading,
                            release.version,
                            0
                        )
                    )
                    .setView(progress)
                    .setCancelable(false)
                    .create()

                progressDialog.show()

                val apk = try {
                    UpdateManager.download(
                        this@SettingsActivity,
                        release
                    ) { percent ->
                        runOnUiThread {
                            progress.progress = percent
                            progressDialog.setMessage(
                                getString(
                                    R.string.update_downloading,
                                    release.version,
                                    percent
                                )
                            )
                        }
                    }
                } finally {
                    if (progressDialog.isShowing) {
                        progressDialog.dismiss()
                    }
                }

                Toast.makeText(
                    this@SettingsActivity,
                    R.string.update_installing,
                    Toast.LENGTH_SHORT
                ).show()

                UpdateManager.requestInstall(
                    this@SettingsActivity,
                    apk
                )
            } catch (e: Exception) {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.settings_update_title)
                    .setMessage(
                        getString(
                            R.string.update_network_error,
                            e.message ?: getString(R.string.unknown_error)
                        )
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } finally {
                b.settingsUpdate.isEnabled = true
                b.settingsUpdate.text =
                    getString(R.string.settings_update_title)
            }
        }
    }

    private fun showAppDiagnostics() {
        val notificationsAllowed =
            if (Build.VERSION.SDK_INT < 33) {
                true
            } else {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            }

        val pm = getSystemService(PowerManager::class.java)
        val unrestricted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                pm.isIgnoringBatteryOptimizations(packageName)

        val message = getString(
            R.string.settings_app_diag_body,
            BuildConfig.VERSION_NAME,
            getString(
                if (notificationsAllowed) {
                    R.string.settings_allowed
                } else {
                    R.string.settings_blocked
                }
            ),
            getString(
                if (unrestricted) {
                    R.string.settings_unrestricted
                } else {
                    R.string.settings_optimized
                }
            ),
            getString(
                if (ProManager.isProCached(this)) {
                    R.string.pro_status_active
                } else {
                    R.string.pro_status_free
                }
            )
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_app_diagnostics)
            .setMessage(message)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun setupPro() {
        b.settingsProPrice.text = "…"

        lifecycleScope.launch {
            runCatching {
                ProManager.getQuote(this@SettingsActivity)
            }.onSuccess { quote ->
                b.settingsProPrice.text =
                    ProManager.formatPrice(
                        quote.currency,
                        quote.amountMinor
                    )
            }
        }

        val email = ProManager.savedEmail(this)
        if (email.isNotBlank()) b.settingsProEmail.setText(email)

        val pendingPurchase =
            ProManager.savedPurchaseId(this)

        if (pendingPurchase.isNotBlank()) {
            renderProPendingState()
        } else {
            renderProState(
                ProManager.isProCached(this),
                null
            )
        }

        b.settingsProBuy.setOnClickListener {
            startCheckout()
        }
        b.settingsProCheck.setOnClickListener {
            refreshPro(true)
        }

        if (pendingPurchase.isNotBlank()) {
            lastProSyncElapsed =
                SystemClock.elapsedRealtime()
            reconcilePurchase(pendingPurchase)
        } else if (email.isNotBlank()) {
            lastProSyncElapsed =
                SystemClock.elapsedRealtime()
            refreshPro(false)
        }
    }

    private fun startCheckout() {
        val email = b.settingsProEmail.text?.toString()?.trim().orEmpty()

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            b.settingsProEmailLayout.error =
                getString(R.string.pro_invalid_email)
            return
        }

        b.settingsProEmailLayout.error = null
        b.settingsProBuy.isEnabled = false
        b.settingsProCheck.isEnabled = false
        b.settingsProStatus.text =
            getString(R.string.pro_preparing_checkout)

        proJob?.cancel()
        proJob = lifecycleScope.launch {
            try {
                val checkout =
                    ProManager.createCheckout(this@SettingsActivity, email)

                b.settingsProPrice.text =
                    ProManager.formatPrice(
                        checkout.currency,
                        checkout.amountMinor
                    )

                b.settingsProStatus.text =
                    getString(
                        when (checkout.provider.lowercase()) {
                            "mercadopago" ->
                                R.string.pro_opening_mercadopago
                            "stripe" ->
                                R.string.pro_opening_stripe
                            else ->
                                R.string.pro_opening_checkout
                        }
                    )

                val checkoutIntent =
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(
                            checkout.checkoutUrl
                        )
                    )

                startActivity(
                    Intent.createChooser(
                        checkoutIntent,
                        getString(
                            R.string.pro_checkout_choose_app
                        )
                    )
                )
            } catch (e: Exception) {
                renderProState(
                    ProManager.isProCached(this@SettingsActivity),
                    getString(
                        R.string.pro_checkout_error,
                        e.message ?: getString(R.string.unknown_error)
                    )
                )
            } finally {
                val pending =
                    ProManager.savedPurchaseId(
                        this@SettingsActivity
                    )

                if (pending.isNotBlank()) {
                    renderProPendingState()
                } else if (
                    !ProManager.isProCached(
                        this@SettingsActivity
                    )
                ) {
                    b.settingsProBuy.isEnabled = true
                    b.settingsProCheck.isEnabled = true
                    b.settingsProEmailLayout.isEnabled = true
                }
            }
        }
    }

    private fun refreshPro(showFeedback: Boolean) {
        val email = b.settingsProEmail.text
            ?.toString()
            ?.trim()
            .orEmpty()
            .ifBlank { ProManager.savedEmail(this) }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            if (showFeedback) {
                b.settingsProEmailLayout.error =
                    getString(R.string.pro_invalid_email)
            }
            return
        }

        b.settingsProEmailLayout.error = null
        proJob?.cancel()

        proJob = lifecycleScope.launch {
            b.settingsProStatus.text =
                getString(R.string.pro_status_checking)
            b.settingsProCheck.isEnabled = false

            try {
                // 1) PRIMEIRO valida exclusivamente a licença.
                val status = ProManager.refreshStatus(
                    this@SettingsActivity,
                    email,
                    force = showFeedback
                )

                val licenseMessage =
                    if (showFeedback && !status.active) {
                        when (status.status.lowercase()) {
                            "pending",
                            "in_process",
                            "in_mediation",
                            "waiting_payment" ->
                                getString(R.string.pro_status_pending)

                            "device_mismatch" ->
                                getString(
                                    R.string.pro_status_device_mismatch
                                )

                            "device_unbound" ->
                                getString(
                                    R.string.pro_status_device_unbound
                                )

                            "offline_expired" ->
                                getString(
                                    R.string.pro_status_offline_expired
                                )

                            "revoked",
                            "refunded",
                            "charged_back",
                            "cancelled",
                            "canceled" ->
                                getString(R.string.pro_status_revoked)

                            else ->
                                getString(R.string.pro_status_not_found)
                        }
                    } else {
                        null
                    }

                // A partir daqui, se active=true, a UI fica Pro ativa
                // independentemente de qualquer falha de nuvem.
                renderProState(
                    status.active,
                    licenseMessage
                )

                if (!status.active) {
                    return@launch
                }

                // 2) Backup/restauração é secundário.
                // Nunca altera o estado da licença em caso de falha.
                try {
                    val sync =
                        LibrarySyncManager.restoreAndMerge(
                            this@SettingsActivity
                        )

                    if (showFeedback && sync.changedLocal) {
                        Toast.makeText(
                            this@SettingsActivity,
                            getString(
                                R.string.library_sync_restored,
                                sync.totalRecords
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    } else if (
                        showFeedback &&
                        sync.seededCloud
                    ) {
                        Toast.makeText(
                            this@SettingsActivity,
                            R.string.library_sync_seeded,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (_: Exception) {
                    // Mantém "Pro ativo" e agenda novas tentativas.
                    renderProState(true, null)
                    LibrarySyncManager.enqueueRestore(
                        this@SettingsActivity
                    )

                    if (showFeedback) {
                        Toast.makeText(
                            this@SettingsActivity,
                            R.string.library_sync_retry,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                // Só erros da própria validação Pro chegam aqui.
                renderProState(
                    ProManager.isProCached(
                        this@SettingsActivity
                    ),
                    if (showFeedback) {
                        getString(
                            R.string.pro_check_error,
                            e.message
                                ?: getString(
                                    R.string.unknown_error
                                )
                        )
                    } else {
                        null
                    }
                )
            } finally {
                b.settingsProCheck.isEnabled = true
            }
        }
    }

    private fun reconcilePurchase(purchaseId: String) {
        if (purchaseId.isBlank()) return

        proJob?.cancel()
        proJob = lifecycleScope.launch {
            b.settingsProStatus.text =
                getString(R.string.pro_status_checking)

            b.settingsProBuy.isEnabled = false
            b.settingsProCheck.isEnabled = false
            b.settingsProEmailLayout.isEnabled = false

            try {
                val result =
                    ProManager.reconcile(
                        this@SettingsActivity,
                        purchaseId
                    )

                if (result.activated) {
                    renderProState(true, null)

                    try {
                        val sync =
                            LibrarySyncManager.restoreAndMerge(
                                this@SettingsActivity
                            )

                        if (sync.changedLocal) {
                            Toast.makeText(
                                this@SettingsActivity,
                                getString(
                                    R.string.library_sync_restored,
                                    sync.totalRecords
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } catch (_: Exception) {
                        LibrarySyncManager.enqueueRestore(
                            this@SettingsActivity
                        )
                    }

                    return@launch
                }

                when (
                    result.status
                        .trim()
                        .lowercase()
                ) {
                    // Pagamento realmente existente,
                    // mas ainda aguardando conclusão.
                    "pending",
                    "in_process",
                    "in_mediation",
                    "waiting_payment" -> {
                        renderProPendingState()
                    }

                    "device_mismatch",
                    "device_unbound",
                    "offline_expired" -> {
                        ProManager.clearPurchaseId(
                            this@SettingsActivity
                        )

                        val message =
                            when (
                                result.status
                                    .trim()
                                    .lowercase()
                            ) {
                                "device_mismatch" ->
                                    getString(
                                        R.string.pro_status_device_mismatch
                                    )

                                "device_unbound" ->
                                    getString(
                                        R.string.pro_status_device_unbound
                                    )

                                else ->
                                    getString(
                                        R.string.pro_status_offline_expired
                                    )
                            }

                        renderProState(
                            false,
                            message
                        )

                        b.settingsProBuy.isEnabled =
                            true

                        b.settingsProCheck.isEnabled =
                            true

                        b.settingsProEmailLayout.isEnabled =
                            true
                    }

                    // Checkout sem pagamento ou encerrado.
                    // Libera uma nova tentativa.
                    "not_found",
                    "unpaid",
                    "expired",
                    "cancelled",
                    "canceled",
                    "rejected",
                    "failed",
                    "refunded",
                    "charged_back" -> {
                        ProManager.clearPurchaseId(
                            this@SettingsActivity
                        )

                        renderProState(
                            false,
                            getString(
                                R.string.pro_status_failed
                            )
                        )

                        b.settingsProBuy.isEnabled = true
                        b.settingsProCheck.isEnabled = true
                        b.settingsProEmailLayout.isEnabled = true
                    }

                    // Estado desconhecido não deve prender
                    // o usuário eternamente.
                    else -> {
                        ProManager.clearPurchaseId(
                            this@SettingsActivity
                        )

                        renderProState(
                            false,
                            null
                        )

                        b.settingsProBuy.isEnabled = true
                        b.settingsProCheck.isEnabled = true
                        b.settingsProEmailLayout.isEnabled = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Erro de rede é diferente de pagamento recusado.
                // Mantemos o ID para tentar novamente depois,
                // sem permitir checkout duplicado nesse instante.
                renderProPendingState(
                    getString(
                        R.string.pro_check_error,
                        e.message
                            ?: getString(
                                R.string.unknown_error
                            )
                    )
                )
            }
        }
    }

    private fun renderProPendingState(
        message: String? = null
    ) {
        b.settingsProStatus.text =
            message
                ?: getString(
                    R.string.pro_status_pending
                )

        b.settingsProActiveEmail.visibility = View.GONE
        b.settingsProEmailLayout.visibility = View.VISIBLE
        b.settingsProEmailLayout.isEnabled = false

        b.settingsProBuy.visibility = View.VISIBLE
        b.settingsProBuy.isEnabled = false

        b.settingsProCheck.visibility = View.VISIBLE
        b.settingsProCheck.isEnabled = false
    }

    private fun renderProState(active: Boolean, message: String?) {
        b.settingsProStatus.text =
            message ?: getString(
                if (active) {
                    R.string.pro_status_active
                } else {
                    R.string.pro_status_free
                }
            )

        if (active) {
            val email = ProManager.savedEmail(this)

            b.settingsProEmailLayout.visibility = View.GONE

            b.settingsProActiveEmail.text = email
            b.settingsProActiveEmail.visibility =
                if (email.isBlank()) View.GONE else View.VISIBLE

            b.settingsProBuy.visibility = View.GONE

            b.settingsProCheck.visibility = View.VISIBLE
            b.settingsProCheck.isEnabled = true

            val params =
                b.settingsProCheck.layoutParams as LinearLayout.LayoutParams

            params.width = LinearLayout.LayoutParams.WRAP_CONTENT
            params.weight = 0f
            params.marginStart = 0
            b.settingsProCheck.layoutParams = params

        } else {
            b.settingsProActiveEmail.visibility = View.GONE

            b.settingsProEmailLayout.visibility = View.VISIBLE
            b.settingsProEmailLayout.isEnabled = true

            b.settingsProBuy.visibility = View.VISIBLE
            b.settingsProBuy.isEnabled = true

            b.settingsProCheck.visibility = View.VISIBLE
            b.settingsProCheck.isEnabled = true

            val buyParams =
                b.settingsProBuy.layoutParams as LinearLayout.LayoutParams

            buyParams.width = 0
            buyParams.weight = 1f
            b.settingsProBuy.layoutParams = buyParams

            val checkParams =
                b.settingsProCheck.layoutParams as LinearLayout.LayoutParams

            checkParams.width = 0
            checkParams.weight = 1f
            checkParams.marginStart = dp(8)
            b.settingsProCheck.layoutParams = checkParams
        }
    }

    private fun handleCheckoutIntent(source: Intent?) {
        val data = source?.data ?: return

        if (
            !data.scheme.equals("pkgpocket", true) ||
            !data.host.equals("checkout", true)
        ) {
            return
        }

        val purchaseId = data.getQueryParameter("purchase_id")
            ?.takeIf { it.startsWith("pp_") }
            ?: ProManager.savedPurchaseId(this)

        source.data = null

        if (purchaseId.isNotBlank()) {
            ProManager.savePurchaseId(this, purchaseId)
            reconcilePurchase(purchaseId)
        } else {
            refreshPro(true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::b.isInitialized) handleCheckoutIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!::b.isInitialized) return

        UpdateManager.resumePendingInstall(this)

        val now = SystemClock.elapsedRealtime()
        if (now - lastProSyncElapsed < 2_500L) return
        lastProSyncElapsed = now

        val pending = ProManager.savedPurchaseId(this)

        if (pending.isNotBlank()) {
            reconcilePurchase(pending)
        } else if (ProManager.savedEmail(this).isNotBlank()) {
            refreshPro(false)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

}
