package org.example.config

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource
import org.springframework.transaction.support.TransactionSynchronizationManager

enum class DbTarget { PRIMARY, REPLICA }

/**
 * Picks the primary or the replica each time a real connection is needed.
 *
 * OPT-IN: the replica is used only when code has explicitly asked for it through
 * [org.example.service.ReplicaReads] (which also opens a read-only transaction). Everything else
 * goes to the primary, including Spring Data's findById, even though Spring Data marks it
 * @Transactional(readOnly = true). Routing is never a side effect of a flag the framework sets.
 *
 * A mistake therefore costs performance (a read that could have used the replica didn't), never
 * correctness (a read that needed fresh data got stale data).
 *
 * Order of checks:
 *  1. This user wrote in the last few seconds (RecentWriteInterceptor) -> PRIMARY, even for an
 *     opted-in read, so they see their own write.
 *  2. Code opted in AND we're in a read-only transaction -> REPLICA.
 *  3. Anything else -> PRIMARY.
 */
class ReadWriteRoutingDataSource : AbstractRoutingDataSource() {
    override fun determineCurrentLookupKey(): DbTarget {
        val target = when {
            DbRouting.isPrimaryForced() -> DbTarget.PRIMARY
            DbRouting.isReplicaRequested() &&
                TransactionSynchronizationManager.isCurrentTransactionReadOnly() -> DbTarget.REPLICA
            else -> DbTarget.PRIMARY
        }
        logger.debug("Routing connection to $target")
        return target
    }
}

/**
 * Per-thread routing flags. Works with virtual threads: each request runs on its own virtual
 * thread, so a ThreadLocal is still per request.
 */
object DbRouting {
    private val forcePrimary = ThreadLocal.withInitial { false }
    private val replicaRequested = ThreadLocal.withInitial { false }

    fun isPrimaryForced(): Boolean = forcePrimary.get()

    fun isReplicaRequested(): Boolean = replicaRequested.get()

    /** Read-your-own-writes: set for a whole request by RecentWriteInterceptor. */
    fun forcePrimary() = forcePrimary.set(true)

    fun clear() {
        forcePrimary.remove()
        replicaRequested.remove()
    }

    /** Only for ReplicaReads. Restores the previous value so nesting is safe. */
    internal fun <T> withReplicaRequested(block: () -> T): T {
        val previous = replicaRequested.get()
        replicaRequested.set(true)
        try {
            return block()
        } finally {
            replicaRequested.set(previous)
        }
    }
}
