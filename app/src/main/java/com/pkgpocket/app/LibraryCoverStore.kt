package com.pkgpocket.app

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

object LibraryCoverStore {
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

    fun load(context: Context, titleId: String): ByteArray? {
        val file = fileFor(context, titleId)
        if (!file.isFile) return null

        return runCatching {
            val bytes = file.readBytes()
            if (valid(bytes)) {
                bytes
            } else {
                file.delete()
                null
            }
        }.getOrNull()
    }

    fun save(context: Context, titleId: String, bytes: ByteArray?) {
        if (titleId.isBlank() || bytes == null || !valid(bytes)) return
        runCatching { fileFor(context, titleId).writeBytes(bytes) }
    }

    fun seedFromGame(context: Context, item: PkgItem) {
        if (item.kind != PkgKind.GAME || item.titleId.isBlank()) return
        save(context, item.titleId, item.icon)
    }

    fun clear(context: Context) {
        runCatching {
            File(context.filesDir, "library_covers")
                .takeIf { it.exists() }
                ?.deleteRecursively()
        }
    }

    private fun valid(bytes: ByteArray): Boolean {
        return bytes.isNotEmpty() &&
            bytes.size <= MAX_IMAGE_BYTES &&
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null
    }

    private fun fileFor(context: Context, titleId: String): File {
        val dir = File(context.filesDir, "library_covers")
        if (!dir.exists()) dir.mkdirs()

        val normalized = titleId.trim().uppercase(Locale.ROOT)
        val digest = MessageDigest
            .getInstance("SHA-256")
            .digest(normalized.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        return File(dir, "$digest.img")
    }
}
