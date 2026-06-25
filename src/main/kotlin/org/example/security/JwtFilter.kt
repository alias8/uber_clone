package org.example.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtFilter(private val jwtUtil: JwtUtil) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain
    ) {
        val header = request.getHeader("Authorization")
        val token = when {
            header != null && header.startsWith("Bearer ") -> header.removePrefix("Bearer ")
            else -> request.cookies?.find { it.name == "auth_token" }?.value
        }

        if (token != null && jwtUtil.isValid(token)) {
            val username = jwtUtil.extractUsername(token)
            val auth = UsernamePasswordAuthenticationToken(
                username, null, listOf(SimpleGrantedAuthority("ROLE_USER"))
            )
            SecurityContextHolder.getContext().authentication = auth
        }

        chain.doFilter(request, response)
    }
}
