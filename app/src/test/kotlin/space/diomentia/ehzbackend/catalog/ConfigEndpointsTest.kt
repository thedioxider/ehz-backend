package space.diomentia.ehzbackend.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import space.diomentia.ehzbackend.AbstractPostgresIntegrationTest
import space.diomentia.ehzbackend.catalog.dto.AreasYaml
import tools.jackson.dataformat.yaml.YAMLMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * Covers the two `/api/config` endpoints:
 *   - `/areas` — DB-backed routes + sections, serialized as YAML
 *   - `/report-config` — file-passthrough with `{ehz.config-dir}` override
 *
 * The override case uses `@TempDir` to stage a replacement report-config.yaml.
 * `ehz.config-dir` is pinned via [DynamicPropertySource] because the Spring
 * context is shared across tests and the property must land before the
 * controller gets its `@Value` injected.
 */
@AutoConfigureMockMvc
class ConfigEndpointsTest : AbstractPostgresIntegrationTest() {

    companion object {
        @JvmStatic
        val configDir: Path = Files.createTempDirectory("ehz-config-test-")

        @DynamicPropertySource
        @JvmStatic
        fun overrideConfigDir(registry: DynamicPropertyRegistry) {
            registry.add("ehz.config-dir") { configDir.toString() }
        }
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var yamlMapper: YAMLMapper

    @Test
    fun `GET api config areas returns seeded catalog as YAML`() {
        val response = mockMvc.get("/api/config/areas")
            .andExpect {
                status { isOk() }
                header { string("X-Contract-Version", "1") }
            }
            .andReturn().response

        val contentType = response.contentType ?: ""
        assertThat(contentType).startsWith("application/yaml")

        val body = response.contentAsString
        assertThat(body).contains("diagnostic_objects:")
        assertThat(body).contains("diagnostic_areas:")

        val parsed = yamlMapper.readValue(body, AreasYaml::class.java)
        assertThat(parsed.diagnosticObjects).anySatisfy { route ->
            assertThat(route.name).isEqualTo("САЦ-III")
            assertThat(route.lengthKm).isEqualTo(3600)
        }
        assertThat(parsed.diagnosticAreas).hasSizeGreaterThanOrEqualTo(2)
    }

    @Test
    fun `GET api config report-config serves bundled YAML by default`() {
        // Make sure no override file is left behind from the override test below.
        val override = configDir.resolve("report-config.yaml")
        Files.deleteIfExists(override)

        val response = mockMvc.get("/api/config/report-config")
            .andExpect {
                status { isOk() }
                header { string("X-Contract-Version", "1") }
            }
            .andReturn().response

        assertThat(response.contentType ?: "").startsWith("application/yaml")
        val body = response.contentAsString
        assertThat(body).startsWith("field_specs:")
    }

    @Test
    fun `GET api config report-config serves on-disk override when present`(@TempDir unusedPerTestTempDir: Path) {
        // Write into the class-static `configDir` that DynamicPropertySource pointed at.
        val override = configDir.resolve("report-config.yaml")
        val markerBytes = "field_specs: []\n# override marker: ${System.nanoTime()}\n".toByteArray()
        Files.write(override, markerBytes)

        try {
            val response = mockMvc.get("/api/config/report-config")
                .andExpect { status { isOk() } }
                .andReturn().response

            assertThat(response.contentAsByteArray).isEqualTo(markerBytes)
        } finally {
            Files.deleteIfExists(override)
        }
    }
}
