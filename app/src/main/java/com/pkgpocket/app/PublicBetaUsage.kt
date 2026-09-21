package com.pkgpocket.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import java.io.File
import java.security.MessageDigest

object PublicBetaUsage {
    private const val PREFS = "pkg_pocket_public_beta"
    private const val KEY_GAMES = "completed_game_uses"
    private const val KEY_DLCS = "completed_dlc_uses"
    private const val KEY_UPDATES = "completed_update_uses"

    // Cópia discreta em armazenamento compartilhado.
    // Ela sobrevive a "Limpar dados" porque não fica dentro de /data/data do app.
    private const val BACKUP_NAME = ".ppb_9c4f7b1d.dat"
    private const val BACKUP_DIR = ".pkgpocket"
    private const val FORMAT_VERSION = 1
    private const val SIGNING_SALT = "pkg-pocket-public-beta-usage-v1-7f2c91"

    enum class Bucket {
        GAME,
        DLC,
        UPDATE
    }

    private data class Counts(
        val games: Int,
        val dlcs: Int,
        val updates: Int
    )

    private fun bucketFor(kind: PkgKind): Bucket {
        return when (kind) {
            PkgKind.GAME -> Bucket.GAME
            PkgKind.DLC -> Bucket.DLC
            PkgKind.UPDATE -> Bucket.UPDATE
            // PKG não classificado usa a cota de jogos para não virar brecha.
            PkgKind.OTHER -> Bucket.GAME
        }
    }

    private fun keyFor(bucket: Bucket): String {
        return when (bucket) {
            Bucket.GAME -> KEY_GAMES
            Bucket.DLC -> KEY_DLCS
            Bucket.UPDATE -> KEY_UPDATES
        }
    }

    private fun maxFor(bucket: Bucket): Int {
        return when (bucket) {
            Bucket.GAME -> BuildConfig.BETA_MAX_GAMES
            Bucket.DLC -> BuildConfig.BETA_MAX_DLCS
            Bucket.UPDATE -> BuildConfig.BETA_MAX_UPDATES
        }
    }

    private fun clamp(counts: Counts): Counts {
        return Counts(
            games = counts.games.coerceIn(0, BuildConfig.BETA_MAX_GAMES),
            dlcs = counts.dlcs.coerceIn(0, BuildConfig.BETA_MAX_DLCS),
            updates = counts.updates.coerceIn(0, BuildConfig.BETA_MAX_UPDATES)
        )
    }

    private fun localCounts(context: Context): Counts {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return clamp(
            Counts(
                games = prefs.getInt(KEY_GAMES, 0),
                dlcs = prefs.getInt(KEY_DLCS, 0),
                updates = prefs.getInt(KEY_UPDATES, 0)
            )
        )
    }

