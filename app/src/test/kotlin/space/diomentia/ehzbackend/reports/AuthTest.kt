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
 * allowlisted verb+path combos, and only with a valid bearer on `PUT /api/reports/{id}`;
 * the internal connector ignores the filter entirely.
 *
 * Uses [SpringBootTest.WebEnvironment.DEFINED_PORT] (not RANDOM_PORT) because
 * we need the LAN listener on a known, stable port we pre-reserve via
 * [ServerSocket]. MockMvc can't drive this — the whole point is to exercise
 * the real Tomcat connectors.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class AuthTest : AbstractPostgresIntegrationTest() {

    companion object {
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

    private fun minimalReportBody(reportId: UUID): String = objectMapper.writeValueAsString(
        TestFixtures.reportPayload(
            reportId = reportId,
            routeId = UUID.randomUUID(),
            sectionId = UUID.randomUUID(),
            siteId = UUID.randomUUID(),
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
    fun `PUT api reports id on LAN without auth returns 401 plus WWW-Authenticate`() {
        val reportId = UUID.randomUUID()
        val response = lanClient.put().uri("/api/reports/$reportId")
            .contentType(MediaType.APPLICATION_JSON)
            .body(minimalReportBody(reportId))
            .exchange { _, r -> r }

        assertThat(response.statusCode.value()).isEqualTo(401)
        assertThat(response.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer")
        assertThat(response.headers.getFirst(ApiContract.HEADER_NAME))
            .isEqualTo(ApiContract.VERSION.toString())
    }

    @Test
    fun `PUT api reports id on LAN with wrong bearer returns 401`() {
        val reportId = UUID.randomUUID()
        val response = lanClient.put().uri("/api/reports/$reportId")
            .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-key")
            .contentType(MediaType.APPLICATION_JSON)
            .body(minimalReportBody(reportId))
            .exchange { _, r -> r }

        assertThat(response.statusCode.value()).isEqualTo(401)
        assertThat(response.headers.getFirst(ApiContract.HEADER_NAME))
            .isEqualTo(ApiContract.VERSION.toString())
    }

    @Test
    fun `PUT api reports id on LAN with correct bearer returns 201`() {
        val reportId = UUID.randomUUID()
        val response = lanClient.put().uri("/api/reports/$reportId")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $TEST_API_KEY")
            .contentType(MediaType.APPLICATION_JSON)
            .body(minimalReportBody(reportId))
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

    @Test
    fun `contract version header is present on 401 short-circuit`() {
        val reportId = UUID.randomUUID()
        val response = lanClient.put().uri("/api/reports/$reportId")
            .contentType(MediaType.APPLICATION_JSON)
            .body("{}")
            .exchange { _, r -> r }

        assertThat(response.statusCode.value()).isEqualTo(401)
        assertThat(response.headers.getFirst(ApiContract.HEADER_NAME))
            .isEqualTo(ApiContract.VERSION.toString())
    }
}
