package com.pkgpocket.app

import android.content.ContentResolver
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PkgHttpServer(
    private val resolver: ContentResolver,
    private val port: Int = 8080,
    private val itemsProvider: () -> List<PkgItem>
) {
    private val running = AtomicBoolean(false)
    private val workers = Executors.newFixedThreadPool(12)
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        server = ServerSocket(port)
        acceptThread = Thread {
            while (running.get()) {
                try {
                    val socket = server?.accept() ?: break
                    workers.execute { handle(socket) }
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }.apply { name = "pkg-http-accept"; start() }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        workers.shutdownNow()
    }

    fun urlFor(localIp: String, item: PkgItem): String {
        val safeName = item.fileName.replace(" ", "%20")
        return "http://$localIp:$port/pkg/${item.token}/$safeName"
    }

    private fun handle(socket: Socket) = socket.use { s ->
        s.soTimeout = 30_000
        val input = BufferedInputStream(s.getInputStream())
        val output = BufferedOutputStream(s.getOutputStream(), 256 * 1024)
        val headerText = readHeaders(input) ?: return@use
        val lines = headerText.split("\r\n")
        val req = lines.firstOrNull()?.split(' ') ?: return@use
        if (req.size < 2) return@use
        val method = req[0]
        val path = URLDecoder.decode(req[1], StandardCharsets.UTF_8.name())
        val token = path.split('/').getOrNull(2)
        val item = itemsProvider().firstOrNull { it.token == token }
        if (item == null || (method != "GET" && method != "HEAD")) {
            output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush(); return@use
        }

        val rangeHeader = lines.firstOrNull { it.startsWith("Range:", true) }?.substringAfter(':')?.trim()
        val parsed = parseRange(rangeHeader, item.size)
        val start = parsed.first
        val end = parsed.second
        val len = end - start + 1
        val partial = rangeHeader != null
        val status = if (partial) "206 Partial Content" else "200 OK"
        val h = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $len\r\n")
            if (partial) append("Content-Range: bytes $start-$end/${item.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(h.toByteArray(StandardCharsets.US_ASCII))
        if (method == "HEAD") { output.flush(); return@use }

        resolver.openFileDescriptor(item.uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { fis ->
                val ch = fis.channel
                ch.position(start)
                val buf = ByteArray(512 * 1024)
                var remaining = len
                while (remaining > 0) {
                    val ask = minOf(buf.size.toLong(), remaining).toInt()
                    val n = fis.read(buf, 0, ask)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    remaining -= n
                }
            }
        }
        output.flush()
    }

    private fun parseRange(value: String?, size: Long): Pair<Long, Long> {
        if (value.isNullOrBlank() || !value.startsWith("bytes=")) return 0L to (size - 1)
        val raw = value.removePrefix("bytes=").substringBefore(',')
        val a = raw.substringBefore('-').toLongOrNull() ?: 0L
        val b = raw.substringAfter('-', "").toLongOrNull() ?: (size - 1)
        return a.coerceIn(0, size - 1) to b.coerceIn(a, size - 1)
    }

    private fun readHeaders(input: BufferedInputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        var state = 0
        while (out.size() < 64 * 1024) {
            val x = input.read()
            if (x < 0) return null
            out.write(x)
            state = when {
                state == 0 && x == '\r'.code -> 1
                state == 1 && x == '\n'.code -> 2
                state == 2 && x == '\r'.code -> 3
                state == 3 && x == '\n'.code -> 4
                else -> 0
            }
            if (state == 4) break
        }
        return out.toString(StandardCharsets.US_ASCII.name())
    }
}
