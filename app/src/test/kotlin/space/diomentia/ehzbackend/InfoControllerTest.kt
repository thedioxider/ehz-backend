package space.diomentia.ehzbackend

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * Covers `/api/info`, `/api/ping`, and the cross-cutting `X-Contract-Version`
 * interceptor. The `/api/reports` assertion exists specifically to prove the
 * interceptor is registered for the whole API umbrella, not just wired into
 * the info endpoints.
 */
@AutoConfigureMockMvc
class InfoControllerTest : AbstractPostgresIntegrationTest() {

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
        // the header proves the interceptor covers the whole API tree,
        // not just this test's own controller.
        mockMvc.get("/api/reports").andExpect {
            status { isOk() }
            header { string(ApiContract.HEADER_NAME, ApiContract.VERSION.toString()) }
        }
    }
}
