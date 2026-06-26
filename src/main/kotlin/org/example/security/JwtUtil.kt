package org.example.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.example.model.Role
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.*

@Component
class JwtUtil(
    @Value("\${jwt.secret}") secret: String,
    @Value("\${jwt.expiration-ms}") val expirationMs: Long
) {
    private val key = Keys.hmacShaKeyFor(secret.toByteArray())

    fun generate(username: String, role: Role): String =
        Jwts.builder()
            .subject(username)
            .claim("role", role.name)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + expirationMs))
            .signWith(key)
            .compact()

    fun extractUsername(token: String): String =
        Jwts.parser().verifyWith(key).build()
            .parseSignedClaims(token).payload.subject

    fun extractRole(token: String): Role? = runCatching {
        val claim = Jwts.parser().verifyWith(key).build()
            .parseSignedClaims(token).payload
            .get("role", String::class.java)
        Role.valueOf(claim)
    }.getOrNull()

    fun isValid(token: String): Boolean = runCatching { extractUsername(token); true }.getOrDefault(false)
}
