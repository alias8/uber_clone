package org.example.service

import org.example.config.DbRouting
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * The only way to read from the replica: `replicaReads.read { ... }`.
 *
 * Runs the block in a read-only transaction with the "replica requested" flag set, so the router
 * picks the replica on the first SQL statement. Visible at the call site, easy to grep for, and
 * easy to review: "can everything inside this block be a second stale?"
 *
 * If a transaction is already running (e.g. called from inside acceptRide), the block just joins
 * it, and that transaction's connection is already on the primary. So calling this from a write
 * path is harmless: you get fresh data, not stale.
 *
 * (An annotation like @ReplicaRead would read nicer, but needs Spring AOP, which this project
 * doesn't include. A helper also has no self-invocation trap: it works the same whether called
 * from inside or outside the class.)
 */
@Component
class ReplicaReads(transactionManager: PlatformTransactionManager) {
    private val readOnlyTx = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    @Suppress("UNCHECKED_CAST")
    fun <T> read(block: () -> T): T =
        DbRouting.withReplicaRequested { readOnlyTx.execute { block() } as T }
}
