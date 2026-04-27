package space.diomentia.ehzbackend.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.module.SimpleModule
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant

/**
 * YAML read/write surface for seed loading and `/api/config` serialization.
 *
 * Spring Boot 4 auto-configures a `JsonMapper` (the JSON variant of Jackson 3's
 * `ObjectMapper`) but does NOT spin up a YAML mapper — we own it ourselves.
 * The Kotlin module is registered so data-class DTOs (with non-null types and
 * default values) bind cleanly without no-arg ceremony.
 */
@Configuration
class JacksonConfig {

    @Bean
    fun yamlMapper(): YAMLMapper =
        YAMLMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .build()

    /**
     * Overrides Jackson's default Instant deserializer so that numeric
     * epoch-second or epoch-millisecond values are rejected with a clear 400.
     * Only ISO-8601 strings are accepted (e.g. "2024-01-03T22:40:00Z").
     */
    @Bean
    fun strictInstantModule(): SimpleModule =
        SimpleModule("strict-instant")
            .addDeserializer(Instant::class.java, StrictInstantDeserializer.INSTANCE)
}
