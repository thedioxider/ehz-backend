package space.diomentia.ehzbackend.catalog

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SectionRepository : JpaRepository<Section, UUID>
