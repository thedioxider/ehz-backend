package space.diomentia.ehzbackend.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import space.diomentia.ehzbackend.bootstrap.CredentialsStore
import java.security.MessageDigest
import kotlin.text.Charsets.UTF_8

/**
 * Port-scoped auth + path allowlist for the LAN-facing ingest listener.
 *
 * The app runs three Spring listeners backed by the same controllers; *what's
 * reachable* depends on which Tomcat connector accepted the request. This
 * filter implements that policy at the servlet layer because we have no
 * Spring Security on the classpath (deliberately — port scoping + a single
 * static API key is the whole v1 auth model).
 *
 * Policy when `request.localPort == ehz.ingest.port`:
 *
 * | Verb + Path              | Auth required | Behavior          |
 * |--------------------------|---------------|-------------------|
 * | `GET /api/ping`          | no            | pass through      |
 * | `GET /api/info`          | no            | pass through      |
 * | `POST /api/reports`      | yes           | validate bearer   |
 * | anything else            | —             | 404 (hide path)   |
 *
 * Unauthorized paths return **404, not 403** — we deliberately don't admit
 * that a path exists to a LAN attacker. Paths that *are* on the allowlist
 * but miss/fail auth return 401 with `WWW-Authenticate: Bearer`.
 *
 * On other ports (internal 8082, management 8080) the filter is a no-op and
 * `doFilter` passes the request straight through. That's what makes the
 * read endpoints visible to the Docker-network peer but invisible to the LAN.
 *
 * Ordered *after* [ContractVersionFilter] so short-circuited 4xx responses
 * still carry `X-Contract-Version`.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class LanIngestFilter(
    private val credentials: CredentialsStore,
    @param:Value("\${ehz.ingest.port}") private val lanPort: Int,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        if (request.localPort != lanPort) {
            chain.doFilter(request, response)
            return
        }

        val method = request.method
        // Servlet's requestURI is already decoded + normalized enough for exact match;
        // we compare against static literals so trailing slashes are intentionally
        // treated as "different path".
        val path = request.requestURI

        when {
            method == HttpMethod.GET.name() && path == PATH_PING -> {
                chain.doFilter(request, response)
            }
            method == HttpMethod.GET.name() && path == PATH_INFO -> {
                chain.doFilter(request, response)
            }
            method == HttpMethod.POST.name() && path == PATH_REPORTS -> {
                if (isAuthorized(request)) {
                    chain.doFilter(request, response)
                } else {
                    writeUnauthorized(response)
                }
            }
            else -> {
                writeNotFound(response)
            }
        }
    }

    private fun isAuthorized(request: HttpServletRequest): Boolean {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION) ?: return false
        if (!header.startsWith(BEARER_PREFIX)) return false
        val presented = header.substring(BEARER_PREFIX.length).trim()
        if (presented.isEmpty()) return false
        val expected = credentials.apiKey
        // Constant-time on the byte arrays — trivially short-circuits on length
        // mismatch, which leaks no useful info beyond what a length check would
        // anyway.
        return MessageDigest.isEqual(expected.toByteArray(UTF_8), presented.toByteArray(UTF_8))
    }

    private fun writeUnauthorized(response: HttpServletResponse) {
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = UTF_8.name()
        response.writer.write("""{"error":"unauthorized"}""")
    }

    private fun writeNotFound(response: HttpServletResponse) {
        response.status = HttpServletResponse.SC_NOT_FOUND
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = UTF_8.name()
        response.writer.write("""{"error":"not found"}""")
    }

    companion object {
        const val PATH_PING: String = "/api/ping"
        const val PATH_INFO: String = "/api/info"
        const val PATH_REPORTS: String = "/api/reports"
        const val BEARER_PREFIX: String = "Bearer "
    }
}
