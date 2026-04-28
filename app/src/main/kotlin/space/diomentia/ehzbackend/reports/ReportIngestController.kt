package space.diomentia.ehzbackend.reports

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import space.diomentia.ehzbackend.devices.Device
import space.diomentia.ehzbackend.devices.DeviceTrackingFilter
import space.diomentia.ehzbackend.reports.dto.ReportDto
import java.util.UUID

/**
 * Thin HTTP adapter for `PUT /api/reports/{id}`.
 *
 * The client (Android app) generates the report UUID, so it owns the
 * identifier before the request. PUT is idempotent: a duplicate request
 * with the same id overwrites the existing record rather than duplicating
 * it — safe under network-flake retries.
 *
 * Response codes:
 *   201 Created  — first PUT at this id
 *   200 OK       — subsequent PUT overwrites an existing record
 */
@RestController
@RequestMapping("/api/reports")
class ReportIngestController(
    private val ingestService: ReportIngestService,
) {
    @PutMapping("/{id}")
    fun ingest(
        @PathVariable id: UUID,
        @RequestBody dto: ReportDto,
        request: HttpServletRequest,
    ): ResponseEntity<Unit> {
        if (dto.id != id) {
            throw IllegalArgumentException("Path id $id does not match body id ${dto.id}")
        }
        val device = request.getAttribute(DeviceTrackingFilter.REQUEST_ATTR) as? Device
        val wasNew = ingestService.ingest(dto, device)
        return ResponseEntity
            .status(if (wasNew) HttpStatus.CREATED else HttpStatus.OK)
            .build()
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(e: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(mapOf("error" to (e.message ?: "Bad request")))
}
