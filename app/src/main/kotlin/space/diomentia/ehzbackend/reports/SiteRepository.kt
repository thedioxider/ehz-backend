package space.diomentia.ehzbackend.reports

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface SiteRepository : JpaRepository<Site, UUID>, JpaSpecificationExecutor<Site> {
    /**
     * Returns `(reportId, count)` rows for the given report ids. Used by the
     * list endpoint to populate `site_count` in one round trip instead of N.
     *
     * Spring Data interface projection — named aliases in the SELECT drive the
     * getter names on [SiteCountRow], which keeps the call site cast-free.
     */
    @Query(
        """
        select s.report.id as reportId, count(s) as count
        from Site s
        where s.report.id in :reportIds
        group by s.report.id
        """,
    )
    fun countByReportIdIn(@Param("reportIds") reportIds: List<UUID>): List<SiteCountRow>
}

/** Shape of a single row from [SiteRepository.countByReportIdIn]. */
interface SiteCountRow {
    val reportId: UUID
    val count: Long
}
