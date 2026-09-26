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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
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
            val logs = prefs.getString("last_log", "").orEmpty()
            AlertDialog.Builder(this)
                .setTitle(R.string.view_log)
                .setMessage(
                    logs.ifBlank {
                        getString(R.string.settings_log_empty)
                    }
                )
                .setPositiveButton(R.string.close, null)
                .show()
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
                ProManager.getQuote()
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

                CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                    .launchUrl(
                        this@SettingsActivity,
                        Uri.parse(checkout.checkoutUrl)
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
                    email
                )

                val licenseMessage =
                    if (showFeedback && !status.active) {
                        when (status.status.lowercase()) {
                            "pending",
                            "in_process",
                            "in_mediation",
                            "waiting_payment" ->
                                getString(R.string.pro_status_pending)

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
            // Existe uma compra salva: não permita criar outra
            // enquanto o servidor ainda estiver conciliando.
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
                    refreshPro(true)
                } else {
                    renderProPendingState()
                }
            } catch (e: CancellationException) {
                // Cancelamento interno de coroutine não é erro
                // de pagamento/licença.
                throw e
            } catch (e: Exception) {
                // Mantém a compra existente bloqueada.
                // Uma falha de rede não significa que ela deixou
                // de existir.
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

        b.settingsProBuy.visibility = View.VISIBLE
        b.settingsProBuy.isEnabled = false
        b.settingsProCheck.isEnabled = false
        b.settingsProEmailLayout.isEnabled = false
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

        b.settingsProBuy.visibility =
            if (active) View.GONE else View.VISIBLE
        b.settingsProEmailLayout.isEnabled = !active
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
}
