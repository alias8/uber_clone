package org.example.config

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.TransactionException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.sql.SQLTransientConnectionException

/**
 * Fail fast when the DB connection pool is exhausted: 503 + Retry-After instead of a long wait.
 *
 * Pairs with app.datasource.*.connection-timeout in application.properties (1.5 s instead of
 * Hikari's 30 s default). When every connection is busy, a request waits at most that long, then
 * Hikari throws SQLTransientConnectionException ("Connection is not available, request timed out
 * after 1500ms") and this handler turns it into a 503 the client or load balancer can retry.
 *
 * Why not just wait 30 s: the client has usually given up or retried long before, so the work is
 * wasted, and every waiting request keeps its socket, memory and (with platform threads) a Tomcat
 * thread. Requests pile up, the instance stops answering, and the ALB moves its traffic onto the
 * other instances, which tip over too. A fast 503 sheds the excess and keeps the rest moving.
 *
 * Spring wraps the Hikari exception differently depending on where the connection was requested:
 * CannotCreateTransactionException (a TransactionException) at BEGIN, or a DataAccessException on
 * the first SQL statement, which is where LazyConnectionDataSourceProxy asks for it. So this checks
 * the whole cause chain rather than one exception type.
 *
 * Only HTTP requests come through here. Kafka consumers get the exception directly, and their
 * retry/error handling deals with it.
 *
 * Retrying is only safe for idempotent requests. A pool timeout usually means the request never
 * reached the DB, but the client can't know that, so a retried POST /rides needs an idempotency key.
 *
 * Watch hikaricp.connections.pending and hikaricp.connections.timeout (Micrometer): a stream of
 * these 503s means the DB or a slow query is the real problem. The 503 is a symptom, not the fix.
 */
@RestControllerAdvice
class PoolExhaustedHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(DataAccessException::class, TransactionException::class)
    fun handle(ex: RuntimeException): ResponseEntity<Map<String, String>> {
        val poolTimeout = generateSequence<Throwable>(ex) { it.cause }
            .any { it is SQLTransientConnectionException }

        if (!poolTimeout) {
            // Any other DB error: same 500 Spring would have returned, but logged here.
            log.error("Database error", ex)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to "Internal error"))
        }

        log.warn("DB connection pool exhausted, returning 503: {}", ex.message)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, "1")
            .body(mapOf("error" to "Server busy, retry shortly"))
    }
}
