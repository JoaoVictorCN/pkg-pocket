package com.pkgpocket.app

import android.content.ContentResolver
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.FileInputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PkgHttpServer(
    private val resolver: ContentResolver,
    private val port: Int = 8080,
    private val itemsProvider: () -> List<PkgItem>,
    private val onLog: (String) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    private val workers = Executors.newFixedThreadPool(12)
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        server = ServerSocket(port).apply { reuseAddress = true }
        onLog("Servidor HTTP ativo na porta $port")

        acceptThread = Thread {
            while (running.get()) {
                try {
                    val socket = server?.accept() ?: break
                    try {
                        workers.execute {
                            try {
                                handle(socket)
                            } catch (t: Throwable) {
                                if (!isExpectedDisconnect(t)) {
                                    onLog("HTTP falhou: ${t.javaClass.simpleName}: ${t.message ?: "sem detalhes"}")
                                }
                                runCatching { socket.close() }
                            }
                        }
                    } catch (t: Throwable) {
                        runCatching { socket.close() }
                        if (running.get()) onLog("Falha ao despachar conexão HTTP: ${t.message}")
                    }
                } catch (t: Throwable) {
                    if (!running.get()) break
                    onLog("Falha no accept HTTP: ${t.message}")
                }
            }
        }.apply {
            name = "pkg-http-accept"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        workers.shutdownNow()
    }

    fun urlFor(localIp: String, item: PkgItem): String {
        val safeName = item.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
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

        val method = req[0].uppercase()
        val path = URLDecoder.decode(req[1], StandardCharsets.UTF_8.name())
        val token = path.split('/').getOrNull(2)
        val item = itemsProvider().firstOrNull { it.token == token }

        if (item == null) {
            writeSimple(output, "404 Not Found")
            return@use
        }
        if (method != "GET" && method != "HEAD") {
            writeSimple(output, "405 Method Not Allowed")
            return@use
        }
        if (item.size <= 0L) {
            writeSimple(output, "500 Internal Server Error")
            onLog("HTTP: tamanho inválido para ${item.fileName}: ${item.size}")
            return@use
        }

        val rangeHeader = lines.firstOrNull { it.startsWith("Range:", true) }
            ?.substringAfter(':')
            ?.trim()

        val parsed = parseRange(rangeHeader, item.size)
        if (parsed == null) {
            output.write(
                ("HTTP/1.1 416 Range Not Satisfiable\r\n" +
                    "Content-Range: bytes */${item.size}\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n\r\n")
                    .toByteArray(StandardCharsets.US_ASCII)
            )
            output.flush()
            onLog("HTTP 416 para ${item.fileName}: ${rangeHeader ?: "sem Range"}")
            return@use
        }

        val (start, end) = parsed
        val len = end - start + 1
        val partial = rangeHeader != null
        val status = if (partial) "206 Partial Content" else "200 OK"

        onLog("$method ${item.fileName} • bytes $start-$end de ${item.size}")

        val h = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $len\r\n")
            if (partial) append("Content-Range: bytes $start-$end/${item.size}\r\n")
            append("Connection: close\r\n\r\n")
        }

        output.write(h.toByteArray(StandardCharsets.US_ASCII))
        if (method == "HEAD") {
            output.flush()
            return@use
        }

        val pfd = resolver.openFileDescriptor(item.uri, "r")
            ?: throw IllegalStateException("Não foi possível abrir ${item.fileName}")

        pfd.use {
            FileInputStream(it.fileDescriptor).use { fis ->
                seek(fis, start)

                val buf = ByteArray(512 * 1024)
                var remaining = len
                var sent = 0L

                while (remaining > 0 && running.get()) {
                    val ask = minOf(buf.size.toLong(), remaining).toInt()
                    val n = fis.read(buf, 0, ask)
                    if (n < 0) throw EOFException("PKG terminou antes do byte $end")
                    if (n == 0) continue

                    output.write(buf, 0, n)
                    remaining -= n
                    sent += n
                }

                output.flush()
                onLog("HTTP concluiu ${item.fileName}: $sent byte(s)")
            }
        }
    }

    private fun isExpectedDisconnect(t: Throwable): Boolean {
        if (t !is SocketException) return false
        val message = t.message.orEmpty().lowercase()
        return message.contains("broken pipe") ||
            message.contains("connection reset") ||
            message.contains("socket closed") ||
            message.contains("software caused connection abort")
    }

    private fun seek(fis: FileInputStream, offset: Long) {
        if (offset == 0L) return
        try {
            fis.channel.position(offset)
            return
        } catch (_: Throwable) {
            // Alguns DocumentProviders entregam FDs não-seekable.
        }

        var remaining = offset
        while (remaining > 0) {
            val skipped = fis.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (fis.read() < 0) throw EOFException("Não foi possível avançar até o byte $offset")
                remaining--
            }
        }
    }

    private fun parseRange(value: String?, size: Long): Pair<Long, Long>? {
        if (size <= 0L) return null
        if (value.isNullOrBlank()) return 0L to (size - 1)
        if (!value.startsWith("bytes=", ignoreCase = true)) return null

        val raw = value.substringAfter('=').substringBefore(',').trim()
        val dash = raw.indexOf('-')
        if (dash < 0) return null

        val left = raw.substring(0, dash).trim()
        val right = raw.substring(dash + 1).trim()

        // Suffix range: bytes=-500 => últimos 500 bytes
        if (left.isEmpty()) {
            val suffix = right.toLongOrNull() ?: return null
            if (suffix <= 0L) return null
            val len = minOf(suffix, size)
            return (size - len) to (size - 1)
        }

        val start = left.toLongOrNull() ?: return null
        if (start < 0L || start >= size) return null

        val end = if (right.isEmpty()) {
            size - 1
        } else {
            val requested = right.toLongOrNull() ?: return null
            if (requested < start) return null
            minOf(requested, size - 1)
        }

        return start to end
    }

    private fun writeSimple(output: BufferedOutputStream, status: String) {
        output.write(
            ("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .toByteArray(StandardCharsets.US_ASCII)
        )
        output.flush()
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
