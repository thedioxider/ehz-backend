package space.diomentia.ehzbackend.bootstrap

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import space.diomentia.ehzbackend.catalog.Route
import space.diomentia.ehzbackend.catalog.RouteRepository
import space.diomentia.ehzbackend.catalog.Section
import space.diomentia.ehzbackend.catalog.SectionRepository
import space.diomentia.ehzbackend.catalog.dto.AreasYaml
import tools.jackson.dataformat.yaml.YAMLMapper

/**
 * Populates `routes` + `sections` from the bundled `seed/initial_areas.yaml`
 * on first boot (i.e. when `routes` is empty). Triggered on
 * [ApplicationReadyEvent] so the whole context — Liquibase, JPA, repositories
 * — is fully up before we touch the DB.
 *
 * Idempotent by design: if `routeRepository.count() > 0`, we log and return.
 * This lets the loader run on every boot without re-seeding a populated DB
 * and without breaking edits the operator has already made through
 * (eventual) write endpoints or direct SQL.
 *
 * Foreign-key order is enforced: routes first, then sections (each section
 * references a route by id).
 */
@Component
class SeedLoader(
    private val routeRepository: RouteRepository,
    private val sectionRepository: SectionRepository,
    private val yamlMapper: YAMLMapper,
) {

    private val log = LoggerFactory.getLogger(SeedLoader::class.java)

    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun seed() {
        if (routeRepository.count() > 0L) {
            log.info("catalog already seeded ({} routes); skipping", routeRepository.count())
            return
        }

        val resource = ClassPathResource(SEED_PATH)
        if (!resource.exists()) {
            log.warn("seed file {} not present on classpath; skipping", SEED_PATH)
            return
        }

        val parsed: AreasYaml = resource.inputStream.use {
            yamlMapper.readValue(it, AreasYaml::class.java)
        }

        // Save routes first so the FK from sections resolves.
        val routesById = parsed.diagnosticObjects.associate { r ->
            r.id to routeRepository.save(
                Route(id = r.id, name = r.name, lengthKm = r.lengthKm),
            )
        }

        parsed.diagnosticAreas.forEach { s ->
            val route = routesById[s.routeId]
                ?: routeRepository.findById(s.routeId).orElseThrow {
                    IllegalStateException(
                        "seed section ${s.id} references unknown route ${s.routeId}",
                    )
                }
            sectionRepository.save(
                Section(
                    id = s.id,
                    route = route,
                    beginKm = s.beginKm,
                    endKm = s.endKm,
                ),
            )
        }

        log.info(
            "seeded catalog: {} routes, {} sections",
            parsed.diagnosticObjects.size,
            parsed.diagnosticAreas.size,
        )
    }

    companion object {
        const val SEED_PATH: String = "seed/initial_areas.yaml"
    }
}
