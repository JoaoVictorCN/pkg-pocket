package com.pkgpocket.app

import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

object NetworkUtils {
    data class RpiEndpoint(
        val ip: String,
        val port: Int
    )

    private val RPI_PORTS = listOf(
        12800,
        12801,
        12802,
        12803,
        12804,
        12805,
        12806,
        12807,
        12808,
        12809,
        12810
    )

    fun localIpv4(): String? {
        val all = NetworkInterface.getNetworkInterfaces() ?: return null
        while (all.hasMoreElements()) {
            val ni = all.nextElement()
            if (!ni.isUp || ni.isLoopback) continue
            val addrs = ni.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress
            }
        }
        return null
    }

    /**
     * Valida se o serviço na porta é realmente a API do Remote Package Installer.
     *
     * /api/is_exists é usado como probe por ser somente leitura. Um servidor HTTP
     * comum (por exemplo python -m http.server) pode ter a porta aberta, mas não
     * responde com o formato esperado da API RPI e portanto é rejeitado.
     */
    private fun isRpiService(
        ip: String,
        port: Int,
        timeoutMs: Int = 900
    ): Boolean {
        var conn: HttpURLConnection? = null

        return try {
            conn = (URL("http://$ip:$port/api/is_exists")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs.coerceAtLeast(250)
                readTimeout = timeoutMs.coerceAtLeast(900)
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            val body = "{\"title_id\":\"CUSA00000\"}"
            conn.outputStream.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) {
                conn.inputStream
            } else {
                conn.errorStream
            }

            val text = stream
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
                .lowercase()

            code in 200..299 &&
                text.contains("\"status\"") &&
                (
                    text.contains("\"success\"") ||
                    text.contains("\"fail\"")
                ) &&
                (
                    text.contains("\"exists\"") ||
                    text.contains("\"error_code\"")
                )
        } catch (_: Exception) {
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun findRpiOnPort(port: Int): String? {
        val local = localIpv4() ?: return null
        val prefix = local.substringBeforeLast('.')
        val found = AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(48)

        val futures = (1..254).map { n ->
            pool.submit {
                if (found.get() != null) return@submit

                val ip = "$prefix.$n"
                if (ip == local) return@submit

                try {
                    // TCP rápido primeiro para não fazer HTTP em todos os 254 hosts.
                    val portOpen = Socket().use { socket ->
                        socket.connect(
                            InetSocketAddress(ip, port),
                            180
                        )
                        true
                    }

                    if (
                        portOpen &&
                        found.get() == null &&
                        isRpiService(ip, port)
                    ) {
                        found.compareAndSet(null, ip)
                    }
                } catch (_: Exception) {
                    // Host/porta indisponível: continua a varredura.
                }
            }
        }

        futures.forEach { runCatching { it.get() } }
        pool.shutdownNow()
        return found.get()
    }

    /**
     * Detecta automaticamente o endpoint do RPI.
     *
     * A porta escolhida pelo usuário é testada primeiro. Se não houver um RPI
     * válido nela, 12800..12810 continuam como fallback.
     */
    fun findRpiEndpoint(preferredPort: Int? = null): RpiEndpoint? {
        val ports = buildList {
            preferredPort
                ?.takeIf { it in 1..65535 }
                ?.let { add(it) }

            addAll(RPI_PORTS)
        }.distinct()

        for (port in ports) {
            val ip = findRpiOnPort(port)
            if (ip != null) {
                return RpiEndpoint(ip, port)
            }
        }
        return null
    }

    /** Compatibilidade com chamadas antigas. */
    fun findRpi(port: Int = 12800): String? {
        return if (port != 12800) {
            findRpiOnPort(port)
        } else {
            findRpiEndpoint()?.ip
        }
    }

    /**
     * Verifica RPI de verdade, e não apenas se existe qualquer serviço TCP na porta.
     * Usado pelo diagnóstico, status da Home e pré-check do InstallerService.
     */
    fun canConnect(
        ip: String,
        port: Int = 12800,
        timeoutMs: Int = 900
    ): Boolean = isRpiService(ip, port, timeoutMs)
}
