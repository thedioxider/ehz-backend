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
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
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

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

// JPA entities must have a no-arg constructor; kotlin("plugin.jpa") handles that.
// Annotations below tell the all-open plugin (pulled in by kotlin-spring) to make JPA classes open.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
