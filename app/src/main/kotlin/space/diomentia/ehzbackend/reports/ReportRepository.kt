package space.diomentia.ehzbackend.reports

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ReportRepository : JpaRepository<Report, UUID>
