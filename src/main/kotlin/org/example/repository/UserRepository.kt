package org.example.repository

import org.example.model.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserRepository : JpaRepository<User, String> {
    fun findByUsername(username: String): User?
    fun existsByUsername(username: String): Boolean

    // Same atomic read-modify-write as DriverRepository.addRating.
    @Modifying
    @Query(
        value = """
            UPDATE users
            SET avg_rating   = ROUND(CAST((COALESCE(avg_rating, 0) * rating_count + :score) / (rating_count + 1) AS numeric), 2),
                rating_count = rating_count + 1
            WHERE id = :id
        """,
        nativeQuery = true
    )
    fun addRating(@Param("id") id: String, @Param("score") score: Int): Int
}
