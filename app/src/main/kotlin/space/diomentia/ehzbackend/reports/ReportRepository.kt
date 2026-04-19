package space.diomentia.ehzbackend.reports

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ReportRepository : JpaRepository<Report, UUID>, JpaSpecificationExecutor<Report> {
    /**
     * Detail-fetch for a single report: eagerly joins sites + their fields in
     * one query. Avoids the N+1 (one SELECT per site's fields) the plain
     * `findById` path would trigger when the detail DTO walks the graph, and
     * also removes the LazyInitializationException landmine if the read ever
     * escapes its transaction.
     *
     * `distinct` is needed because the two left-join-fetches multiply rows.
     */
    @Query(
        """
        select distinct r from Report r
        left join fetch r.sites s
        left join fetch s.fields
        where r.id = :id
        """,
    )
    fun findDetailById(@Param("id") id: UUID): Report?
}
