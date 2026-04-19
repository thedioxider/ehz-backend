package space.diomentia.ehzbackend.reports.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * Wire-format DTOs mirroring the Android app's `ReportExporter` output
 * (see ehz-diagnostics-tracker/docs/export-schema.yaml).
 *
 * IDs are `java.util.UUID` — non-UUID strings are rejected with 400 by Jackson.
 * `Instant` fields accept either epoch-seconds numbers (current Android shape)
 * or ISO-8601 strings (future), enforced via `spring.jackson.deserialization.
 * read-date-timestamps-as-nanoseconds=false` in application.yml.
 *
 * `@JsonIgnoreProperties(ignoreUnknown = true)` is set on each DTO so unknown
 * fields (notably `version`) do not cause deserialization failures — the
 * schema-version integer is accepted and silently ignored for now.
 */

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReportDto(
    val id: UUID,
    val inspector: String,
    val route: RouteDto,
    val section: SectionDto,
    @JsonProperty("start_time")
    val startTime: Instant,
    @JsonProperty("end_time")
    val endTime: Instant? = null,
    val sites: List<SiteDto> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RouteDto(
    val id: UUID,
    val name: String,
    @JsonProperty("length_km")
    val lengthKm: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SectionDto(
    val id: UUID,
    @JsonProperty("begin_km")
    val beginKm: Int,
    @JsonProperty("end_km")
    val endKm: Int,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SiteDto(
    val id: UUID,
    val timestamp: Instant,
    @JsonProperty("position_km")
    val positionKm: Double? = null,
    val location: CoordinatesDto? = null,
    @JsonProperty("validation_id")
    val validationId: String? = null,
    /** null ⇒ location-only site (no measurements). Treated same as "no fields". */
    val fields: List<FieldDto>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CoordinatesDto(
    val lat: Double,
    val lng: Double,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FieldDto(
    @JsonProperty("spec_id")
    val specId: String,
    val name: String,
    /** One of: text, bool, integer, decimal, photo, raw. */
    val type: String,
    /** For type=photo, this is base64-encoded WebP bytes (standard, not URL-safe). */
    val value: String,
    val unit: String? = null,
)
