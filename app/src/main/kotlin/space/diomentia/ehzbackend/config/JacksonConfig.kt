package space.diomentia.ehzbackend.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.KotlinModule

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
}
