package space.diomentia.ehzbackend.reports

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import space.diomentia.ehzbackend.AbstractPostgresIntegrationTest
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * End-to-end test for the read surface: `GET /api/reports`, `GET /api/reports/
 * {id}`, and `GET /api/photos/...`. Seeds data via the real ingest endpoint so
 * we exercise the same code path production clients take.
 *
 * `ehz.data-dir` is overridden via [DynamicPropertySource] the same way the
 * ingest test does — MockMvc shares the application context across tests in
 * the same class, so the override must land before context start.
 */
@AutoConfigureMockMvc
class ReadIntegrationTest : AbstractPostgresIntegrationTest() {

    companion object {
        @JvmStatic
        val tempDir: Path = Files.createTempDirectory("ehz-read-test-")

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

    private fun ingest(payload: Any) {
        mockMvc.post("/api/reports") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(payload)
        }.andExpect { status { isCreated() } }
    }

    @Test
    fun `list returns paged results sorted by start_time desc with site_count`() {
        val routeId = UUID.randomUUID()
        val sectionId = UUID.randomUUID()

        val olderReportId = UUID.randomUUID()
        val newerReportId = UUID.randomUUID()
        val olderSiteId = UUID.randomUUID()
        val newerSiteId = UUID.randomUUID()

        // Two reports sharing the same route/section; newer has the larger start_time.
        val older = TestFixtures.reportPayload(
            reportId = olderReportId,
            routeId = routeId,
            sectionId = sectionId,
            siteId = olderSiteId,
            inspector = "Alpha",
            startTime = 1704321600L,
            endTime = 1704325200L,
            fields = listOf(TestFixtures.textField("comment", "c", "x")),
        )
        val newer = TestFixtures.reportPayload(
            reportId = newerReportId,
            routeId = routeId,
            sectionId = sectionId,
            siteId = newerSiteId,
            inspector = "Beta",
            startTime = 1704400000L,
            endTime = 1704403600L,
            fields = listOf(TestFixtures.textField("comment", "c", "y")),
        )

        ingest(listOf(older, newer))

        // Filter by this test's route so unrelated reports from other tests
        // in the shared context don't pollute the assertions.
        mockMvc.get("/api/reports") { param("route", routeId.toString()) }.andExpect {
            status { isOk() }
            jsonPath("$.content.length()") { value(2) }
            // Sorted start_time DESC: newer first.
            jsonPath("$.content[0].id") { value(newerReportId.toString()) }
            jsonPath("$.content[1].id") { value(olderReportId.toString()) }
            jsonPath("$.content[0].site_count") { value(1) }
            jsonPath("$.content[0].route.id") { value(routeId.toString()) }
            jsonPath("$.content[0].section.begin_km") { value(0) }
            // Spring Data 3.1+ envelope: content[] + page{ size, number, totalElements, totalPages }.
            jsonPath("$.page.totalElements") { value(2) }
        }
    }

    @Test
    fun `list filters by route and inspector substring`() {
        val routeAId = UUID.randomUUID()
        val routeBId = UUID.randomUUID()
        val sectionAId = UUID.randomUUID()
        val sectionBId = UUID.randomUUID()

        val onRouteA = TestFixtures.reportPayload(
            reportId = UUID.randomUUID(),
            routeId = routeAId,
            sectionId = sectionAId,
            siteId = UUID.randomUUID(),
            inspector = "Петров Павел",
            routeName = "A-route",
        )
        val onRouteBNoMatchInspector = TestFixtures.reportPayload(
            reportId = UUID.randomUUID(),
            routeId = routeBId,
            sectionId = sectionBId,
            siteId = UUID.randomUUID(),
            inspector = "Сидоров Семён",
            routeName = "B-route",
        )
        val onRouteAWrongInspector = TestFixtures.reportPayload(
            reportId = UUID.randomUUID(),
            routeId = routeAId,
            sectionId = sectionAId,
            siteId = UUID.randomUUID(),
            inspector = "Иванов Иван",
            routeName = "A-route",
        )

        ingest(listOf(onRouteA, onRouteBNoMatchInspector, onRouteAWrongInspector))

        // route=A AND inspector~=петров (case-insensitive) -> exactly one match.
        mockMvc.get("/api/reports") {
            param("route", routeAId.toString())
            param("inspector", "петров")
        }.andExpect {
            status { isOk() }
            jsonPath("$.content.length()") { value(1) }
            jsonPath("$.content[0].inspector") { value("Петров Павел") }
            jsonPath("$.content[0].route.id") { value(routeAId.toString()) }
        }
    }

