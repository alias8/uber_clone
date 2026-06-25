package org.example.model

enum class RideStatus {
    REQUESTED,   // rider submitted, no driver yet
    MATCHED,     // driver accepted, en route to pickup
    IN_PROGRESS, // driver started the trip
    COMPLETED,   // trip finished, fare set
    CANCELLED    // cancelled before IN_PROGRESS
}
