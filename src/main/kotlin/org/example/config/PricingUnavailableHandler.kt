package org.example.config

import org.example.client.PricingUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * No fare quote, no ride: POST /rides returns 503 + Retry-After instead of guessing a price.
 *
 * The alternative was falling back to the base fare without surge. That keeps rides flowing but
 * undercharges exactly when demand is highest, and the rider would be charged a price pricing
 * never agreed to. Pricing outages should be rare and short, so asking the rider to retry in a few
 * seconds is the safer trade. Nothing was saved yet: requestRide calls pricing before the INSERT.
 */
@RestControllerAdvice
class PricingUnavailableHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(PricingUnavailableException::class)
    fun handle(ex: PricingUnavailableException): ResponseEntity<Map<String, String>> {
        log.warn("Pricing unavailable, returning 503: {}", ex.cause?.message)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, "5")
            .body(mapOf("error" to "Can't price this ride right now, please try again in a few seconds"))
    }
}
