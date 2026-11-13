package org.example.config

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource
import org.springframework.transaction.support.TransactionSynchronizationManager

enum class DbTarget { PRIMARY, REPLICA }

/**
 * Picks the primary or the replica each time a connection is needed.
 *
 * Rule: replica only for a read-only transaction, and only if nothing has forced the primary
 * for this request. Everything else, including any query run outside a transaction, goes to
 * the primary. Defaulting to the primary means a mistake costs some performance, not stale data.
 */
class ReadWriteRoutingDataSource : AbstractRoutingDataSource() {
    override fun determineCurrentLookupKey(): DbTarget {
        val target = when {
            DbRouting.isPrimaryForced() -> DbTarget.PRIMARY
            TransactionSynchronizationManager.isCurrentTransactionReadOnly() -> DbTarget.REPLICA
            else -> DbTarget.PRIMARY
        }
        logger.debug("Routing connection to $target")
        return target
    }
}

/**
 * Per-thread "use the primary" switch. Works with virtual threads: each request runs on its own
 * virtual thread, so a ThreadLocal is still per request.
 *
 * Used two ways:
 *  - RecentWriteInterceptor turns it on for a whole request when this user wrote in the last few
 *    seconds (read-your-own-writes).
 *  - DbRouting.primary { ... } wraps code that must never see stale data, e.g. a Kafka consumer
 *    reading the row an event was just published about.
 */
object DbRouting {
    private val forcePrimary = ThreadLocal.withInitial { false }

    fun isPrimaryForced(): Boolean = forcePrimary.get()

    fun forcePrimary() = forcePrimary.set(true)

    fun clear() = forcePrimary.remove()

    fun <T> primary(block: () -> T): T {
        val previous = forcePrimary.get()
        forcePrimary.set(true)
        try {
            return block()
        } finally {
            forcePrimary.set(previous)
        }
    }
}