    private fun saveLocal(context: Context, counts: Counts) {
        val safe = clamp(counts)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_GAMES, safe.games)
            .putInt(KEY_DLCS, safe.dlcs)
            .putInt(KEY_UPDATES, safe.updates)
            .commit()
    }

    private fun androidId(context: Context): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
    }

    private fun digest(context: Context, body: String): String {
        val source = "$body|${androidId(context)}|$SIGNING_SALT"
        return MessageDigest
            .getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }

    private fun encode(context: Context, counts: Counts): String {
        val safe = clamp(counts)
        val body =
            "$FORMAT_VERSION|${safe.games}|${safe.dlcs}|${safe.updates}"
        return "$body|${digest(context, body)}"
    }

    private fun decode(context: Context, text: String): Counts? {
        val parts = text.trim().split('|')
        if (parts.size != 5) return null

        val version = parts[0].toIntOrNull() ?: return null
        val games = parts[1].toIntOrNull() ?: return null
        val dlcs = parts[2].toIntOrNull() ?: return null
        val updates = parts[3].toIntOrNull() ?: return null
        val signature = parts[4]

        if (version != FORMAT_VERSION) return null

        val body = "$version|$games|$dlcs|$updates"
        if (signature != digest(context, body)) return null

        return clamp(Counts(games, dlcs, updates))
    }

    private fun backupRelativePath(): String =
        "${Environment.DIRECTORY_DOWNLOADS}/$BACKUP_DIR/"

    private fun findMediaStoreBackup(context: Context): android.net.Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection =
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val args = arrayOf(BACKUP_NAME, backupRelativePath())

        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null

            val id = cursor.getLong(
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            )

            return android.content.ContentUris.withAppendedId(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                id
            )
        }

        return null
    }

    private fun readBackup(context: Context): Counts? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = findMediaStoreBackup(context) ?: return@runCatching null

                context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { decode(context, it.readText()) }
            } else {
                val file = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    ),
                    "$BACKUP_DIR/$BACKUP_NAME"
                )

                if (!file.exists()) null
                else decode(context, file.readText())
            }
        }.getOrNull()
    }

    private fun writeBackup(context: Context, counts: Counts) {
        val payload = encode(context, counts)

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver

                val uri = findMediaStoreBackup(context) ?: run {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, BACKUP_NAME)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            backupRelativePath()
                        )
                    }

                    resolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        values
                    )
                } ?: return@runCatching

                resolver.openOutputStream(uri, "wt")
                    ?.bufferedWriter()
                    ?.use { it.write(payload) }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    ),
                    BACKUP_DIR
                )

                if (!dir.exists()) dir.mkdirs()

                File(dir, BACKUP_NAME).writeText(payload)
            }
        }
    }

    private fun mergedCounts(context: Context): Counts {
        val local = localCounts(context)
        val backup = readBackup(context)

        if (backup == null) {
            // Primeira execução: cria a cópia persistente com o estado atual.
            writeBackup(context, local)
            return local
        }

        // Nunca diminui uma cota consumida.
        val merged = clamp(
            Counts(
                games = maxOf(local.games, backup.games),
                dlcs = maxOf(local.dlcs, backup.dlcs),
                updates = maxOf(local.updates, backup.updates)
            )
        )

        if (merged != local) saveLocal(context, merged)
        if (merged != backup) writeBackup(context, merged)

        return merged
    }

    fun used(context: Context, bucket: Bucket): Int {
        if (!BuildConfig.PUBLIC_BETA) return 0

        val counts = mergedCounts(context)

        return when (bucket) {
            Bucket.GAME -> counts.games
            Bucket.DLC -> counts.dlcs
            Bucket.UPDATE -> counts.updates
        }
    }

    fun remaining(context: Context, bucket: Bucket): Int {
        if (!BuildConfig.PUBLIC_BETA) return Int.MAX_VALUE
        return (maxFor(bucket) - used(context, bucket)).coerceAtLeast(0)
    }

    fun usedGames(context: Context): Int = used(context, Bucket.GAME)
    fun usedDlcs(context: Context): Int = used(context, Bucket.DLC)
    fun usedUpdates(context: Context): Int = used(context, Bucket.UPDATE)

    fun remainingGames(context: Context): Int = remaining(context, Bucket.GAME)
    fun remainingDlcs(context: Context): Int = remaining(context, Bucket.DLC)
    fun remainingUpdates(context: Context): Int = remaining(context, Bucket.UPDATE)

    fun fullyExhausted(context: Context): Boolean {
        return BuildConfig.PUBLIC_BETA &&
            remainingGames(context) <= 0 &&
            remainingDlcs(context) <= 0 &&
            remainingUpdates(context) <= 0
    }

    fun requestedGames(items: List<PkgItem>): Int {
        return items.count { bucketFor(it.kind) == Bucket.GAME }
    }

    fun requestedDlcs(items: List<PkgItem>): Int {
        return items.count { bucketFor(it.kind) == Bucket.DLC }
    }

    fun requestedUpdates(items: List<PkgItem>): Int {
        return items.count { bucketFor(it.kind) == Bucket.UPDATE }
    }

    fun canFit(context: Context, items: List<PkgItem>): Boolean {
        if (!BuildConfig.PUBLIC_BETA) return true

        return requestedGames(items) <= remainingGames(context) &&
            requestedDlcs(items) <= remainingDlcs(context) &&
            requestedUpdates(items) <= remainingUpdates(context)
    }

    @Synchronized
    fun recordCompleted(context: Context, kind: PkgKind) {
        if (!BuildConfig.PUBLIC_BETA) return

        val current = mergedCounts(context)

        val next = when (bucketFor(kind)) {
            Bucket.GAME -> current.copy(
                games = (current.games + 1)
                    .coerceAtMost(BuildConfig.BETA_MAX_GAMES)
            )

            Bucket.DLC -> current.copy(
                dlcs = (current.dlcs + 1)
                    .coerceAtMost(BuildConfig.BETA_MAX_DLCS)
            )

            Bucket.UPDATE -> current.copy(
                updates = (current.updates + 1)
                    .coerceAtMost(BuildConfig.BETA_MAX_UPDATES)
            )
        }

        saveLocal(context, next)
        writeBackup(context, next)
    }
}
