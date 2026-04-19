package space.diomentia.ehzbackend.dashboard

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextType
import org.springframework.boot.actuate.autoconfigure.web.server.ConditionalOnManagementPort
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.view.ThymeleafViewResolver
import space.diomentia.ehzbackend.bootstrap.CredentialsStore
import space.diomentia.ehzbackend.reports.ReportRepository
import javax.sql.DataSource

/**
 * Wires [DashboardController] onto the actuator's **child management context**
 * (`management.server.port`, bound to 127.0.0.1 by `application.yml`) so the
 * dashboard is reachable **only** from the host loopback — never from the LAN
 * connector (8081) and never from the internal Docker-bridge connector (8082).
 *
 * This class is discovered via
 * `META-INF/spring/org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration.imports`.
 * The main application context's `@ComponentScan` does NOT pick it up — that's
 * deliberate; if it did, the controller would be registered on the main
 * dispatcher servlet (port 8082) as well.
 *
 * `@ConditionalOnManagementPort(DIFFERENT)` means "only when management runs on
 * its own port". With a same-port config the child context isn't created and
 * this whole class is a no-op (which is the correct fallback — the dashboard
 * simply isn't served).
 *
 * ## Thymeleaf wiring note
 *
 * `WebMvcEndpointChildContextConfiguration` (Boot's own management-child
 * config) instantiates its own DispatcherServlet with
 * `setDetectAllViewResolvers(false)` + a `CompositeHandler*` pipeline that
 * only sees **child-context** beans. So the main context's auto-configured
 * `ThymeleafViewResolver` is invisible to the child dispatcher — we have to
 * register our own [ThymeleafViewResolver] bean here.
 *
 * The underlying [SpringTemplateEngine] and template resolver, on the other
 * hand, are plain bean dependencies (not special-cased by Boot's child
 * DispatcherServlet), so they inherit cleanly from the parent context and we
 * just reuse them.
 */
@ManagementContextConfiguration(ManagementContextType.CHILD)
@ConditionalOnManagementPort(ManagementPortType.DIFFERENT)
class DashboardManagementContextConfiguration {

    @Bean
    fun dashboardController(
        dataSource: DataSource,
        reportRepository: ReportRepository,
        credentialsStore: CredentialsStore,
        @Value("\${ehz.public-api-port:19081}") apiPort: Int,
        @Value("\${ehz.data-dir}") dataDir: String,
        buildProperties: BuildProperties?,
    ): DashboardController = DashboardController(
        dataSource,
        reportRepository,
        credentialsStore,
        apiPort,
        dataDir,
        buildProperties,
    )

    @Bean
    fun dashboardViewResolver(templateEngine: SpringTemplateEngine): ThymeleafViewResolver {
        val resolver = ThymeleafViewResolver()
        resolver.templateEngine = templateEngine
        resolver.characterEncoding = "UTF-8"
        resolver.order = 1
        return resolver
    }
}
