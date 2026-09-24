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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.pkgpocket.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding
    private var proJob: Job? = null
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
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/JoaoVictorCN/pkg-pocket/releases")
                )
            )
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
        val email = ProManager.savedEmail(this)
        if (email.isNotBlank()) b.settingsProEmail.setText(email)

        renderProState(ProManager.isProCached(this), null)

        b.settingsProBuy.setOnClickListener { startCheckout() }
        b.settingsProCheck.setOnClickListener { refreshPro(true) }

        if (email.isNotBlank()) refreshPro(false)
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
        b.settingsProStatus.text = getString(R.string.pro_status_checkout)

        proJob?.cancel()
        proJob = lifecycleScope.launch {
            try {
                val checkout =
                    ProManager.createCheckout(this@SettingsActivity, email)

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
                b.settingsProBuy.isEnabled = true
                b.settingsProCheck.isEnabled = true
            }
        }
    }

    private fun refreshPro(showFeedback: Boolean) {
        val email = b.settingsProEmail.text?.toString()?.trim().orEmpty()
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
                val status =
                    ProManager.refreshStatus(this@SettingsActivity, email)

                val message =
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

                renderProState(status.active, message)
            } catch (e: Exception) {
                renderProState(
                    ProManager.isProCached(this@SettingsActivity),
                    if (showFeedback) {
                        getString(
                            R.string.pro_check_error,
                            e.message ?: getString(R.string.unknown_error)
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

            try {
                val result =
                    ProManager.reconcile(this@SettingsActivity, purchaseId)

                if (result.activated) {
                    refreshPro(true)
                } else {
                    renderProState(
                        false,
                        getString(R.string.pro_status_pending)
                    )
                }
            } catch (e: Exception) {
                renderProState(
                    ProManager.isProCached(this@SettingsActivity),
                    getString(
                        R.string.pro_check_error,
                        e.message ?: getString(R.string.unknown_error)
                    )
                )
            }
        }
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
