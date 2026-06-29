Retry for unmatched rides                                                                                                                              
If no drivers are nearby, the ride sits as REQUESTED forever. A Spring @Scheduled job that periodically finds stale REQUESTED rides and re-publishes to
Kafka teaches scheduling, idempotency, and resilience.

Tests                                                                                                                                                  
No tests exist at all. Writing unit tests for RideService (mocking repos) and integration tests for acceptRide race conditions would teach you a lot —
and it's the thing most interviewers ask to see.

Circuit breaker (Resilience4j)                                                                                                                         
What happens if Redis goes down mid-dispatch? Currently the whole request fails. A circuit breaker teaches you the resilience patterns (retry,         
fallback, half-open state) that come up constantly in system design interviews.

ETA on the ride offer                                                                                                                                  
Use the driver's current coordinates (already in the Redis geo-index) and the pickup coordinates to calculate estimated minutes to arrival. Teaches you
to compose existing data in new ways.      