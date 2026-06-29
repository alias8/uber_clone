package org.example.repository

import org.example.model.Rating
import org.springframework.data.jpa.repository.JpaRepository

interface RatingRepository : JpaRepository<Rating, String> {
    fun existsByRideIdAndFromUserId(rideId: String, fromUserId: String): Boolean
}