    @Test
    fun `inspector filter treats LIKE wildcards as literals`() {
        val routeId = UUID.randomUUID()
        val sectionId = UUID.randomUUID()

        // Two inspectors under the same route: one contains a literal `_`,
        // one doesn't. Without escape, `?inspector=_` (LIKE `%_%`) would
        // match both (since `_` = any single char). With escape, only the
        // one containing a real underscore matches.
        val withUnderscore = "Ivan_Petrov"
        val withoutUnderscore = "AlphaBeta"

        ingest(
            listOf(
                TestFixtures.reportPayload(
                    reportId = UUID.randomUUID(),
                    routeId = routeId,
                    sectionId = sectionId,
                    siteId = UUID.randomUUID(),
                    inspector = withUnderscore,
                    routeName = "Escape-route",
                ),
                TestFixtures.reportPayload(
                    reportId = UUID.randomUUID(),
                    routeId = routeId,
                    sectionId = sectionId,
                    siteId = UUID.randomUUID(),
                    inspector = withoutUnderscore,
                    routeName = "Escape-route",
                ),
            ),
        )

        // `_` must match only the inspector that actually contains `_`.
        mockMvc.get("/api/reports") {
            param("route", routeId.toString())
            param("inspector", "_")
        }.andExpect {
            status { isOk() }
            jsonPath("$.content.length()") { value(1) }
            jsonPath("$.content[0].inspector") { value(withUnderscore) }
        }

        // `%` is not present in either stored inspector — must match nothing.
        mockMvc.get("/api/reports") {
            param("route", routeId.toString())
            param("inspector", "%")
        }.andExpect {
            status { isOk() }
            jsonPath("$.content.length()") { value(0) }
        }

        // The literal substring `Ivan_Petrov` still matches that one row.
        mockMvc.get("/api/reports") {
            param("route", routeId.toString())
            param("inspector", "Ivan_Petrov")
        }.andExpect {
            status { isOk() }
            jsonPath("$.content.length()") { value(1) }
            jsonPath("$.content[0].inspector") { value(withUnderscore) }
        }
    }

    @Test
    fun `list caps size at MAX_PAGE_SIZE`() {
        // We don't need 100+ rows to prove the cap — the response's page.size
        // reflects the Pageable the service used, so one row is enough.
        ingest(
            listOf(
                TestFixtures.reportPayload(
                    reportId = UUID.randomUUID(),
                    routeId = UUID.randomUUID(),
                    sectionId = UUID.randomUUID(),
                    siteId = UUID.randomUUID(),
                ),
            ),
        )

        mockMvc.get("/api/reports") { param("size", "500") }.andExpect {
            status { isOk() }
            jsonPath("$.page.size") { value(ReportQueryController.MAX_PAGE_SIZE) }
        }
    }

    @Test
    fun `detail returns full nested shape`() {
        val reportId = UUID.randomUUID()
        val routeId = UUID.randomUUID()
        val sectionId = UUID.randomUUID()
        val siteId = UUID.randomUUID()

        val photoBytes = TestFixtures.photoBytes()
        val photoB64 = TestFixtures.base64(photoBytes)

        val payload = TestFixtures.reportPayload(
            reportId = reportId,
            routeId = routeId,
            sectionId = sectionId,
            siteId = siteId,
            inspector = "Detail Inspector",
            fields = listOf(
                TestFixtures.photoField("site_photo", base64Value = photoB64),
                TestFixtures.textField("comment", "Комментарий", "ok"),
            ),
        )
        ingest(listOf(payload))

        mockMvc.get("/api/reports/$reportId").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(reportId.toString()) }
            jsonPath("$.inspector") { value("Detail Inspector") }
            jsonPath("$.route.id") { value(routeId.toString()) }
            jsonPath("$.section.id") { value(sectionId.toString()) }
            jsonPath("$.section.begin_km") { value(0) }
            jsonPath("$.section.end_km") { value(41) }
            jsonPath("$.sites.length()") { value(1) }
            jsonPath("$.sites[0].id") { value(siteId.toString()) }
            jsonPath("$.sites[0].position_km") { value(12.5) }
            jsonPath("$.sites[0].location.lat") { value(55.75) }
            jsonPath("$.sites[0].location.lng") { value(37.62) }
            jsonPath("$.sites[0].fields.length()") { value(2) }
            // Photo field value should be the relative path, NOT base64.
            jsonPath("$.sites[0].fields[?(@.spec_id=='site_photo')].value") {
                value("photos/$reportId/$siteId/site_photo.webp")
            }
            jsonPath("$.sites[0].fields[?(@.spec_id=='comment')].value") { value("ok") }
        }
    }

    @Test
    fun `detail returns 404 for unknown id`() {
        val unknown = UUID.randomUUID()
        mockMvc.get("/api/reports/$unknown").andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `photo endpoint returns the stored bytes`() {
        val reportId = UUID.randomUUID()
        val routeId = UUID.randomUUID()
        val sectionId = UUID.randomUUID()
        val siteId = UUID.randomUUID()

        val photoBytes = TestFixtures.photoBytes(64)
        val photoB64 = TestFixtures.base64(photoBytes)
        val payload = TestFixtures.reportPayload(
            reportId = reportId,
            routeId = routeId,
            sectionId = sectionId,
            siteId = siteId,
            fields = listOf(TestFixtures.photoField("site_photo", base64Value = photoB64)),
        )
        ingest(listOf(payload))

        val response = mockMvc.get("/api/photos/$reportId/$siteId/site_photo.webp")
            .andExpect {
                status { isOk() }
                header { string("Content-Type", "image/webp") }
                header { string("Content-Disposition", "inline; filename=\"site_photo.webp\"") }
                header { longValue("Content-Length", photoBytes.size.toLong()) }
            }
            .andReturn()
        assertThat(response.response.contentAsByteArray).isEqualTo(photoBytes)
    }

    @Test
    fun `photo endpoint returns 404 when file is missing`() {
        val reportId = UUID.randomUUID()
        val wrongSiteId = UUID.randomUUID() // never written to disk
        mockMvc.get("/api/photos/$reportId/$wrongSiteId/site_photo.webp").andExpect {
            status { isNotFound() }
        }
    }
}
