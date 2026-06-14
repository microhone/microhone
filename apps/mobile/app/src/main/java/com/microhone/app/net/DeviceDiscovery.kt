package com.microhone.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

/** A microhone host found on the local network. */
data class DiscoveredDevice(val name: String, val host: String, val port: Int)

/**
 * Discovers `_microhone._tcp` hosts via Android's NSD (mDNS) so the user does
 * not have to type the PC's IP. Resolved devices are pushed to
 * [onDevicesChanged] on the main thread.
 */
class DeviceDiscovery(context: Context) {

    companion object {
        const val SERVICE_TYPE = "_microhone._tcp."
    }

    private val nsd =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val devices = LinkedHashMap<String, DiscoveredDevice>()

    var onDevicesChanged: (List<DiscoveredDevice>) -> Unit = {}

    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) = resolve(service)
            override fun onServiceLost(service: NsdServiceInfo) {
                devices.remove(service.serviceName)
                publish()
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        listener = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        devices.clear()
    }

    @Suppress("DEPRECATION") // registerServiceInfoCallback is API 34+; resolve covers older devices
    private fun resolve(service: NsdServiceInfo) {
        nsd.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                devices[serviceInfo.serviceName] =
                    DiscoveredDevice(serviceInfo.serviceName, host, serviceInfo.port)
                publish()
            }
        })
    }

    private fun publish() {
        val snapshot = devices.values.toList()
        main.post { onDevicesChanged(snapshot) }
    }
}
