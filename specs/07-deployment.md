# 07 — Deployment

## 1. Artifacts

| File | Purpose |
|---|---|
| `Dockerfile` | Multi-stage build → minimal non-root JRE runtime image |
| `.dockerignore` | Excludes `target/`, `.git/`, IDE files, `scripts/`, `specs/` |
| `docker-compose.yml` | `postgres` + `app` |
| `.env.example` | Every variable from §3 with safe placeholder values (never real secrets) |
| `src/main/resources/application*.yml` | Externalized configuration (§4) |

## 2. Dockerfile requirements

```dockerfile
# syntax=docker/dockerfile:1
# Pin both images by digest at implementation time (tags shown for readability).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src/ src/
# Tests need Docker (Testcontainers), so they run via ./mvnw verify outside the image build.
RUN ./mvnw -B -q -DskipTests package && cp target/seat-reservation-service-*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --home /app app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar
USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# "exec" makes java PID 1, so it receives SIGTERM for graceful shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
```

Requirements: a non-root user; Java runs as PID 1; no secrets baked into the image; the build
succeeds from a clean checkout with only Docker installed; the same image is used locally and on the
host.

## 3. Environment-variable contract

Every variable is mapped explicitly in `application.yml` rather than relying on relaxed binding.

| Variable | Required | Default | Description |
|---|---|---|---|
| `PORT` | no | `8080` | HTTP port (hosts like Render inject it) |
| `SPRING_PROFILES_ACTIVE` | no | (none) | `local` (Compose), `prod` (hosted), `test` (tests) |
| `DB_URL` | **yes** | — | JDBC URL, e.g. `jdbc:postgresql://host:5432/seatres?sslmode=require` |
| `DB_USERNAME` | **yes** | — | |
| `DB_PASSWORD` | **yes** | — | |
| `DB_POOL_MAX_SIZE` | no | `20` | Hikari `maximum-pool-size` (§5) |
| `DB_POOL_CONNECTION_TIMEOUT_MS` | no | `60000` | Max wait in line for a pooled connection. A last resort: past it, a 503 (ADR-022) |
| `DB_LOCK_TIMEOUT_MS` | no | `30000` | PostgreSQL `lock_timeout` per connection (last resort, ADR-022) |
| `DB_STATEMENT_TIMEOUT_MS` | no | `35000` | PostgreSQL `statement_timeout` per connection; longer than `lock_timeout` |
| `APP_AUTH_JWT_SECRET` | **yes** (except in profile `local`, which has a dev default) | — | HS256 key, ≥ 32 bytes; generate with `openssl rand -base64 48`. Never shared |
| `APP_AUTH_ADMIN_KEY` | **yes** (except in profile `local`, which has a dev default) | — | Required in `X-Admin-Key` to get an ADMIN token from `POST /auth/token` (ADR-021). ≥ 32 bytes; generate with `openssl rand -base64 48`. Shared privately with the evaluator |
| `APP_IDEMPOTENCY_RETENTION` | no | `PT24H` | ISO-8601 duration, ≥ `PT24H` (`StartupChecks` refuses lower values, because `04` §5 promises keys are honoured for at least 24 h) |
| `JAVA_OPTS` | no | see Dockerfile | JVM flags |

`.env.example` lists all of these; `.env` is git-ignored.

Fixed values (constants, not configuration): JWT `iss = seat-reservation` and
`aud = seat-reservation-api`; demo token TTL 1 h; default `perUserLimit` 4; at most 3 transaction
attempts with a 45 s transaction timeout; cleanup every 15 min; the seats gauge tracks the 20 newest
shows and is read at scrape time over a 2-connection observability pool (ADR-023).

## 4. Spring configuration files

### 4.1 `application.yml` (base; all profiles)

