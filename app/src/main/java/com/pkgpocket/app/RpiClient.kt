package com.pkgpocket.app

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

object RpiClient {
    data class InstallResult(val taskId: Int?, val raw: String)

    fun install(ps4Ip: String, pkgUrl: String): InstallResult {
        val body = JSONObject()
            .put("type", "direct")
            .put("packages", org.json.JSONArray().put(pkgUrl))

        val raw = post(ps4Ip, "/api/install", body)
        val json = runCatching { JSONObject(raw) }.getOrNull()

        // Algumas versões do RPI devolvem respostas HTTP malformadas.
        // Se o JSON normal falhar, ainda tentamos extrair o task_id do texto recebido.
        val id = json?.optInt("task_id", -1)?.takeIf { it >= 0 }
            ?: Regex("""["']?task_id["']?\s*:\s*(\d+)""")
                .find(raw)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        return InstallResult(id, raw)
    }

    fun progress(ps4Ip: String, taskId: Int): JSONObject {
        val raw = post(
            ps4Ip,
            "/api/get_task_progress",
            JSONObject().put("task_id", taskId)
        )
        return JSONObject(raw)
    }

    fun pause(ps4Ip: String, taskId: Int) =
        post(ps4Ip, "/api/pause_task", JSONObject().put("task_id", taskId))

    fun resume(ps4Ip: String, taskId: Int) =
        post(ps4Ip, "/api/resume_task", JSONObject().put("task_id", taskId))

    fun stop(ps4Ip: String, taskId: Int) =
        post(ps4Ip, "/api/stop_task", JSONObject().put("task_id", taskId))

    fun unregister(ps4Ip: String, taskId: Int) =
        post(ps4Ip, "/api/unregister_task", JSONObject().put("task_id", taskId))

    /*
     * O servidor HTTP do Remote Package Installer pode fechar a conexão
     * antes do que HttpURLConnection/OkHttp espera. No Android isso vira
     * "unexpected end of stream", mesmo quando o corpo JSON já chegou.
     *
     * Aqui usamos um socket HTTP simples e tolerante: se o PS4 fechar cedo,
     * preservamos os bytes que já chegaram e tentamos interpretar o JSON.
     */
    private fun post(ip: String, path: String, json: JSONObject): String {
        val requestBody = json.toString().toByteArray(Charsets.UTF_8)

        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = 5000
            socket.connect(InetSocketAddress(ip, 12800), 2500)

            val output = BufferedOutputStream(socket.getOutputStream())
            val headers = buildString {
                append("POST $path HTTP/1.1\r\n")
                append("Host: $ip:12800\r\n")
                append("Content-Type: application/json\r\n")
                append("Content-Length: ${requestBody.size}\r\n")
                append("Accept: application/json\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }

            output.write(headers.toByteArray(Charsets.US_ASCII))
            output.write(requestBody)
            output.flush()

            val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)

            // Algumas builds do RPI retornam JSON direto no socket, sem status-line HTTP.
            // Detectamos isso antes de tentar interpretar os headers.
            val prefix = ByteArrayOutputStream()
            var firstMeaningful = -1
            while (prefix.size() < 32 && firstMeaningful < 0) {
                val b = input.read()
                if (b < 0) break
                prefix.write(b)
                if (!b.toChar().isWhitespace()) firstMeaningful = b
            }

            val statusCode: Int
            val bodyBytes: ByteArray

            if (firstMeaningful == '{'.code || firstMeaningful == '['.code) {
                statusCode = 200
                bodyBytes = readJsonBody(input, prefix.toByteArray())
            } else {
                val responseHeaders = readResponseHeaders(input, prefix.toByteArray())
                statusCode = parseStatusCode(responseHeaders)

                val headerMap = responseHeaders
                    .lineSequence()
                    .drop(1)
                    .mapNotNull { line ->
                        val i = line.indexOf(':')
                        if (i <= 0) null
                        else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
                    }
                    .toMap()

                bodyBytes = when {
                    headerMap["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true ->
                        readChunkedBody(input)

                    headerMap["content-length"]?.toLongOrNull() != null ->
                        readKnownLengthBody(input, headerMap["content-length"]!!.toLong())

                    else ->
                        readUntilCloseOrTimeout(input)
                }
            }

            var text = bodyBytes.toString(Charsets.UTF_8).trim()

            // O RPI às vezes retorna hexadecimal sem aspas, que não é JSON válido.
            text = text.replace(
                Regex("""0[xX][0-9a-fA-F]+""")
            ) { "\"${it.value}\"" }

            // Descarta eventual lixo antes/depois do objeto JSON.
            val firstBrace = text.indexOf('{')
            val lastBrace = text.lastIndexOf('}')
            if (firstBrace >= 0 && lastBrace >= firstBrace) {
                text = text.substring(firstBrace, lastBrace + 1)
            }

            if (statusCode !in 200..299) {
                error("RPI respondeu HTTP $statusCode: $text")
            }

            if (text.isBlank()) {
                error("RPI respondeu sem corpo")
            }

            return text
        }
    }

    private fun readResponseHeaders(input: BufferedInputStream, prefix: ByteArray): String {
        val out = ByteArrayOutputStream()
        out.write(prefix)

        var state = 0
        for (b in prefix) {
            val x = b.toInt() and 0xFF
            state = when {
                state == 0 && x == '\r'.code -> 1
                state == 1 && x == '\n'.code -> 2
                state == 2 && x == '\r'.code -> 3
                state == 3 && x == '\n'.code -> 4
                else -> 0
            }
        }

        while (out.size() < 64 * 1024 && state != 4) {
            val b = input.read()
            if (b < 0) break
            out.write(b)

            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> 4
                else -> 0
            }
        }

        val text = out.toString(Charsets.US_ASCII.name())
        if (!text.trimStart().startsWith("HTTP/")) {
            error("Resposta inválida do RPI: ${text.take(120).replace("\r", "\\r").replace("\n", "\\n")}")
        }
        return text.trimStart()
    }

