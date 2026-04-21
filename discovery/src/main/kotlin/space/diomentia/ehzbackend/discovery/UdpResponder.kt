package space.diomentia.ehzbackend.discovery

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * Blocking UDP broadcast responder. The Android client blasts
 * [MAGIC] to UDP [listenPort] on its LAN broadcast address; we reply with
 * a [DiscoveryReply] unicast to the sender.
 *
 * [listenPort] is a protocol constant — the client never learns it from
 * anywhere, so it cannot be remapped. That's why the discovery service has
 * to run on the host network (UDP broadcast doesn't traverse Docker's bridge).
 *
 * Bind failures are logged at WARN and swallowed; mDNS + manual entry still
 * work. `listenPort = 0` picks an ephemeral port (used by tests).
 */
class UdpResponder(
    private val ip: String,
    private val port: Int,
    private val contractVersion: Int,
    private val listenPort: Int = DEFAULT_LISTEN_PORT,
) {
    private val log = LoggerFactory.getLogger(UdpResponder::class.java)
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    @Volatile private var stopping = false

    /**
     * The port the socket actually bound to. Useful when [listenPort] was 0
     * (ephemeral). `-1` if the responder never started.
     */
    val boundPort: Int
        get() = socket?.localPort ?: -1

    fun start() {
        val bound = try {
            DatagramSocket(listenPort)
        } catch (e: SocketException) {
            log.warn("UDP broadcast disabled: could not bind port {} ({})", listenPort, e.message)
            return
        }
        socket = bound
        val replyJson = Json.encodeToString(
            DiscoveryReply.serializer(),
            DiscoveryReply(
                ip = ip,
                port = port,
                apiBasePath = MdnsAnnouncer.API_BASE_PATH,
                version = contractVersion,
            ),
        ).toByteArray(Charsets.US_ASCII)

        val worker = Thread({ runLoop(bound, replyJson) }, "ehz-udp-responder")
        worker.isDaemon = true
        worker.start()
        thread = worker
        log.info(
            "UDP broadcast responder listening on 0.0.0.0:{} (announcing {}:{}, contract={})",
            bound.localPort, ip, port, contractVersion,
        )
    }

    private fun runLoop(bound: DatagramSocket, reply: ByteArray) {
        val buf = ByteArray(1024)
        val magic = MAGIC.toByteArray(Charsets.US_ASCII)
        while (!bound.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                bound.receive(packet)
            } catch (e: SocketException) {
                if (stopping) return   // expected close
                log.error("UDP receive loop aborted unexpectedly", e)
                return
            } catch (e: Exception) {
                log.error("UDP receive failed", e)
                continue
            }
            if (packet.length != magic.size) continue
            var match = true
            for (i in magic.indices) {
                if (buf[i] != magic[i]) {
                    match = false
                    break
                }
            }
            if (!match) continue

            try {
                val out = DatagramPacket(reply, reply.size, packet.socketAddress as InetSocketAddress)
                bound.send(out)
            } catch (e: Exception) {
                log.error("UDP reply failed", e)
            }
        }
    }

    fun close() {
        val s = socket ?: return
        socket = null
        stopping = true
        s.close()
        thread = null
    }

    companion object {
        const val DEFAULT_LISTEN_PORT = 5370
        const val MAGIC = "EHZ_DISCOVER_V1"
    }
}
