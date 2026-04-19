package space.diomentia.ehzbackend

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Boots a Postgres container, lets Liquibase apply the schema, and then starts the Spring
 * context with `spring.jpa.hibernate.ddl-auto=validate` (as configured in application.yml).
 *
 * If any JPA entity's column/relationship mapping diverges from the Liquibase-applied DDL,
 * Hibernate will fail context startup — which is the correctness signal we want in CI.
 */
@SpringBootTest
@Testcontainers
abstract class AbstractPostgresIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")
    }
}
