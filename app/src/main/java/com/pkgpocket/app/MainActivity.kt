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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.pkgpocket.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val logLines = mutableListOf<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val pickPkgs = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val reading = "Lendo metadados de ${uris.size} PKG(s)…"
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
                            addLog("ERRO: $msg")
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                        }
                    }.getOrNull()
                }
            }

            PkgRepository.items = parsed
            render(parsed)

            parsed.forEach { item ->
                addLog(
                    "PKG: ${item.kind.label} • ${item.title} • " +
                        "${item.titleId.ifBlank { "sem Title ID" }} • " +
                        "v${item.version.ifBlank { "?" }} • ${humanSize(item.size)}"
                )
            }

            val ready = "${parsed.size} PKG(s) prontos. Abra o Remote Package Installer no PS4."
            b.status.text = ready
            b.liveLog.text = "Sem transferência ativa."
            addLog(ready)
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(InstallerService.EXTRA_STATUS).orEmpty()
            val logOnly = intent?.getBooleanExtra(InstallerService.EXTRA_LOG_ONLY, false) ?: false
            val liveUpdate = intent?.getBooleanExtra(InstallerService.EXTRA_LIVE_UPDATE, false) ?: false
            val active = intent?.getBooleanExtra(InstallerService.EXTRA_ACTIVE, false) ?: false

            b.cancelInstall.visibility = if (active) View.VISIBLE else View.GONE
            b.installAll.isEnabled = !active

            if (liveUpdate) {
                b.status.text = text
                b.liveLog.text = text
                b.progress.progress = intent?.getIntExtra(InstallerService.EXTRA_PERCENT, 0) ?: 0
                return
            }

            if (!logOnly) {
                b.status.text = text
                b.liveLog.text = text
                b.progress.progress = intent?.getIntExtra(InstallerService.EXTRA_PERCENT, 0) ?: 0
            }

            if (text.isNotBlank()) addLog(text)
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

        b.selectPkgs.setOnClickListener {
            addLog("Abrindo seletor de PKGs…")
            pickPkgs.launch(arrayOf("application/octet-stream", "application/x-pkg", "*/*"))
        }

        b.detectPs4.setOnClickListener {
            lifecycleScope.launch {
                val searching = "Procurando PS4 com RPI na rede local…"
                b.status.text = searching
                addLog(searching)
                val ip = withContext(Dispatchers.IO) { NetworkUtils.findRpi() }
                if (ip != null) {
                    b.ps4Ip.setText(ip)
                    val found = "RPI encontrado em $ip:12800"
                    b.status.text = found
                    addLog(found)
                } else {
                    val notFound = "Não encontrei o RPI. Abra-o no PS4 e tente novamente."
                    b.status.text = notFound
                    addLog(notFound)
                }
            }
        }

        b.installAll.setOnClickListener {
            val ip = b.ps4Ip.text?.toString()?.trim().orEmpty()
            if (PkgRepository.items.isEmpty()) {
                Toast.makeText(this, "Selecione os PKGs primeiro", Toast.LENGTH_SHORT).show()
                addLog("Instalação cancelada: nenhum PKG selecionado.")
                return@setOnClickListener
            }
            if (ip.isBlank()) {
                Toast.makeText(this, "Digite ou detecte o IP do PS4", Toast.LENGTH_SHORT).show()
                addLog("Instalação cancelada: IP do PS4 não informado.")
                return@setOnClickListener
            }

            b.cancelInstall.visibility = View.VISIBLE
            b.installAll.isEnabled = false
            b.liveLog.text = "Preparando envio…"
            addLog("Iniciando fila de ${PkgRepository.items.size} PKG(s) para $ip…")
            ensureService(InstallerService.ACTION_INSTALL_ALL, ip)
        }

        b.cancelInstall.setOnClickListener {
            b.cancelInstall.isEnabled = false
            b.liveLog.text = "Cancelando envio…"
            ensureService(InstallerService.ACTION_CANCEL)
        }

        b.toggleLog.setOnClickListener {
            val show = b.logContainer.visibility != View.VISIBLE
            b.logContainer.visibility = if (show) View.VISIBLE else View.GONE
            b.toggleLog.text = if (show) "Ocultar log" else "Ver log"
        }

        b.liveLog.text = "Sem transferência ativa."
        addLog("PKG Pocket iniciado.")
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf<String>()

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }

        // Em Android 13+ o seletor de documentos concede acesso ao PKG diretamente.
        // Esta permissão só é necessária em aparelhos antigos.
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
        val sorted = items.sortedWith(
            compareBy<PkgItem>({ it.titleId }, { it.kind.order }, { it.fileName })
        )

        sorted.forEach { item ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_pkg, b.pkgList, false)
            row.findViewById<TextView>(R.id.title).text = item.title
            row.findViewById<TextView>(R.id.kindBadge).text = item.kind.label

            val details = buildString {
                append(if (item.version.isNotBlank()) "Versão ${item.version}" else "Versão não informada")
                append("  •  ${humanSize(item.size)}")
            }
            row.findViewById<TextView>(R.id.details).text = details
            row.findViewById<TextView>(R.id.titleId).text =
                "Title ID: ${item.titleId.ifBlank { "—" }}"
            row.findViewById<TextView>(R.id.contentId).text =
                "Content ID: ${item.contentId.ifBlank { "—" }}"
            row.findViewById<TextView>(R.id.fileName).text = item.fileName

            val iv = row.findViewById<ImageView>(R.id.icon)
            item.icon?.let { bytes ->
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                    .getOrNull()
                    ?.let(iv::setImageBitmap)
            }

            b.pkgList.addView(row)
        }
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
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }
}
