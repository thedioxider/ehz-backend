package space.diomentia.ehzbackend.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * MVC-level wiring. Currently just registers [ContractVersionInterceptor] for
 * the API path pattern so every API response advertises the contract version.
 */
@Configuration
class WebConfig(
    private val contractVersionInterceptor: ContractVersionInterceptor,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(contractVersionInterceptor)
            .addPathPatterns("/api/**")
    }
}
