package com.pkgpocket.app

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
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
                    Socket().use { s ->
                        s.connect(InetSocketAddress(ip, port), 120)
                        found.compareAndSet(null, ip)
                    }
                } catch (_: Exception) { }
            }
        }
        futures.forEach { runCatching { it.get() } }
        pool.shutdownNow()
        return found.get()
    }

    /**
     * Detecta automaticamente o endpoint do RPI.
     *
     * 12800 continua tendo prioridade para manter a detecção
     * da beta tão rápida quanto antes.
     */
    fun findRpiEndpoint(): RpiEndpoint? {
        for (port in RPI_PORTS) {
            val ip = findRpiOnPort(port)
            if (ip != null) {
                return RpiEndpoint(ip, port)
            }
        }
        return null
    }

    /**
     * Compatibilidade com chamadas antigas.
     */
    fun findRpi(port: Int = 12800): String? {
        return if (port != 12800) {
            findRpiOnPort(port)
        } else {
            findRpiEndpoint()?.ip
        }
    }



    fun canConnect(ip: String, port: Int = 12800, timeoutMs: Int = 700): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }
        true
    } catch (_: Exception) { false }
}
