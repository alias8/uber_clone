package org.example.controller

import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.example.dto.AuthResponse
import org.example.dto.LoginRequest
import org.example.dto.MeResponse
import org.example.dto.RegisterRequest
import org.example.model.User
import org.example.repository.UserRepository
import org.example.security.JwtUtil
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/auth")
class AuthController(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtUtil: JwtUtil,
    @param:Value("\${cookie.secure}") private val cookieSecure: Boolean
) {

    @PostMapping("/register")
    fun register(
        @RequestBody request: RegisterRequest,
        response: HttpServletResponse
    ): ResponseEntity<AuthResponse> {
        if (userRepository.existsByUsername(request.username)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build()
        }
        val user = User(username = request.username, passwordHash = passwordEncoder.encode(request.password))
        userRepository.save(user)
        val token = jwtUtil.generate(user.username)
        response.addCookie(authCookie(token))
        return ResponseEntity.status(HttpStatus.CREATED).body(AuthResponse(token))
    }

    @PostMapping("/login")
    fun login(
        @RequestBody request: LoginRequest,
        response: HttpServletResponse
    ): ResponseEntity<AuthResponse> {
        val user = userRepository.findByUsername(request.username)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        if (!passwordEncoder.matches(request.password, user.passwordHash)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }

        val token = jwtUtil.generate(user.username)
        response.addCookie(authCookie(token))
        return ResponseEntity.ok(AuthResponse(token))
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal username: String): ResponseEntity<MeResponse> {
        val user = userRepository.findByUsername(username)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return ResponseEntity.ok(MeResponse(userId = user.id))
    }

    private fun authCookie(token: String) = Cookie("auth_token", token).apply {
        isHttpOnly = true
        secure = cookieSecure
        path = "/"
        maxAge = (jwtUtil.expirationMs / 1000).toInt()
    }
}
