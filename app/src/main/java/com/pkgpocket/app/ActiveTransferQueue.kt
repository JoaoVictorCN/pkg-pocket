package com.pkgpocket.app

object ActiveTransferQueue {
    @Volatile
    private var snapshot: List<PkgItem> = emptyList()

    fun set(items: List<PkgItem>) {
        snapshot = items.toList()
    }

    fun get(): List<PkgItem> = snapshot

    fun clear() {
        snapshot = emptyList()
    }
}
