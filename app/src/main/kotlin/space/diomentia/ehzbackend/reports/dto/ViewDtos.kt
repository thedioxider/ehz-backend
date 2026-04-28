package space.diomentia.ehzbackend.reports.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * Read-surface DTOs for `GET /api/reports` (list + detail).
 *
 * Deliberately distinct from [ReportDto] & friends (the ingest/write surface):
 *   - Timestamps are `Instant` — serialized as ISO-8601 strings by default
 *     Jackson. The `read-date-timestamps-as-nanoseconds=false` override in
 *     application.yml is deserialization-only and doesn't affect output.
 *   - Photo fields carry the relative path (e.g. `photos/{reportId}/{siteId}/
 *     {specId}.webp`), not base64 bytes. The client fetches the file via
 *     `GET /api/photos/{reportId}/{siteId}/{specId}.webp`.
 *
 * JSON field naming is snake_case via explicit `@JsonProperty` — matches the
 * ingest DTOs and keeps each DTO self-documenting without relying on a global
 * `ObjectMapper` naming strategy (which would also affect the ingest surface).
 */

data class ReportSummaryDto(
    val id: UUID,
    val route: RouteSummaryDto,
    val section: SectionSummaryDto,
    val inspector: String,
    @JsonProperty("start_time")
    val startTime: Instant,
    @JsonProperty("end_time")
    val endTime: Instant?,
    @JsonProperty("received_at")
    val receivedAt: Instant?,
    @JsonProperty("site_count")
    val siteCount: Long,
)

data class ReportDetailDto(
    val id: UUID,
    val inspector: String,
    val route: RouteSummaryDto,
    val section: SectionSummaryDto,
    @JsonProperty("start_time")
    val startTime: Instant,
    @JsonProperty("end_time")
    val endTime: Instant?,
    @JsonProperty("received_at")
    val receivedAt: Instant?,
    val sites: List<SiteViewDto>,
)

data class RouteSummaryDto(
    val id: UUID,
    val name: String,
)

data class SectionSummaryDto(
    val id: UUID,
    @JsonProperty("begin_km")
    val beginKm: Int,
    @JsonProperty("end_km")
    val endKm: Int,
)

data class SiteViewDto(
    val id: UUID,
    val timestamp: Instant,
    @JsonProperty("position_km")
    val positionKm: Double?,
    val location: CoordinatesViewDto?,
    @JsonProperty("validation_id")
    val validationId: String?,
    val fields: List<FieldViewDto>,
)

data class CoordinatesViewDto(
    val lat: Double,
    val lng: Double,
)

data class FieldViewDto(
    @JsonProperty("spec_id")
    val specId: String,
    val name: String,
    val type: String,
    val unit: String?,
    /**
     * For `type=photo`, this is the relative path under the data-dir
     * (e.g. `photos/{reportId}/{siteId}/{specId}.webp`). All other types
     * carry the raw value as a string.
     */
    val value: String?,
)
