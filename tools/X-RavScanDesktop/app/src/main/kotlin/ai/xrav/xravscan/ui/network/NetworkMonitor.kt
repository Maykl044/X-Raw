package ai.xrav.xravscan.ui.network

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Desktop network state, mirrored from the Android variant so screens
 * can rely on the same ``shouldPauseScans`` flag.
 */
data class NetworkState(
    val available: Boolean,
    val transport: Transport,
    val isVpn: Boolean,
) {
    /** Convenience: scanner pauses while [shouldPauseScans] is true. */
    val shouldPauseScans: Boolean get() = !available || isVpn

    enum class Transport { WIFI, CELLULAR, ETHERNET, OTHER, NONE }

    companion object {
        val Unknown = NetworkState(available = false, transport = Transport.NONE, isVpn = false)
    }
}

/**
 * JVM connectivity monitor. Polls every 5s on a background scope by:
 *   1. enumerating [NetworkInterface]s — VPN tunnels usually appear as
 *      ``tun*``, ``tap*``, ``utun*`` or ``ppp*`` interfaces
 *   2. opening a brief socket to ``1.1.1.1:443`` to verify the path is
 *      actually reachable (catches captive-portal / no-DNS scenarios)
 *
 * Result is exposed as a hot StateFlow so multiple screens can react
 * without each running their own probe.
 */
class NetworkMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(NetworkState.Unknown)
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    init {
        scope.launch {
            while (true) {
                _state.value = sample()
                delay(5.seconds)
            }
        }
    }

    private suspend fun sample(): NetworkState = withContext(Dispatchers.IO) {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
            .getOrDefault(emptyList())
        val isVpn = interfaces.any { iface ->
            val name = iface.name.lowercase()
            (name.startsWith("tun") || name.startsWith("tap") ||
                name.startsWith("utun") || name.startsWith("ppp")) &&
                runCatching { iface.isUp }.getOrDefault(false)
        }
        val transport = when {
            interfaces.any { it.name.lowercase().let { n -> n.startsWith("eth") || n.startsWith("eno") || n.startsWith("ens") || n.startsWith("enp") } && runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) } ->
                NetworkState.Transport.ETHERNET
            interfaces.any { it.name.lowercase().let { n -> n.startsWith("wlan") || n.startsWith("wlp") || n.startsWith("wifi") || n.startsWith("en0") || n.startsWith("airport") } && runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) } ->
                NetworkState.Transport.WIFI
            else -> NetworkState.Transport.OTHER
        }
        val available = withTimeoutOrNull(2.seconds) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("1.1.1.1", 443), 1500)
                    socket.isConnected
                }
            }.getOrDefault(false)
        } ?: false
        NetworkState(available = available, transport = transport, isVpn = isVpn)
    }
}

val LocalNetworkState = compositionLocalOf<NetworkState> { NetworkState.Unknown }

@Composable
fun ProvideNetworkState(monitor: NetworkMonitor, content: @Composable () -> Unit) {
    val state by monitor.state.collectAsState()
    CompositionLocalProvider(LocalNetworkState provides state, content = content)
}
