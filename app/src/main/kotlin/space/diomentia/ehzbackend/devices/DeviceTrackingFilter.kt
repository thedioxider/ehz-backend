package space.diomentia.ehzbackend.devices

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Passive device tracking via the `X-Device-Id` request header.
 *
 * If present and parseable as a UUID, upsert the `devices` row (first_seen on
 * insert, last_seen on both insert and update) and expose the resolved entity
 * as a request attribute so the ingestion controller can link the report to
 * the device without a second DB lookup.
 *
 * Absent or malformed header → do nothing. `reports.device_id` is nullable, so
 * a report from an un-identified source is still ingestible; we simply don't
 * know whose device it came from.
 *
 * Ordered after [space.diomentia.ehzbackend.config.LanIngestFilter] so the
 * DB is never touched when the LAN filter short-circuits with 401/404.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
class DeviceTrackingFilter(
    private val tracker: DeviceTracker,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(DeviceTrackingFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val raw = request.getHeader(HEADER_NAME)
        if (!raw.isNullOrBlank()) {
            val id = parseUuid(raw)
            if (id != null) {
                try {
                    val device = tracker.touch(id)
                    request.setAttribute(REQUEST_ATTR, device)
                } catch (e: Exception) {
                    // Never let a devices-table upsert fail the actual request;
                    // the FK stays nullable so the downstream insert still works.
                    log.warn("Device upsert failed for {}: {}", id, e.message)
                }
            }
        }
        chain.doFilter(request, response)
    }

    private fun parseUuid(raw: String): UUID? = try {
        UUID.fromString(raw.trim())
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        const val HEADER_NAME: String = "X-Device-Id"
        const val REQUEST_ATTR: String = "ehz.device"
    }
}
