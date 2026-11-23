package org.example.repository

import org.example.model.Driver
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DriverRepository : JpaRepository<Driver, String> {

    // Read-modify-write inside one UPDATE, so two ratings landing at once can't lose one.
    // Postgres row-locks the driver, the second UPDATE waits, then re-reads the committed row.
    // In SET, rating_count on the right-hand side is the OLD value. Returns rows updated (0 = no driver).
    @Modifying
    @Query(
        value = """
            UPDATE drivers
            SET avg_rating   = ROUND(CAST((COALESCE(avg_rating, 0) * rating_count + :score) / (rating_count + 1) AS numeric), 2),
                rating_count = rating_count + 1
            WHERE user_id = :id
        """,
        nativeQuery = true
    )
    fun addRating(@Param("id") id: String, @Param("score") score: Int): Int
}
