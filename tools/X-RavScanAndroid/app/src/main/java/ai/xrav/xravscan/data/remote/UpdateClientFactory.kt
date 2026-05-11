package ai.xrav.xravscan.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress

/**
 * Produces specialised [OkHttpClient]s for the **update** path (Smart
 * Append, Bunny edge fetch, ASN lookups). Differs from the general
 * scanner client in two ways:
 *
 *  * **DNS-over-HTTPS** — every name lookup goes through Cloudflare
 *    `1.1.1.1` over HTTPS, so blocked / poisoned recursive resolvers
 *    don't kill the update. Bootstraps with the literal IPs
 *    `1.1.1.1` and `1.0.0.1` so the very first lookup doesn't depend
 *    on system DNS.
 *  * **Optional VPN bypass** — when the device is currently behind a
 *    VPN we try to grab a parallel non-VPN [Network] via
 *    [ConnectivityManager.requestNetwork] and pin OkHttp to that
 *    network's socket factory + DNS. The user keeps their VPN active;
 *    only the update traffic exits the tunnel.
 *
 * If no non-VPN network can be acquired in 3s (airplane mode, hotel
 * Wi-Fi captive portal, …) we silently fall back to the DoH-only
 * client so the request still gets a chance via DNS-over-HTTPS.
 */
class UpdateClientFactory(
    private val context: Context,
    private val baseBuilder: OkHttpClient.Builder,
) {

    /** Vanilla DoH client. Safe to reuse across requests. */
    val dohClient: OkHttpClient by lazy { buildDohClient(baseBuilder.build()) }

    /**
     * Returns a client that prefers a non-VPN network if one is
     * available within [timeoutMs] ms; otherwise returns [dohClient].
     */
    suspend fun bypassVpnOrDoh(timeoutMs: Long = 3_000L): OkHttpClient {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return dohClient

        val network = requestNonVpnNetwork(cm, timeoutMs) ?: return dohClient
        Log.i(TAG, "Update path: pinned to non-VPN network $network")
        return baseBuilder.build().newBuilder()
            .socketFactory(network.socketFactory)
            .dns(NetworkDns(network))
            .build()
            .let { buildDohClient(it) }
    }

    /** Wraps [parent] with a DoH resolver bootstrapped via literal IPs. */
    private fun buildDohClient(parent: OkHttpClient): OkHttpClient {
        val bootstrap = listOf(
            InetAddress.getByName("1.1.1.1"),
            InetAddress.getByName("1.0.0.1"),
        )
        val doh = DnsOverHttps.Builder()
            .client(parent)
            .url("https://1.1.1.1/dns-query".toHttpUrl())
            .bootstrapDnsHosts(bootstrap)
            .includeIPv6(false)
            .resolvePrivateAddresses(false)
            .post(true)
            .build()
        return parent.newBuilder().dns(doh).build()
    }

    /**
     * Asks the platform for a non-VPN network with internet, blocking
     * up to [timeoutMs] until one shows up. Returns null on timeout.
     */
    private suspend fun requestNonVpnNetwork(
        cm: ConnectivityManager,
        timeoutMs: Long,
    ): Network? {
        // Fast path — if active network is already non-VPN, return it.
        val active = cm.activeNetwork
        val activeCaps = active?.let(cm::getNetworkCapabilities)
        if (active != null && activeCaps != null &&
            !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        ) {
            return active
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (cont.isActive) cont.resumeWith(Result.success(network))
                }
            }
            runCatching { cm.requestNetwork(request, callback, timeoutMs.toInt()) }
                .onFailure {
                    Log.w(TAG, "requestNetwork(non-VPN) failed", it)
                    if (cont.isActive) cont.resumeWith(Result.success(null))
                }
            cont.invokeOnCancellation {
                runCatching { cm.unregisterNetworkCallback(callback) }
            }
        }
    }

    /**
     * Delegates DNS lookups through a specific platform [Network] so
     * the resolver hits the non-VPN interface. Combined with the DoH
     * wrap above this gives us two independent resolution paths.
     */
    private class NetworkDns(private val network: Network) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return runCatching { network.getAllByName(hostname).toList() }
                .getOrElse { Dns.SYSTEM.lookup(hostname) }
        }
    }

    companion object {
        private const val TAG = "XRavScan.UpdateClient"
    }
}
