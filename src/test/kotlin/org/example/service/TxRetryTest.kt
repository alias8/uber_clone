package org.example.service

import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TxRetryTest {

    private val waits = mutableListOf<Long>()
    private val txRetry = TxRetry().apply { sleep = { waits += it } }

    // How Spring surfaces these: a DataAccessException wrapping the driver's SQLException.
    private fun deadlock() = CannotAcquireLockException("deadlock", SQLException("deadlock detected", "40P01"))
    private fun connectionLost() = DataAccessResourceFailureException("I/O error", SQLException("connection closed", "08006"))
    private fun adminShutdown() = DataAccessResourceFailureException("terminated", SQLException("terminating connection", "57P01"))

    @AfterTest
    fun cleanUp() {
        TxAttemptTracker.reset()
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
    }

    @Test
    fun `deadlock twice then success takes three attempts with 100 and 200 ms waits`() {
        var calls = 0
        val result = txRetry.run { calls++; if (calls < 3) throw deadlock() else "ok" }
        assertEquals("ok", result)
        assertEquals(3, calls)
        assertEquals(2, waits.size)
        assert(waits[0] in 100L..149L && waits[1] in 200L..249L) { "waits were $waits" }
    }

    @Test
    fun `gives up after three attempts and rethrows the last error`() {
        var calls = 0
        assertFailsWith<CannotAcquireLockException> { txRetry.run { calls++; throw deadlock() } }
        assertEquals(3, calls)
    }

    @Test
    fun `connection lost before commit is retried`() {
        var calls = 0
        txRetry.run { calls++; if (calls == 1) throw connectionLost() else "ok" }
        assertEquals(2, calls)
    }

    @Test
    fun `server shutting down (failover) before commit is retried`() {
        var calls = 0
        txRetry.run { calls++; if (calls == 1) throw adminShutdown() else "ok" }
        assertEquals(2, calls)
    }

    @Test
    fun `connection lost during commit is not retried`() {
        var calls = 0
        assertFailsWith<DataAccessResourceFailureException> {
            txRetry.run {
                calls++
                TxAttemptTracker.beforeCommit(FakeExecution) // what the transaction manager does just before COMMIT
                throw connectionLost()
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `deadlock reported at commit is still retried, because Postgres rolled it back`() {
        var calls = 0
        txRetry.run {
            calls++
            if (calls == 1) { TxAttemptTracker.beforeCommit(FakeExecution); throw deadlock() }
            "ok"
        }
        assertEquals(2, calls)
    }

    @Test
    fun `failed rollback on a dead connection is retried (what a killed backend really throws)`() {
        var calls = 0
        // The original 57P01 is replaced by the rollback's own error, which has no SQLState.
        val rollbackError = org.springframework.orm.jpa.JpaSystemException(
            RuntimeException("Unable to rollback against JDBC Connection", SQLException("Connection is closed"))
        )
        txRetry.run {
            calls++
            if (calls == 1) {
                TxAttemptTracker.afterRollback(FakeExecution, rollbackError.cause)
                throw rollbackError
            }
            "ok"
        }
        assertEquals(2, calls)
    }

    @Test
    fun `pool timeout is not retried, Hikari already waited`() {
        var calls = 0
        val poolTimeout = DataAccessResourceFailureException(
            "no connection", SQLTransientConnectionException("Connection is not available", "08001")
        )
        assertFailsWith<DataAccessResourceFailureException> { txRetry.run { calls++; throw poolTimeout } }
        assertEquals(1, calls)
    }

    @Test
    fun `non-transient errors are not retried`() {
        var calls = 0
        assertFailsWith<DataIntegrityViolationException> {
            txRetry.run { calls++; throw DataIntegrityViolationException("dup", SQLException("duplicate key", "23505")) }
        }
        assertEquals(1, calls)
        assertEquals(emptyList(), waits)
    }

    @Test
    fun `refuses to run inside an existing transaction`() {
        TransactionSynchronizationManager.setActualTransactionActive(true)
        assertFailsWith<IllegalStateException> { txRetry.run { "ok" } }
    }

    private object FakeExecution : org.springframework.transaction.TransactionExecution {
        override fun isNewTransaction() = true
        override fun setRollbackOnly() {}
        override fun isRollbackOnly() = false
        override fun isCompleted() = false
    }
}
