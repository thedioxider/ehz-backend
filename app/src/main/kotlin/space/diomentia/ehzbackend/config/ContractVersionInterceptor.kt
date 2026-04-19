package space.diomentia.ehzbackend.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import space.diomentia.ehzbackend.ApiContract

/**
 * Stamps every API response with [ApiContract.HEADER_NAME] so a client can
 * detect an incompatible backend without parsing a body.
 *
 * Set in `preHandle` (not `postHandle`) so the header goes out even when the
 * handler throws and the response is produced by an `@ExceptionHandler`.
 * Registered under the API path pattern by [WebConfig].
 */
@Component
class ContractVersionInterceptor : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        response.setHeader(ApiContract.HEADER_NAME, ApiContract.VERSION.toString())
        return true
    }
}
