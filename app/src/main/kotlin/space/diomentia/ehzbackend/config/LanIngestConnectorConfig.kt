package space.diomentia.ehzbackend.config

import org.apache.catalina.connector.Connector
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Adds a second Tomcat connector for the LAN-facing ingest listener.
 *
 * The primary connector is the internal API on `server.port` (8082 by default),
 * reachable only from the Docker bridge network. This extra connector binds on
 * `0.0.0.0:{ehz.ingest.port}` and is where the Android companion app
 * delivers reports from the LAN. Both connectors feed the same servlet
 * container, so the same controllers handle the requests — port-scoped
 * filtering in [LanIngestFilter] decides what's actually reachable from where.
 *
 * Boot 4 moved `TomcatServletWebServerFactory` under `org.springframework.boot.tomcat.servlet`
 * and renamed the prior `addAdditionalTomcatConnectors(Connector...)` to
 * `addAdditionalConnectors(Connector...)` (inherited from the new
 * `TomcatWebServerFactory` superclass) — that's the sanctioned hook for
 * multi-port setups.
 */
@Configuration
class LanIngestConnectorConfig {

    @Bean
    fun lanIngestConnectorCustomizer(
        @Value("\${ehz.ingest.port}") port: Int,
    ): WebServerFactoryCustomizer<TomcatServletWebServerFactory> =
        WebServerFactoryCustomizer { factory ->
            factory.addAdditionalConnectors(buildConnector(port))
        }

    private fun buildConnector(port: Int): Connector {
        val connector = Connector("org.apache.coyote.http11.Http11NioProtocol")
        connector.port = port
        // Bind to all interfaces — the Docker compose file is what decides
        // whether this port is published to the host LAN or not.
        connector.setProperty("address", "0.0.0.0")
        return connector
    }
}
