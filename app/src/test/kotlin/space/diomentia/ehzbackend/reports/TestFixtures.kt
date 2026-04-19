package space.diomentia.ehzbackend.reports

import java.util.Base64
import java.util.UUID

/**
 * Builders for ingest-shaped JSON payloads, shared across the ingest + read
 * integration tests. Returning `Map<String, Any?>` (rather than typed DTOs)
 * so we also cover the JSON unmarshalling path the real app exercises.
 */
object TestFixtures {

    /** Deterministic "WebP" bytes — format-agnostic from the backend's POV. */
    fun photoBytes(size: Int = 32): ByteArray = ByteArray(size) { it.toByte() }

    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /**
     * Minimal report payload with a single site and configurable field set.
     * Matches the on-the-wire shape emitted by `ReportExporter` in the Android
     * companion app (snake_case field names, epoch-seconds timestamps).
     */
    fun reportPayload(
        reportId: UUID,
        routeId: UUID,
        sectionId: UUID,
        siteId: UUID,
        inspector: String = "Иванов Иван",
        routeName: String = "САЦ-III",
        routeLengthKm: Int? = 3600,
        sectionBeginKm: Int = 0,
        sectionEndKm: Int = 41,
        startTime: Long = 1704321600L,
        endTime: Long? = 1704329400L,
        siteTimestamp: Long = 1704322000L,
        positionKm: Double? = 12.5,
        lat: Double? = 55.75,
        lng: Double? = 37.62,
        fields: List<Map<String, Any?>> = emptyList(),
    ): Map<String, Any?> {
        val location: Map<String, Any?>? = if (lat != null && lng != null) {
            mapOf("lat" to lat, "lng" to lng)
        } else null
        return mapOf(
            "id" to reportId.toString(),
            "inspector" to inspector,
            "route" to mapOf(
                "id" to routeId.toString(),
                "name" to routeName,
                "length_km" to routeLengthKm,
            ),
            "section" to mapOf(
                "id" to sectionId.toString(),
                "begin_km" to sectionBeginKm,
                "end_km" to sectionEndKm,
            ),
            "start_time" to startTime,
            "end_time" to endTime,
            "sites" to listOf(
                mapOf(
                    "id" to siteId.toString(),
                    "timestamp" to siteTimestamp,
                    "position_km" to positionKm,
                    "location" to location,
                    "validation_id" to null,
                    "fields" to fields,
                ),
            ),
        )
    }

    fun photoField(
        specId: String,
        name: String = "Фотография точки",
        base64Value: String,
    ): Map<String, Any?> = mapOf(
        "spec_id" to specId,
        "name" to name,
        "type" to "photo",
        "value" to base64Value,
        "unit" to null,
    )

    fun textField(
        specId: String,
        name: String,
        value: String,
    ): Map<String, Any?> = mapOf(
        "spec_id" to specId,
        "name" to name,
        "type" to "text",
        "value" to value,
        "unit" to null,
    )
}
