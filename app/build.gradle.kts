plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    // Boot 4 split Liquibase autoconfig out of the core framework jar; `spring-boot-liquibase`
    // pulls in `liquibase-core` plus the autoconfig/metadata that wires up Liquibase at startup.
    implementation("org.springframework.boot:spring-boot-liquibase")
    // Boot 4 ships Jackson 3 (group-id `tools.jackson`, package `tools.jackson.*`) as the default
    // JSON mapper and auto-configures a `tools.jackson.databind.json.JsonMapper` bean. Jackson 2's
    // `com.fasterxml.jackson.databind.ObjectMapper` is NOT auto-configured — use the Jackson 3
    // equivalents. Annotations in `com.fasterxml.jackson.annotation.*` remain unchanged across 2/3.
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Boot 4 moved MockMvc autoconfigure (@AutoConfigureMockMvc, @WebMvcTest) into its own
    // module under the webmvc tree (package `org.springframework.boot.webmvc.test.autoconfigure`).
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    // Testcontainers 2.x renamed the module artifacts: `junit-jupiter` -> `testcontainers-junit-jupiter`,
    // `postgresql` -> `testcontainers-postgresql`.
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

springBoot {
    buildInfo()
    mainClass.set("space.diomentia.ehzbackend.EhzBackendApplicationKt")
}

// Ship only the Spring Boot uber-jar (`bootJar`). Without this, the `jar` task
// also produces a `-plain.jar` that collides with the Dockerfile's COPY glob.
tasks.named<Jar>("jar") { enabled = false }

// We don't use `bootRun` — development is via `docker compose -f docker-compose.dev.yml up --build`.
tasks.named("bootRun") { enabled = false }

tasks.register<Test>("regenOpenApiSpec") {
    description = "Regenerate docs/api-contract-{lan,internal}.yaml from the running app"
    group = "documentation"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*OpenApiContractTest*") }
    systemProperty("ehz.openapi.regen", "true")
    useJUnitPlatform()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        // -Xjsr305=strict: treat JSR-305 @Nonnull/@CheckForNull from Spring as strict Kotlin nullability.
        // -Xannotation-default-target=param-property: opt in to KT-73255's future behavior — annotations
        //   on constructor params (e.g. Jackson's @JsonProperty) apply to both the param and the backing
        //   field, which is what we want for data-class DTOs.
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

// JPA entities must have a no-arg constructor; kotlin("plugin.jpa") handles that.
// Annotations below tell the all-open plugin (pulled in by kotlin-spring) to make JPA classes open.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
