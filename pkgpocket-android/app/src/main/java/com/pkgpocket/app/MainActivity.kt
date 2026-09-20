package com.pkgpocket.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.pkgpocket.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding

    private val pickPkgs = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            b.status.text = "Lendo metadados de ${uris.size} PKG(s)…"
            val parsed = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        PkgParser.parse(contentResolver, uri)
                    }.onFailure { e -> runOnUiThread { Toast.makeText(this@MainActivity, "${uri.lastPathSegment}: ${e.message}", Toast.LENGTH_LONG).show() } }.getOrNull()
                }
            }
            PkgRepository.items = parsed
            render(parsed)
            ensureService(InstallerService.ACTION_REFRESH)
            b.status.text = "${parsed.size} PKG(s) prontos. Abra o Remote Package Installer no PS4."
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            b.status.text = intent?.getStringExtra(InstallerService.EXTRA_STATUS).orEmpty()
            b.progress.progress = intent?.getIntExtra(InstallerService.EXTRA_PERCENT, 0) ?: 0
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
        }
        ContextCompat.registerReceiver(this, statusReceiver, IntentFilter(InstallerService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)

        b.selectPkgs.setOnClickListener { pickPkgs.launch(arrayOf("application/octet-stream", "application/x-pkg", "*/*")) }
        b.detectPs4.setOnClickListener {
            lifecycleScope.launch {
                b.status.text = "Procurando PS4 com RPI na rede local…"
                val ip = withContext(Dispatchers.IO) { NetworkUtils.findRpi() }
                if (ip != null) { b.ps4Ip.setText(ip); b.status.text = "RPI encontrado em $ip:12800" }
                else b.status.text = "Não encontrei o RPI. Abra-o no PS4 e tente novamente."
            }
        }
        b.installAll.setOnClickListener {
            val ip = b.ps4Ip.text?.toString()?.trim().orEmpty()
            if (PkgRepository.items.isEmpty()) { Toast.makeText(this, "Selecione os PKGs primeiro", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (ip.isBlank()) { Toast.makeText(this, "Digite ou detecte o IP do PS4", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            ensureService(InstallerService.ACTION_INSTALL_ALL, ip)
        }
    }

    private fun ensureService(action: String, ip: String? = null) {
        val i = Intent(this, InstallerService::class.java).setAction(action)
        if (ip != null) i.putExtra(InstallerService.EXTRA_PS4_IP, ip)
        ContextCompat.startForegroundService(this, i)
    }

    private fun render(items: List<PkgItem>) {
        b.pkgList.removeAllViews()
        val sorted = items.sortedWith(compareBy<PkgItem>({ it.titleId }, { it.kind.order }, { it.fileName }))
        sorted.forEach { item ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_pkg, b.pkgList, false)
            row.findViewById<TextView>(R.id.title).text = item.title
            val meta = buildString {
                append(item.kind.label)
                if (item.titleId.isNotBlank()) append(" • ${item.titleId}")
                if (item.version.isNotBlank()) append(" • v${item.version}")
                append(" • ${humanSize(item.size)}")
            }
            row.findViewById<TextView>(R.id.meta).text = meta
            val iv = row.findViewById<ImageView>(R.id.icon)
            item.icon?.let { bytes -> runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()?.let(iv::setImageBitmap) }
            b.pkgList.addView(row)
        }
    }

    private fun humanSize(n: Long): String {
        if (n < 1024) return "$n B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = n.toDouble()
        var i = -1
        do { v /= 1024.0; i++ } while (v >= 1024 && i < units.lastIndex)
        return String.format(Locale.US, "%.1f %s", v, units[i])
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }
}
