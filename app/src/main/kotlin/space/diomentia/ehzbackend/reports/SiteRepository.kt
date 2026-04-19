package space.diomentia.ehzbackend.reports

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface SiteRepository : JpaRepository<Site, UUID>, JpaSpecificationExecutor<Site> {
    /**
     * Returns `[reportId, count]` rows for the given report ids. Used by the
     * list endpoint to populate `site_count` in one round trip instead of N.
     */
    @Query("select s.report.id, count(s) from Site s where s.report.id in :reportIds group by s.report.id")
    fun countByReportIdIn(reportIds: Collection<UUID>): List<Array<Any>>
}
