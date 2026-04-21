package space.diomentia.ehzbackend.reports

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import space.diomentia.ehzbackend.ApiContract
import space.diomentia.ehzbackend.AbstractPostgresIntegrationTest
import tools.jackson.databind.ObjectMapper
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Covers the port-scoped auth model: the LAN connector only admits the
 * allowlisted verb+path combos, and only with a valid bearer on `POST /api/reports`;
 * the internal connector ignores the filter entirely.
 *
 * Uses [SpringBootTest.WebEnvironment.DEFINED_PORT] (not RANDOM_PORT) because
 * we need the LAN listener on a known, stable port we pre-reserve via
 * [ServerSocket]. MockMvc can't drive this — the whole point is to exercise
 * the real Tomcat connectors.
 *
 * The test API key is injected via [DynamicPropertySource] as a System
 * property; the `CredentialsStore.envOverride()` seam is overridden in its
 * unit tests, but here we simulate the env-override branch via the test
 * harness by writing the credentials file ourselves — simpler and doesn't
 * require a bean replacement.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class AuthTest : AbstractPostgresIntegrationTest() {

    companion object {
        // Pre-reserve two free ports at class-init time. Closing the sockets
        // leaves a brief window where the kernel could hand them to someone
        // else, but the JVM-lifetime Postgres container + the fact that these
        // tests don't run in parallel within the same module makes the race
        // safe in practice.
        @JvmStatic val internalPort: Int = reservePort()
        @JvmStatic val lanPort: Int = reservePort()

        @JvmStatic
        val tempDir: Path = Files.createTempDirectory("ehz-auth-test-")

        const val TEST_API_KEY: String = "test-api-key-fixed-for-authtest"

        @DynamicPropertySource
        @JvmStatic
        fun overrideProps(registry: DynamicPropertyRegistry) {
            registry.add("server.port") { internalPort }
            registry.add("ehz.ingest.port") { lanPort }
            registry.add("ehz.data-dir") { tempDir.toString() }

            // Seed the credentials file before the bean initialises so the
            // store reads our known key instead of generating a random one.
            val credsFile = tempDir.resolve("credentials.txt")
            Files.writeString(credsFile, "EHZ_API_KEY=$TEST_API_KEY\n")
        }

        private fun reservePort(): Int =
            ServerSocket(0).use { it.localPort }
    }

    @Autowired lateinit var objectMapper: ObjectMapper

    private val lanClient: RestClient by lazy {
        RestClient.builder().baseUrl("http://localhost:$lanPort").build()
    }
    private val internalClient: RestClient by lazy {
        RestClient.builder().baseUrl("http://localhost:$internalPort").build()
    }

    private fun minimalReportJson(): String = objectMapper.writeValueAsString(
        listOf(
            TestFixtures.reportPayload(
                reportId = UUID.randomUUID(),
                routeId = UUID.randomUUID(),
                sectionId = UUID.randomUUID(),
                siteId = UUID.randomUUID(),
            ),
        ),
    )

    @Test
    fun `GET api ping on LAN port returns 200 without auth`() {
        val response = lanClient.get().uri("/api/ping").retrieve().toBodilessEntity()
        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.headers.getFirst(ApiContract.HEADER_NAME))
            .isEqualTo(ApiContract.VERSION.toString())
    }

    @Test
    fun `POST api reports on LAN without auth returns 401 plus WWW-Authenticate`() {
        val response = lanClient.post().uri("/api/reports")
            .contentType(MediaType.APPLICATION_JSON)
            .body(minimalReportJson())
            .exchange { _, r -> r }

        assertThat(response.statusCode.value()).isEqualTo(401)
        assertThat(response.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer")
        assertThat(response.headers.getFirst(ApiContract.HEADER_NAME))
            .isEqualTo(ApiContract.VERSION.toString())
    }

    @Test
    fun `POST api reports on LAN with wrong bearer returns 401`() {
        val response = lanClient.post().uri("/api/reports")
            .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-key")
            .contentType(MediaType.APPLICATION_JSON)
            .body(minimalReportJson())
            .exchange { _, r -> r }

        assertThat(response.statusCode.value()).isEqualTo(401)
        assertThat(response.headers.getFirst(ApiContract.HEADER_NAME))
            .isEqualTo(ApiContract.VERSION.toString())
    }

    @Test
    fun `POST api reports on LAN with correct bearer returns 201`() {
        val response = lanClient.post().uri("/api/reports")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $TEST_API_KEY")
            .contentType(MediaType.APPLICATION_JSON)
            .body(minimalReportJson())
            .exchange { _, r -> r }

        assertThat(response.statusCode.value()).isEqualTo(201)
        assertThat(response.headers.getFirst(ApiContract.HEADER_NAME))
            .isEqualTo(ApiContract.VERSION.toString())
    }

    @Test
    fun `GET api reports on LAN port returns 404 even with correct bearer`() {
        val response = lanClient.get().uri("/api/reports")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $TEST_API_KEY")
            .exchange { _, r -> r }

        assertThat(response.statusCode.value()).isEqualTo(404)
        assertThat(response.headers.getFirst(ApiContract.HEADER_NAME))
            .isEqualTo(ApiContract.VERSION.toString())
    }

    @Test
    fun `GET api reports on internal port returns 200 with no auth`() {
        val response = internalClient.get().uri("/api/reports").retrieve().toBodilessEntity()
        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.headers.getFirst(ApiContract.HEADER_NAME))
            .isEqualTo(ApiContract.VERSION.toString())
    }

    /**
     * Regression guard: the ordering between `ContractVersionFilter` and
     * `LanIngestFilter` must not drift. `X-Contract-Version` has to be
     * stamped on short-circuited LAN 4xx responses so a mismatched client
     * can still diagnose a contract change instead of seeing a bare 401.
     */
    @Test
    fun `contract version header is present on 401 short-circuit`() {
        val response = lanClient.post().uri("/api/reports")
            .contentType(MediaType.APPLICATION_JSON)
            .body("{}")
            .exchange { _, r -> r }

        assertThat(response.statusCode.value()).isEqualTo(401)
        assertThat(response.headers.getFirst(ApiContract.HEADER_NAME))
            .isEqualTo(ApiContract.VERSION.toString())
    }
}
