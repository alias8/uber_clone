package org.example.repository

import org.example.model.Rating
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface RatingRepository : JpaRepository<Rating, String> {
    fun existsByRideIdAndFromUserId(rideId: String, fromUserId: String): Boolean

    @Query("SELECT AVG(r.score) FROM Rating r WHERE r.toUserId = :userId")
    fun avgScoreForUser(userId: String): Double?
}
