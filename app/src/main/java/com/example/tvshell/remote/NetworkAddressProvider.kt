package com.example.tvshell.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class LocalAddress(
    val interfaceName: String,
    val ip: String
) {
    fun label(context: android.content.Context): String {
        val type = when {
            interfaceName.startsWith("eth") -> context.getString(com.example.tvshell.R.string.network_wired)
            interfaceName.startsWith("wlan") ||
                interfaceName.startsWith("wifi") -> context.getString(com.example.tvshell.R.string.network_wifi)
            interfaceName.startsWith("ap") ||
                interfaceName.startsWith("swlan") -> context.getString(com.example.tvshell.R.string.network_hotspot)
            else -> interfaceName
        }
        return context.getString(com.example.tvshell.R.string.network_item, type, interfaceName, ip)
    }
}

class NetworkAddressProvider(
    context: Context,
    private val onNetworkChanged: () -> Unit
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun startListening() {
        if (networkCallback != null) return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onNetworkChanged()
            }

            override fun onLost(network: Network) {
                onNetworkChanged()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                onNetworkChanged()
            }
        }

        try {
            networkCallback?.let {
                connectivityManager?.registerNetworkCallback(request, it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopListening() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            networkCallback = null
        }
    }

    fun listLocalIpv4Addresses(): List<LocalAddress> {
        val result = ArrayList<LocalAddress>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val sorted = interfaces.sortedWith(compareBy<NetworkInterface> { intf ->
                val name = intf.name.lowercase()
                when {
                    name.startsWith("eth") -> 1
                    name.startsWith("wlan") || name.startsWith("wifi") -> 2
                    else -> 3
                }
            }.thenBy { it.name.lowercase() })

            for (intf in sorted) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (addr.isLoopbackAddress || addr !is Inet4Address) continue
                    val host = addr.hostAddress ?: continue
                    if (host.startsWith("127.") || host.startsWith("169.254.")) continue
                    result.add(LocalAddress(intf.name, host))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    fun resolveAddress(preferredInterface: String?): LocalAddress? {
        val addresses = listLocalIpv4Addresses()
        if (addresses.isEmpty()) return null
        if (!preferredInterface.isNullOrBlank()) {
            addresses.firstOrNull { it.interfaceName == preferredInterface }?.let { return it }
        }
        return addresses.first()
    }

    fun getLocalIpv4Address(): String? = resolveAddress(null)?.ip
}
