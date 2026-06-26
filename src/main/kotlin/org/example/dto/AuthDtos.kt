package org.example.dto

data class RegisterRequest(val username: String, val password: String)
data class LoginRequest(val username: String, val password: String)
data class SwitchModeRequest(val mode: String)
data class AuthResponse(val token: String)
data class MeResponse(val userId: String)
