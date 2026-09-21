package com.pkgpocket.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class InstalledPkgRecord(
    val key: String,
    val title: String,
    val titleId: String,
    val contentId: String,
    val version: String,
    val kind: String,
    val fileName: String,
    val installedAt: Long
)

object InstallHistoryStore {
    private const val PREFS = "pkg_pocket"
    private const val KEY = "install_history_json"
    private const val MAX_ITEMS = 250

    fun record(context: Context, item: PkgItem) {
        val records = all(context).toMutableList()
        val key = keyFor(item)

        records.removeAll { it.key == key }
        records.add(
            0,
            InstalledPkgRecord(
                key = key,
                title = item.title,
                titleId = item.titleId,
                contentId = item.contentId,
                version = item.version,
                kind = item.kind.name,
                fileName = item.fileName,
                installedAt = System.currentTimeMillis()
            )
        )

        save(context, records.take(MAX_ITEMS))
    }

    fun contains(context: Context, item: PkgItem): Boolean {
        val key = keyFor(item)
        return all(context).any { it.key == key }
    }

    fun all(context: Context): List<InstalledPkgRecord> {
        val raw = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]")
            .orEmpty()

        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val out = mutableListOf<InstalledPkgRecord>()

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue

            out += InstalledPkgRecord(
                key = obj.optString("key"),
                title = obj.optString("title"),
                titleId = obj.optString("titleId"),
                contentId = obj.optString("contentId"),
                version = obj.optString("version"),
                kind = obj.optString("kind"),
                fileName = obj.optString("fileName"),
                installedAt = obj.optLong("installedAt", 0L)
            )
        }

        return out.sortedByDescending { it.installedAt }
    }

    fun clear(context: Context) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, "[]")
            .apply()
    }

    private fun save(context: Context, records: List<InstalledPkgRecord>) {
        val array = JSONArray()

        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("key", record.key)
                    .put("title", record.title)
                    .put("titleId", record.titleId)
                    .put("contentId", record.contentId)
                    .put("version", record.version)
                    .put("kind", record.kind)
                    .put("fileName", record.fileName)
                    .put("installedAt", record.installedAt)
            )
        }

        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }

    private fun keyFor(item: PkgItem): String {
        return listOf(
            item.titleId.trim().uppercase(),
            item.contentId.trim().uppercase(),
            item.kind.name,
            item.version.trim()
        ).joinToString("|")
    }
}
