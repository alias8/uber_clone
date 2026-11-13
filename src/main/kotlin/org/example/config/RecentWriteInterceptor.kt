package org.example.config

import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

private const val RECENT_WRITE_COOKIE = "recent_write"
private val WRITE_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")

/**
 * Read-your-own-writes, per user: after this user writes, their reads use the primary for a few
 * seconds, then go back to the replica. Everyone else stays on the replica the whole time.
 *
 * The cookie's Max-Age does the timing, so there is no server-side state: a write sets a cookie
 * that the browser drops after N seconds, and a read just checks whether it is still there.
 *
 * Example: rider POSTs /rides, then the app polls GET /rides/{id}. Without this, the first poll
 * can hit the replica before the new ride has arrived and return 404.
 *
 * N must be comfortably above normal replica lag (milliseconds). If lag goes past N, this stops
 * protecting you, which is why lag also needs an alarm.
 */
@Component
class RecentWriteInterceptor(
    @Value("\${app.datasource.read-your-writes-seconds:5}") private val windowSeconds: Int
) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (request.method in WRITE_METHODS) {
            // Set before the handler runs because the response may be committed by the time it
            // returns. Slightly conservative: a write that fails still sets the cookie.
            response.addCookie(Cookie(RECENT_WRITE_COOKIE, "1").apply {
                maxAge = windowSeconds
                path = "/"
                isHttpOnly = true
            })
        } else if (request.cookies?.any { it.name == RECENT_WRITE_COOKIE } == true) {
            DbRouting.forcePrimary()
        }
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest, response: HttpServletResponse, handler: Any, ex: Exception?
    ) {
        DbRouting.clear() // don't leak the flag to the next request on a reused thread
    }
}

@Configuration
class WebConfig(private val recentWriteInterceptor: RecentWriteInterceptor) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(recentWriteInterceptor)
    }
}
