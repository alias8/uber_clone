package org.example.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

/**
 * Load shedding: cap the requests this instance works on at once, and reject the excess with an
 * immediate 503 + Retry-After before any work is done.
 *
 * Not the same as RateLimiterService. Rate limiting caps each CALLER (5 ride requests a minute per
 * user) however idle the server is. This caps the SERVER however well-behaved each caller is: it's
 * what stops everyone together from overloading an instance.
 *
 * Why it matters here: with virtual threads there's no Tomcat thread cap (normally 200), so
 * without this an instance accepts everything in a spike. Memory and latency climb until the DB
 * pool times out (PoolExhaustedHandler's 503), by which point each request has already done auth,
 * Redis and maybe a pricing call. Shedding at the front door rejects for almost nothing.
 *
 * Sizing (Little's law): in flight = rate x time per request. 300 req/s x 0.1 s = 30 normally, so
 * 150 leaves room for bursts but stops a pile-up. An adaptive limiter (Netflix concurrency-limits)
 * would tune this from measured latency instead of a fixed number.
 *
 * Priority: ride actions that move money or a car (request, accept, start, complete, cancel) can use
 * every permit; everything else stops when only the reserved permits are left. So when overloaded,
 * history and polling are shed first and accept/complete keep working.
 *
 * Registered first in the servlet filter chain by FilterConfig (not a @Component), so it runs
 * before Spring Security and a shed request doesn't even parse its JWT.
 * Skipped for /health (the ALB must still see the instance as alive; shedding is a healthy state)
 * and for SSE streams, which stay open for minutes and would hold a permit the whole time.
 */
class LoadSheddingFilter(
    maxInFlight: Int,
    reservedForCritical: Int
) : OncePerRequestFilter() {

    private val limiter = InFlightLimiter(maxInFlight, reservedForCritical)
    private val log = LoggerFactory.getLogger(javaClass)
    private val shedSinceLastLog = AtomicLong()
    private val lastLogAt = AtomicLong()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return path == "/health" || path == "/driver/offers" || SSE_LOCATION.matches(path)
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val critical = request.method == "POST" && CRITICAL.matches(request.requestURI)
        if (!limiter.tryEnter(critical)) {
            recordShed()
            response.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
            response.setHeader(HttpHeaders.RETRY_AFTER, "1")
            response.contentType = "application/json"
            response.writer.write("""{"error":"Server busy, retry shortly"}""")
            return
        }
        try {
            chain.doFilter(request, response)
        } finally {
            limiter.exit()
        }
    }

    // One summary line at most every 5 s: under overload, a line per shed request would flood the logs.
    private fun recordShed() {
        shedSinceLastLog.incrementAndGet()
        val now = System.currentTimeMillis()
        val last = lastLogAt.get()
        if (now - last >= 5_000 && lastLogAt.compareAndSet(last, now)) {
            log.warn("Load shedding: rejected {} request(s) with 503 in the last 5 s (in flight: {})",
                shedSinceLastLog.getAndSet(0), limiter.inFlight())
        }
    }

    companion object {
        private val CRITICAL = Regex("^/rides(/[^/]+/(accept|start|complete|cancel))?$")
        private val SSE_LOCATION = Regex("^/rides/[^/]+/location$")
    }
}

/**
 * The counting behind LoadSheddingFilter, kept separate so it can be tested without a servlet.
 * Normal requests only get a permit while more than [reservedForCritical] are free; critical ones
 * can take the last permit. The check-then-acquire for normal requests is approximate under a race
 * (a few extra may slip into the reserve), which is fine for shedding.
 */
class InFlightLimiter(private val max: Int, private val reservedForCritical: Int) {
    init {
        require(max > 0 && reservedForCritical in 0 until max) { "need 0 <= reserved < max" }
    }

    private val permits = Semaphore(max)

    fun tryEnter(critical: Boolean): Boolean {
        if (!critical && permits.availablePermits() <= reservedForCritical) return false
        return permits.tryAcquire()
    }

    fun exit() = permits.release()

    fun inFlight(): Int = max - permits.availablePermits()
}
