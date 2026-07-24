# pricing-service

A standalone gRPC service that quotes ride fares for [`uber_clone`](../README.md). Split out from the main app specifically to practice gRPC for synchronous, must-answer-now service-to-service calls: a rider needs a fare before they'll confirm a ride, so this can't be async like the rest of the dispatch pipeline.

## What it does

One RPC, defined in [`src/main/proto/pricing.proto`](src/main/proto/pricing.proto):

```proto
service PricingService {
  rpc GetFareQuote (FareQuoteRequest) returns (FareQuoteResponse);
}
```

Given a pickup/dropoff pair, it computes the Haversine distance, looks up (or computes and caches) the current surge multiplier for the pickup area, and returns `fare` and `surge_multiplier` as decimal strings — protobuf has no native decimal type, and a `double` would reintroduce the rounding problems `BigDecimal` exists to avoid.

Surge multiplier logic is ported as-is from `uber_clone`'s old `SurgeService`: it scales with the ratio of nearby pending ride requests to nearby available drivers, capped at 3x, cached in Redis for 30s per ~1km grid cell.

## A known simplification

This service reads `uber_clone`'s Postgres `rides` table and Redis driver geo-index (`drivers:locations`, `drivers:available`) **directly**, rather than maintaining its own copy of that state via events. In a real production system this would be a red flag — it couples the two services' schemas, so a column rename in `uber_clone` silently breaks this service. The "correct" version would have this service subscribe to ride/driver events and build its own local materialized view.

That's out of scope for what this project is practicing (gRPC mechanics, and the sync-vs-async architectural split), so the shortcut is intentional here — but worth being able to name out loud rather than gloss over.

## Running locally

**Prerequisites:** Java 21, the same Postgres and Redis `uber_clone` uses, already running (see the main [README](../README.md)).

```bash
./gradlew bootRun
```

Starts a gRPC server on port `6565` (override with `GRPC_PORT`). Not a web app — no HTTP port, no health endpoint; `uber_clone`'s ride requests will fail fast with an `UNAVAILABLE` error if this isn't running.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `GRPC_PORT` | `6565` | gRPC server port |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/uber_clone` | Same DB as `uber_clone` |
| `DATABASE_USER` | `jameskirk` | DB username |
| `DATABASE_PASSWORD` | `password` | DB password |

Redis defaults to `localhost:6379`, same instance as `uber_clone`.
