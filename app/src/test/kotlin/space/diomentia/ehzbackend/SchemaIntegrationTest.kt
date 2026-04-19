package space.diomentia.ehzbackend

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import space.diomentia.ehzbackend.catalog.RouteRepository
import space.diomentia.ehzbackend.catalog.SectionRepository
import space.diomentia.ehzbackend.devices.DeviceRepository
import space.diomentia.ehzbackend.reports.ReportRepository

/**
 * The mere fact that the Spring context boots with Liquibase applied and
 * `ddl-auto=validate` active proves that the JPA entities align with the
 * Liquibase-owned schema. The assertions below are just sanity checks that
 * the repositories wired up.
 */
class SchemaIntegrationTest : AbstractPostgresIntegrationTest() {

    @Autowired
    lateinit var deviceRepository: DeviceRepository

    @Autowired
    lateinit var routeRepository: RouteRepository

    @Autowired
    lateinit var sectionRepository: SectionRepository

    @Autowired
    lateinit var reportRepository: ReportRepository

    @Test
    fun `context starts and repositories are wired`() {
        assertThat(deviceRepository).isNotNull
        assertThat(routeRepository).isNotNull
        assertThat(sectionRepository).isNotNull
        assertThat(reportRepository).isNotNull

        // Touch each repository against the live schema to exercise the query layer.
        // `routes` / `sections` are seeded by SeedLoader on ApplicationReadyEvent, and
        // other integration tests in the same JVM may have ingested reports into the
        // shared container — so we assert non-negative rather than zero. The point of
        // this test is "repositories talk to the live schema", not row volume.
        assertThat(deviceRepository.count()).isNotNegative()
        assertThat(routeRepository.count()).isNotNegative()
        assertThat(sectionRepository.count()).isNotNegative()
        assertThat(reportRepository.count()).isNotNegative()
    }
}
