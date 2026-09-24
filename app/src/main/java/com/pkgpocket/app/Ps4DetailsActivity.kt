package com.pkgpocket.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.pkgpocket.app.databinding.ActivityPs4DetailsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class Ps4DetailsActivity : AppCompatActivity() {
    private lateinit var b: ActivityPs4DetailsBinding

    private enum class Model {
        UNKNOWN, FAT, SLIM, PRO
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPs4DetailsBinding.inflate(layoutInflater)
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

        b.ps4DetailsBack.setOnClickListener { finish() }
        b.ps4DetailsDetect.setOnClickListener { detect() }
        b.ps4DetailsEditIp.setOnClickListener { editIp() }
        b.ps4DetailsTestRpi.setOnClickListener { testRpi() }
        b.ps4DetailsForget.setOnClickListener { forget() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::b.isInitialized) refresh()
    }

    private fun savedIp(): String {
        return getSharedPreferences("pkg_pocket", MODE_PRIVATE)
            .getString("last_ps4_ip", "")
            .orEmpty()
            .trim()
    }

    private fun saveIp(ip: String) {
        getSharedPreferences("pkg_pocket", MODE_PRIVATE)
            .edit()
            .putString("last_ps4_ip", ip.trim())
            .apply()
    }

    private fun refresh() {
        val ip = savedIp()

        if (ip.isBlank()) {
            render("", null, false)
            return
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val info = Ps4Discovery.query(ip)
                val rpi = NetworkUtils.canConnect(ip)
                info to rpi
            }
            render(ip, result.first, result.second)
        }
    }

    private fun detect() {
        b.ps4DetailsDetect.isEnabled = false
        b.ps4DetailsStatus.text = getString(R.string.ps4_status_identifying)
        setDot("#FBBF24")

        lifecycleScope.launch {
            val ip = withContext(Dispatchers.IO) { NetworkUtils.findRpi() }

            if (ip == null) {
                Toast.makeText(
                    this@Ps4DetailsActivity,
                    R.string.rpi_not_found,
                    Toast.LENGTH_LONG
                ).show()
                b.ps4DetailsDetect.isEnabled = true
                refresh()
                return@launch
            }

            saveIp(ip)
            b.ps4DetailsDetect.isEnabled = true
            refresh()
        }
    }

    private fun testRpi() {
        val ip = savedIp()
        if (ip.isBlank()) {
            Toast.makeText(
                this,
                R.string.ps4_details_no_saved,
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        b.ps4DetailsTestRpi.isEnabled = false
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                NetworkUtils.canConnect(ip, port = 12800)
            }

            b.ps4DetailsTestRpi.isEnabled = true

            AlertDialog.Builder(this@Ps4DetailsActivity)
                .setTitle(R.string.ps4_details_test_rpi)
                .setMessage(
                    if (ok) {
                        R.string.ps4_details_test_rpi_ok
                    } else {
                        R.string.ps4_details_test_rpi_fail
                    }
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()

            refresh()
        }
    }

    private fun editIp() {
        val input = EditText(this).apply {
            hint = getString(R.string.ps4_details_ip_hint)
            setText(savedIp())
            setSelection(text.length)
            isSingleLine = true
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.ps4_details_edit_ip)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ps4_details_save) { _, _ ->
                saveIp(input.text?.toString().orEmpty())
                refresh()
            }
            .show()
    }

    private fun forget() {
        if (savedIp().isBlank()) return

        AlertDialog.Builder(this)
            .setTitle(R.string.ps4_details_forget_confirm_title)
            .setMessage(R.string.ps4_details_forget_confirm_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ps4_details_forget) { _, _ ->
                saveIp("")
                Toast.makeText(
                    this,
                    R.string.ps4_details_removed,
                    Toast.LENGTH_SHORT
                ).show()
                render("", null, false)
            }
            .show()
    }

    private fun render(
        ip: String,
        info: Ps4Discovery.Info?,
        rpiReachable: Boolean
    ) {
        val model = inferModel(info)
        val modelText = getString(modelLabel(model))

        b.ps4DetailsConsoleImage.setImageResource(modelIcon(model))
        b.ps4DetailsModelTop.text = modelText
        b.ps4DetailsModel.text = modelText
        b.ps4DetailsFirmware.text =
            info?.firmware ?: getString(R.string.diag_unavailable)

        b.ps4DetailsStorage.text =
            getString(R.string.ps4_details_storage_unavailable)

        b.ps4DetailsMeta.text = if (ip.isBlank()) {
            getString(R.string.ps4_details_no_saved)
        } else {
            getString(R.string.ps4_details_ip_saved, ip)
        }

        when {
            rpiReachable -> {
                b.ps4DetailsStatus.text =
                    getString(R.string.ps4_status_connected)
                setDot("#4ADE80")
            }
            info != null -> {
                b.ps4DetailsStatus.text =
                    getString(R.string.ps4_status_detected)
                setDot("#FBBF24")
            }
            else -> {
                b.ps4DetailsStatus.text =
                    getString(R.string.ps4_status_unknown)
                setDot("#6B7280")
            }
        }

        b.ps4DetailsForget.isEnabled = ip.isNotBlank()
        b.ps4DetailsEditIp.isEnabled = true
        b.ps4DetailsTestRpi.isEnabled = ip.isNotBlank()
    }

    private fun setDot(hex: String) {
        b.ps4DetailsStatusDot.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor(hex))
    }

    private fun inferModel(info: Ps4Discovery.Info?): Model {
        if (info == null) return Model.UNKNOWN

        val source = listOf(
            info.modelHint.orEmpty(),
            info.hostName,
            info.hostType
        ).joinToString(" ").lowercase(Locale.ROOT)

        val cuh = Regex(
            "cuh[-_ ]?(\\d{4})",
            RegexOption.IGNORE_CASE
        ).find(source)?.groupValues?.getOrNull(1)

        if (cuh != null) {
            return when {
                cuh.startsWith("10") ||
                    cuh.startsWith("11") ||
                    cuh.startsWith("12") -> Model.FAT

                cuh.startsWith("20") ||
                    cuh.startsWith("21") ||
                    cuh.startsWith("22") -> Model.SLIM

                cuh.startsWith("70") ||
                    cuh.startsWith("71") ||
                    cuh.startsWith("72") -> Model.PRO

                else -> Model.UNKNOWN
            }
        }

        return when {
            "pro" in source -> Model.PRO
            "slim" in source -> Model.SLIM
            "fat" in source || "phat" in source -> Model.FAT
            else -> Model.UNKNOWN
        }
    }

    private fun modelLabel(model: Model): Int = when (model) {
        Model.FAT -> R.string.ps4_model_fat
        Model.SLIM -> R.string.ps4_model_slim
        Model.PRO -> R.string.ps4_model_pro
        Model.UNKNOWN -> R.string.ps4_model_generic
    }

    private fun modelIcon(model: Model): Int = when (model) {
        Model.FAT -> R.drawable.ps4_fat_real
        Model.SLIM -> R.drawable.ps4_slim_real
        Model.PRO -> R.drawable.ps4_pro_real
        Model.UNKNOWN -> R.drawable.ps4_generic_real
    }
}
