package com.pkgpocket.app

import android.content.ContentResolver
import android.net.Uri
import java.io.EOFException
import java.io.FileInputStream
import java.security.MessageDigest

object PkgRangeDiagnostic {

    data class Expected(
        val start: Long,
        val sha256: String
    )

    private const val BLOCK =
        16L * 1024L * 1024L

    private val expected = listOf(
        Expected(
            38581239808L,
            "bf1d4952b8f1ac5a496c9a93b4426f5f2572db952058c099ccbef08aee99ce19"
        ),
        Expected(
            38598017024L,
            "77df21da33ff15cd42e5e6fe7b1c6aa94a1c97bd99fd47f7fc76fac2c2025e02"
        ),
        Expected(
            38614794240L,
            "1fb47e51358328c620d60fe9e649e2f9e14375a7b16f50cbe564135b50a6f05f"
        ),
        Expected(
            38631571456L,
            "04594e12591a84a235a5d8f670dd0217f15b6dd57721579d0b88338fe03b6cf3"
        ),
        Expected(
            38648348672L,
            "00263b455e8f4749e5dbcc2f2a17cfecb078e0f60fcbe3db6ecd8c298b99346a"
        )
    )

    fun run(
        resolver: ContentResolver,
        uri: Uri
    ): String {

        val fileName =
            uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.ifBlank { "package.pkg" }
                ?: "package.pkg"

        val fileSize =
            resolver.openFileDescriptor(
                uri,
                "r"
            )?.use {
                it.statSize
            } ?: -1L

        val out = StringBuilder()

        out.appendLine(
            "=== PKG POCKET RANGE DIAGNOSTIC ==="
        )
        out.appendLine("Arquivo: $fileName")
        out.appendLine("Tamanho: $fileSize")
        out.appendLine("URI: $uri")
        out.appendLine()

        if (
            fileSize >= 0L &&
            fileSize < 38665125888L
        ) {
            out.appendLine(
                "ERRO: arquivo menor que a região crítica."
            )
            return out.toString()
        }

        val actualHashes =
            expected.mapIndexed { index, e ->

                val end =
                    e.start + BLOCK - 1L

                val actual =
                    hashRange(
                        resolver,
                        uri,
                        e.start,
                        BLOCK
                    )

                val ok =
                    actual.equals(
                        e.sha256,
                        ignoreCase = true
                    )

                out.appendLine(
                    "BLOCK ${index + 1}"
                )
                out.appendLine(
                    "range=${e.start}-$end"
                )
                out.appendLine(
                    "size=$BLOCK"
                )
                out.appendLine(
                    "expected=${e.sha256}"
                )
                out.appendLine(
                    "actual=$actual"
                )
                out.appendLine(
                    "result=" +
                        if (ok) {
                            "MATCH"
                        } else {
                            "MISMATCH"
                        }
                )
                out.appendLine()

                actual
            }

        /*
         * O bloco 3 já foi lido uma vez acima.
         * Aqui abrimos mais DOIS descritores independentes.
         *
         * Total do teste:
         * 5 blocos + 2 repetições = 112 MiB.
         */
        val criticalStart =
            38614794240L

        val repeat1 =
            hashRange(
                resolver,
                uri,
                criticalStart,
                BLOCK
            )

        val repeat2 =
            hashRange(
                resolver,
                uri,
                criticalStart,
                BLOCK
            )

        val originalCritical =
            actualHashes[2]

        out.appendLine(
            "=== CRITICAL REPEAT ==="
        )
        out.appendLine(
            "original=$originalCritical"
        )
        out.appendLine(
            "repeat1=$repeat1"
        )
        out.appendLine(
            "repeat2=$repeat2"
        )

        val stable =
            originalCritical == repeat1 &&
                repeat1 == repeat2

        out.appendLine(
            "LOCAL_REPEAT=" +
                if (stable) {
                    "IDENTICAL"
                } else {
                    "DIFFERENT"
                }
        )

        val mismatches =
            actualHashes
                .zip(expected)
                .count { (actual, e) ->
                    !actual.equals(
                        e.sha256,
                        ignoreCase = true
                    )
                }

        out.appendLine()
        out.appendLine(
            "=== RESULTADO ==="
        )

        when {
            !stable -> {
                out.appendLine(
                    "LOCAL_READ_UNSTABLE"
                )
            }

            mismatches == 0 -> {
                out.appendLine(
                    "ALL_RANGES_MATCH_MIRROR"
                )
            }

            else -> {
                out.appendLine(
                    "LOCAL_PKG_DIFFERS_FROM_MIRROR"
                )
                out.appendLine(
                    "MISMATCHES=$mismatches"
                )
            }
        }

        return out.toString()
    }

    private fun hashRange(
        resolver: ContentResolver,
        uri: Uri,
        start: Long,
        length: Long
    ): String {

        val digest =
            MessageDigest.getInstance(
                "SHA-256"
            )

        val pfd =
            resolver.openFileDescriptor(
                uri,
                "r"
            ) ?: error(
                "Não foi possível abrir o PKG"
            )

        pfd.use {

            FileInputStream(
                it.fileDescriptor
            ).use { fis ->

                seek(
                    fis,
                    start
                )

                val buffer =
                    ByteArray(
                        1024 * 1024
                    )

                var remaining =
                    length

                while (
                    remaining > 0L
                ) {

                    val ask =
                        minOf(
                            buffer.size.toLong(),
                            remaining
                        ).toInt()

                    val n =
                        fis.read(
                            buffer,
                            0,
                            ask
                        )

                    if (n < 0) {
                        throw EOFException(
                            "PKG terminou durante range iniciado em $start"
                        )
                    }

                    if (n == 0) {
                        continue
                    }

                    digest.update(
                        buffer,
                        0,
                        n
                    )

                    remaining -= n
                }
            }
        }

        return digest
            .digest()
            .joinToString("") {
                "%02x".format(it)
            }
    }

    /*
     * Mesmo método do PkgHttpServer:
     * FileChannel.position() e fallback skip/read.
     */
    private fun seek(
        fis: FileInputStream,
        offset: Long
    ) {

        if (offset == 0L) {
            return
        }

        try {
            fis.channel.position(
                offset
            )
            return
        } catch (_: Throwable) {
        }

        var remaining =
            offset

        while (
            remaining > 0L
        ) {

            val skipped =
                fis.skip(
                    remaining
                )

            if (skipped > 0L) {
                remaining -= skipped
            } else {

                if (fis.read() < 0) {
                    throw EOFException(
                        "Não foi possível avançar até o byte $offset"
                    )
                }

                remaining--
            }
        }
    }
}