```yaml
server:
  port: ${PORT:8080}
  shutdown: graceful
  tomcat:
    max-connections: 25000               # hold the evaluator's burst open while it queues (ADR-022)
    accept-count: 1000
  error:
    include-message: never
    include-stacktrace: never
    include-binding-errors: never

spring:
  application:
    name: seat-reservation-service
  lifecycle:
    timeout-per-shutdown-phase: 20s
  threads:
    virtual:
      enabled: true
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    hikari:
      pool-name: seatres
      maximum-pool-size: ${DB_POOL_MAX_SIZE:20}
      connection-timeout: ${DB_POOL_CONNECTION_TIMEOUT_MS:60000}
      connection-init-sql: >-
        SET lock_timeout = '${DB_LOCK_TIMEOUT_MS:30000}ms';
        SET statement_timeout = '${DB_STATEMENT_TIMEOUT_MS:35000}ms';
        SET idle_in_transaction_session_timeout = '30000ms'
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate.jdbc.time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration
    validate-on-migrate: true
    clean-disabled: true
    baseline-on-migrate: false
  jackson:
    deserialization:
      fail-on-unknown-properties: true
      accept-float-as-int: false
      fail-on-null-for-primitives: true
    mapper:
      allow-coercion-of-scalars: false
    serialization:
      write-dates-as-timestamps: false
    default-property-inclusion: non_null
    time-zone: UTC
  mvc:
    problemdetails:
      enabled: true

logging:
  structured:
    format:
      console: ecs

app:
  auth:
    jwt-secret: ${APP_AUTH_JWT_SECRET}
    admin-key: ${APP_AUTH_ADMIN_KEY}
  idempotency:
    retention: ${APP_IDEMPOTENCY_RETENTION:PT24H}
  observability:
    pool:                                # second, tiny pool used only by SeatGaugeCollector (ADR-023)
      maximum-pool-size: 2               # same DB_URL / DB_USERNAME / DB_PASSWORD as the main pool
      connection-timeout: 2000
      connection-init-sql: SET statement_timeout = '2000ms'

management:   # full block in 05 §2
  endpoints.web.exposure.include: health,prometheus
  endpoint.health.show-details: never
  endpoint.health.probes.enabled: true
  endpoint.health.probes.add-additional-paths: true
  endpoint.health.group.liveness.include: livenessState
  endpoint.health.group.readiness.include: readinessState,db
  metrics.distribution.percentiles-histogram.http.server.requests: true
```

The implementer must verify that PgJDBC executes the three-statement `connection-init-sql`. If it
doesn't, fall back to the JDBC URL parameter
`options=-c%20lock_timeout%3D30000%20-c%20statement_timeout%3D35000%20-c%20idle_in_transaction_session_timeout%3D30000`.
IT-FAIL-01 checks that `lock_timeout` is in effect on a pooled connection. (With this fallback the
timeouts live in `DB_URL`, so `DB_LOCK_TIMEOUT_MS` / `DB_STATEMENT_TIMEOUT_MS` stop applying, and the
observability pool would inherit them unless it gets its own URL.)

**Two DataSources:** `ObservabilityDataSourceConfig` builds the second Hikari pool. It must **not**
replace Spring Boot's auto-configured `DataSource`. Either expose it under a non-`DataSource` type
(e.g. inside the `SeatGaugeCollector` bean), or declare both pools explicitly and mark the main one
`@Primary`, so JPA, Flyway, and `JdbcTemplate` keep using the main pool.

### 4.2 `application-local.yml`

```yaml
app:
  auth:
    # DEV ONLY. StartupChecks refuses this value when the 'prod' profile is active.
    jwt-secret: ${APP_AUTH_JWT_SECRET:local-dev-only-hs256-secret-change-me-0123456789}
    # DEV ONLY. StartupChecks refuses this value when the 'prod' profile is active.
    admin-key: ${APP_AUTH_ADMIN_KEY:local-dev-only-admin-key-change-me-0123456789}
```

### 4.3 `application-prod.yml`

Empty apart from a comment: it activates the `StartupChecks` rules for `prod`. A JWT secret or admin
key shorter than 32 bytes, or equal to its local dev default, aborts startup.

## 5. PostgreSQL configuration and pool sizing

