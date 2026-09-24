package com.pkgpocket.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

object LibraryCoverStore {
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

    private val bitmapCache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024L) / 16L)
            .coerceIn(8L * 1024L, 32L * 1024L)
            .toInt()
    ) {
        override fun sizeOf(
            key: String,
            value: Bitmap
        ): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    fun peekBitmap(titleId: String): Bitmap? {
        if (titleId.isBlank()) return null
        return bitmapCache.get(cacheKey(titleId))
    }

    fun loadBitmap(
        context: Context,
        titleId: String
    ): Bitmap? {
        if (titleId.isBlank()) return null

        val key = cacheKey(titleId)

        bitmapCache.get(key)?.let {
            return it
        }

        val file = fileFor(context, titleId)
        if (!file.isFile) return null

        return runCatching {
            val bytes = file.readBytes()

            if (
                bytes.isEmpty() ||
                bytes.size > MAX_IMAGE_BYTES
            ) {
                file.delete()
                return@runCatching null
            }

            val bitmap = BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size
            )

            if (bitmap == null) {
                file.delete()
                null
            } else {
                bitmapCache.put(key, bitmap)
                bitmap
            }
        }.getOrNull()
    }

    fun load(
        context: Context,
        titleId: String
    ): ByteArray? {
        val file = fileFor(context, titleId)
        if (!file.isFile) return null

        return runCatching {
            val bytes = file.readBytes()

            if (valid(bytes)) {
                bytes
            } else {
                file.delete()
                bitmapCache.remove(cacheKey(titleId))
                null
            }
        }.getOrNull()
    }

    fun save(
        context: Context,
        titleId: String,
        bytes: ByteArray?
    ) {
        if (
            titleId.isBlank() ||
            bytes == null ||
            !valid(bytes)
        ) {
            return
        }

        runCatching {
            fileFor(context, titleId).writeBytes(bytes)

            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size
            )?.let {
                bitmapCache.put(
                    cacheKey(titleId),
                    it
                )
            }
        }
    }

    fun seedFromGame(
        context: Context,
        item: PkgItem
    ) {
        if (
            item.kind != PkgKind.GAME ||
            item.titleId.isBlank()
        ) {
            return
        }

        save(
            context,
            item.titleId,
            item.icon
        )
    }

    fun clear(context: Context) {
        bitmapCache.evictAll()

        runCatching {
            File(
                context.filesDir,
                "library_covers"
            )
                .takeIf { it.exists() }
                ?.deleteRecursively()
        }
    }

    private fun valid(bytes: ByteArray): Boolean {
        return bytes.isNotEmpty() &&
            bytes.size <= MAX_IMAGE_BYTES &&
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size
            ) != null
    }

    private fun cacheKey(titleId: String): String {
        return titleId
            .trim()
            .uppercase(Locale.ROOT)
    }

    private fun fileFor(
        context: Context,
        titleId: String
    ): File {
        val dir = File(
            context.filesDir,
            "library_covers"
        )

        if (!dir.exists()) {
            dir.mkdirs()
        }

        val normalized = cacheKey(titleId)

        val digest = MessageDigest
            .getInstance("SHA-256")
            .digest(
                normalized.toByteArray(
                    StandardCharsets.UTF_8
                )
            )
            .joinToString("") {
                "%02x".format(it)
            }

        return File(
            dir,
            "$digest.img"
        )
    }
}
