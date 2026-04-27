package space.diomentia.ehzbackend.reports

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.put
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
 * `@Transactional` service, and the idempotent-replace test issues two PUTs
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
        val photoBytes = ByteArray(32) { it.toByte() }
        val photoBase64 = Base64.getEncoder().encodeToString(photoBytes)

        val payload = TestFixtures.reportPayload(
            reportId = reportId,
            routeId = routeId,
            sectionId = sectionId,
            siteId = siteId,
            fields = listOf(
                TestFixtures.photoField("site_photo", base64Value = photoBase64),
                TestFixtures.textField("comment", "Комментарий", "ok"),
            ),
        )

        mockMvc.put("/api/reports/{id}", reportId) {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(payload)
        }.andExpect {
            status { isCreated() }
        }

        assertThat(routeRepository.findById(routeId)).isPresent
        assertThat(sectionRepository.findById(sectionId)).isPresent

        readInTx {
            val persistedReport = reportRepository.findById(reportId).orElseThrow()
            assertThat(persistedReport.inspector).isEqualTo("Иванов Иван")
            assertThat(persistedReport.device).isNull()
            assertThat(persistedReport.route.id).isEqualTo(routeId)
            assertThat(persistedReport.section.id).isEqualTo(sectionId)

            val persistedSite = siteRepository.findById(siteId).orElseThrow()
            assertThat(persistedSite.lat).isEqualTo(55.75)
            assertThat(persistedSite.lng).isEqualTo(37.62)
            assertThat(persistedSite.positionKm).isEqualTo(12.5)

            val fieldsBySpec = persistedSite.fields.associateBy { it.specId }
            assertThat(fieldsBySpec).containsOnlyKeys("site_photo", "comment")
            val expectedRelPath = "photos/$reportId/$siteId/site_photo.webp"
            assertThat(fieldsBySpec["site_photo"]!!.value).isEqualTo(expectedRelPath)
        }

        val onDisk = tempDir.resolve("photos/$reportId/$siteId/site_photo.webp")
        assertThat(Files.exists(onDisk)).isTrue()
        assertThat(Files.readAllBytes(onDisk)).isEqualTo(photoBytes)
    }

    @Test
    fun `re-putting the same report id replaces sites and fields`() {
        val reportId = UUID.randomUUID()
        val routeId = UUID.randomUUID()
        val sectionId = UUID.randomUUID()
        val firstSiteId = UUID.randomUUID()
        val secondSiteId = UUID.randomUUID()

        val firstPayload = TestFixtures.reportPayload(
            reportId = reportId,
            routeId = routeId,
            sectionId = sectionId,
            siteId = firstSiteId,
            inspector = "Петров П.П.",
            routeName = "САЦ-IV",
            routeLengthKm = 1200,
            sectionBeginKm = 10,
            sectionEndKm = 20,
            fields = listOf(TestFixtures.textField("comment", "Комментарий", "first")),
        )
        val secondPayload = TestFixtures.reportPayload(
            reportId = reportId,
            routeId = routeId,
            sectionId = sectionId,
            siteId = secondSiteId,
            inspector = "Петров П.П.",
            routeName = "САЦ-IV",
            routeLengthKm = 1200,
            sectionBeginKm = 10,
            sectionEndKm = 20,
            fields = listOf(TestFixtures.textField("comment", "Комментарий", "second")),
        )

        mockMvc.put("/api/reports/{id}", reportId) {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(firstPayload)
        }.andExpect { status { isCreated() } }

        assertThat(siteRepository.findById(firstSiteId)).isPresent

        // Re-PUT same id → overwrite (200, not 201)
        mockMvc.put("/api/reports/{id}", reportId) {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(secondPayload)
        }.andExpect { status { isOk() } }

        assertThat(siteRepository.findById(firstSiteId)).isEmpty
        readInTx {
            val newSite = siteRepository.findById(secondSiteId).orElseThrow()
            assertThat(newSite.report.id).isEqualTo(reportId)
            assertThat(newSite.fields).hasSize(1)
            assertThat(newSite.fields.single().value).isEqualTo("second")
        }
    }

    @Test
    fun `rejects photo field whose spec_id would escape the photos root`() {
        val reportId = UUID.randomUUID()
        val routeId = UUID.randomUUID()
        val sectionId = UUID.randomUUID()
        val siteId = UUID.randomUUID()
        val photoBase64 = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })

        val payload = TestFixtures.reportPayload(
            reportId = reportId,
            routeId = routeId,
            sectionId = sectionId,
            siteId = siteId,
            fields = listOf(
                TestFixtures.photoField(
                    specId = "../../../../escape/attempt",
                    base64Value = photoBase64,
                ),
            ),
        )

        mockMvc.put("/api/reports/{id}", reportId) {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(payload)
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `rejects numeric epoch-second timestamp`() {
        val reportId = UUID.randomUUID()
        val payload = mapOf(
            "id" to reportId.toString(),
            "inspector" to "Test",
            "route" to mapOf(
                "id" to UUID.randomUUID().toString(),
                "name" to "R",
            ),
            "section" to mapOf(
                "id" to UUID.randomUUID().toString(),
                "begin_km" to 0,
                "end_km" to 1,
            ),
            "start_time" to 1704321600L,  // numeric — must be rejected
            "sites" to emptyList<Any>(),
        )

        mockMvc.put("/api/reports/{id}", reportId) {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(payload)
        }.andExpect {
            status { isBadRequest() }
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

        val payload = TestFixtures.reportPayload(
            reportId = reportId,
            routeId = routeId,
            sectionId = sectionId,
            siteId = siteId,
            routeName = "Новый маршрут",
            routeLengthKm = 42,
            sectionBeginKm = 3,
            sectionEndKm = 7,
            endTime = null,
        )

        mockMvc.put("/api/reports/{id}", reportId) {
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
