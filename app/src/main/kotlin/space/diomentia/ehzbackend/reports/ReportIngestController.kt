package space.diomentia.ehzbackend.reports

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import space.diomentia.ehzbackend.devices.Device
import space.diomentia.ehzbackend.devices.DeviceTrackingFilter
import space.diomentia.ehzbackend.reports.dto.ReportDto
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Thin HTTP adapter for `POST /api/reports`.
 *
 * The app's exporter emits a JSON **array** of reports; for robustness we also
 * accept a bare object. The body is parsed as [JsonNode] first and converted
 * per-element so a malformed element surfaces a clear 400 rather than mangling
 * the whole batch.
 *
 * Response is 201 for both "created" and "replaced" — this endpoint is
 * ingestion, not REST creation, and we want a single success code.
 */
@RestController
@RequestMapping("/api/reports")
class ReportIngestController(
    private val ingestService: ReportIngestService,
    private val objectMapper: ObjectMapper,
) {
    @PostMapping
    fun ingest(
        @RequestBody body: JsonNode,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Int>> {
        val nodes: List<JsonNode> = when {
            body.isArray -> body.toList()
            body.isObject -> listOf(body)
            else -> throw IllegalArgumentException(
                "Request body must be a JSON object or array of reports"
            )
        }

        val reports: List<ReportDto> = nodes.map {
            try {
                objectMapper.treeToValue(it, ReportDto::class.java)
            } catch (e: JacksonException) {
                // Jackson 3: JacksonException is the unchecked base for all parser/binder failures.
                throw IllegalArgumentException("Invalid report payload: ${e.message}", e)
            }
        }

        // DeviceTrackingFilter stashes the upserted entity here when a valid
        // X-Device-Id arrived on the request; null otherwise.
        val device = request.getAttribute(DeviceTrackingFilter.REQUEST_ATTR) as? Device

        reports.forEach { ingestService.ingest(it, device) }

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(mapOf("accepted" to reports.size))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(e: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(mapOf("error" to (e.message ?: "Bad request")))
}
