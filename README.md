# Kotlin Backend Boilerplate

A personal starting point for Kotlin backend apps. Wires together the services I reach for most often so I can clone and build on top rather than scaffold from scratch.

## Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.2 / JVM 21 |
| Framework | Spring Boot 3.4 |
| Database | PostgreSQL (Spring Data JPA) |
| Cache | Redis (Spring Data Redis) |
| Messaging | Kafka (Spring Kafka) |
| Auth | JWT (JJWT 0.12) via HttpOnly cookies |
| Build | Gradle (Kotlin DSL) |

## What's included

**Auth** — register, login, and `/auth/me`. JWTs are issued on login/register, stored as HttpOnly cookies (30-day expiry by default), and validated on every request via a servlet filter. Passwords are BCrypt-hashed.

**Security** — Spring Security in stateless mode. `/auth/register` and `/auth/login` are public; everything else requires a valid JWT.

**User model** — a single `User` entity backed by Postgres with JPA auto-DDL so the schema creates itself on first boot.

**Redis utilities** — helper functions for list-based caching with TTL and size capping. Stub these out into real cache keys for whatever domain you're building.

**Kafka producer/consumer** — a typed event producer and a `@KafkaListener` consumer stubbed to the `post-created` topic. Drop your async fan-out or processing logic into the consumer body.

**Health endpoint** — `GET /health` returns 200, useful for load balancer checks.

## Running locally

**Prerequisites:** Java 21, PostgreSQL, Redis, Kafka running locally.

```bash
./gradlew bootRun
```

The app starts on port `8080`. Default datasource points to `localhost:5432/twitter` — change the database name in `application.properties` or override via env vars.

## Configuration

All secrets and connection strings are externalised. Set these env vars to override the defaults:

| Variable | Default | Description |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/twitter` | JDBC connection string |
| `DATABASE_USER` | `jameskirk` | DB username |
| `DATABASE_PASSWORD` | `password` | DB password |
| `JWT_SECRET` | `change-me-...` | HMAC signing key — **change in prod** |
| `COOKIE_SECURE` | `false` | Set `true` in prod (requires HTTPS) |

Kafka defaults to `localhost:9092` and Redis to `localhost:6379`. Override in `application.properties` as needed.

## API

```
POST /auth/register   { username, password }  → 201 + sets auth cookie
POST /auth/login      { username, password }  → 200 + sets auth cookie
GET  /auth/me                                 → { userId }
GET  /health                                  → 200
```

All other routes require the `auth_token` cookie (or `Authorization: Bearer <token>` header).

## Adapting for a new project

1. Rename the package from `org.example` to your own.
2. Swap the database name in `application.properties`.
3. Replace the `RedisUtils` stub keys with your domain's cache keys.
4. Replace the Kafka `post-created` topic and consumer body with your event types.
5. Add entities, repositories, services, and controllers on top.
