package ai.xrav.xravscan.domain.repository

/**
 * Localised log-line builders the ViewModel feeds into
 * [DiscoveryRepository.runSmartAppend]. Keeps Android Context /
 * `Resources` out of the repository layer.
 */
data class SmartAppendMessages(
    val offlinePaused: String = "Offline — Smart Append paused until connectivity returns.",
    val vpnActiveNote: String = "VPN active — using DoH + bypass for update fetches.",
    val noProviders: String = "No enabled providers — nothing to do",
    val starting: (Int) -> String = { n -> "Smart Append starting — $n providers" },
    val bunnyLoading: String = "Loading Bunny CDN ranges via direct API…",
    val noAsnConfigured: (String) -> String = { name -> "$name: no ASN configured" },
    val errorLine: (String, String) -> String = { name, err -> "$name: error — $err" },
    val noPrefixes: (String) -> String = { name -> "$name: no prefixes" },
    val fallback: (String, Int) -> String =
        { name, count -> "$name: fallback to built-in list ($count CIDR)" },
    val fallbackWithReason: (String, Int, String) -> String =
        { name, count, reason -> "$name: fallback to built-in list ($count CIDR) — $reason" },
    val providerLine: (String, Int, Int, Int) -> String =
        { name, found, dup, added -> "$name: found $found, duplicates $dup, added $added" },
    val providerLineWithInvalid: (String, Int, Int, Int, Int) -> String =
        { name, found, dup, added, invalid ->
            "$name: found $found, duplicates $dup, added $added · $invalid invalid"
        },
)
