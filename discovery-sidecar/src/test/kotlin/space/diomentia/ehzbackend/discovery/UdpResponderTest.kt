package space.diomentia.ehzbackend.discovery

import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UdpResponderTest {

    @Test
    fun `magic packet triggers unicast reply with resolved fields`() {
        val responder = UdpResponder(
            ip = "10.0.0.42",
            port = 19081,
            contractVersion = 7,
            listenPort = 0,
        )
        responder.start()
        try {
            val target = responder.boundPort
            check(target > 0) { "responder failed to bind" }

            DatagramSocket().use { client ->
                client.soTimeout = 2_000
                val magic = UdpResponder.MAGIC.toByteArray(Charsets.US_ASCII)
                val loopback = InetAddress.getByName("127.0.0.1")
                client.send(DatagramPacket(magic, magic.size, loopback, target))

                val buf = ByteArray(2048)
                val reply = DatagramPacket(buf, buf.size)
                client.receive(reply)
                val json = String(reply.data, reply.offset, reply.length, Charsets.US_ASCII)
                val parsed = Json.decodeFromString<DiscoveryReply>(json)
                assertEquals("10.0.0.42", parsed.ip)
                assertEquals(19081, parsed.port)
                assertEquals("/api", parsed.apiBasePath)
                assertEquals(7, parsed.version)
            }
        } finally {
            responder.close()
        }
    }

    @Test
    fun `non-magic packet is ignored and no reply arrives`() {
        val responder = UdpResponder(
            ip = "10.0.0.42",
            port = 19081,
            contractVersion = 1,
            listenPort = 0,
        )
        responder.start()
        try {
            val target = responder.boundPort
            check(target > 0) { "responder failed to bind" }

            DatagramSocket().use { client ->
                client.soTimeout = 1_000
                val junk = "hello-world".toByteArray(Charsets.US_ASCII)
                val loopback = InetAddress.getByName("127.0.0.1")
                client.send(DatagramPacket(junk, junk.size, loopback, target))

                val buf = ByteArray(2048)
                val reply = DatagramPacket(buf, buf.size)
                assertFailsWith<SocketTimeoutException> { client.receive(reply) }
            }
        } finally {
            responder.close()
        }
    }
}
