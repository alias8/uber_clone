package org.example.utils

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Run [action] only once the current DB transaction has committed. If there is no transaction,
 * run it now.
 *
 * Use it for side effects outside the database (Kafka, Redis, HTTP calls) that
 * @Transactional can't roll back:
 *  - Before commit, other connections can't see the new row yet (Postgres "read committed"), so
 *    an event sent early can be consumed before its data exists.
 *  - If the transaction rolls back (e.g. lost an optimistic-lock race), afterCommit never fires,
 *    so no event for something that never happened, and no Redis change to undo.
 *
 * What it does NOT cover: the process dying between COMMIT and the action. The commit stands and
 * the action is lost. If that matters, use an outbox table instead (write the event in the same
 * transaction, publish from a relay), see interview notes "Slow work" thread 3.
 */
fun afterCommit(action: () -> Unit) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = action()
        })
    } else {
        action()
    }
}
