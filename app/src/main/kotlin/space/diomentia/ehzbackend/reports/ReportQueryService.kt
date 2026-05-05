package space.diomentia.ehzbackend.reports

import jakarta.persistence.criteria.Predicate
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import space.diomentia.ehzbackend.reports.dto.CoordinatesViewDto
import space.diomentia.ehzbackend.reports.dto.FieldViewDto
import space.diomentia.ehzbackend.reports.dto.ReportDetailDto
import space.diomentia.ehzbackend.reports.dto.ReportSummaryDto
import space.diomentia.ehzbackend.reports.dto.RouteSummaryDto
import space.diomentia.ehzbackend.reports.dto.SectionSummaryDto
import space.diomentia.ehzbackend.reports.dto.SiteViewDto
import java.time.Instant
import java.util.UUID

/**
 * Read-side queries for reports. Separate from [ReportIngestService] because
 * the read and write surfaces have different DTOs and different transaction
 * semantics (reads are read-only, ingestion is write-heavy + idempotent).
 */
@Service
class ReportQueryService(
    private val reportRepository: ReportRepository,
    private val siteRepository: SiteRepository,
) {

    /**
     * List reports with optional filters, sorted by start_time DESC.
     *
     * `site_count` is populated by a second grouped query over the page's
     * report ids — one extra round trip, O(pageSize) rows back.
     */
    @Transactional(readOnly = true)
    fun list(filter: ReportListFilter, page: Int, size: Int): Page<ReportSummaryDto> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startTime"))
        val spec = buildSpec(filter)
        val reports = reportRepository.findAll(spec, pageable)

        val counts: Map<UUID, Long> = if (reports.isEmpty) {
            emptyMap()
        } else {
            siteRepository
                .countByReportIdIn(reports.content.map { it.id })
                .associate { it.reportId to it.count }
        }

        return reports.map { r ->
            ReportSummaryDto(
                id = r.id,
                route = RouteSummaryDto(r.route.id, r.route.name),
                section = SectionSummaryDto(r.section.id, r.section.beginKm, r.section.endKm),
                inspector = r.inspector,
                startTime = r.startTime,
                endTime = r.endTime,
                receivedAt = r.receivedAt,
                siteCount = counts[r.id] ?: 0L,
            )
        }
    }

    /**
     * Full detail for a single report, including all sites + fields. Returns
     * `null` when the id is unknown — the controller maps this to 404.
     *
     * Uses [ReportRepository.findDetailById] so sites + their fields come back
     * in a single join-fetch, sidestepping the N+1 that a lazy walk would
     * trigger here.
     */
    @Transactional(readOnly = true)
    fun detail(id: UUID): ReportDetailDto? {
        val report = reportRepository.findDetailById(id) ?: return null
        val sites = report.sites.sortedBy { it.timestamp }

        return ReportDetailDto(
            id = report.id,
            inspector = report.inspector,
            route = RouteSummaryDto(report.route.id, report.route.name),
            section = SectionSummaryDto(
                report.section.id,
                report.section.beginKm,
                report.section.endKm,
            ),
            startTime = report.startTime,
            endTime = report.endTime,
            receivedAt = report.receivedAt,
            sites = sites.map { toSiteView(it) },
        )
    }

    private fun toSiteView(site: Site): SiteViewDto =
        SiteViewDto(
            id = site.id,
            timestamp = site.timestamp,
            name = site.name,
            positionKm = site.positionKm,
            location = if (site.lat != null && site.lng != null) {
                CoordinatesViewDto(site.lat!!, site.lng!!)
            } else null,
            validationId = site.validationId,
            fields = site.fields.map { f ->
                FieldViewDto(
                    specId = f.specId,
                    name = f.name,
                    type = f.type,
                    unit = f.unit,
                    value = f.value,
                )
            },
        )

    private fun buildSpec(filter: ReportListFilter): Specification<Report> =
        Specification { root, _, cb ->
            val predicates = mutableListOf<Predicate>()
            filter.from?.let { predicates += cb.greaterThanOrEqualTo(root.get("startTime"), it) }
            filter.to?.let { predicates += cb.lessThan(root.get("startTime"), it) }
            filter.routeId?.let { predicates += cb.equal(root.get<Any>("route").get<UUID>("id"), it) }
            filter.sectionId?.let { predicates += cb.equal(root.get<Any>("section").get<UUID>("id"), it) }
            filter.inspector?.takeIf { s -> s.isNotBlank() }?.let { raw ->
                // User input is a substring match, not a pattern — escape the
                // LIKE metacharacters (`%`, `_`) plus the escape char itself so
                // an inspector name containing `_` doesn't silently match
                // anything, and a `%` doesn't widen the match.
                val escaped = raw.lowercase()
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_")
                predicates += cb.like(cb.lower(root.get("inspector")), "%$escaped%", '\\')
            }
            if (predicates.isEmpty()) null else cb.and(*predicates.toTypedArray())
        }
}

data class ReportListFilter(
    val from: Instant? = null,
    val to: Instant? = null,
    val routeId: UUID? = null,
    val sectionId: UUID? = null,
    val inspector: String? = null,
)
