package space.diomentia.ehzbackend.discovery

import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

private val log = LoggerFactory.getLogger("space.diomentia.ehzbackend.discovery.Main")

fun main() {
    val announceIpRaw = System.getenv("EHZ_ANNOUNCE_IP")?.takeIf { it.isNotBlank() } ?: "auto"
    val announcePort = System.getenv("EHZ_ANNOUNCE_PORT")?.toIntOrNull() ?: 19081
    val contractVersion = System.getenv("EHZ_CONTRACT_VERSION")?.toIntOrNull() ?: 1

    val announceIp: InetAddress = if (announceIpRaw == "auto") {
        pickLanIpv4() ?: run {
            log.warn("no non-loopback IPv4 found; falling back to InetAddress.getLocalHost()")
            InetAddress.getLocalHost()
        }
    } else {
        InetAddress.getByName(announceIpRaw)
    }

    log.info(
        "ehz-discovery-sidecar starting: ip={} port={} contractVersion={}",
        announceIp.hostAddress, announcePort, contractVersion,
    )

    val mdns = MdnsAnnouncer(announceIp, announcePort, contractVersion)
    mdns.start()

    val udp = UdpResponder(announceIp.hostAddress, announcePort, contractVersion)
    udp.start()

    Runtime.getRuntime().addShutdownHook(Thread({
        log.info("ehz-discovery-sidecar shutting down")
        udp.close()
        mdns.close()
    }, "ehz-discovery-shutdown"))

    Thread.currentThread().join()
}

/** First non-loopback, non-link-local IPv4 across enabled interfaces. */
internal fun pickLanIpv4(): InetAddress? {
    val candidates = mutableListOf<Pair<String, InetAddress>>()
    for (iface in NetworkInterface.getNetworkInterfaces() ?: return null) {
        if (!iface.isUp || iface.isLoopback) continue
        for (addr in iface.inetAddresses) {
            if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                candidates += iface.name to addr
            }
        }
    }
    return when (candidates.size) {
        0 -> null
        1 -> candidates[0].second.also {
            log.info("auto-picked LAN IPv4 {} on {}", it.hostAddress, candidates[0].first)
        }
        else -> {
            log.warn(
                "multiple LAN IPv4 candidates found (picked first); consider setting EHZ_ANNOUNCE_IP explicitly. candidates={}",
                candidates.joinToString { "${it.second.hostAddress} (${it.first})" },
            )
            candidates[0].second
        }
    }
}
