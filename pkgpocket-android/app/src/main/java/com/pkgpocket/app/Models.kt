package com.pkgpocket.app

import android.net.Uri

enum class PkgKind(val label: String, val order: Int) {
    GAME("JOGO", 0), UPDATE("UPDATE", 1), DLC("DLC", 2), OTHER("PKG", 3)
}

data class PkgItem(
    val token: String,
    val uri: Uri,
    val fileName: String,
    val size: Long,
    val title: String,
    val titleId: String,
    val contentId: String,
    val version: String,
    val category: String,
    val kind: PkgKind,
    val icon: ByteArray?
)

object PkgRepository {
    @Volatile var items: List<PkgItem> = emptyList()
}
