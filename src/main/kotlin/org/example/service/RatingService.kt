package org.example.service

import org.example.model.Rating
import org.example.model.RideStatus
import org.example.repository.DriverRepository
import org.example.repository.RatingRepository
import org.example.repository.RideRepository
import org.example.repository.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class RatingService(
    private val ratingRepository: RatingRepository,
    private val rideRepository: RideRepository,
    private val userRepository: UserRepository,
    private val driverRepository: DriverRepository
) {
    @Transactional(readOnly = false) // the status and "already rated" checks, the rating insert and the average updates commit together
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

        // The averages are updated in SQL, not read into Kotlin, changed and saved. That version
        // lost ratings: two riders rating the same driver at once both read count = 10 and both
        // wrote 11. Doing the arithmetic in one UPDATE lets the row lock serialise them, so there
        // is no conflict to detect and nothing to retry. (Same rows as before: a driver's rating
        // also updates their users row, matching V3's backfill of users.rating_count.)
        driverRepository.addRating(toUserId, score)
        userRepository.addRating(toUserId, score)
    }
}
