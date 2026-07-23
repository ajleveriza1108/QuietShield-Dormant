package com.ajcoder.quietshield.dormant.wireless

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds Android's temporary Wireless Debugging pairing service. The pairing
 * port changes whenever Android opens a new pairing-code screen, so the port
 * is intentionally discovered instead of shown to the user.
 */
class WirelessPairingDiscovery(
    context: Context,
    private val callback: Callback,
) {
    data class Endpoint(
        val host: String,
        val port: Int,
        val serviceName: String,
    )

    interface Callback {
        fun onSearching()
        fun onEndpointChanged(endpoint: Endpoint)
        fun onEndpointLost()
        fun onFailure(message: String)
    }

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val started = AtomicBoolean(false)
    private val resolving = AtomicBoolean(false)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var currentServiceName: String? = null

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            callback.onSearching()
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (!serviceInfo.serviceType.startsWith(SERVICE_TYPE_PREFIX)) return
            if (!resolving.compareAndSet(false, true)) return
            runCatching {
                nsdManager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            resolving.set(false)
                        }

                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            resolving.set(false)
                            val host = resolved.host?.hostAddress.orEmpty()
                            val port = resolved.port
                            if (host.isBlank() || port !in 1..65535) return
                            currentServiceName = resolved.serviceName
                            callback.onEndpointChanged(
                                Endpoint(
                                    host = host,
                                    port = port,
                                    serviceName = resolved.serviceName,
                                ),
                            )
                        }
                    },
                )
            }.onFailure {
                resolving.set(false)
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            if (serviceInfo.serviceName == currentServiceName) {
                currentServiceName = null
                callback.onEndpointLost()
            }
        }

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            started.set(false)
            releaseMulticastLock()
            callback.onFailure(
                "Dormant could not look for Android's pairing screen. Turn Wi-Fi off and on, then try again.",
            )
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        acquireMulticastLock()
        callback.onSearching()
        runCatching {
            nsdManager.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener,
            )
        }.onFailure {
            started.set(false)
            releaseMulticastLock()
            callback.onFailure(
                "Dormant could not start Wireless Debugging discovery. Make sure Wi-Fi is on.",
            )
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        releaseMulticastLock()
        resolving.set(false)
        currentServiceName = null
    }

    private fun acquireMulticastLock() {
        multicastLock = runCatching {
            wifiManager.createMulticastLock("quietshield-dormant-pairing").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun releaseMulticastLock() {
        runCatching {
            multicastLock?.takeIf { it.isHeld }?.release()
        }
        multicastLock = null
    }

    companion object {
        private const val SERVICE_TYPE = "_adb-tls-pairing._tcp."
        private const val SERVICE_TYPE_PREFIX = "_adb-tls-pairing._tcp"
    }
}
