package org.example.service

import org.example.model.Rating
import org.example.model.RideStatus
import org.example.repository.DriverRepository
import org.example.repository.RatingRepository
import org.example.repository.RideRepository
import org.example.repository.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class RatingService(
    private val ratingRepository: RatingRepository,
    private val rideRepository: RideRepository,
    private val userRepository: UserRepository,
    private val driverRepository: DriverRepository
) {
    fun rate(rideId: String, fromUserId: String, score: Int, comment: String?) {
        if (score !in 1..5) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Score must be between 1 and 5")
        }

        val ride = rideRepository.findById(rideId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found")
        }

        if (ride.status != RideStatus.COMPLETED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Can only rate a completed ride")
        }

        val toUserId = when (fromUserId) {
            ride.riderId -> ride.driverId
                ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Ride has no assigned driver")
            ride.driverId -> ride.riderId
            else -> throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not a participant in this ride")
        }

        if (ratingRepository.existsByRideIdAndFromUserId(rideId, fromUserId)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Already rated this ride")
        }

        ratingRepository.save(Rating(rideId = rideId, fromUserId = fromUserId, toUserId = toUserId, score = score, comment = comment))

        // Recalculate and persist the recipient's new average rating
        val newAvg = ratingRepository.avgScoreForUser(toUserId)
            ?.let { BigDecimal(it).setScale(2, RoundingMode.HALF_UP).toDouble() }

        // Update whichever profile the recipient has
        driverRepository.findById(toUserId).ifPresent { driver ->
            driverRepository.save(driver.copy(avgRating = newAvg))
        }
        userRepository.findById(toUserId).ifPresent { user ->
            userRepository.save(user.copy(avgRating = newAvg))
        }
    }
}