- Version: PostgreSQL 16 (15+ acceptable). The design relies on the default isolation,
  `read committed`.
- Rule: `DB_POOL_MAX_SIZE + 2 ≤ max_connections − superuser_reserved_connections − headroom (≈10)`.
  The `+ 2` is the observability pool used by the seats gauge (ADR-023).
  Check the managed plan's actual `max_connections` (`SHOW max_connections;`). Small plans can be
  well below the stock default of 100.
- Starting point: `DB_POOL_MAX_SIZE=20`. A bigger pool doesn't mean more throughput: the DB's CPU
  cores and fsync rate are the ceiling, and hot rows serialize regardless of pool size.
- `DB_POOL_CONNECTION_TIMEOUT_MS=60000`: bursts **queue** instead of being shed (ADR-022), because the
  assignment requires 0 × 5xx. The cost is tail latency. Size the plan so the 20k burst drains well
  inside 60 s, and verify it with run L-4. A lower value would shed sooner with 503s, and fail the
  burst.
- **Connection poolers:** don't point the app at a PgBouncer **transaction-mode** endpoint (some
  hosts' "pooled" URLs). The session-level `SET`s in `connection-init-sql` and server-side prepared
  statements assume a dedicated backend per connection. Use the direct connection string.
- TLS: append `sslmode=require` for hosted databases.

## 6. Docker Compose

```yaml
# docker-compose.yml
services:
  postgres:
    image: postgres:16-alpine        # pin digest at implementation
    environment:
      POSTGRES_DB: seatres
      POSTGRES_USER: seatres
      POSTGRES_PASSWORD: seatres-local-only
    ports: ["5432:5432"]
    volumes: ["pgdata:/var/lib/postgresql/data"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U seatres -d seatres"]
      interval: 2s
      timeout: 3s
      retries: 30

  app:
    build: .
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      SPRING_PROFILES_ACTIVE: local
      DB_URL: jdbc:postgresql://postgres:5432/seatres
      DB_USERNAME: seatres
      DB_PASSWORD: seatres-local-only
    ports: ["8080:8080"]
    ulimits:
      nofile: 65536                  # room for Tomcat's 25,000 queued connections (ADR-022)
    stop_grace_period: 30s

volumes:
  pgdata:
```

The local DB password is a throwaway value scoped to the local Compose network. It isn't a secret,
and the `prod` profile never uses it.

Commands: `docker compose up -d --build`, `docker compose logs -f app`, and
`docker compose down -v` to reset all data.

## 7. Public hosting

### 7.1 Options (no tier is assumed to be always-on, persistent, or big enough)

| Host | App | Database | Notes |
|---|---|---|---|
| **Render (recommended)** | Web Service from the `Dockerfile` | Render PostgreSQL (same region) | Health check path `/livez` (ADR-019); injects `PORT`; health-gated deploys. Free web instances spin down when idle, and free databases are time-limited. **Use paid instances for the evaluation window.** Check current terms. |
| Railway | Docker deploy | Railway PostgreSQL | Usage-based pricing |
| Fly.io | `fly deploy` with the Dockerfile | Fly-managed or external Postgres (direct URL) | HTTP health check on `/livez` (ADR-019) |

### 7.2 Render deployment steps

1. Push the repository to a Git host. The URL is created by the developer; none is assumed here.
2. Create a **PostgreSQL** instance (PG 16) in the same region as the app. It provides persistent
   storage. Note its internal host, port, database, user, and password.
3. Create a **Web Service**: "Docker" runtime, repository root, an instance with **≥ 1 GiB RAM**, and
   exactly **one** instance.
4. Set the health check path to `/livez` (ADR-019). Per Render's docs, checks time out after 5 s;
   Render stops routing after 15 s of failures and restarts the instance after 60 s. So the check
   must not depend on the DB or the pool: `/readyz` can exceed 5 s when the pool is saturated.
   Startup already proves DB connectivity, because Flyway runs before Tomcat accepts traffic. Keep
   `/readyz` for external monitoring (`05` §8, `SeatresNotReady`).
