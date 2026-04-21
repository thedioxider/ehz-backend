package space.diomentia.ehzbackend.dashboard

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import space.diomentia.ehzbackend.AbstractPostgresIntegrationTest
import space.diomentia.ehzbackend.bootstrap.CredentialsStore
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The dashboard's defining property: it must **only** be reachable from
 * localhost on the host, via the management connector. Three assertions
 * protect that:
 *
 *   1. Reachable on `http://localhost:{management-port}/` — 200, HTML,
 *      containing the literal API key value from [CredentialsStore]. Proves
 *      the controller is wired onto the child context and Thymeleaf renders.
 *
 *   2. NOT reachable on `http://{non-loopback-ip}:{management-port}/`.
 *      `management.server.address=127.0.0.1` means the socket bind refuses
 *      non-loopback connects at the kernel level; the client sees a
 *      connection refused (or timeout). Skipped when the test host has no
 *      non-loopback IPv4 (clean CI containers).
 *
 *   3. NOT reachable on `http://localhost:{main-port}/` — a plain 404. Proves
 *      the controller did NOT also get registered on the main context via a
 *      stray component scan.
 *
 * Real HTTP (not MockMvc) is mandatory: MockMvc dispatches against a single
 * `WebApplicationContext`, and the management child context is a separate
 * context entirely, so MockMvc can't reach the dashboard.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DashboardLocalhostOnlyTest : AbstractPostgresIntegrationTest() {

    companion object {
        @JvmStatic
        val tempDir: Path = Files.createTempDirectory("ehz-dashboard-test-")

        @DynamicPropertySource
        @JvmStatic
        fun overrideProps(registry: DynamicPropertyRegistry) {
            // Pin the management server address to 127.0.0.1 — the production
            // default is 0.0.0.0 (so Docker's port forward can reach it), with
            // the compose publish binding `127.0.0.1:19080:8080` doing the
            // host-side restriction. This test exercises the socket-level
            // binding mechanism directly without relying on compose.
            registry.add("management.server.address") { "127.0.0.1" }
            // Let Boot pick a free management port; the production default
            // (8080) would collide with any local dev server.
            registry.add("management.server.port") { "0" }
            // LAN ingest connector wants a free port too so we don't collide
            // with whatever's on 8081 on the developer's box.
            registry.add("ehz.ingest.port") { "0" }
            registry.add("ehz.data-dir") { tempDir.toString() }
        }
    }

    @Autowired
    lateinit var credentialsStore: CredentialsStore

    @LocalServerPort
    var mainPort: Int = 0

    @LocalManagementPort
    var managementPort: Int = 0

    @Test
    fun `dashboard on management port returns HTML containing the API key`() {
        val client = RestClient.builder()
            .baseUrl("http://localhost:$managementPort")
            .build()

        val response = client.get().uri("/").retrieve().toEntity(String::class.java)

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.headers.contentType?.toString() ?: "")
            .startsWith("text/html")
        val body = response.body ?: ""
        assertThat(body).contains("ehz-backend")
        assertThat(body).contains(credentialsStore.apiKey)
    }

    @Test
    fun `dashboard is NOT reachable on a non-loopback interface`() {
        val lanIp = firstNonLoopbackIpv4()
        Assumptions.assumeTrue(
            lanIp != null,
            "no non-loopback IPv4 on this host — skipping LAN-binding assertion",
        )

        // Use a raw Socket rather than RestClient so we're asserting the
        // kernel-level refusal, not a higher-level handshake failure.
        var connected = false
        try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(lanIp, managementPort), 1500)
                connected = sock.isConnected
            }
        } catch (_: ConnectException) {
            // expected
        } catch (_: SocketTimeoutException) {
            // acceptable — some stacks drop rather than RST
        } catch (_: NoRouteToHostException) {
            // acceptable
        }
        assertThat(connected)
            .withFailMessage(
                "management port %d accepted a connection on %s — it must be localhost-only",
                managementPort,
                lanIp,
            )
            .isFalse
    }

    @Test
    fun `main server does not expose the dashboard at root`() {
        val client = RestClient.builder()
            .baseUrl("http://localhost:$mainPort")
            .build()

        val response = client.get().uri("/").exchange { _, r -> r }

        // The dashboard controller must live on the management context only.
        // The main server has no root handler, so a plain 404 is the signal
        // that we haven't accidentally double-registered.
        assertThat(response.statusCode.value()).isEqualTo(404)
    }

    private fun firstNonLoopbackIpv4(): String? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces() ?: return null
        } catch (_: Exception) {
            return null
        }
        for (nic in interfaces) {
            if (!nic.isUp || nic.isLoopback) continue
            for (addr in nic.inetAddresses) {
                if (addr is InetAddress
                    && !addr.isLoopbackAddress
                    && !addr.isLinkLocalAddress
                    && addr.address.size == 4
                ) {
                    return addr.hostAddress
                }
            }
        }
        return null
    }
}
