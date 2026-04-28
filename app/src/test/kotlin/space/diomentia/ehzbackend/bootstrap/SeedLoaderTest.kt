package space.diomentia.ehzbackend.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import space.diomentia.ehzbackend.AbstractPostgresIntegrationTest
import space.diomentia.ehzbackend.catalog.RouteRepository
import space.diomentia.ehzbackend.catalog.SectionRepository
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Proves the catalog gets seeded on first boot and that rerunning the seeder
 * against a populated DB is a no-op.
 *
 * The seed row is identified by its deterministic route UUID (declared in
 * `seed/initial_areas.yaml`), not by overall row count — the Postgres
 * container is shared across test classes in the same JVM, so other tests
 * may have inserted their own random-UUID routes into the same DB.
 */
class SeedLoaderTest : AbstractPostgresIntegrationTest() {

    @Autowired
    lateinit var routeRepository: RouteRepository

    @Autowired
    lateinit var sectionRepository: SectionRepository

    @Autowired
    lateinit var seedLoader: SeedLoader

    @Test
    fun `first boot seeds САЦ-III route and its two sections`() {
        val seededRoute = routeRepository.findById(SEEDED_ROUTE_ID).orElseThrow {
            AssertionError("seed route $SEEDED_ROUTE_ID not present")
        }
        assertThat(seededRoute.name).isEqualTo("САЦ-III")
        assertThat(seededRoute.lengthKm).isEqualTo(3600)

        val firstSection = sectionRepository.findById(SEEDED_SECTION_0_41_ID).orElseThrow {
            AssertionError("seed section $SEEDED_SECTION_0_41_ID not present")
        }
        assertThat(firstSection.beginKm).isEqualTo(0)
        assertThat(firstSection.endKm).isEqualTo(41)

        val secondSection = sectionRepository.findById(SEEDED_SECTION_41_82_ID).orElseThrow {
            AssertionError("seed section $SEEDED_SECTION_41_82_ID not present")
        }
        assertThat(secondSection.beginKm).isEqualTo(41)
        assertThat(secondSection.endKm).isEqualTo(82)
        // Route FK: don't touch the lazy proxy outside a tx — just check the
        // section exists with the expected spans; the route linkage is exercised
        // by the `/api/config/areas` integration test.
    }

    @Test
    fun `seed is idempotent on re-invocation`() {
        val routesBefore = routeRepository.count()
        val sectionsBefore = sectionRepository.count()

        // Run the loader again — should short-circuit on the `count() > 0` guard.
        seedLoader.seed()

        assertThat(routeRepository.count()).isEqualTo(routesBefore)
        assertThat(sectionRepository.count()).isEqualTo(sectionsBefore)
    }

    companion object {
        @JvmStatic
        val dataDir: Path = Files.createTempDirectory("ehz-seed-data-")

        @DynamicPropertySource
        @JvmStatic
        fun overrideDataDir(registry: DynamicPropertyRegistry) {
            // Pin data-dir so `CredentialsStore` doesn't write a stray
            // `./data/credentials.txt` into the project tree during tests.
            registry.add("ehz.data-dir") { dataDir.toString() }
        }

        // Mirrors the UUIDs baked into `seed/initial_areas.yaml`. Keep in sync
        // if the seed file is ever re-keyed.
        private val SEEDED_ROUTE_ID =
            UUID.fromString("5cb6f3ca-6b42-4a76-a7d9-2c12b1e8f0d3")
        private val SEEDED_SECTION_0_41_ID =
            UUID.fromString("f19c3e67-04d1-4c3f-9b8d-8a2ed6f54e2f")
        private val SEEDED_SECTION_41_82_ID =
            UUID.fromString("7a18e5d2-3c0b-4e5f-90c9-41b5a02fb8d7")
    }
}
