package org.example.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.TransactionExecution
import org.springframework.transaction.TransactionExecutionListener
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import kotlin.random.Random

/**
 * Re-run a @Transactional service call when the DB failed it for a transient reason:
 * try, wait ~100 ms, try, wait ~200 ms, try, then give up and let the error through.
 *
 * Wraps the call from OUTSIDE the transaction: `txRetry.run { rideService.acceptRide(id, userId) }`.
 * Each attempt goes through the @Transactional proxy, so it gets a fresh BEGIN and a fresh
 * Hibernate session. Retrying inside the transaction would re-run statements on a transaction
 * Postgres has already aborted, which is why run() refuses to start inside one.
 *
 * Retried (see isRetryable):
 *  - 40P01 deadlock, 40001 serialization failure: Postgres rolled the transaction back, so
 *    nothing happened and re-running is safe. Retried even if the error came at commit.
 *  - Connection lost (08xxx; 57P01/57P02 = server shutting down, e.g. a failover or restart),
 *    but only if COMMIT hadn't started. If the connection died during COMMIT, the transaction may
 *    or may not have committed, and re-running a non-idempotent write could do it twice.
 *    Conservative edge: Hibernate flushes queued UPDATEs inside commit, so a connection lost
 *    during that flush also counts as "commit started" and isn't retried.
 *  - The rollback itself failed, before COMMIT. Found by killing the app's backend mid-UPDATE:
 *    Spring then rolls back on the dead connection, that fails with "Connection is closed" (no
 *    SQLState), and Spring throws the rollback error INSTEAD of the original 57P01. So the
 *    SQLState is gone; TxAttemptTracker.afterRollback records the failure instead. A failed
 *    rollback means the connection is gone, and nothing committed.
 *
 * Not retried:
 *  - Pool timeout (SQLTransientConnectionException): Hikari already kept trying for
 *    connection-timeout (1.5 s); PoolExhaustedHandler returns 503 and the client retries.
 *  - statement_timeout (57014), constraint violations, optimistic lock conflicts, 4xx
 *    ResponseStatusExceptions: retrying gives the same answer or just adds load.
 *
 * Side effects are safe to repeat: Kafka and the driver-available Redis set only change in
 * afterCommit, which never fires for a rolled-back attempt. goOnline/goOffline write Redis
 * directly, but GEOADD/ZREM of the same member are idempotent.
 *
 * This is the only layer that retries the DB. It covers blips (well under a second); a 30-60 s
 * failover outlasts it, ends in a 500/503, and the client retries later.
 */
@Component
class TxRetry {

    private val log = LoggerFactory.getLogger(javaClass)

    // Replaced in tests so they don't sleep.
    internal var sleep: (Long) -> Unit = { Thread.sleep(it) }

    fun <T> run(block: () -> T): T {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "TxRetry must wrap a @Transactional call from outside; inside, a failed transaction is already dead"
        }
        var attempt = 1
        while (true) {
            TxAttemptTracker.reset()
            try {
                return block()
            } catch (e: RuntimeException) {
                val retryable = isRetryable(e, TxAttemptTracker.commitStarted(), TxAttemptTracker.rollbackFailed())
                if (attempt >= MAX_ATTEMPTS || !retryable) throw e
                val waitMs = BACKOFF_MS[attempt - 1] + Random.nextLong(JITTER_MS)
                log.warn("Transient DB failure on attempt {}/{}, retrying in {} ms: {}", attempt, MAX_ATTEMPTS, waitMs, e.message)
                sleep(waitMs)
                attempt++
            } finally {
                TxAttemptTracker.reset()
            }
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        private val BACKOFF_MS = longArrayOf(100, 200) // wait after attempt 1, after attempt 2
        private const val JITTER_MS = 50L // so retries from many requests don't land together
    }
}

private val ROLLED_BACK = setOf("40001", "40P01")
private val SERVER_SHUTDOWN = setOf("57P01", "57P02")

internal fun isRetryable(e: Throwable, commitStarted: Boolean, rollbackFailed: Boolean = false): Boolean {
    val chain = generateSequence(e) { it.cause }.toList()
    if (chain.any { it is SQLTransientConnectionException }) return false
    if (rollbackFailed && !commitStarted) return true
    val states = chain.filterIsInstance<SQLException>().mapNotNull { it.sqlState }
    if (states.any { it in ROLLED_BACK }) return true
    if (states.any { it.startsWith("08") || it in SERVER_SHUTDOWN }) return !commitStarted
    return false
}

/**
 * Records, per thread, what happened to the current TxRetry attempt's transaction: whether it
 * reached COMMIT (beforeCommit fires just before Spring commits), and whether a rollback failed.
 * Registered with the transaction manager in TransactionConfig.
 */
object TxAttemptTracker : TransactionExecutionListener {
    private val commitStarted = ThreadLocal.withInitial { false }
    private val rollbackFailed = ThreadLocal.withInitial { false }

    override fun beforeCommit(transaction: TransactionExecution) = commitStarted.set(true)

    override fun afterRollback(transaction: TransactionExecution, rollbackFailure: Throwable?) {
        if (rollbackFailure != null) rollbackFailed.set(true)
    }

    fun commitStarted(): Boolean = commitStarted.get()

    fun rollbackFailed(): Boolean = rollbackFailed.get()

    fun reset() {
        commitStarted.remove()
        rollbackFailed.remove()
    }
}