5. Set the environment variables (mark secrets as secret):
   - `SPRING_PROFILES_ACTIVE=prod`
   - `DB_URL=jdbc:postgresql://<internal-host>:5432/<db>?sslmode=require`. Render gives a
     `postgres://user:pass@host/db` URL; convert it into a JDBC URL plus separate
     `DB_USERNAME`/`DB_PASSWORD`. Use `sslmode=prefer` if the internal network doesn't offer TLS.
   - `DB_USERNAME`, `DB_PASSWORD`
   - `APP_AUTH_JWT_SECRET` (output of `openssl rand -base64 48`)
   - `APP_AUTH_ADMIN_KEY` (a different `openssl rand -base64 48` value)
   - `DB_POOL_MAX_SIZE`, sized per §5
6. Deploy, and check the logs for Flyway success and `Started SeatReservationApplication`.
7. Run the verification checklist (§11).
8. Record the public URL in `README.md` and `WRITEUP.md`. The evaluator gets USER tokens from
   `POST /auth/token` with no extra credential. For ADMIN tokens (creating shows), share the **admin
   key** privately, never in the repo, and rotate it after the evaluation. The JWT signing secret is
   never shared (ADR-021).

### 7.3 Realistic limitations and mitigations

| Limitation | Effect | Mitigation |
|---|---|---|
| Small instance CPU/RAM | Lower throughput; GC pauses | ≥ 1 GiB RAM; `MaxRAMPercentage=75`; measure and report honestly |
| Idle spin-down / cold start | The first request after idle is slow or times out at the edge | Paid always-on instance during evaluation; warm up with `/readyz` and one GET before the burst |
| Small DB plan `max_connections` / IOPS | Long queues under the 20k burst; a 503 only if a last-resort timeout is reached | Pool sizing per §5; choose the plan **by measurement** so L-4 shows 0 × 5xx (A-12, ADR-022) |
| Edge proxy limits / timeouts | 502/504/429 from the platform, not the app | Counted separately in the burst output; moderate concurrency (200) for deployed runs |
| Free DB expiry / no backups | Data loss | Paid DB for the evaluation window; the burst creates its own data, so it's reproducible |

The assignment requires **zero 5xx** across the evaluator's ~20,000-request burst (NFR-1). The design
queues overload instead of shedding it (ADR-022), but no design can guarantee that on an undersized
plan. So the plan is chosen by measurement: run L-4 (20k against the deployed URL) and require
0 × 5xx. If it fails, scale the instance or the DB, or tune the pool, or adopt the fast-path
fallback in `04` §12, then rerun. Also check that the host's edge request timeout and the container's
open-file limit (≥ 25,000 connections) don't cut the queue short.

## 8. Secrets management

- No real secret is committed. The only committed "secrets" are the dev-only values in
  `application-local.yml` and `docker-compose.yml`. They are labelled as such, and the `prod`
  profile refuses them.
- Hosted secrets live in the host's environment/secret store. To rotate the signing secret, set a new
  `APP_AUTH_JWT_SECRET` and redeploy: existing tokens become invalid, so get new ones from
  `POST /auth/token`. To revoke the evaluator's admin access, set a new `APP_AUTH_ADMIN_KEY` and
  redeploy (already-issued ADMIN tokens stay valid until they expire, at most 1 h).
- `.gitignore` includes `.env`, `*.pem`, `*.key`, and `target/`.
- Logs never print secrets (05 §7.1), and `server.error.include-*` are set to `never`.

## 9. Startup (Flyway, cold start) and graceful shutdown

Startup sequence: JVM start → Spring context → Hikari connections → Flyway validate/migrate → JPA
schema validation → Tomcat start → readiness `ACCEPTING_TRAFFIC`.

- Migrations run automatically at startup, before the app accepts traffic. A failed migration aborts
  startup with a non-zero exit, and the host keeps the previous healthy release (health-gated
  deploys) or marks the deploy as failed.
- Cold start takes seconds to tens of seconds on small instances; measure it and record it in
  `WRITEUP.md`. The host must allow ≥ 60 s for startup before the first successful health check.

