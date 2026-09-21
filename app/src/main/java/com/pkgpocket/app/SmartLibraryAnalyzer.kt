package com.pkgpocket.app

import java.util.Locale

data class SmartPkgGroup(
    val titleId: String,
    val title: String,
    val items: List<PkgItem>,
    val totalSize: Long,
    val games: List<PkgItem>,
    val updates: List<PkgItem>,
    val dlcs: List<PkgItem>,
    val others: List<PkgItem>
)

data class SmartAnalysis(
    val groups: List<SmartPkgGroup>,
    val duplicateTokens: Set<String>,
    val olderUpdateTokens: Set<String>,
    val notices: List<String>,
    val importantWarnings: List<String>
)

object SmartLibraryAnalyzer {
    fun analyze(items: List<PkgItem>): SmartAnalysis {
        val duplicates = findDuplicateTokens(items)
        val olderUpdates = findOlderUpdateTokens(items)
        val notices = mutableListOf<String>()
        val important = mutableListOf<String>()

        val groups = items
            .groupBy { item ->
                item.titleId.trim().uppercase(Locale.ROOT).ifBlank {
                    "NO-ID:${item.title.trim().uppercase(Locale.ROOT)}"
                }
            }
            .map { (key, groupItems) ->
                val games = groupItems.filter { it.kind == PkgKind.GAME }
                val updates = groupItems.filter { it.kind == PkgKind.UPDATE }
                val dlcs = groupItems.filter { it.kind == PkgKind.DLC }
                val others = groupItems.filter { it.kind == PkgKind.OTHER }

                if (games.size > 1) {
                    important += "MULTIPLE_BASES|$key|${games.size}"
                }

                if (games.isEmpty() && updates.isNotEmpty()) {
                    notices += "UPDATE_WITHOUT_BASE|$key"
                }

                if (games.isEmpty() && dlcs.isNotEmpty()) {
                    notices += "DLC_WITHOUT_BASE|$key"
                }

                SmartPkgGroup(
                    titleId = groupItems.firstOrNull()?.titleId.orEmpty(),
                    title = (
                        games.firstOrNull()?.title
                            ?: updates.firstOrNull()?.title
                            ?: dlcs.firstOrNull()?.title
                            ?: groupItems.firstOrNull()?.title
                            ?: key
                        ),
                    items = groupItems.sortedWith(
                        compareBy<PkgItem>({ it.kind.order }, { it.fileName })
                    ),
                    totalSize = groupItems.sumOf { it.size.coerceAtLeast(0L) },
                    games = games,
                    updates = updates,
                    dlcs = dlcs,
                    others = others
                )
            }
            .sortedBy { it.title.lowercase(Locale.ROOT) }

        if (duplicates.isNotEmpty()) {
            important += "DUPLICATES|${duplicates.size}"
        }

        if (olderUpdates.isNotEmpty()) {
            important += "OLDER_UPDATES|${olderUpdates.size}"
        }

        return SmartAnalysis(
            groups = groups,
            duplicateTokens = duplicates,
            olderUpdateTokens = olderUpdates,
            notices = notices,
            importantWarnings = important
        )
    }

    fun optimize(items: List<PkgItem>): List<PkgItem> {
        val analysis = analyze(items)
        val remove = analysis.duplicateTokens + analysis.olderUpdateTokens
        return items.filterNot { it.token in remove }
    }

    private fun findDuplicateTokens(items: List<PkgItem>): Set<String> {
        val remove = linkedSetOf<String>()

        items.groupBy { item ->
            listOf(
                item.titleId.trim().uppercase(Locale.ROOT),
                item.contentId.trim().uppercase(Locale.ROOT),
                item.kind.name,
                item.version.trim(),
                item.size.toString()
            ).joinToString("|")
        }.values.forEach { group ->
            if (group.size > 1) {
                group.drop(1).forEach { remove += it.token }
            }
        }

        return remove
    }

    private fun findOlderUpdateTokens(items: List<PkgItem>): Set<String> {
        val remove = linkedSetOf<String>()

        items
            .filter { it.kind == PkgKind.UPDATE && it.titleId.isNotBlank() }
            .groupBy { it.titleId.trim().uppercase(Locale.ROOT) }
            .values
            .forEach { updates ->
                val candidates = updates.filter { it.version.isNotBlank() }
                if (candidates.size <= 1) return@forEach

                val newest = candidates.maxWithOrNull(
                    Comparator { a, b -> compareVersions(a.version, b.version) }
                ) ?: return@forEach

                candidates
                    .filter { it.token != newest.token }
                    .forEach { remove += it.token }
            }

        return remove
    }

    private fun compareVersions(a: String, b: String): Int {
        val aa = Regex("\\d+").findAll(a).map { it.value.toIntOrNull() ?: 0 }.toList()
        val bb = Regex("\\d+").findAll(b).map { it.value.toIntOrNull() ?: 0 }.toList()
        val count = maxOf(aa.size, bb.size)

        for (i in 0 until count) {
            val av = aa.getOrElse(i) { 0 }
            val bv = bb.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }

        return a.compareTo(b, ignoreCase = true)
    }
}
