package com.pkgpocket.app

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.UUID

object PkgParser {
    private const val PKG_MAGIC = 0x7F434E54
    private const val PARAM_SFO = 0x1000
    private const val ICON0_PNG = 0x1200

    fun parse(resolver: ContentResolver, uri: Uri): PkgItem {
        val (name, fileSize) = queryMeta(resolver, uri)
        resolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { fis ->
                val ch = fis.channel
                val header = read(ch, 0, 0x1000).order(ByteOrder.BIG_ENDIAN)
                require(header.getInt(0) == PKG_MAGIC) { "Arquivo não é um PKG de PS4 válido" }

                val entryCount = header.getInt(0x10).toLong() and 0xffffffffL
                val tableOffset = header.getInt(0x18).toLong() and 0xffffffffL
                require(entryCount in 1..10000) { "Tabela de entradas inválida" }

                val contentIdHeader = readAscii(header, 0x40, 0x30)
                val tableSize = entryCount * 0x20L
                require(tableSize <= 4L * 1024 * 1024) { "Tabela PKG grande demais" }
                val table = read(ch, tableOffset, tableSize.toInt()).order(ByteOrder.BIG_ENDIAN)

                var sfoBytes: ByteArray? = null
                var iconBytes: ByteArray? = null
                for (i in 0 until entryCount.toInt()) {
                    val base = i * 0x20
                    val id = table.getInt(base)
                    val off = table.getInt(base + 0x10).toLong() and 0xffffffffL
                    val len = table.getInt(base + 0x14).toLong() and 0xffffffffL
                    if (id == PARAM_SFO && len in 1..(2 * 1024 * 1024)) {
                        sfoBytes = read(ch, off, len.toInt()).array()
                    } else if (id == ICON0_PNG && len in 1..(8 * 1024 * 1024)) {
                        iconBytes = read(ch, off, len.toInt()).array()
                    }
                    if (sfoBytes != null && iconBytes != null) break
                }

                val sfo = sfoBytes?.let { parseSfo(it) }.orEmpty()
                val contentId = sfo["CONTENT_ID"].orEmpty().ifBlank { contentIdHeader }
                val titleId = sfo["TITLE_ID"].orEmpty().ifBlank {
                    Regex("CUSA\\d{5}", RegexOption.IGNORE_CASE).find(contentId)?.value?.uppercase().orEmpty()
                }
                val category = sfo["CATEGORY"].orEmpty().lowercase()
                val kind = when (category) {
                    "gd" -> PkgKind.GAME
                    "gp" -> PkgKind.UPDATE
                    "ac", "al" -> PkgKind.DLC
                    else -> when {
                        name.contains("patch", true) || name.contains("update", true) -> PkgKind.UPDATE
                        else -> PkgKind.OTHER
                    }
                }
                val title = sfo["TITLE"].orEmpty().ifBlank { name.removeSuffix(".pkg") }
                val version = sfo["APP_VER"].orEmpty().ifBlank { sfo["VERSION"].orEmpty() }

                return PkgItem(
                    token = UUID.randomUUID().toString(),
                    uri = uri,
                    fileName = name,
                    size = if (fileSize >= 0) fileSize else ch.size(),
                    title = title,
                    titleId = titleId,
                    contentId = contentId,
                    version = version,
                    category = category,
                    kind = kind,
                    icon = iconBytes
                )
            }
        }
    }

    private fun queryMeta(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
        var name = "package.pkg"
        var size = -1L
        val c: Cursor? = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        c?.use {
            if (it.moveToFirst()) {
                val ni = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = it.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = it.getString(ni) ?: name
                if (si >= 0 && !it.isNull(si)) size = it.getLong(si)
            }
        }
        return name to size
    }

    private fun read(ch: FileChannel, offset: Long, size: Int): ByteBuffer {
        val out = ByteBuffer.allocate(size)
        ch.position(offset)
        while (out.hasRemaining()) {
            if (ch.read(out) < 0) break
        }
        require(!out.hasRemaining()) { "PKG terminou antes do esperado" }
        out.flip()
        return out
    }

    private fun readAscii(buf: ByteBuffer, offset: Int, maxLen: Int): String {
        val b = ByteArray(maxLen)
        val dup = buf.duplicate()
        dup.position(offset)
        dup.get(b)
        val end = b.indexOf(0).let { if (it < 0) b.size else it }
        return b.copyOfRange(0, end).toString(Charsets.US_ASCII).trim()
    }

    private fun parseSfo(bytes: ByteArray): Map<String, String> {
        if (bytes.size < 20) return emptyMap()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(0) != 0x46535000) return emptyMap() // "\0PSF"
        val keyTable = b.getInt(8)
        val dataTable = b.getInt(12)
        val count = b.getInt(16)
        if (count !in 0..2048) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (i in 0 until count) {
            val p = 20 + i * 16
            if (p + 16 > bytes.size) break
            val keyOff = b.getShort(p).toInt() and 0xffff
            val fmt = b.getShort(p + 2).toInt() and 0xffff
            val len = b.getInt(p + 4)
            val dataOff = b.getInt(p + 12)
            val keyPos = keyTable + keyOff
            val valuePos = dataTable + dataOff
            if (keyPos !in bytes.indices || valuePos !in bytes.indices || len < 0) continue
            val key = readCString(bytes, keyPos, 256)
            if (key.isBlank()) continue
            val value = when (fmt) {
                0x0404 -> if (valuePos + 4 <= bytes.size) ByteBuffer.wrap(bytes, valuePos, 4).order(ByteOrder.LITTLE_ENDIAN).int.toString() else ""
                else -> readCString(bytes, valuePos, len.coerceAtMost(4096))
            }
            out[key] = value
        }
        return out
    }

    private fun readCString(bytes: ByteArray, pos: Int, maxLen: Int): String {
        val endLimit = (pos + maxLen).coerceAtMost(bytes.size)
        var end = pos
        while (end < endLimit && bytes[end].toInt() != 0) end++
        return bytes.copyOfRange(pos, end).toString(Charsets.UTF_8).trim()
    }
}
