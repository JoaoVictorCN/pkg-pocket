package com.pkgpocket.app

import java.util.Locale

data class LibraryGameGroup(
    val key: String,
    val titleId: String,
    val title: String,
    val records: List<InstalledPkgRecord>,
    val games: List<InstalledPkgRecord>,
    val updates: List<InstalledPkgRecord>,
    val dlcs: List<InstalledPkgRecord>,
    val others: List<InstalledPkgRecord>,
    val latestUpdate: InstalledPkgRecord?
)

object LibraryHistory {
    fun groups(records: List<InstalledPkgRecord>): List<LibraryGameGroup> {
        return records
            .groupBy { groupKey(it) }
            .map { (key, groupRecords) ->
                val games = groupRecords.filter { it.kind == PkgKind.GAME.name }
                val updates = groupRecords.filter { it.kind == PkgKind.UPDATE.name }
                val dlcs = groupRecords.filter { it.kind == PkgKind.DLC.name }
                val others = groupRecords.filter {
                    it.kind != PkgKind.GAME.name &&
                        it.kind != PkgKind.UPDATE.name &&
                        it.kind != PkgKind.DLC.name
                }

                val title = (
                    games.firstOrNull { it.title.isNotBlank() }?.title
                        ?: updates.firstOrNull { it.title.isNotBlank() }?.title
                        ?: dlcs.firstOrNull { it.title.isNotBlank() }?.title
                        ?: groupRecords.firstOrNull()?.title
                        ?: groupRecords.firstOrNull()?.fileName
                        ?: key
                    )

                LibraryGameGroup(
                    key = key,
                    titleId = groupRecords.firstOrNull { it.titleId.isNotBlank() }?.titleId.orEmpty(),
                    title = title,
                    records = groupRecords.sortedByDescending { it.installedAt },
                    games = games.sortedByDescending { it.installedAt },
                    updates = updates.sortedWith(
                        Comparator { a, b ->
                            val version = compareVersions(b.version, a.version)
                            if (version != 0) version
                            else b.installedAt.compareTo(a.installedAt)
                        }
                    ),
                    dlcs = dlcs.sortedBy {
                        (it.title.ifBlank { it.fileName }).lowercase(Locale.ROOT)
                    },
                    others = others.sortedByDescending { it.installedAt },
                    latestUpdate = updates.maxWithOrNull(
                        Comparator { a, b -> compareVersions(a.version, b.version) }
                    )
                )
            }
            .sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    fun find(records: List<InstalledPkgRecord>, key: String): LibraryGameGroup? {
        return groups(records).firstOrNull { it.key == key }
    }

    fun groupKey(record: InstalledPkgRecord): String {
        val titleId = record.titleId.trim().uppercase(Locale.ROOT)
        if (titleId.isNotBlank()) return "ID:$titleId"

        val fallback = record.title
            .ifBlank { record.fileName }
            .trim()
            .uppercase(Locale.ROOT)

        return "TITLE:$fallback"
    }

    fun compareVersions(a: String, b: String): Int {
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
