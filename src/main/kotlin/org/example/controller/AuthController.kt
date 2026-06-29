package org.example.controller

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.example.dto.AuthResponse
import org.example.dto.LoginRequest
import org.example.dto.MeResponse
import org.example.dto.RegisterRequest
import org.example.dto.SwitchModeRequest
import org.example.model.Role
import org.example.model.User
import org.example.repository.UserRepository
import org.example.security.JwtCookieService
import org.example.service.RateLimiterService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/auth")
class AuthController(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtCookieService: JwtCookieService,
    private val rateLimiterService: RateLimiterService
) {
    private fun clientIp(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-For")?.split(",")?.first()?.trim() ?: request.remoteAddr


    @PostMapping("/register")
    fun register(
        @RequestBody request: RegisterRequest,
        httpRequest: HttpServletRequest,
        response: HttpServletResponse
    ): ResponseEntity<AuthResponse> {
        if (!rateLimiterService.allowAuthAttempt(clientIp(httpRequest))) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts — try again later")
        }
        if (userRepository.existsByUsername(request.username)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build()
        }
        val user = userRepository.save(
            User(username = request.username, passwordHash = passwordEncoder.encode(request.password))
        )
        val cookie = jwtCookieService.issueTokenCookie(user.username, Role.RIDER)
        response.addCookie(cookie)
        return ResponseEntity.status(HttpStatus.CREATED).body(AuthResponse(cookie.value))
    }

    @PostMapping("/login")
    fun login(
        @RequestBody request: LoginRequest,
        httpRequest: HttpServletRequest,
        response: HttpServletResponse
    ): ResponseEntity<AuthResponse> {
        if (!rateLimiterService.allowAuthAttempt(clientIp(httpRequest))) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts — try again later")
        }
        val user = userRepository.findByUsername(request.username)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        if (!passwordEncoder.matches(request.password, user.passwordHash)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }

        val cookie = jwtCookieService.issueTokenCookie(user.username, user.role)
        response.addCookie(cookie)
        return ResponseEntity.ok(AuthResponse(cookie.value))
    }

    @PostMapping("/switch-mode")
    fun switchMode(
        @RequestBody request: SwitchModeRequest,
        @AuthenticationPrincipal username: String,
        response: HttpServletResponse
    ): ResponseEntity<AuthResponse> {
        val requestedMode = runCatching { Role.valueOf(request.mode.uppercase()) }.getOrElse {
            return ResponseEntity.badRequest().build()
        }
        if (requestedMode == Role.DRIVER) {
            val user = userRepository.findByUsername(username)
                ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
            if (user.role != Role.DRIVER) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
            }
        }
        val cookie = jwtCookieService.issueTokenCookie(username, requestedMode)
        response.addCookie(cookie)
        return ResponseEntity.ok(AuthResponse(cookie.value))
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal username: String): ResponseEntity<MeResponse> {
        val user = userRepository.findByUsername(username)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return ResponseEntity.ok(MeResponse(userId = user.id))
    }
}
