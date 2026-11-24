package org.example.repository

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class StoredIdempotencyKey(val requestHash: String, val rideId: String)

/**
 * Native SQL on idempotency_keys. No JPA entity: the table is only ever inserted into with
 * ON CONFLICT, looked up by primary key, and bulk-deleted, none of which an entity helps with.
 */
@Repository
class IdempotencyKeyRepository {

    @PersistenceContext
    private lateinit var em: EntityManager

    /**
     * Claim [key] for [rideId]. Returns 1 if this call claimed it, 0 if the key already existed.
     *
     * Must run inside the transaction that inserts the ride. If another request with the same key
     * is mid-transaction, Postgres makes this INSERT wait on the primary key's unique index until
     * that transaction ends: commit -> 0 rows here, rollback -> this insert goes through.
     */
    fun insertIfAbsent(userId: String, key: String, requestHash: String, rideId: String): Int =
        em.createNativeQuery(
            """
            INSERT INTO idempotency_keys (user_id, idem_key, request_hash, ride_id)
            VALUES (:userId, :key, :hash, :rideId)
            ON CONFLICT (user_id, idem_key) DO NOTHING
            """
        )
            .setParameter("userId", userId)
            .setParameter("key", key)
            .setParameter("hash", requestHash)
            .setParameter("rideId", rideId)
            .executeUpdate()

    fun find(userId: String, key: String): StoredIdempotencyKey? =
        em.createNativeQuery("SELECT request_hash, ride_id FROM idempotency_keys WHERE user_id = :userId AND idem_key = :key")
            .setParameter("userId", userId)
            .setParameter("key", key)
            .resultList
            .firstOrNull()
            ?.let { row -> (row as Array<*>).let { StoredIdempotencyKey(it[0] as String, it[1] as String) } }

    @Transactional
    fun deleteCreatedBefore(cutoff: Instant): Int =
        em.createNativeQuery("DELETE FROM idempotency_keys WHERE created_at < :cutoff")
            .setParameter("cutoff", cutoff)
            .executeUpdate()
}
