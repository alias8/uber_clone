package org.example.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.*

@Component
class JwtUtil(
    @Value("\${jwt.secret}") secret: String,
    @Value("\${jwt.expiration-ms}") val expirationMs: Long
) {
    private val key = Keys.hmacShaKeyFor(secret.toByteArray())

    fun generate(username: String): String =
        Jwts.builder()
            .subject(username)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + expirationMs))
            .signWith(key)
            .compact()

    fun extractUsername(token: String): String =
        Jwts.parser().verifyWith(key).build()
            .parseSignedClaims(token)
            .payload
            .subject

    fun isValid(token: String): Boolean = runCatching { extractUsername(token); true }.getOrDefault(false)
}
