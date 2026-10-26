package it.lumetv.app.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper

internal class NetworkMonitor(context: Context, private val onChanged: (Boolean) -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    val isOnline: Boolean get() {
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun start() {
        if (callback != null) return
        val listener = object : ConnectivityManager.NetworkCallback() {
            private fun publish() {
                // Read the default network on the main thread, after the callback's state update.
                main.post { if (callback === this) onChanged(isOnline) }
            }
            override fun onAvailable(network: Network) = publish()
            override fun onLost(network: Network) = publish()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publish()
        }
        callback = listener
        if (Build.VERSION.SDK_INT >= 24) connectivity.registerDefaultNetworkCallback(listener)
        else connectivity.registerNetworkCallback(NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), listener)
    }

    fun stop() {
        callback?.let(connectivity::unregisterNetworkCallback)
        callback = null
        main.removeCallbacksAndMessages(null)
    }
}
