package space.diomentia.ehzbackend.devices

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
import space.diomentia.ehzbackend.reports.ReportRepository
import space.diomentia.ehzbackend.reports.TestFixtures
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Exercises the passive `X-Device-Id` device-tracking path end-to-end.
 *
 * Not transactional at the test level — same reasoning as [space.diomentia.ehzbackend.reports.IngestIntegrationTest]:
 * each ingest commits independently, and we also want the filter's own
 * transactional upsert to commit so a subsequent request sees the row.
 */
@AutoConfigureMockMvc
class DeviceTrackingTest : AbstractPostgresIntegrationTest() {

    companion object {
        @JvmStatic
        val tempDir: Path = Files.createTempDirectory("ehz-device-test-")

        @DynamicPropertySource
        @JvmStatic
        fun overrideDataDir(registry: DynamicPropertyRegistry) {
            registry.add("ehz.data-dir") { tempDir.toString() }
        }
    }

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var reportRepository: ReportRepository
    @Autowired lateinit var deviceRepository: DeviceRepository
    @Autowired lateinit var transactionTemplate: TransactionTemplate

    private fun <T> readInTx(block: () -> T): T =
        transactionTemplate.execute { block() }!!

    private fun ingest(payload: Map<String, Any?>, deviceId: String? = null) {
        val reportId = UUID.fromString(payload["id"] as String)
        mockMvc.put("/api/reports/{id}", reportId) {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(payload)
            if (deviceId != null) header("X-Device-Id", deviceId)
        }.andExpect { status { isCreated() } }
    }

    @Test
    fun `posting with X-Device-Id creates a device and links the report`() {
        val deviceId = UUID.randomUUID()
        val reportId = UUID.randomUUID()
        val payload = TestFixtures.reportPayload(
            reportId = reportId,
            routeId = UUID.randomUUID(),
            sectionId = UUID.randomUUID(),
            siteId = UUID.randomUUID(),
        )

        ingest(payload, deviceId = deviceId.toString())

        val device = deviceRepository.findById(deviceId).orElseThrow()
        assertThat(device.firstSeen).isNotNull()
        assertThat(device.lastSeen).isNotNull()

        readInTx {
            val report = reportRepository.findById(reportId).orElseThrow()
            assertThat(report.device).isNotNull()
            assertThat(report.device!!.id).isEqualTo(deviceId)
        }
    }

    @Test
    fun `second post with same device updates last_seen`() {
        val deviceId = UUID.randomUUID()
        val firstReportId = UUID.randomUUID()
        val secondReportId = UUID.randomUUID()
        val routeId = UUID.randomUUID()
        val sectionId = UUID.randomUUID()

        ingest(
            TestFixtures.reportPayload(
                reportId = firstReportId,
                routeId = routeId, sectionId = sectionId,
                siteId = UUID.randomUUID(),
            ),
            deviceId = deviceId.toString(),
        )
        val afterFirst = deviceRepository.findById(deviceId).orElseThrow()
        val firstSeenAfter = afterFirst.firstSeen
        val firstLastSeen = afterFirst.lastSeen

        ingest(
            TestFixtures.reportPayload(
                reportId = secondReportId,
                routeId = routeId, sectionId = sectionId,
                siteId = UUID.randomUUID(),
            ),
            deviceId = deviceId.toString(),
        )

        val afterSecond = deviceRepository.findById(deviceId).orElseThrow()
        assertThat(afterSecond.firstSeen).isEqualTo(firstSeenAfter) // unchanged
        assertThat(afterSecond.lastSeen).isAfter(firstLastSeen)
    }

    @Test
    fun `posting without X-Device-Id leaves report device null and does not insert`() {
        val countBefore = deviceRepository.count()
        val reportId = UUID.randomUUID()
        val payload = TestFixtures.reportPayload(
            reportId = reportId,
            routeId = UUID.randomUUID(),
            sectionId = UUID.randomUUID(),
            siteId = UUID.randomUUID(),
        )

        ingest(payload, deviceId = null)

        assertThat(deviceRepository.count()).isEqualTo(countBefore)
        readInTx {
            val report = reportRepository.findById(reportId).orElseThrow()
            assertThat(report.device).isNull()
        }
    }

    @Test
    fun `malformed X-Device-Id is ignored - report still ingests with null device`() {
        val countBefore = deviceRepository.count()
        val reportId = UUID.randomUUID()
        val payload = TestFixtures.reportPayload(
            reportId = reportId,
            routeId = UUID.randomUUID(),
            sectionId = UUID.randomUUID(),
            siteId = UUID.randomUUID(),
        )

        ingest(payload, deviceId = "not-a-uuid")

        assertThat(deviceRepository.count()).isEqualTo(countBefore)
        readInTx {
            val report = reportRepository.findById(reportId).orElseThrow()
            assertThat(report.device).isNull()
        }
    }
}
