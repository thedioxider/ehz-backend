package space.diomentia.ehzbackend

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
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
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
