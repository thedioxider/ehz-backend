package space.diomentia.ehzbackend.reports

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate
import space.diomentia.ehzbackend.AbstractPostgresIntegrationTest
import space.diomentia.ehzbackend.catalog.RouteRepository
import space.diomentia.ehzbackend.catalog.SectionRepository
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID

/**
 * End-to-end ingestion test. Boots the full Spring context against a real
 * Postgres container, drives the controller via [MockMvc], and asserts both
 * DB state and the on-disk photo.
 *
 * `ehz.data-dir` is overridden to a JUnit @TempDir-equivalent via a static
 * tempdir path — MockMvc shares the application context across tests, so the
 * override must happen before context start via [DynamicPropertySource].
 *
 * Deliberately NOT `@Transactional`: ingestion writes through its own
 * `@Transactional` service, and the idempotent-replace test issues two POSTs
 * that must each commit independently. Wrapping the whole test in one tx
 * would keep sites attached in the JPA session across the delete-and-reinsert
 * and trigger TransientPropertyValueException.
 */
@AutoConfigureMockMvc
class IngestIntegrationTest : AbstractPostgresIntegrationTest() {

    companion object {
        @JvmStatic
        val tempDir: Path = Files.createTempDirectory("ehz-ingest-test-")

        @DynamicPropertySource
        @JvmStatic
        fun overrideDataDir(registry: DynamicPropertyRegistry) {
            registry.add("ehz.data-dir") { tempDir.toString() }
        }
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var reportRepository: ReportRepository

    @Autowired
    lateinit var siteRepository: SiteRepository

    @Autowired
    lateinit var routeRepository: RouteRepository

    @Autowired
    lateinit var sectionRepository: SectionRepository

    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

    /**
     * Run [block] inside a short read-only transaction so lazy JPA collections
     * (`Site.fields`, `Report.route`, etc.) stay initialisable for the asserts.
     * The outer test method is deliberately non-transactional — see class doc.
     */
    private fun <T> readInTx(block: () -> T): T =
        transactionTemplate.execute { block() }!!

    @Test
    fun `round-trips a report with a photo field`() {
        val reportId = UUID.randomUUID()
        val routeId = UUID.randomUUID()
        val sectionId = UUID.randomUUID()
        val siteId = UUID.randomUUID()

        // Deterministic, small "WebP" payload. The backend is format-agnostic
        // — it only base64-decodes and writes bytes — so a valid RIFF header
        // is overkill for this round-trip assertion.
        val photoBytes = ByteArray(32) { it.toByte() }
        val photoBase64 = Base64.getEncoder().encodeToString(photoBytes)

        val payload = listOf(
            mapOf(
                "version" to 2,
                "id" to reportId.toString(),
                "inspector" to "Иванов Иван",
                "route" to mapOf(
                    "id" to routeId.toString(),
                    "name" to "САЦ-III",
                    "length_km" to 3600,
                ),
                "section" to mapOf(
                    "id" to sectionId.toString(),
                    "begin_km" to 0,
                    "end_km" to 41,
                ),
                "start_time" to 1704321600L,
                "end_time" to 1704329400L,
                "sites" to listOf(
                    mapOf(
                        "id" to siteId.toString(),
                        "timestamp" to 1704322000L,
                        "position_km" to 12.5,
                        "location" to mapOf("lat" to 55.75, "lng" to 37.62),
                        "validation_id" to null,
                        "fields" to listOf(
                            mapOf(
                                "spec_id" to "site_photo",
                                "name" to "Фотография точки",
                                "type" to "photo",
                                "value" to photoBase64,
                                "unit" to null,
                            ),
                            mapOf(
                                "spec_id" to "comment",
                                "name" to "Комментарий",
                                "type" to "text",
                                "value" to "ok",
                                "unit" to null,
                            ),
                        ),
                    ),
                ),
            ),
        )

        mockMvc.post("/api/reports") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(payload)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.accepted") { value(1) }
        }

        // Catalog upserts
        assertThat(routeRepository.findById(routeId)).isPresent
        assertThat(sectionRepository.findById(sectionId)).isPresent

        // Report + site + fields. Read inside a tx so Site.fields (lazy) stays
        // initialisable for the asserts.
        readInTx {
            val persistedReport = reportRepository.findById(reportId).orElseThrow()
            assertThat(persistedReport.inspector).isEqualTo("Иванов Иван")
            assertThat(persistedReport.device).isNull()
            assertThat(persistedReport.route.id).isEqualTo(routeId)
            assertThat(persistedReport.section.id).isEqualTo(sectionId)

            val persistedSite = siteRepository.findById(siteId).orElseThrow()
            assertThat(persistedSite.report.id).isEqualTo(reportId)
            assertThat(persistedSite.lat).isEqualTo(55.75)
            assertThat(persistedSite.lng).isEqualTo(37.62)
            assertThat(persistedSite.positionKm).isEqualTo(12.5)

            val fieldsBySpec = persistedSite.fields.associateBy { it.specId }
            assertThat(fieldsBySpec).containsOnlyKeys("site_photo", "comment")

            val expectedRelPath = "photos/$reportId/$siteId/site_photo.webp"
            assertThat(fieldsBySpec["site_photo"]!!.value).isEqualTo(expectedRelPath)
            assertThat(fieldsBySpec["comment"]!!.value).isEqualTo("ok")
        }

        val expectedRelPath = "photos/$reportId/$siteId/site_photo.webp"
        val onDisk = tempDir.resolve(expectedRelPath)
        assertThat(Files.exists(onDisk)).isTrue()
        assertThat(Files.readAllBytes(onDisk)).isEqualTo(photoBytes)
    }

