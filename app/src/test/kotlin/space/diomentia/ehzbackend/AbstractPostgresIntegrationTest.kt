package space.diomentia.ehzbackend

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Boots a Postgres container, lets Liquibase apply the schema, and then starts the Spring
 * context with `spring.jpa.hibernate.ddl-auto=validate` (as configured in application.yml).
 *
 * If any JPA entity's column/relationship mapping diverges from the Liquibase-applied DDL,
 * Hibernate will fail context startup — which is the correctness signal we want in CI.
 *
 * The container is started **once per JVM** via a static init block — not via JUnit's
 * `@Container` extension, which would tie lifecycle to a single test class and stop the
 * container between classes. Spring's test-context cache can then reuse the same URL
 * across every subclass's cached context without pointing at a dead container. JVM
 * shutdown reaps the container (Testcontainers' Ryuk takes care of it).
 */
@SpringBootTest
abstract class AbstractPostgresIntegrationTest {

    companion object {
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")
            .also { it.start() }
    }
}
