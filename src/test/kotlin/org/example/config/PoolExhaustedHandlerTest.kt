package org.example.config

import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.transaction.CannotCreateTransactionException
import java.sql.SQLTransientConnectionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PoolExhaustedHandlerTest {

    private val handler = PoolExhaustedHandler()
    private val hikariTimeout =
        SQLTransientConnectionException("primary - Connection is not available, request timed out after 1500ms.")

    @Test
    fun `pool timeout at BEGIN returns 503 with Retry-After`() {
        val ex = CannotCreateTransactionException("Could not open JPA EntityManager", hikariTimeout)
        val response = handler.handle(ex)
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        assertEquals("1", response.headers.getFirst(HttpHeaders.RETRY_AFTER))
    }

    @Test
    fun `pool timeout on first SQL statement, wrapped twice, returns 503`() {
        val ex = DataAccessResourceFailureException("could not prepare statement", RuntimeException(hikariTimeout))
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, handler.handle(ex).statusCode)
    }

    @Test
    fun `other DB errors stay 500 with no Retry-After`() {
        val response = handler.handle(DataIntegrityViolationException("duplicate key"))
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertNull(response.headers.getFirst(HttpHeaders.RETRY_AFTER))
    }
}
