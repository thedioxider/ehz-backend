package space.diomentia.ehzbackend

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.nio.file.Files
import java.nio.file.Path

/**
 * Covers `/api/info`, `/api/ping`, and the cross-cutting `X-Contract-Version`
 * filter. The `/api/reports` assertion exists specifically to prove the
 * filter covers the whole API umbrella, not just the info endpoints; the
 * 404 assertion proves it also stamps responses that never reach a handler,
 * which is the scenario a client uses to diagnose a contract mismatch.
 */
@AutoConfigureMockMvc
class InfoControllerTest : AbstractPostgresIntegrationTest() {

    companion object {
        @JvmStatic
        val dataDir: Path = Files.createTempDirectory("ehz-info-data-")

        @DynamicPropertySource
        @JvmStatic
        fun overrideDataDir(registry: DynamicPropertyRegistry) {
            registry.add("ehz.data-dir") { dataDir.toString() }
        }
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `api ping returns pong true and X-Contract-Version header`() {
        mockMvc.get("/api/ping").andExpect {
            status { isOk() }
            jsonPath("$.pong") { value(true) }
            header { string(ApiContract.HEADER_NAME, ApiContract.VERSION.toString()) }
        }
    }

    @Test
    fun `api info exposes contract build and commit`() {
        mockMvc.get("/api/info").andExpect {
            status { isOk() }
            jsonPath("$.contract") { value(ApiContract.VERSION) }
            jsonPath("$.build") { exists() }
            jsonPath("$.commit") { exists() }
            header { string(ApiContract.HEADER_NAME, ApiContract.VERSION.toString()) }
        }
    }

    @Test
    fun `contract version header is stamped on all api responses`() {
        // `/api/reports` is a fully-unrelated endpoint — its response carrying
        // the header proves the filter covers the whole API tree, not just
        // this test's own controller.
        mockMvc.get("/api/reports").andExpect {
            status { isOk() }
            header { string(ApiContract.HEADER_NAME, ApiContract.VERSION.toString()) }
        }
    }

    @Test
    fun `contract version header is stamped on api 404 responses`() {
        // Unknown `/api/...` paths never reach a Spring handler, so a
        // HandlerInterceptor would miss them. The filter runs regardless —
        // clients hitting a retired endpoint still get the version header and
        // can diagnose the contract mismatch instead of a bare 404.
        mockMvc.get("/api/does-not-exist").andExpect {
            status { isNotFound() }
            header { string(ApiContract.HEADER_NAME, ApiContract.VERSION.toString()) }
        }
    }
}
