package com.pkgpocket.app

import android.content.Context

object PublicBetaUsage {
    private const val PREFS = "pkg_pocket_public_beta"
    private const val KEY_GAMES = "completed_game_uses"
    private const val KEY_DLCS = "completed_dlc_uses"
    private const val KEY_UPDATES = "completed_update_uses"

    enum class Bucket {
        GAME,
        DLC,
        UPDATE
    }

    private fun bucketFor(kind: PkgKind): Bucket {
        return when (kind) {
            PkgKind.GAME -> Bucket.GAME
            PkgKind.DLC -> Bucket.DLC
            PkgKind.UPDATE -> Bucket.UPDATE
            // Evita uma brecha de uso ilimitado para PKGs que não forem classificados.
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

    fun used(context: Context, bucket: Bucket): Int {
        if (!BuildConfig.PUBLIC_BETA) return 0

        return context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(keyFor(bucket), 0)
            .coerceIn(0, maxFor(bucket))
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
        return items.count {
            bucketFor(it.kind) == Bucket.GAME
        }
    }

    fun requestedDlcs(items: List<PkgItem>): Int {
        return items.count {
            bucketFor(it.kind) == Bucket.DLC
        }
    }

    fun requestedUpdates(items: List<PkgItem>): Int {
        return items.count {
            bucketFor(it.kind) == Bucket.UPDATE
        }
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

        val bucket = bucketFor(kind)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = keyFor(bucket)
        val max = maxFor(bucket)
        val current = prefs.getInt(key, 0).coerceIn(0, max)

        if (current >= max) return

        prefs.edit()
            .putInt(key, current + 1)
            .commit()
    }
}
