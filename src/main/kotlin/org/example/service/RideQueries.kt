package org.example.service

import org.example.model.Ride
import org.example.repository.RideRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/**
 * Ride reads that may be served from the read replica, i.e. may be a moment stale.
 *
 * Rule for this class: only reads that show data to a user, never a read that a write or a
 * decision depends on. Anything that checks state before changing it (acceptRide, startRide...)
 * reads through RideService on the primary instead. Read-your-own-writes is handled centrally by
 * RecentWriteInterceptor, which sends these to the primary for 5 s after the user writes.
 *
 * Adding a query here is the decision "this can be stale". That's the line to review in a PR.
 */
@Service
class RideQueries(
    private val rideRepository: RideRepository,
    private val replicaReads: ReplicaReads
) {
    // GET /rides/{id}: the rider's app polls this. A driver accepting shows up a few ms late,
    // which nobody notices.
    fun rideForDisplay(rideId: String): Ride = replicaReads.read {
        rideRepository.findById(rideId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found")
        }
    }

    // History pages: varied and paged, the kind of read a replica is for (too varied to cache well).
    fun riderHistory(riderId: String, pageable: Pageable): Page<Ride> = replicaReads.read {
        rideRepository.findByRiderIdOrderByRequestedAtDesc(riderId, pageable)
    }

    fun driverHistory(driverId: String, pageable: Pageable): Page<Ride> = replicaReads.read {
        rideRepository.findByDriverIdOrderByRequestedAtDesc(driverId, pageable)
    }
}
