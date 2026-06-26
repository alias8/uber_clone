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

---

## Interview Questions

### "Design a ride-hailing dispatch system"
The core challenge is fanout with low latency. Key points to hit:
- **Geo-indexing**: use Redis `GEOADD`/`GEORADIUS` to find nearby drivers in O(log N). Don't query Postgres for location — it has no spatial index by default.
- **Async dispatch**: the rider's `POST /rides` should return immediately. Publish a Kafka event and let a consumer do the fanout asynchronously.
- **Driver notification**: drivers hold a persistent SSE (or WebSocket) connection. When a ride is dispatched, publish to a Redis pub/sub channel per driver; a `MessageListener` on each server instance pushes it to the right SSE emitter.
- **Race condition on accept**: multiple drivers receive the same offer. Use optimistic locking (`@Version`) so only one write succeeds; the rest get a 409.
- **Fare locking**: calculate fare upfront (distance formula + surge), store it, don't recalculate at completion.

### "Why Kafka instead of calling the dispatch service directly?"
Two reasons: response time and resilience. The rider's POST blocks until dispatch completes if you call directly — that's a Redis geo query plus N pub/sub publishes. With Kafka, the ride is saved and the event is published in one transaction; the rider gets a response immediately. Kafka also gives you durability: if the consumer is slow or restarts, the event isn't lost.
The tradeoff is added latency before drivers see the offer (milliseconds in practice) and operational complexity of running Kafka.

### "Optimistic vs pessimistic locking — which and when?"
- **Optimistic** (`@Version`): adds `WHERE version = ?` to the UPDATE. No lock held, no blocking. Losers get an exception at commit time. Right when contention is low and short-lived — like multiple drivers racing to accept a single ride over a few seconds.
- **Pessimistic** (`SELECT FOR UPDATE`): locks the row at read time. Losers wait (or fail fast). Right when contention is high and sustained — like a counter being incremented by many workers simultaneously.
For ride acceptance, optimistic wins: conflicts are rare, the window is short, and you don't want to serialize every accept attempt behind a DB lock.

### "How does Redis pub/sub work across multiple server instances?"
Every instance subscribes to `ride_offers:*` via `RedisMessageListenerContainer` when it starts. When `DispatchService` publishes to `ride_offers:{driverId}`, Redis broadcasts the message to all subscribers — meaning all server instances receive it. Each instance looks up `driverId` in its local `EmitterRegistry`. The instance holding that driver's SSE connection delivers the message; the others get a null and do nothing. No sticky sessions needed.

### "How would you scale this to 100k concurrent drivers?"
- **SSE connections don't consume threads** — async servlets release the Tomcat thread after headers are sent. The bottleneck is file descriptors (raise `ulimit -n`) and heap (each emitter is a few KB).
- **Driver availability query is the real bottleneck**: `findByIsAvailableTrue()` is a full Postgres table scan on every dispatch. Fix: track availability in a Redis Set so you can do `SISMEMBER` instead of a DB query.
- **Kafka partitions**: one partition means one consumer processes all dispatch events serially. Add partitions and consumer instances to parallelize.
- **Postgres connection pool**: `acceptRide` is `@Transactional` and holds a connection. Under burst accepts, the default pool of 10 exhausts. Size to `(cores × 2) + spindles`.
- **Horizontal scaling**: stateless app instances behind a load balancer. Redis handles shared state (geo-index, pub/sub, surge cache).

### "Why SSE instead of WebSockets?"
SSE is unidirectional (server → client) and runs over plain HTTP — simpler to implement, works through proxies and load balancers without special config, and auto-reconnects in most client libraries. For this use case (server pushing location updates to rider, server pushing offers to driver), the client never needs to send data over the same connection, so WebSockets are unnecessary complexity. The one advantage of WebSockets is bidirectional communication over a single connection — useful if the driver needed to send location updates *and* receive offers on one socket, but here those are separate HTTP calls.

### "When would you use a message queue vs. direct service calls?"
Use a queue when: (1) the caller shouldn't wait for the work to finish (async fan-out, background jobs), (2) the receiver might be temporarily unavailable and you need durability, or (3) multiple consumers need to react to the same event independently. Use direct calls when: the result is needed immediately, failure should block the operation, or the added latency and operational cost of a queue isn't justified. In this codebase, dispatch is async (queue); ride state transitions are synchronous (direct calls).

### "How does the fare price get calculated?"
Haversine formula gives straight-line distance in km between pickup and dropoff coordinates. That's multiplied by a per-km rate plus a base fare, then multiplied by the surge multiplier (fetched from Redis, cached with TTL). The result is stored as `estimatedFare` at request time. Real Uber also factors in estimated time, vehicle type, and local market rates — the Haversine distance is a simplification since it ignores actual road routing.

---

## Surge pricing & fare locking

The surge multiplier is cached in Redis with a TTL. Fare is calculated once at request time (Haversine distance × surge multiplier) and stored as `estimatedFare`. At completion, `fare` is set to that locked-in value — no recalculation. This matches real Uber behaviour: the rider sees and pays the upfront price regardless of surge changes during the trip. The only staleness risk is within the surge cache TTL at the moment of request, which is acceptable.
