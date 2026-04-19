package space.diomentia.ehzbackend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType

/**
 * The main `@SpringBootApplication` scans the whole `space.diomentia.ehzbackend`
 * package tree — with the single exception of [dashboard][space.diomentia.ehzbackend.dashboard].
 * The dashboard controller is `@Controller`-annotated so the actuator child
 * context's `RequestMappingHandlerMapping` will detect it, but if the main
 * context also scanned it, `GET /` would end up on the internal API port (8082)
 * as well, defeating the whole "dashboard lives on management-context-only"
 * model. Registration into the child context happens in
 * `DashboardManagementContextConfiguration`, reached via
 * `META-INF/spring/...ManagementContextConfiguration.imports`.
 */
@SpringBootApplication
@ComponentScan(
    excludeFilters = [ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = ["space\\.diomentia\\.ehzbackend\\.dashboard\\..*"],
    )],
)
class EhzBackendApplication

fun main(args: Array<String>) {
    runApplication<EhzBackendApplication>(*args)
}
