package space.diomentia.ehzbackend

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Drift check: the two committed OpenAPI YAML files must exactly match what
 * the running app generates. If this test fails, run:
 *
 *   ./gradlew :app:regenOpenApiSpec
 *
 * then commit the updated docs/api-contract-{lan,internal}.yaml.
 *
 * In regen mode (-Dehz.openapi.regen=true), the test writes the live YAML
 * to disk instead of asserting. The regenOpenApiSpec Gradle task sets this flag.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiContractTest : AbstractPostgresIntegrationTest() {

    companion object {
        val tempDir: Path = Files.createTempDirectory("ehz-openapi-test-")

        @DynamicPropertySource
        @JvmStatic
        fun overrideProps(registry: DynamicPropertyRegistry) {
            registry.add("ehz.data-dir") { tempDir.toString() }
        }

        private val regen = System.getProperty("ehz.openapi.regen") == "true"

        private fun docFile(name: String): File =
            File(System.getProperty("user.dir"), "../docs/$name")
    }

    @LocalServerPort
    var serverPort: Int = 0

    private val client by lazy {
        RestClient.builder().baseUrl("http://localhost:$serverPort").build()
    }

    @Test
    fun `lan group openapi spec matches committed yaml`() {
        checkOrRegen("lan", "api-contract-lan.yaml")
    }

    @Test
    fun `internal group openapi spec matches committed yaml`() {
        checkOrRegen("internal", "api-contract-internal.yaml")
    }

    private fun checkOrRegen(group: String, fileName: String) {
        val live = client.get()
            .uri("/v3/api-docs.yaml/$group")
            .retrieve()
            .body(ByteArray::class.java)!!
            .toString(Charsets.UTF_8)

        val file = docFile(fileName)
        if (regen) {
            file.parentFile.mkdirs()
            file.writeText(live)
        } else {
            assertThat(file.exists())
                .withFailMessage("$fileName does not exist — run ./gradlew :app:regenOpenApiSpec")
                .isTrue()
            assertThat(live)
                .withFailMessage(
                    "$fileName is stale. Run: ./gradlew :app:regenOpenApiSpec\n" +
                        "then commit the updated file.",
                )
                .isEqualTo(file.readText())
        }
    }
}
