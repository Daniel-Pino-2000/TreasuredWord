package com.application.bibleapp.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

object NetworkUtils {

    /**
     * True only if there's an active, validated internet-capable network.
     * Used as a pre-flight check before starting a bulk download so we fail
     * fast with a clear message instead of burning through hundreds of
     * doomed requests.
     */
    fun isOnline(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

/**
 * Whether the phone is online, kept up to date while on screen — for screens that change as the
 * connection comes and goes, like the Bible Helper's offline banner (#128).
 */
@Composable
fun rememberIsOnline(): Boolean {
    val context = LocalContext.current
    var online by remember { mutableStateOf(NetworkUtils.isOnline(context)) }
    DisposableEffect(context) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        // Trust what each callback says rather than re-reading the system: in onLost,
        // activeNetwork can still return the network just lost, still "validated", and with no
        // network left no later callback would correct it (#208). onAvailable is followed by
        // onCapabilitiesChanged, which says whether the new network really reaches the internet.
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) { online = false }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }
        val registered = runCatching { connectivityManager?.registerDefaultNetworkCallback(callback) }.isSuccess
        online = NetworkUtils.isOnline(context)
        onDispose { if (registered) runCatching { connectivityManager?.unregisterNetworkCallback(callback) } }
    }
    return online
}