    private fun readJsonBody(input: BufferedInputStream, prefix: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(prefix)

        var depth = 0
        var started = false
        var inString = false
        var escaped = false

        fun consume(x: Int): Boolean {
            val c = x.toChar()

            if (!started) {
                if (c == '{' || c == '[') {
                    started = true
                    depth = 1
                }
                return false
            }

            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                return false
            }

            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return true
                }
            }
            return false
        }

        // Reprocessa apenas a partir do primeiro caractere JSON do prefixo.
        depth = 0
        started = false
        inString = false
        escaped = false

        var complete = false
        for (b in prefix) {
            if (consume(b.toInt() and 0xFF)) {
                complete = true
                break
            }
        }

        while (!complete && out.size() < 1024 * 1024) {
            val b = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                break
            }
            if (b < 0) break
            out.write(b)
            if (consume(b)) complete = true
        }

        return out.toByteArray()
    }

    private fun parseStatusCode(headers: String): Int {
        return headers.lineSequence()
            .firstOrNull()
            ?.split(' ')
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: error("Status HTTP inválido do RPI")
    }

    private fun readKnownLengthBody(input: BufferedInputStream, expected: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var remaining = expected.coerceAtMost(1024L * 1024L)

        while (remaining > 0) {
            val ask = minOf(buffer.size.toLong(), remaining).toInt()
            val n = try {
                input.read(buffer, 0, ask)
            } catch (_: SocketTimeoutException) {
                break
            }

            if (n < 0) break
            if (n == 0) continue

            out.write(buffer, 0, n)
            remaining -= n
        }

        return out.toByteArray()
    }

    private fun readUntilCloseOrTimeout(input: BufferedInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)

        while (out.size() < 1024 * 1024) {
            val n = try {
                input.read(buffer)
            } catch (_: SocketTimeoutException) {
                break
            }

            if (n < 0) break
            if (n == 0) continue
            out.write(buffer, 0, n)
        }

        return out.toByteArray()
    }

    private fun readChunkedBody(input: BufferedInputStream): ByteArray {
        val out = ByteArrayOutputStream()

        while (out.size() < 1024 * 1024) {
            val sizeLine = readAsciiLine(input).substringBefore(';').trim()
            val chunkSize = sizeLine.toLongOrNull(16) ?: break
            if (chunkSize <= 0L) break

            val chunk = readKnownLengthBody(input, chunkSize)
            out.write(chunk)

            // CRLF depois do chunk.
            runCatching { input.read() }
            runCatching { input.read() }

            if (chunk.size.toLong() < chunkSize) break
        }

        return out.toByteArray()
    }

    private fun readAsciiLine(input: BufferedInputStream): String {
        val out = ByteArrayOutputStream()

        while (out.size() < 8192) {
            val b = input.read()
            if (b < 0) break

            if (b == '\r'.code) {
                val next = input.read()
                if (next == '\n'.code) break
                out.write(b)
                if (next >= 0) out.write(next)
            } else {
                out.write(b)
            }
        }

        return out.toString(Charsets.US_ASCII.name())
    }

    fun bytesTotal(j: JSONObject): Long =
        j.optLong("length_total", j.optLong("length", 0L))

    fun bytesDone(j: JSONObject): Long =
        j.optLong("transferred_total", j.optLong("transferred", 0L))

    fun percent(j: JSONObject): Int {
        val total = bytesTotal(j)
        val done = bytesDone(j)

        return if (total > 0) {
            ((done * 100L) / total).toInt().coerceIn(0, 100)
        } else {
            j.optInt("preparing_percent", 0).coerceIn(0, 100)
        }
    }

    fun isFinished(j: JSONObject): Boolean {
        val total = bytesTotal(j)
        val done = bytesDone(j)
        return total > 0 && done >= total
    }
}
