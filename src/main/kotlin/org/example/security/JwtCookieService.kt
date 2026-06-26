package org.example.security

import jakarta.servlet.http.Cookie
import org.example.model.Role
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class JwtCookieService(
    private val jwtUtil: JwtUtil,
    @Value("\${cookie.secure}") private val cookieSecure: Boolean
) {
    fun issueTokenCookie(username: String, role: Role): Cookie =
        Cookie("auth_token", jwtUtil.generate(username, role)).apply {
            isHttpOnly = true
            secure = cookieSecure
            path = "/"
            maxAge = (jwtUtil.expirationMs / 1000).toInt()
        }
}
