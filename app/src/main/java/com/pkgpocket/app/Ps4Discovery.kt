package com.pkgpocket.app

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Locale

object Ps4Discovery {
    data class Info(
        val statusCode: Int,
        val hostName: String,
        val hostType: String,
        val hostId: String,
        val systemVersionRaw: String,
        val firmware: String?
    )

    private const val PS4_DISCOVERY_PORT = 987
    private const val LOCAL_DISCOVERY_PORT = 1987
    private const val PS4_DDP_VERSION = "00020020"

    fun query(
        ip: String,
        timeoutMs: Int = 650
    ): Info? {
        if (ip.isBlank()) return null

        val address = runCatching { InetAddress.getByName(ip) }.getOrNull()
            ?: return null

        val socket = openSocket() ?: return null

        return try {
            socket.soTimeout = timeoutMs

            val request = (
                "SRCH * HTTP/1.1\r\n" +
                    "device-discovery-protocol-version:$PS4_DDP_VERSION\r\n\r\n"
                ).toByteArray(StandardCharsets.US_ASCII)

            val send = DatagramPacket(
                request,
                request.size,
                address,
                PS4_DISCOVERY_PORT
            )

            repeat(2) {
                runCatching { socket.send(send) }

                val buffer = ByteArray(2048)
                val receive = DatagramPacket(buffer, buffer.size)

                val text = runCatching {
                    socket.receive(receive)
                    String(
                        receive.data,
                        receive.offset,
                        receive.length,
                        StandardCharsets.UTF_8
                    )
                }.getOrNull()

                if (!text.isNullOrBlank()) {
                    parse(text)?.let { return it }
                }
            }

            null
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun openSocket(): DatagramSocket? {
        val preferred = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(LOCAL_DISCOVERY_PORT))
            }
        }.getOrNull()

        if (preferred != null) return preferred

        return runCatching { DatagramSocket() }.getOrNull()
    }

    private fun parse(raw: String): Info? {
        val normalized = raw.replace("\u0000", "")
        val lines = normalized
            .split(Regex("\\r?\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val first = lines.firstOrNull() ?: return null
        if (!first.startsWith("HTTP/1.1", ignoreCase = true)) return null

        val statusCode = first
            .split(Regex("\\s+"))
            .getOrNull(1)
            ?.toIntOrNull()
            ?: 0

        val headers = linkedMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val p = line.indexOf(':')
            if (p > 0) {
                headers[line.substring(0, p).trim().lowercase(Locale.ROOT)] =
                    line.substring(p + 1).trim()
            }
        }

        val rawVersion = headers["system-version"].orEmpty()

        return Info(
            statusCode = statusCode,
            hostName = headers["host-name"].orEmpty(),
            hostType = headers["host-type"].orEmpty(),
            hostId = headers["host-id"].orEmpty(),
            systemVersionRaw = rawVersion,
            firmware = firmwareFromSystemVersion(rawVersion)
        )
    }

    fun firmwareFromSystemVersion(raw: String): String? {
        val clean = raw.trim().uppercase(Locale.ROOT)
        if (clean.length < 4) return null

        val first4 = clean.take(4)

        if (first4.all { it.isDigit() }) {
            val major = first4.substring(0, 2).toIntOrNull() ?: return null
            val minor = first4.substring(2, 4).toIntOrNull() ?: return null
            return "%d.%02d".format(Locale.US, major, minor)
        }

        val major = first4.substring(0, 2).toIntOrNull(16) ?: return null
        val minor = first4.substring(2, 4).toIntOrNull(16) ?: return null
        return "%d.%02d".format(Locale.US, major, minor)
    }
}
