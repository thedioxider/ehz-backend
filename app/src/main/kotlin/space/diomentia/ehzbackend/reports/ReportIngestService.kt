package space.diomentia.ehzbackend.reports

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import space.diomentia.ehzbackend.catalog.Route
import space.diomentia.ehzbackend.catalog.RouteRepository
import space.diomentia.ehzbackend.catalog.Section
import space.diomentia.ehzbackend.catalog.SectionRepository
import space.diomentia.ehzbackend.devices.Device
import space.diomentia.ehzbackend.reports.dto.FieldDto
import space.diomentia.ehzbackend.reports.dto.ReportDto
import space.diomentia.ehzbackend.reports.dto.SiteDto
import space.diomentia.ehzbackend.reports.photos.PhotoStorage
import java.util.UUID

/**
 * Transactional ingestion for a single report payload:
 *
 * - Route/Section are insert-if-missing (catalog rows are operator-owned;
 *   app payloads ship a snapshot but must NOT overwrite server-side edits).
 * - An existing report with the same id is deleted first (FK cascade handles
 *   sites + report_fields) and replaced — this makes the endpoint idempotent
 *   under app retries.
 * - `device_id` is set if the caller resolves one from the `X-Device-Id`
 *   header (see `DeviceTrackingFilter`), otherwise stays null.
 * - `received_at` is DB-defaulted; entity field is insertable=false.
 *
 * Photo fields are decoded + written to disk before the report row is
 * persisted; a photo-write failure rolls back the whole report transaction.
 *
 * Concern (v1): photo-write rollback leaves orphaned files on disk; a
 * housekeeping pass (or subsequent re-ingestion that overwrites the same
 * paths) is expected to clean them up.
 */
@Service
class ReportIngestService(
    private val reportRepository: ReportRepository,
    private val siteRepository: SiteRepository,
    private val routeRepository: RouteRepository,
    private val sectionRepository: SectionRepository,
    private val photoStorage: PhotoStorage,
) {
    @Transactional
    fun ingest(dto: ReportDto, device: Device? = null): Boolean {
        val route = upsertRoute(dto.route.id, dto.route.name, dto.route.lengthKm)
        val section = upsertSection(dto.section.id, route, dto.section.beginKm, dto.section.endKm)

        val wasNew = !reportRepository.existsById(dto.id)
        if (!wasNew) {
            reportRepository.deleteById(dto.id)
            reportRepository.flush()
        }

        val report = reportRepository.save(
            Report(
                id = dto.id,
                device = device,
                route = route,
                section = section,
                inspector = dto.inspector,
                startTime = dto.startTime,
                endTime = dto.endTime,
            )
        )

        for (siteDto in dto.sites) {
            val site = Site(
                id = siteDto.id,
                report = report,
                positionKm = siteDto.positionKm,
                lat = siteDto.location?.lat,
                lng = siteDto.location?.lng,
                timestamp = siteDto.timestamp,
                validationId = siteDto.validationId,
            )
            // fields == null means "location-only site" — persist the site, no fields.
            siteDto.fields?.forEach { fieldDto ->
                site.fields += buildField(dto.id, siteDto.id, site, fieldDto)
            }
            // Cascade from Site.fields (CascadeType.ALL) persists fields alongside the site.
            siteRepository.save(site)
        }

        return wasNew
    }

    private fun upsertRoute(id: UUID, name: String, lengthKm: Int?): Route {
        return routeRepository.findById(id).orElseGet {
            routeRepository.save(Route(id = id, name = name, lengthKm = lengthKm))
        }
    }

    private fun upsertSection(
        id: UUID,
        route: Route,
        beginKm: Int,
        endKm: Int,
    ): Section {
        return sectionRepository.findById(id).orElseGet {
            sectionRepository.save(Section(id = id, route = route, beginKm = beginKm, endKm = endKm))
        }
    }

    private fun buildField(
        reportId: UUID,
        siteId: UUID,
        site: Site,
        fieldDto: FieldDto,
    ): ReportField {
        val storedValue = if (fieldDto.type == "photo") {
            photoStorage.save(reportId, siteId, fieldDto.specId, fieldDto.value)
        } else {
            fieldDto.value
        }
        return ReportField(
            site = site,
            specId = fieldDto.specId,
            name = fieldDto.name,
            type = fieldDto.type,
            unit = fieldDto.unit,
            value = storedValue,
        )
    }
}
