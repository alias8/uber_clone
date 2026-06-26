# Design Considerations & Limits

## SSE connections (driver offer stream)

`SseEmitter` uses async servlet processing — Tomcat releases the thread back to the pool after sending the initial response headers. The connection stays open as a socket, not a thread. So the 200-thread default does **not** cap concurrent driver connections.

The real limits for SSE:
- **File descriptors**: each open connection is an FD. OS default is often 1024 (soft limit), tuneable to 65k+ with `ulimit -n`. Must be raised before running large numbers of drivers.
- **JVM heap**: each `SseEmitter` is a small object. 10k drivers ≈ a few MB — not a concern in practice.
- **Tomcat thread pool**: still limits concurrent non-SSE requests (REST calls). Default 200 threads. Raise with `server.tomcat.threads.max` if under heavy API load.

## Horizontal scaling & EmitterRegistry

`EmitterRegistry` is an in-memory map — not shared across server instances. This looks like a problem but works correctly: every instance subscribes to `ride_offers:*` via `RedisMessageListenerContainer`. When Redis pub/sub broadcasts a message, all instances receive it. The one holding the driver's SSE connection delivers it; the others do a null-safe no-op. Redis pub/sub does the cross-instance fan-out for free.

Implication: drivers can connect to any instance. No sticky sessions needed.

## Driver availability cross-reference (scalability bug)

`DriverService.findNearby` does two things:
1. Redis `GEORADIUS` — returns nearby drivers by location (fast, O(log N))
2. `driverRepository.findByIsAvailableTrue()` — loads **all** available drivers from Postgres to cross-reference

As driver count grows this becomes a full table scan on every dispatch. Fix: store availability in Redis as a Set (`drivers:available`) and use `SISMEMBER` per result instead of loading the whole Postgres table.

## Postgres connection pool

Spring Boot defaults to HikariCP with **10 connections**. `acceptRide` is `@Transactional` and holds a connection for the duration of the read + two saves. Under concurrent accept attempts this pool exhausts quickly. Tune with `spring.datasource.hikari.maximum-pool-size` — rule of thumb is `(cores * 2) + effective_spindle_count` for Postgres.

## Redis connection pool

Spring Boot uses Lettuce with a default pool of **8 connections**. `DispatchService` publishes one message per nearby driver — dispatching to 20 drivers burns 20 pool borrows in quick succession. Under high dispatch volume this saturates the pool. Tune with `spring.data.redis.lettuce.pool.max-active`.

The `RedisMessageListenerContainer` uses a **dedicated** connection outside the pool for its `PSUBSCRIBE` — this doesn't compete with the pool.

## Kafka consumer parallelism

The `ride-requested` topic defaults to 1 partition, so only 1 consumer instance processes it regardless of how many app instances are running. Dispatch is sequential. To parallelize: increase the topic's partition count and ensure the consumer group has one instance per partition. Each `fanoutToNearbyDrivers` call is synchronous inside the consumer — a slow geo query backs up the consumer lag. Consider making the fanout async (coroutine or `@Async`) if dispatch latency becomes a concern.

## Optimistic locking under contention

`acceptRide` catches `ObjectOptimisticLockingFailureException` and returns 409. Under a burst (many drivers tapping accept at the same time), all but one get a 409. This is correct but the driver app must handle 409 gracefully — show "ride already taken, wait for next offer" rather than treating it as an error.

## SSE reconnection & missed offers

The SSE spec supports `Last-Event-ID` for replay on reconnect, but this server doesn't implement it. If a driver's connection drops and they reconnect, any offers published during the gap are lost — they simply won't see those rides. Driver apps should reconnect immediately on disconnect (most SSE client libraries do this automatically). Rides missed during the gap remain as `REQUESTED` and will be picked up if a retry/timeout mechanism is added later.

## HTTP/1.1 browser connection limit

Browsers cap HTTP/1.1 connections per origin at 6. The SSE stream consumes one, leaving 5 for API calls — fine for a driver app. With HTTP/2 (requires TLS) connections are multiplexed so the limit disappears. Set `server.http2.enabled=true` and configure SSL to enable.

## Surge pricing staleness

The surge multiplier is cached in Redis with a TTL. The price shown to the rider at request time may differ from the multiplier applied when the fare is calculated at completion. This is acceptable (and is how Uber works — upfront price estimate vs. actual fare), but worth knowing.
