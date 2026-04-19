package space.diomentia.ehzbackend.reports

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.util.UUID

interface ReportRepository : JpaRepository<Report, UUID>, JpaSpecificationExecutor<Report>
