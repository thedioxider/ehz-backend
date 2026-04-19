package space.diomentia.ehzbackend.catalog

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import java.nio.charset.StandardCharsets
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import space.diomentia.ehzbackend.catalog.dto.AreasYaml
import space.diomentia.ehzbackend.catalog.dto.RouteYaml
import space.diomentia.ehzbackend.catalog.dto.SectionYaml
import tools.jackson.dataformat.yaml.YAMLMapper

/**
 * Serves the current catalog (routes + sections) as YAML for the Android app
 * to sync against. Read-only in v1; write endpoints are deferred.
 *
 * The response is serialized as a `String` (not bound into Spring's content
 * negotiation) because we use a purpose-built [YAMLMapper] bean with the
 * Kotlin module registered — routing through Spring's message converters
 * would pick up the auto-configured JSON mapper instead.
 */
@RestController
@RequestMapping("/api/config")
class AreasController(
    private val routeRepository: RouteRepository,
    private val sectionRepository: SectionRepository,
    private val yamlMapper: YAMLMapper,
) {

    @GetMapping("/areas", produces = [MEDIA_TYPE_YAML_VALUE])
    fun areas(): ResponseEntity<String> {
        val routes = routeRepository.findAll().map {
            RouteYaml(id = it.id, name = it.name, lengthKm = it.lengthKm)
        }
        val sections = sectionRepository.findAll().map {
            SectionYaml(
                id = it.id,
                beginKm = it.beginKm,
                endKm = it.endKm,
                routeId = it.route.id,
            )
        }
        val body = yamlMapper.writeValueAsString(
            AreasYaml(diagnosticObjects = routes, diagnosticAreas = sections),
        )
        // Explicit UTF-8 charset: route names contain Cyrillic, and without a
        // charset in the Content-Type Tomcat falls back to ISO-8859-1 and
        // mangles the body bytes on the way out.
        val parsed = MediaType.parseMediaType(MEDIA_TYPE_YAML_VALUE)
        val mediaType = MediaType(parsed.type, parsed.subtype, StandardCharsets.UTF_8)
        return ResponseEntity.ok()
            .contentType(mediaType)
            .body(body)
    }

    companion object {
        const val MEDIA_TYPE_YAML_VALUE = "application/yaml"
    }
}