Graceful shutdown (SIGTERM):

1. Readiness → `REFUSING_TRAFFIC` (`/readyz` 503); the host stops routing new requests.
2. Tomcat stops accepting connections. In-flight requests get up to
   `spring.lifecycle.timeout-per-shutdown-phase=20s` to finish; transactions take seconds at most.
3. A request still running at the timeout is aborted. Its transaction rolls back when the connection
   closes, and the client retries with the same key.
4. Hikari closes its connections, and the JVM exits with 0.
5. `stop_grace_period: 30s` (Compose) and the host's termination grace period must exceed 20 s.

## 10. Clean checkout, reproducible startup, and test-data cleanup

```bash
git clone <repo-url> && cd <repo>
./mvnw -v                          # Maven Wrapper; local builds need only JDK 21
./mvnw verify                      # requires Docker for Testcontainers
docker compose up -d --build       # app on http://localhost:8080
curl -fsS localhost:8080/readyz
java scripts/BurstTest.java --scenario all --base-url http://localhost:8080
```

Manual purge of burst data on a deployed DB. Use it deliberately only, inside `psql`, after checking
the row counts:

```sql
BEGIN;
UPDATE seats SET status = 'AVAILABLE', reservation_id = NULL WHERE show_id IN (SELECT id FROM shows WHERE name LIKE 'burst-%');
DELETE FROM reservation_seats WHERE show_id IN (SELECT id FROM shows WHERE name LIKE 'burst-%');
DELETE FROM reservations WHERE show_id IN (SELECT id FROM shows WHERE name LIKE 'burst-%');
DELETE FROM user_show_quotas WHERE show_id IN (SELECT id FROM shows WHERE name LIKE 'burst-%');
DELETE FROM seats WHERE show_id IN (SELECT id FROM shows WHERE name LIKE 'burst-%');
DELETE FROM shows WHERE name LIKE 'burst-%';
COMMIT;
```

Idempotency records don't reference shows or reservations; they expire through retention.

## 11. Deployment verification checklist

| # | Check | Command / evidence |
|---|---|---|
| 1 | The image builds from a clean checkout | `docker build .` exits 0 |
| 2 | The local stack is ready | `curl -fsS localhost:8080/readyz` → `{"status":"UP"}` |
| 3 | Hosted readiness | `curl -fsS https://<host>/readyz` → 200 |
| 4 | Liveness is independent of the DB | Locally: `docker compose stop postgres` → `/livez` 200, `/readyz` 503; restart the DB → `/readyz` 200 |
| 5 | Auth is enforced | `curl -i -X POST https://<host>/shows` → 401 problem JSON |
| 6 | Metrics are exposed | `curl -fsS https://<host>/actuator/prometheus` contains `seatres_reservations_confirmed_total`, and `seatres_seats{show_id=…}` for a fresh show matches `GET /shows/{id}` |
| 7 | No other actuator endpoints | `/actuator/env` without a token → 401; with a valid token → 404 |
| 8 | Happy path | Get tokens from `POST /auth/token` (ADMIN with the admin key), create a show, reserve, GET, cancel (`02` §7) |
| 9 | Burst correctness (hosted) | `BurstTest --scenario all --concurrency 200` **and** `--scenario pool-burst --requests 20000 --concurrency 300` → `ASSERTIONS: PASS`, with 0 × 5xx |
| 10 | No invariant violations | `seatres_invariant_violations_total` = 0 |
| 11 | Logs are JSON, contain `requestId`, and contain no tokens | Host log viewer |
| 12 | Graceful shutdown | Redeploy during light load: no 500s; at most retryable 503s |
| 13 | No secrets in Git | `git grep -nE 'APP_AUTH_(JWT_SECRET|ADMIN_KEY)=\S|BEGIN PRIVATE KEY'` shows only placeholders |
| 14 | Token endpoint | `POST /auth/token` without `X-Admin-Key` for role `ADMIN` → 403; with the key → 200 |
