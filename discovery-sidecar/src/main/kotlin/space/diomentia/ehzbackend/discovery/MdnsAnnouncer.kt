package space.diomentia.ehzbackend.discovery

import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * Registers the ehz-backend mDNS service on the host's LAN interface.
 *
 * One JmDNS instance is bound to the announced [ip] and registered with the
 * hostname `ehz-backend`, which causes it to publish an A-record for
 * `ehz-backend.local -> ip`. On top of that we publish a single SRV/TXT via
 * [ServiceInfo] at service type `_ehz-backend._tcp.local.`.
 *
 * Bind failures (typically UDP/5353 already owned by Avahi on the host) are
 * logged at WARN and swallowed; the UDP responder and manual entry still work.
 */
class MdnsAnnouncer(
    private val ip: InetAddress,
    private val port: Int,
    private val contractVersion: Int,
) {
    private val log = LoggerFactory.getLogger(MdnsAnnouncer::class.java)
    private var jmdns: JmDNS? = null

    fun start() {
        try {
            val instance = JmDNS.create(ip, HOSTNAME)
            val props = mapOf(
                "version" to contractVersion.toString(),
                "apiBasePath" to API_BASE_PATH,
            )
            val info = ServiceInfo.create(SERVICE_TYPE, SERVICE_NAME, port, 0, 0, props)
            instance.registerService(info)
            jmdns = instance
            log.info(
                "mDNS announced: {}.{} at {}:{} (hostname={}.local, contract={})",
                SERVICE_NAME, SERVICE_TYPE, ip.hostAddress, port, HOSTNAME, contractVersion,
            )
        } catch (e: IOException) {
            log.warn("mDNS disabled: could not bind multicast socket ({})", e.message)
        } catch (e: RuntimeException) {
            // JmDNS wraps some bind failures in unchecked exceptions.
            log.warn("mDNS disabled: {}", e.message)
        }
    }

    fun close() {
        val instance = jmdns ?: return
        jmdns = null
        try {
            instance.unregisterAllServices()
            instance.close()
        } catch (e: IOException) {
            log.warn("mDNS shutdown: {}", e.message)
        }
    }

    companion object {
        const val SERVICE_TYPE = "_ehz-backend._tcp.local."
        const val SERVICE_NAME = "ehz-backend"
        const val HOSTNAME = "ehz-backend"
        const val API_BASE_PATH = "/api"
    }
}
