package space.diomentia.ehzbackend.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import space.diomentia.ehzbackend.ApiContract

// Splits the API into two GroupedOpenApi docs by listener (lan / internal),
// both served on the management port (8080, localhost-only).
@Configuration
class OpenApiConfig {

    @Bean
    fun globalOpenApi(): OpenAPI = OpenAPI()
        .servers(listOf(Server().url("/").description("ehz-backend")))
        .info(
            Info()
                .title("ehz-backend API")
                .version(ApiContract.VERSION.toString())
                .description(
                    "Wire contract for the ehz-backend service. " +
                        "Contract version ${ApiContract.VERSION} — bump on any backwards-incompatible change.",
                ),
        )
        .addSecurityItem(SecurityRequirement().addList("bearerAuth"))
        .components(
            Components()
                .addSecuritySchemes(
                    "bearerAuth",
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .description("Static API key — obtain from the operator dashboard."),
                ),
        )

    @Bean
    fun lanGroup(): GroupedOpenApi = GroupedOpenApi.builder()
        .group("lan")
        .displayName("LAN ingest (port 8081, bearer-token required)")
        .pathsToMatch("/api/ping", "/api/info", "/api/reports/{id}")
        .addOpenApiCustomizer { openApi ->
            // PUT-only on LAN; remove GET (belongs to internal)
            openApi.paths["/api/reports/{id}"]?.get = null
        }
        .build()

    @Bean
    fun internalGroup(): GroupedOpenApi = GroupedOpenApi.builder()
        .group("internal")
        .displayName("Internal API (port 8082, Docker-network only)")
        .pathsToMatch("/api/**")
        .pathsToExclude("/api/ping", "/api/info")
        .addOpenApiCustomizer { openApi ->
            // PUT is LAN-only; remove it from the internal spec
            openApi.paths["/api/reports/{id}"]?.put = null
        }
        .build()
}
