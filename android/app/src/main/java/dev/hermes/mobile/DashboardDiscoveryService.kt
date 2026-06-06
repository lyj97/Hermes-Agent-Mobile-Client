package dev.hermes.mobile

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object DashboardDiscoveryService {
    private const val LOG_TAG = "DashboardDiscovery"

    fun discoverHermesDashboardBases(
        context: Context,
        onAttempt: (String) -> Unit = {},
    ): List<String> {
        val candidates = linkedSetOf<String>()

        discoverViaMdns(context).forEach { host ->
            candidates += "http://$host:${HermesConfig.HERMES_DEFAULT_PORT}"
        }
        discoverViaLanProbe().forEach { host ->
            candidates += "http://$host:${HermesConfig.HERMES_DEFAULT_PORT}"
        }

        val verified = mutableListOf<String>()
        for (base in candidates) {
            onAttempt(base)
            if (isHermesDashboardBase(base)) {
                verified += base
            }
        }
        Log.d(LOG_TAG, "Discovery verified ${verified.size} Hermes dashboard endpoints")
        return verified
    }

    fun discoverViaMdns(context: Context): Set<String> {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptySet()
        val hosts = Collections.synchronizedSet(mutableSetOf<String>())
        val done = CountDownLatch(1)
        val resolvePending = Collections.synchronizedList(mutableListOf<CountDownLatch>())

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {
                done.countDown()
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                done.countDown()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                done.countDown()
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                val name = service.serviceName.lowercase()
                if (!name.contains("hermes")) return
                val wait = CountDownLatch(1)
                resolvePending += wait
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        wait.countDown()
                    }
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress
                        if (!host.isNullOrBlank()) hosts += host
                        wait.countDown()
                    }
                })
            }
        }

        return try {
            nsd.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            done.await(3500, TimeUnit.MILLISECONDS)
            runCatching { nsd.stopServiceDiscovery(listener) }
            resolvePending.forEach { it.await(1200, TimeUnit.MILLISECONDS) }
            hosts
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun discoverViaLanProbe(): Set<String> {
        val localIp = localIpv4Address() ?: return emptySet()
        val parts = localIp.split(".")
        if (parts.size != 4) return emptySet()
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}."
        val found = Collections.synchronizedSet(mutableSetOf<String>())
        val pool = Executors.newFixedThreadPool(32)
        val stop = AtomicBoolean(false)
        try {
            for (i in 1..254) {
                pool.execute {
                    if (stop.get()) return@execute
                    val host = "$prefix$i"
                    if (isHermesDashboardBase("http://$host:${HermesConfig.HERMES_DEFAULT_PORT}")) {
                        found += host
                        stop.set(true)
                    }
                }
            }
            pool.shutdown()
            val finished = pool.awaitTermination(8, TimeUnit.SECONDS)
            if (!finished) pool.shutdownNow()
        } catch (_: Exception) {
            pool.shutdownNow()
        }
        return found
    }

    fun isHermesDashboardBase(baseUrl: String): Boolean {
        val clean = normalizeDashboardBase(baseUrl)
        if (clean.isBlank()) return false
        val statusUrl = "$clean${HermesConfig.ENDPOINT_API_STATUS}"
        return try {
            Log.d(LOG_TAG, "probe $statusUrl")
            val conn = (URL(statusUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = HermesConfig.DISCOVERY_CONNECT_TIMEOUT_MS
                readTimeout = HermesConfig.DISCOVERY_CONNECT_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code != 200) return false
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            body.contains("\"version\"") && body.contains("\"gateway_running\"")
        } catch (_: Exception) {
            false
        }
    }

    fun normalizeDashboardBase(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return try {
            val url = URL(withScheme)
            val host = url.host
            if (host.isNullOrBlank()) return ""
            val protocol = if (url.protocol.equals("https", ignoreCase = true)) "https" else "http"
            val port = if (url.port > 0) ":${url.port}" else ""
            "$protocol://$host$port"
        } catch (_: Exception) {
            withScheme
                .substringBefore("?")
                .substringBefore("#")
                .removeSuffix("/")
                .removeSuffix(HermesConfig.ENDPOINT_CHAT)
                .removeSuffix("/")
        }
    }

    fun localIpv4Address(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (!name.startsWith("wlan") && !name.startsWith("eth") && !name.startsWith("en")) continue
                val addresses = iface.inetAddresses
                for (addr in addresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
