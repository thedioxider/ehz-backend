package space.diomentia.ehzbackend.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import space.diomentia.ehzbackend.ApiContract

/**
 * Stamps every response under `/api/` with [ApiContract.HEADER_NAME] so a
 * client can detect an incompatible backend without parsing a body.
 *
 * Implemented as a servlet filter rather than a [org.springframework.web.servlet.HandlerInterceptor]
 * because a `HandlerInterceptor.preHandle` only runs once Spring has resolved a
 * handler — a 404 for an unknown `/api/...` path, or any request that errors
 * before reaching the handler, would bypass the interceptor and the response
 * would ship without the header. Clients need the header precisely on those
 * 4xx cases to spot a version mismatch, so the filter sets the header upfront
 * and then delegates down the chain.
 */
@Component
class ContractVersionFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        if (request.requestURI.startsWith("/api/")) {
            response.setHeader(ApiContract.HEADER_NAME, ApiContract.VERSION.toString())
        }
        chain.doFilter(request, response)
    }
}
