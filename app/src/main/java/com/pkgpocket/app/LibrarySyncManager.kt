package com.pkgpocket.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object LibrarySyncManager {
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val apiBase: String
        get() = BuildConfig.LIBRARY_API_URL.trimEnd('/')

    data class RestoreResult(
        val changedLocal: Boolean,
        val seededCloud: Boolean,
        val totalRecords: Int
    )

    fun enqueueSync(context: Context) {
        if (!ProManager.isProCached(context)) return
        if (ProManager.savedEmail(context).isBlank()) return

        val app = context.applicationContext

        scope.launch {
            runCatching {
                retryProPropagation {
                    syncSnapshot(app)
                }
            }
        }
    }

    fun enqueueRestore(context: Context) {
        if (!ProManager.isProCached(context)) return
        if (ProManager.savedEmail(context).isBlank()) return

        val app = context.applicationContext

        scope.launch {
            runCatching {
                restoreAndMerge(app)
            }
        }
    }

    suspend fun restoreAndMerge(
        context: Context
    ): RestoreResult = withContext(Dispatchers.IO) {
        retryProPropagation {
            restoreAndMergeOnce(context)
        }
    }

    private fun restoreAndMergeOnce(
        context: Context
    ): RestoreResult {
        if (!ProManager.isProCached(context)) {
            return RestoreResult(
                changedLocal = false,
                seededCloud = false,
                totalRecords =
                    InstallHistoryStore.all(context).size
            )
        }

        val email = ProManager.savedEmail(context)
            .trim()
            .lowercase(Locale.ROOT)

        if (email.isBlank()) {
            return RestoreResult(
                changedLocal = false,
                seededCloud = false,
                totalRecords =
                    InstallHistoryStore.all(context).size
            )
        }

        val cloud = restore(email)
        val local = InstallHistoryStore.all(context)

        if (cloud.isEmpty()) {
            if (local.isNotEmpty()) {
                sync(email, local)

                return RestoreResult(
                    changedLocal = false,
                    seededCloud = true,
                    totalRecords = local.size
                )
            }

            return RestoreResult(
                changedLocal = false,
                seededCloud = false,
                totalRecords = 0
            )
        }

        val merged = merge(local, cloud)
        val changed =
            fingerprint(merged) != fingerprint(local)

        if (changed) {
            InstallHistoryStore.replaceFromCloud(
                context,
                merged
            )
        }

        if (fingerprint(merged) != fingerprint(cloud)) {
            sync(email, merged)
        }

        return RestoreResult(
            changedLocal = changed,
            seededCloud = false,
            totalRecords = merged.size
        )
    }

    private fun syncSnapshot(context: Context) {
        val email = ProManager.savedEmail(context)
            .trim()
            .lowercase(Locale.ROOT)

        if (email.isBlank()) return

        sync(
            email,
            InstallHistoryStore.all(context)
        )
    }

    private suspend fun <T> retryProPropagation(
        block: () -> T
    ): T {
        var lastError: IOException? = null

        // A licença e a Biblioteca podem cair em colos diferentes
        // do Cloudflare/KV. Depois de ativar/restaurar, damos tempo
        // para a informação ficar visível antes de considerar falha.
        repeat(6) { attempt ->
            try {
                return block()
            } catch (e: IOException) {
                if (!isProPropagationError(e)) {
                    throw e
                }

                lastError = e

                if (attempt < 5) {
                    delay(
                        when (attempt) {
                            0 -> 750L
                            1 -> 1_250L
                            2 -> 2_000L
                            3 -> 3_000L
                            else -> 4_000L
                        }
                    )
                }
            }
        }

        throw lastError
            ?: IOException("Library sync failed")
    }

    private fun isProPropagationError(
        error: IOException
    ): Boolean {
        val text = error.message.orEmpty()
            .lowercase(Locale.ROOT)

        return (
            "active pro license required" in text ||
                "pro_required" in text ||
                "license required" in text
            )
    }

    private fun sync(
        email: String,
        records: List<InstalledPkgRecord>
    ) {
        val body = JSONObject()
            .put("email", email)
            .put("records", encode(records))
            .toString()

        request(
            method = "POST",
            url = "$apiBase/v1/library/sync",
            body = body
        )
    }

    private fun restore(
        email: String
    ): List<InstalledPkgRecord> {
        val response = request(
            method = "POST",
            url = "$apiBase/v1/library/restore",
            body = JSONObject()
                .put("email", email)
                .toString()
        )

        return decode(
            response.optJSONArray("records")
                ?: JSONArray()
        )
    }

    private fun encode(
        records: List<InstalledPkgRecord>
    ): JSONArray {
        val array = JSONArray()

        records.take(250).forEach { record ->
            array.put(
                JSONObject()
                    .put("key", record.key)
                    .put("title", record.title)
                    .put("titleId", record.titleId)
                    .put("contentId", record.contentId)
                    .put("version", record.version)
                    .put("kind", record.kind)
                    .put("fileName", record.fileName)
                    .put(
                        "installedAt",
                        record.installedAt
                    )
            )
        }

        return array
    }

    private fun decode(
        array: JSONArray
    ): List<InstalledPkgRecord> {
        val out =
            mutableListOf<InstalledPkgRecord>()

        for (index in 0 until array.length()) {
            val obj =
                array.optJSONObject(index) ?: continue

            val key =
                obj.optString("key").trim()

            if (key.isBlank()) continue

            out += InstalledPkgRecord(
                key = key,
                title = obj.optString("title"),
                titleId = obj.optString("titleId"),
                contentId = obj.optString("contentId"),
                version = obj.optString("version"),
                kind = obj.optString("kind"),
                fileName = obj.optString("fileName"),
                installedAt = obj.optLong(
                    "installedAt",
                    System.currentTimeMillis()
                )
            )
        }

        return out
            .distinctBy { it.key }
            .sortedByDescending { it.installedAt }
            .take(250)
    }

    private fun merge(
        local: List<InstalledPkgRecord>,
        cloud: List<InstalledPkgRecord>
    ): List<InstalledPkgRecord> {
        val byKey =
            linkedMapOf<String, InstalledPkgRecord>()

        (cloud + local).forEach { record ->
            val current = byKey[record.key]

            if (
                current == null ||
                record.installedAt >= current.installedAt
            ) {
                byKey[record.key] = record
            }
        }

        return byKey.values
            .sortedByDescending { it.installedAt }
            .take(250)
    }

    private fun fingerprint(
        records: List<InstalledPkgRecord>
    ): String {
        return records
            .sortedBy { it.key }
            .joinToString("\n") {
                "${it.key}|${it.installedAt}|${it.title}|${it.fileName}"
            }
    }

    private fun request(
        method: String,
        url: String,
        body: String
    ): JSONObject {
        val connection =
            URL(url).openConnection()
                as HttpURLConnection

        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 25_000
            connection.doOutput = true

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )
            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=utf-8"
            )
            connection.setRequestProperty(
                "User-Agent",
                "PKG-Pocket/${BuildConfig.VERSION_NAME}"
            )

            val bytes =
                body.toByteArray(Charsets.UTF_8)

            connection.setFixedLengthStreamingMode(
                bytes.size
            )

            connection.outputStream.use {
                it.write(bytes)
            }

            val code = connection.responseCode

            val stream =
                if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val raw = stream
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            val obj = runCatching {
                if (raw.isBlank()) {
                    JSONObject()
                } else {
                    JSONObject(raw)
                }
            }.getOrElse {
                JSONObject().put("raw", raw)
            }

            if (code !in 200..299) {
                throw IOException(
                    obj.optString("message")
                        .ifBlank {
                            obj.optString("error")
                        }
                        .ifBlank {
                            "HTTP $code"
                        }
                )
            }

            return obj
        } finally {
            connection.disconnect()
        }
    }
}
