package ai.xrav.xravscan.ui.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Single, app-wide observer for connectivity state. Drives the top-bar
 * pill, ScanRepository auto-pause, and DiscoveryRepository pre-flight
 * checks. Always reports a valid [NetworkState] — even on the first
 * frame — so callers don't need to guard against null.
 */
data class NetworkState(
    val available: Boolean,
    val transport: Transport,
    val isVpn: Boolean,
    val hasInternetCapability: Boolean,
) {
    /** Convenience: scanner pauses while [shouldPauseScans] is true. */
    val shouldPauseScans: Boolean get() = !available || isVpn

    enum class Transport { WIFI, CELLULAR, ETHERNET, OTHER, NONE }

    companion object {
        val Unknown = NetworkState(
            available = false,
            transport = Transport.NONE,
            isVpn = false,
            hasInternetCapability = false,
        )
    }
}

@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val cm: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Hot, conflated state. Compose collects this via [ProvideNetworkState]. */
    val state: StateFlow<NetworkState> = callbackFlow {
        trySend(snapshot())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(snapshot()) }
            override fun onLost(network: Network) { trySend(snapshot()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(snapshot())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onFailure { trySend(NetworkState.Unknown) }

        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, snapshot())

    /** One-shot snapshot used as the initial value and on every callback. */
    private fun snapshot(): NetworkState {
        val active = cm.activeNetwork ?: return NetworkState.Unknown
        val caps = cm.getNetworkCapabilities(active) ?: return NetworkState.Unknown
        val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkState.Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkState.Transport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkState.Transport.ETHERNET
            isVpn -> NetworkState.Transport.OTHER
            else -> NetworkState.Transport.OTHER
        }
        return NetworkState(
            available = hasInternet,
            transport = transport,
            isVpn = isVpn,
            hasInternetCapability = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        )
    }
}

val LocalNetworkState = compositionLocalOf<NetworkState> { NetworkState.Unknown }

@Composable
fun ProvideNetworkState(
    monitor: NetworkMonitor,
    content: @Composable () -> Unit,
) {
    val state by monitor.state.collectAsState()
    CompositionLocalProvider(LocalNetworkState provides state, content = content)
}
