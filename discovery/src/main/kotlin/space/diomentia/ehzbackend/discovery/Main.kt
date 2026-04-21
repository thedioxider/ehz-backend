package space.diomentia.ehzbackend.discovery

import org.slf4j.LoggerFactory
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path

private val log = LoggerFactory.getLogger("space.diomentia.ehzbackend.discovery.Main")

/**
 * Filesystem location the discovery service writes its announce IP to on startup.
 * The `app` container bind-mounts this into its data dir (`/data/discovery/`)
 * so the dashboard can show the real LAN address — `app` runs on the Docker
 * bridge and cannot see the host's physical NICs on its own.
 *
 * Absent in local / test runs, where nothing mounts `/state`; the dashboard
 * silently falls back to its in-container probe.
 */
private const val STATE_DIR = "/state"
private const val LAN_IP_FILE = "lan-ip"

fun main() {
    val announceIpRaw = System.getenv("EHZ_ANNOUNCE_IP")?.takeIf { it.isNotBlank() } ?: "auto"
    val announcePort = System.getenv("EHZ_ANNOUNCE_PORT")?.toIntOrNull() ?: 19081
    val contractVersion = System.getenv("EHZ_CONTRACT_VERSION")?.toIntOrNull() ?: 1

    val announceIp: InetAddress = if (announceIpRaw == "auto") {
        routedLanIpv4() ?: pickLanIpv4() ?: run {
            log.warn("no non-loopback IPv4 found; falling back to InetAddress.getLocalHost()")
            InetAddress.getLocalHost()
        }
    } else {
        InetAddress.getByName(announceIpRaw)
    }

    log.info(
        "ehz-discovery starting: ip={} port={} contractVersion={}",
        announceIp.hostAddress, announcePort, contractVersion,
    )

    publishAnnounceIp(announceIp)

    val mdns = MdnsAnnouncer(announceIp, announcePort, contractVersion)
    mdns.start()

    val udp = UdpResponder(announceIp.hostAddress, announcePort, contractVersion)
    udp.start()

    Runtime.getRuntime().addShutdownHook(Thread({
        log.info("ehz-discovery shutting down")
        udp.close()
        mdns.close()
    }, "ehz-discovery-shutdown"))

    Thread.currentThread().join()
}

/**
 * Best-effort write of the resolved announce IP to the shared state dir, so
 * the `app` container's dashboard can show the real LAN address. Failures are
 * non-fatal — the dashboard falls back to its own (bridge-local) probe and the
 * two UDP/mDNS announcers keep running regardless.
 */
private fun publishAnnounceIp(announceIp: InetAddress) {
    val stateDir = Path.of(STATE_DIR)
    if (!Files.isDirectory(stateDir)) {
        log.debug("{} is not mounted; skipping lan-ip publish (expected in local/test runs)", STATE_DIR)
        return
    }
    try {
        Files.writeString(stateDir.resolve(LAN_IP_FILE), announceIp.hostAddress)
        log.info("published announce IP to {}/{}", STATE_DIR, LAN_IP_FILE)
    } catch (e: Exception) {
        log.warn("could not publish announce IP to {}/{} ({})", STATE_DIR, LAN_IP_FILE, e.message)
    }
}

/**
 * Ask the kernel which source IP it would use to reach a public destination
 * — returns the address on whichever interface owns the default route. No
 * packets are sent: UDP `connect()` only primes source-address selection.
 *
 * This is the robust path on hosts with multiple NICs (Docker bridges + real
 * LAN), where a naive [pickLanIpv4] enumeration can land on a bridge. On
 * hosts with no default route at all (pure-local LAN) this returns null and
 * the caller falls back to enumeration.
 */
internal fun routedLanIpv4(): InetAddress? = try {
    DatagramSocket().use { socket ->
        socket.connect(InetAddress.getByName("8.8.8.8"), 9)
        val local = socket.localAddress
        if (local is Inet4Address && !local.isLoopbackAddress && !local.isLinkLocalAddress) {
            log.info("auto-picked LAN IPv4 {} via default-route probe", local.hostAddress)
            local
        } else null
    }
} catch (_: Exception) {
    null
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
