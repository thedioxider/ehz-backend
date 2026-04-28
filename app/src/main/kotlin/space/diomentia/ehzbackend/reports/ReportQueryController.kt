package space.diomentia.ehzbackend.reports

import org.springframework.data.web.PagedModel
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import space.diomentia.ehzbackend.reports.dto.ReportDetailDto
import space.diomentia.ehzbackend.reports.dto.ReportSummaryDto
import java.time.Instant
import java.util.UUID

/**
 * Read-side HTTP surface for reports. Deliberately separate from
 * [ReportIngestController] — read and write are different concerns with
 * different DTOs, and keeping them split avoids crowding the ingest class
 * with paging + filter plumbing.
 *
 * No auth / port-scoping here; Step 6 adds a LAN-ingest filter and wires
 * the three-port exposure (dashboard / ingest / internal).
 */
@RestController
@RequestMapping("/api/reports")
class ReportQueryController(
    private val queryService: ReportQueryService,
) {

    /**
     * Returns a [PagedModel] wrapping the underlying `Page` — serializes as
     * `{ content: [...], page: { size, number, totalElements, totalPages } }`
     * (the Spring Data 3.1+ envelope), rather than the legacy flat shape that
     * a raw `Page` emits.
     */
    @GetMapping
    fun list(
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) route: UUID?,
        @RequestParam(required = false) section: UUID?,
        @RequestParam(required = false) inspector: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "$DEFAULT_PAGE_SIZE") size: Int,
    ): PagedModel<ReportSummaryDto> {
        require(page >= 0) { "page must be >= 0" }
        require(size >= 1) { "size must be >= 1" }
        val cappedSize = size.coerceAtMost(MAX_PAGE_SIZE)
        val filter = ReportListFilter(
            from = from,
            to = to,
            routeId = route,
            sectionId = section,
            inspector = inspector,
        )
        return PagedModel(queryService.list(filter, page, cappedSize))
    }

    @GetMapping("/{id}")
    fun detail(@PathVariable id: UUID): ResponseEntity<ReportDetailDto> {
        val dto = queryService.detail(id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(dto)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(e: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(mapOf("error" to (e.message ?: "Bad request")))

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
        /**
         * Hard cap for `?size=` — prevents a client asking for a huge page and
         * OOMing the service. Picked to comfortably cover a day's ingest while
         * staying well below JPA-hydration pain.
         */
        const val MAX_PAGE_SIZE = 100
    }
}
