package org.example.dto

data class RatingRequest(
    val score: Int,        // 1–5
    val comment: String?
)
