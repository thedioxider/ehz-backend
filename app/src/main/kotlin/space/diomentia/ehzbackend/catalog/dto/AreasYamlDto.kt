package space.diomentia.ehzbackend.catalog.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

/**
 * YAML shape for the `/api/config/areas` surface and the bundled
 * `seed/initial_areas.yaml`. Intentionally snake_case on the wire (matches the
 * Android app's existing config format) and mapped to Kotlin camelCase via
 * `@JsonProperty`.
 *
 * Kept as a thin wire DTO — the canonical persistence model lives in the JPA
 * entities [space.diomentia.ehzbackend.catalog.Route] and
 * [space.diomentia.ehzbackend.catalog.Section].
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AreasYaml(
    @JsonProperty("diagnostic_objects")
    val diagnosticObjects: List<RouteYaml> = emptyList(),
    @JsonProperty("diagnostic_areas")
    val diagnosticAreas: List<SectionYaml> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RouteYaml(
    val id: UUID,
    val name: String,
    @JsonProperty("length_km")
    val lengthKm: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SectionYaml(
    val id: UUID,
    @JsonProperty("begin_km")
    val beginKm: Int,
    @JsonProperty("end_km")
    val endKm: Int,
    @JsonProperty("route_id")
    val routeId: UUID,
)
