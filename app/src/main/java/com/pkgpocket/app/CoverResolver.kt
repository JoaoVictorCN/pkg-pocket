package com.pkgpocket.app

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object CoverResolver {
    private const val MAX_JSON_BYTES = 2 * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

    private const val TMDB_HMAC_KEY_HEX =
        "F5DE66D2680E255B2DF79E74F890EBF349262F618BCAE2A9ACCDEE5156CE8DF2" +
            "CDF2D48C71173CDC2594465B87405D197CF1AED3B7E9671EEB56CA6753C2E6B0"

    fun resolve(
        context: Context,
        item: PkgItem,
        allowTitleFallback: Boolean
    ): ByteArray? {
        val contentId = item.contentId.trim()
        val titleId = item.titleId.trim().uppercase(Locale.ROOT)

        if (contentId.isNotBlank()) {
            val cached = readCache(context, "content:$contentId")
            if (cached != null) return cached

            val contentCover = fetchContentCover(contentId)
            if (contentCover != null) {
                writeCache(context, "content:$contentId", contentCover)
                return contentCover
            }
        }

        if (!allowTitleFallback || titleId.isBlank()) return null

        val cachedTitle = readCache(context, "title:$titleId")
        if (cachedTitle != null) return cachedTitle

        val titleCover = fetchTmdbTitleCover(titleId) ?: return null
        writeCache(context, "title:$titleId", titleCover)
        return titleCover
    }

    private fun fetchContentCover(contentId: String): ByteArray? {
        for ((country, language) in regionCandidates(contentId)) {
            val url =
                "https://store.playstation.com/store/api/chihiro/00_09_000/" +
                    "container/$country/$language/999/$contentId"

            val jsonText = fetchBytes(url, MAX_JSON_BYTES)
                ?.toString(StandardCharsets.UTF_8)
                ?: continue

            val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: continue
            val imageUrl = preferredImageUrl(root) ?: continue
            val bytes = fetchImage(imageUrl)

            if (bytes != null) return bytes
        }

        return null
    }

    private fun fetchTmdbTitleCover(titleId: String): ByteArray? {
        val npTitleId = "${titleId}_00"
        val digest = hmacSha1Hex(npTitleId)

        val jsonUrl =
            "https://tmdb.np.dl.playstation.net/tmdb2/" +
                "${npTitleId}_${digest}/${npTitleId}.json"

        val jsonText = fetchBytes(jsonUrl, MAX_JSON_BYTES)
            ?.toString(StandardCharsets.UTF_8)
            ?: return null

        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val icons = root.optJSONArray("icons") ?: return null

        for (i in 0 until icons.length()) {
            val icon = icons.optJSONObject(i) ?: continue
            val rawUrl = icon.optString("icon").trim()
            if (rawUrl.isBlank()) continue

            val bytes = fetchImage(rawUrl)
            if (bytes != null) return bytes
        }

        return null
    }

    private fun preferredImageUrl(root: JSONObject): String? {
        val images = root.optJSONArray("images")

        if (images != null) {
            val preferred = mutableListOf<String>()
            val others = mutableListOf<String>()

            for (i in 0 until images.length()) {
                val obj = images.optJSONObject(i) ?: continue
                val url = obj.optString("url").trim()
                if (!url.startsWith("http", ignoreCase = true)) continue

                val type = (
                    obj.optString("type") + " " +
                        obj.optString("role") + " " +
                        obj.optString("purpose")
                    ).lowercase(Locale.ROOT)

                if (
                    "cover" in type ||
                    "thumbnail" in type ||
                    "master" in type ||
                    "product" in type
                ) {
                    preferred += url
                } else {
                    others += url
                }
            }

            (preferred + others).firstOrNull()?.let { return it }
        }

        return findImageUrlRecursive(root)
    }

    private fun findImageUrlRecursive(value: Any?): String? {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)

                    if (child is String) {
                        val s = child.trim()
                        val lowerKey = key.lowercase(Locale.ROOT)

                        if (
                            s.startsWith("http", ignoreCase = true) &&
                            (
                                "image" in lowerKey ||
                                "thumb" in lowerKey ||
                                "cover" in lowerKey ||
                                s.contains("/image", ignoreCase = true) ||
                                s.endsWith(".png", ignoreCase = true) ||
                                s.endsWith(".jpg", ignoreCase = true) ||
                                s.endsWith(".jpeg", ignoreCase = true)
                            )
                        ) {
                            return s
                        }
                    }

                    findImageUrlRecursive(child)?.let { return it }
                }
            }

            is JSONArray -> {
                for (i in 0 until value.length()) {
                    findImageUrlRecursive(value.opt(i))?.let { return it }
                }
            }
        }

        return null
    }

    private fun regionCandidates(contentId: String): List<Pair<String, String>> {
        val prefix = contentId.take(2).uppercase(Locale.ROOT)

        val primary = when (prefix) {
            "UP" -> "US" to "en"
            "EP" -> "GB" to "en"
            "JP" -> "JP" to "ja"
            "HP" -> "HK" to "en"
            "KP" -> "KR" to "ko"
            else -> "US" to "en"
        }

        return listOf(
            primary,
            "BR" to "pt",
            "US" to "en",
            "GB" to "en"
        ).distinct()
    }

    private fun fetchImage(rawUrl: String): ByteArray? {
        val candidates = buildList {
            if (rawUrl.startsWith("http://", ignoreCase = true)) {
                add("https://" + rawUrl.substringAfter("http://"))
            }
            add(rawUrl)
        }.distinct()

        for (url in candidates) {
            val bytes = fetchBytes(url, MAX_IMAGE_BYTES) ?: continue

            if (
                bytes.isNotEmpty() &&
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null
            ) {
                return bytes
            }
        }

        return null
    }

    private fun fetchBytes(urlText: String, maxBytes: Int): ByteArray? {
        val connection = runCatching {
            (URL(urlText).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4_000
                readTimeout = 7_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "PKG-Pocket/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", "*/*")
            }
        }.getOrNull() ?: return null

        return try {
            val code = connection.responseCode
            if (code !in 200..299) return null

            val declared = connection.contentLengthLong
            if (declared > maxBytes) return null

            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream(
                    if (declared in 1..maxBytes.toLong()) declared.toInt() else 64 * 1024
                )
                val buffer = ByteArray(32 * 1024)
                var total = 0

                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break

                    total += read
                    if (total > maxBytes) return null

                    out.write(buffer, 0, read)
                }

                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun hmacSha1Hex(text: String): String {
        val key = hexToBytes(TMDB_HMAC_KEY_HEX)
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))

        return mac
            .doFinal(text.toByteArray(StandardCharsets.US_ASCII))
            .joinToString("") { "%02X".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun cacheFile(context: Context, key: String): File {
        val dir = File(context.filesDir, "cover_cache")
        if (!dir.exists()) dir.mkdirs()

        val digest = MessageDigest
            .getInstance("SHA-256")
            .digest(key.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        return File(dir, "$digest.img")
    }

    private fun readCache(context: Context, key: String): ByteArray? {
        val file = cacheFile(context, key)
        if (!file.isFile) return null

        return runCatching {
            val bytes = file.readBytes()
            if (
                bytes.isNotEmpty() &&
                bytes.size <= MAX_IMAGE_BYTES &&
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null
            ) {
                bytes
            } else {
                file.delete()
                null
            }
        }.getOrNull()
    }

    private fun writeCache(context: Context, key: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return

        runCatching {
            cacheFile(context, key).writeBytes(bytes)
        }
    }
}