    @Test
    fun `reposting the same report id replaces sites and fields`() {
        val reportId = UUID.randomUUID()
        val routeId = UUID.randomUUID()
        val sectionId = UUID.randomUUID()
        val firstSiteId = UUID.randomUUID()
        val secondSiteId = UUID.randomUUID()

        val basePayload: (UUID, String) -> Map<String, Any?> = { siteId, comment ->
            mapOf(
                "id" to reportId.toString(),
                "inspector" to "Петров П.П.",
                "route" to mapOf(
                    "id" to routeId.toString(),
                    "name" to "САЦ-IV",
                    "length_km" to 1200,
                ),
                "section" to mapOf(
                    "id" to sectionId.toString(),
                    "begin_km" to 10,
                    "end_km" to 20,
                ),
                "start_time" to 1704321600L,
                "end_time" to 1704329400L,
                "sites" to listOf(
                    mapOf(
                        "id" to siteId.toString(),
                        "timestamp" to 1704322000L,
                        "position_km" to 15.0,
                        "location" to null,
                        "validation_id" to null,
                        "fields" to listOf(
                            mapOf(
                                "spec_id" to "comment",
                                "name" to "Комментарий",
                                "type" to "text",
                                "value" to comment,
                                "unit" to null,
                            ),
                        ),
                    ),
                ),
            )
        }

        // First ingest
        mockMvc.post("/api/reports") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(listOf(basePayload(firstSiteId, "first")))
        }.andExpect { status { isCreated() } }

        assertThat(siteRepository.findById(firstSiteId)).isPresent

        // Re-ingest with same report id but a different site id + comment
        mockMvc.post("/api/reports") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(listOf(basePayload(secondSiteId, "second")))
        }.andExpect { status { isCreated() } }

        // Old site + its fields are gone (cascade via reports.id delete).
        // New site is present; lazy fields accessed inside a tx.
        assertThat(siteRepository.findById(firstSiteId)).isEmpty
        readInTx {
            val newSite = siteRepository.findById(secondSiteId).orElseThrow()
            assertThat(newSite.report.id).isEqualTo(reportId)
            assertThat(newSite.fields).hasSize(1)
            assertThat(newSite.fields.single().value).isEqualTo("second")
        }
    }

    @Test
    fun `auto-inserts unknown route and section from the payload`() {
        val reportId = UUID.randomUUID()
        val routeId = UUID.randomUUID()
        val sectionId = UUID.randomUUID()
        val siteId = UUID.randomUUID()

        assertThat(routeRepository.findById(routeId)).isEmpty
        assertThat(sectionRepository.findById(sectionId)).isEmpty

        val payload = listOf(
            mapOf(
                "id" to reportId.toString(),
                "inspector" to "Сидоров С.С.",
                "route" to mapOf(
                    "id" to routeId.toString(),
                    "name" to "Новый маршрут",
                    "length_km" to 42,
                ),
                "section" to mapOf(
                    "id" to sectionId.toString(),
                    "begin_km" to 3,
                    "end_km" to 7,
                ),
                "start_time" to 1704321600L,
                "end_time" to null,
                "sites" to emptyList<Any>(),
            ),
        )

        mockMvc.post("/api/reports") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(payload)
        }.andExpect { status { isCreated() } }

        val route = routeRepository.findById(routeId).orElseThrow()
        assertThat(route.name).isEqualTo("Новый маршрут")
        assertThat(route.lengthKm).isEqualTo(42)

        readInTx {
            val section = sectionRepository.findById(sectionId).orElseThrow()
            assertThat(section.beginKm).isEqualTo(3)
            assertThat(section.endKm).isEqualTo(7)
            assertThat(section.route.id).isEqualTo(routeId)
        }
    }
}
