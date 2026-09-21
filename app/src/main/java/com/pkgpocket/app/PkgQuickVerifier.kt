package com.pkgpocket.app

import android.content.ContentResolver
import android.os.SystemClock
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class QuickIssueSeverity {
    WARNING,
    ERROR
}

enum class QuickIssueCode {
    UNREADABLE,
    TOO_SMALL,
    BAD_MAGIC,
    BAD_ENTRY_COUNT,
    BAD_TABLE,
    METADATA_OUT_OF_BOUNDS,
    MISSING_TITLE_ID,
    MISSING_CONTENT_ID
}

data class QuickVerifyIssue(
    val severity: QuickIssueSeverity,
    val code: QuickIssueCode
)

data class QuickVerifyItemResult(
    val item: PkgItem,
    val issues: List<QuickVerifyIssue>
)

data class QuickVerifyReport(
    val results: List<QuickVerifyItemResult>,
    val elapsedMs: Long
) {
    val issueCount: Int
        get() = results.sumOf { it.issues.size }

    val errorCount: Int
        get() = results.sumOf { result ->
            result.issues.count { it.severity == QuickIssueSeverity.ERROR }
        }

    val okCount: Int
        get() = results.count { it.issues.isEmpty() }
}

object PkgQuickVerifier {
    private const val PKG_MAGIC = 0x7F434E54
    private const val PARAM_SFO = 0x1000
    private const val ICON0_PNG = 0x1200

    fun verify(
        resolver: ContentResolver,
        items: List<PkgItem>
    ): QuickVerifyReport {
        val started = SystemClock.elapsedRealtime()
        val results = items.map { verifyOne(resolver, it) }

        return QuickVerifyReport(
            results = results,
            elapsedMs = SystemClock.elapsedRealtime() - started
        )
    }

    private fun verifyOne(
        resolver: ContentResolver,
        item: PkgItem
    ): QuickVerifyItemResult {
        val issues = mutableListOf<QuickVerifyIssue>()

        runCatching {
            resolver.openFileDescriptor(item.uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    val ch = fis.channel
                    val actualSize = ch.size().takeIf { it > 0L }
                        ?: item.size.takeIf { it > 0L }
                        ?: -1L

                    if (actualSize in 0 until 0x1000L) {
                        issues += QuickVerifyIssue(
                            QuickIssueSeverity.ERROR,
                            QuickIssueCode.TOO_SMALL
                        )
                        return@use
                    }

                    val header = readExact(ch, 0L, 0x1000)
                        .order(ByteOrder.BIG_ENDIAN)

                    if (header.getInt(0) != PKG_MAGIC) {
                        issues += QuickVerifyIssue(
                            QuickIssueSeverity.ERROR,
                            QuickIssueCode.BAD_MAGIC
                        )
                        return@use
                    }

                    val entryCount =
                        header.getInt(0x10).toLong() and 0xffffffffL

                    if (entryCount !in 1..10000) {
                        issues += QuickVerifyIssue(
                            QuickIssueSeverity.ERROR,
                            QuickIssueCode.BAD_ENTRY_COUNT
                        )
                        return@use
                    }

                    val tableOffset =
                        header.getInt(0x18).toLong() and 0xffffffffL
                    val tableSize = entryCount * 0x20L

                    if (
                        tableOffset <= 0L ||
                        tableSize <= 0L ||
                        tableSize > 4L * 1024 * 1024 ||
                        (
                            actualSize > 0L &&
                                tableOffset + tableSize > actualSize
                            )
                    ) {
                        issues += QuickVerifyIssue(
                            QuickIssueSeverity.ERROR,
                            QuickIssueCode.BAD_TABLE
                        )
                        return@use
                    }

                    val table = runCatching {
                        readExact(
                            ch,
                            tableOffset,
                            tableSize.toInt()
                        ).order(ByteOrder.BIG_ENDIAN)
                    }.getOrElse {
                        issues += QuickVerifyIssue(
                            QuickIssueSeverity.ERROR,
                            QuickIssueCode.BAD_TABLE
                        )
                        return@use
                    }

                    for (i in 0 until entryCount.toInt()) {
                        val base = i * 0x20
                        val id = table.getInt(base)

                        if (id != PARAM_SFO && id != ICON0_PNG) continue

                        val off =
                            table.getInt(base + 0x10).toLong() and 0xffffffffL
                        val len =
                            table.getInt(base + 0x14).toLong() and 0xffffffffL

                        if (
                            actualSize > 0L &&
                            (
                                off <= 0L ||
                                    len <= 0L ||
                                    off > actualSize ||
                                    len > actualSize ||
                                    off + len > actualSize
                                )
                        ) {
                            issues += QuickVerifyIssue(
                                QuickIssueSeverity.ERROR,
                                QuickIssueCode.METADATA_OUT_OF_BOUNDS
                            )
                            break
                        }
                    }
                }
            } ?: run {
                issues += QuickVerifyIssue(
                    QuickIssueSeverity.ERROR,
                    QuickIssueCode.UNREADABLE
                )
            }
        }.onFailure {
            if (issues.none { it.code == QuickIssueCode.UNREADABLE }) {
                issues += QuickVerifyIssue(
                    QuickIssueSeverity.ERROR,
                    QuickIssueCode.UNREADABLE
                )
            }
        }

        if (item.titleId.isBlank()) {
            issues += QuickVerifyIssue(
                QuickIssueSeverity.WARNING,
                QuickIssueCode.MISSING_TITLE_ID
            )
        }

        if (item.contentId.isBlank()) {
            issues += QuickVerifyIssue(
                QuickIssueSeverity.WARNING,
                QuickIssueCode.MISSING_CONTENT_ID
            )
        }

        return QuickVerifyItemResult(item, issues.distinctBy { it.code })
    }

    private fun readExact(
        channel: java.nio.channels.FileChannel,
        offset: Long,
        size: Int
    ): ByteBuffer {
        val out = ByteBuffer.allocate(size)
        channel.position(offset)

        while (out.hasRemaining()) {
            if (channel.read(out) < 0) break
        }

        require(!out.hasRemaining()) {
            "Arquivo terminou antes do esperado"
        }

        out.flip()
        return out
    }
}
