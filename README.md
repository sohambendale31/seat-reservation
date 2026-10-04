# Seat Reservation Service

A JSON HTTP API for reserving assigned seats for shows. It stays correct when thousands of users
try to reserve the same seats at the same time.

> **Status:** phases P0–P6 of [specs/08-implementation-plan.md](specs/08-implementation-plan.md) are
> implemented and verified, so the whole API below works: tokens, show creation, show reads,
> reserve and cancel, plus the metrics and structured logs. `./mvnw verify` and `docker compose up`
> are green.
> The burst script runs green against the local stack, including a 20,000-request run with
> **0 × 5xx**; raw output is in [docs/evidence/](docs/evidence/).
> **Not built yet:** the deployment (P7), so the deployed-URL commands below cannot be run yet.
> Live URL: https://seat-reservation-production-6d97.up.railway.app

## Guarantees

- A seat is never confirmed to two users (PostgreSQL row locks + constraints).
- A user holds at most `perUserLimit` seats per show (default 4). This is enforced through a
  per-(show, user) quota row and a DB CHECK constraint.
- Multi-seat requests are all-or-nothing.
- `Idempotency-Key` makes retries safe. The same key with the same request replays the original
  outcome; the same key with a different request returns `409 IDEMPOTENCY_KEY_REUSED`.
- Domain conflicts are `4xx` with stable codes. Overload waits in line instead of failing, and a
  20k burst against the deployed URL must show 0 × 5xx (ADR-022). A `503` means a real dependency
  failure and is safe to retry.
- `available + held + confirmed = total` for every show, by construction.
- Money is integer paise, and identity comes only from the JWT.

Design details: [specs/04-concurrency-and-correctness.md](specs/04-concurrency-and-correctness.md).

## Architecture

One Spring Boot 3 (Java 21) application instance in front of one PostgreSQL 16 database, which
decides every allocation. Concurrency-critical SQL uses `JdbcTemplate` (explicit `FOR UPDATE`,
`ON CONFLICT`, row-count assertions). Flyway manages the schema, Micrometer exports Prometheus
metrics, and logs are ECS JSON.

```
client ─▶ edge/TLS ─▶ app ─▶ HikariCP ─▶ PostgreSQL 16
```

| Spec | Topic |
|---|---|
| [00-overview](specs/00-overview.md) | Scope, requirements, threat model |
| [01-architecture](specs/01-architecture.md) | Stack, packages, request lifecycles, diagrams |
| [02-api-contract](specs/02-api-contract.md) | Endpoints, errors, examples |
| [03-data-model](specs/03-data-model.md) | DDL, state machines, invariants |
| [04-concurrency-and-correctness](specs/04-concurrency-and-correctness.md) | Locking and transactions |
| [05-observability](specs/05-observability.md) | Probes, metrics, logs, alerts |
| [06-testing-and-load](specs/06-testing-and-load.md) | Tests and burst script |
| [07-deployment](specs/07-deployment.md) | Docker, env vars, hosting |
| [08-implementation-plan](specs/08-implementation-plan.md) | Delivery plan |
| [09-writeup-outline](specs/09-writeup-outline.md) | Outline for WRITEUP.md |
| [10-decision-log](specs/10-decision-log.md) | ADRs |

## Prerequisites

- JDK 21 (for `./mvnw` and the scripts)
- Docker with Compose v2 (for the local stack and Testcontainers)
- Optional: `curl`, `jq`

## Run locally

```bash
docker compose up -d --build
curl -fsS localhost:8080/readyz          # {"status":"UP"}
```

Compose starts PostgreSQL 16 and the app with profile `local`, which uses a dev-only JWT secret and
a dev-only admin key (`local-dev-only-admin-key-change-me-0123456789`).
Reset all data with `docker compose down -v`.

## Configuration

The full contract is in [specs/07-deployment.md §3](specs/07-deployment.md).

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | — | PostgreSQL connection (JDBC URL) |
| `APP_AUTH_JWT_SECRET` | — (a dev default in `local` only) | HS256 signing secret, ≥ 32 bytes. Never shared |
| `APP_AUTH_ADMIN_KEY` | — (a dev default in `local` only) | Required to get an ADMIN token from `POST /auth/token`, ≥ 32 bytes |
| `DB_POOL_MAX_SIZE` | `20` | Connection pool size |
| `DB_POOL_CONNECTION_TIMEOUT_MS` | `60000` | How long a request waits in line for a connection (last resort before a 503) |
| `DB_LOCK_TIMEOUT_MS` / `DB_STATEMENT_TIMEOUT_MS` | `30000` / `35000` | PostgreSQL last-resort timeouts |
| `APP_IDEMPOTENCY_RETENTION` | `PT24H` | How long idempotency keys are kept |

To override locally, copy `.env.example` to `.env`. Never commit real secrets.

## Authentication

Requests carry bearer JWTs (HS256) with the claims `sub` (user id), `roles` (`USER`, `ADMIN`),
`iss=seat-reservation`, `aud=seat-reservation-api`, and `exp`. Identity is never read from request
bodies. Get tokens from the demo token endpoint (ADR-021). USER tokens need nothing else; an ADMIN
token also needs the admin key:

```bash
ADMIN_KEY='local-dev-only-admin-key-change-me-0123456789'   # local default; for a deployment, use the shared admin key
ADMIN=$(curl -s localhost:8080/auth/token -H 'Content-Type: application/json' -H "X-Admin-Key: $ADMIN_KEY" \
  -d '{"sub":"admin-1","roles":["ADMIN"]}' | jq -r .accessToken)
ALICE=$(curl -s localhost:8080/auth/token -H 'Content-Type: application/json' \
  -d '{"sub":"alice"}' | jq -r .accessToken)
```

This is a **demo** identity provider: anyone can get a USER token for any user id. The JWT signing
secret is never shared. `java scripts/MintToken.java` still works offline if you have the secret.

## API

Open the deployed URL (or `localhost:8080`) in a browser: `/` redirects to an interactive Swagger UI
that lists every endpoint. Get a token from `POST /auth/token`, click **Authorize**, paste it, and
the rest of the API is callable from the page. Creating a show needs an ADMIN token, which requires
the admin key.

| Method | Path | Role |
|---|---|---|
| GET | `/` → `/swagger-ui.html` | public (interactive docs) |
| GET | `/v3/api-docs` | public (OpenAPI document) |
| POST | `/auth/token` | public (ADMIN role needs `X-Admin-Key`) |
| POST | `/shows` | ADMIN |
| GET | `/shows/{id}` | USER/ADMIN |
| POST | `/shows/{id}/reserve` (header `Idempotency-Key` required) | USER |
| POST | `/reservations/{id}/cancel` | USER (owner) |
| GET | `/livez`, `/readyz`, `/actuator/prometheus` | public |

```bash
SHOW=$(curl -s localhost:8080/shows -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"name":"Demo","startsAt":"2026-12-01T19:30:00+05:30","perUserLimit":4,
       "rows":[{"row":"A","seatCount":10,"pricePaise":25000}]}' | jq -r .id)

KEY=$(uuidgen)
curl -s -X POST localhost:8080/shows/$SHOW/reserve -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d '{"seats":["A1","A2"]}'
# Retrying with the same KEY replays the same response (header Idempotent-Replayed: true)

curl -s localhost:8080/shows/$SHOW -H "Authorization: Bearer $ALICE"
curl -s -X POST localhost:8080/reservations/<reservationId>/cancel -H "Authorization: Bearer $ALICE"
```

Errors are RFC 9457 Problem Details with a stable `code` (e.g. `SEAT_UNAVAILABLE`,
`USER_LIMIT_EXCEEDED`, `IDEMPOTENCY_KEY_REUSED`); see [02-api-contract](specs/02-api-contract.md).

**Client retry rule:** after a timeout, a transport error, or a `503`, retry with the **same**
`Idempotency-Key`. Use a new key only for a new attempt.

## Database migrations

Flyway runs automatically at startup (`src/main/resources/db/migration`, starting with
`V1__baseline_schema.sql`). Hibernate only validates the schema. Migrations are forward-only.

## Tests

```bash
./mvnw test                          # unit tests (no Docker)
./mvnw verify                        # + Testcontainers integration and concurrency tests (Docker)
./mvnw verify -DexcludedGroups=slow  # skip the DB-pause test
```

## Burst / load script

```bash
java scripts/BurstTest.java --scenario all --base-url http://localhost:8080
java scripts/BurstTest.java --scenario pool-burst --requests 20000 --concurrency 500
BASE_URL=https://<deployed-host> ADMIN_KEY='<deployment admin key>' \
  java scripts/BurstTest.java --scenario all --concurrency 200
BASE_URL=https://<deployed-host> ADMIN_KEY='<deployment admin key>' \
  java scripts/BurstTest.java --scenario pool-burst --requests 20000 --concurrency 300
```

Scenarios: `hot-seat`, `pool-burst`, `same-key`, `user-limit`. The script gets its tokens from
`POST /auth/token`. Each scenario ends with a reconciliation against `GET /shows/{id}` and against the
per-show `seatres_seats` gauge, and asserts 0 × 5xx. The script prints counts for 201, 409 (by code), other 4xx, 5xx,
timeouts, and transport errors. It separates HTTP responses from **logical** reservations (a replay
doesn't count twice), resolves ambiguous outcomes by retrying with the same key, and exits non-zero
if a correctness assertion fails. `--concurrency` limits in-flight requests; it does not open 20,000
simultaneous connections.

Test data: the script only creates new `burst-*` shows. Reset locally with `docker compose down -v`;
for a deployed DB, see the purge in [07-deployment §10](specs/07-deployment.md).

## Deployment

The Docker image runs anywhere. The chosen host is **Railway**: one Docker service plus a Railway
PostgreSQL service in the same project. Four Railway defaults must be overridden or they break
guarantees this service makes — the draining grace is 0 (SIGKILL mid-transaction), a slept service
answers 502 (a 5xx), deployments overlap (doubling database connections), and the healthcheck runs
only at deploy time. Steps, the `railway.json` to commit, limitations, and a verification checklist
are in [specs/07-deployment.md §7](specs/07-deployment.md).

Live URL: <https://seat-reservation-production-6d97.up.railway.app>

## Metrics and logs

### Metrics — public, no token needed

| URL | What it gives you |
|---|---|
| [`/actuator/prometheus`](https://seat-reservation-production-6d97.up.railway.app/actuator/prometheus) | The full Prometheus scrape |
| [`/actuator/health`](https://seat-reservation-production-6d97.up.railway.app/actuator/health), [`/livez`](https://seat-reservation-production-6d97.up.railway.app/livez), [`/readyz`](https://seat-reservation-production-6d97.up.railway.app/readyz) | Liveness and readiness |
| [`/actuator`](https://seat-reservation-production-6d97.up.railway.app/actuator) | Index of the two exposed endpoints |

They are also listed in the Swagger UI, so they are reachable from the live URL without reading this
file. Everything else under `/actuator` is unexposed and answers 401/404.

The custom meters are `seatres_reservations_confirmed_total`,
`seatres_reservation_declines_total{reason}`, `seatres_idempotency_replays_total{status}`,
`seatres_invariant_violations_total{check}`, and `seatres_seats{show_id,status}`. Idempotent replays
are never counted as new confirmations. `seatres_seats` covers the 20 newest shows and is read from
PostgreSQL at scrape time, so it matches `GET /shows/{id}` even during a burst (ADR-023). It emits
no series until at least one show exists.

A quick way to watch a show while you load it:

```bash
curl -s https://seat-reservation-production-6d97.up.railway.app/actuator/prometheus | grep seatres_
```

### Logs — stdout, so read them from the host or from this repo

Logs are ECS JSON on stdout, which the host collects; there is deliberately **no HTTP endpoint that
serves them**, since that would publish user references and request ids to anyone. Three ways to see
them:

1. **Committed log captures**, for anyone without console access:
   - [`deployed-logs-2026-10-04.txt`](docs/evidence/deployed-logs-2026-10-04.txt) — 5,000 lines from
     the live service, covering the L-3 and L-4 runs. That is everything Railway will return.
   - [`local-20k-full-logs-2026-10-04.txt`](docs/evidence/local-20k-full-logs-2026-10-04.txt) — the
     **complete** log set for a 20,000-request burst: 20,000 lines for 20,000 requests, verified
     against the server's own counters, nothing dropped. Captured from the local stack, because
     Railway cannot return a set this size (see the note below).
   - [`deployed-build-log-2026-10-04.txt`](docs/evidence/deployed-build-log-2026-10-04.txt) — build log.
2. **With access to the host:** `railway logs --lines 5000` (or the service's Logs tab).
3. **Correlate a specific call:** every response carries `X-Request-Id`, and every error body repeats
   it as `requestId`. Quote that id and it can be found in the logs.

Each line carries `requestId`, plus `showId`, `userRef` and `idemKeyRef` where they apply. `userRef`
and `idemKeyRef` are SHA-256 prefixes: the raw subject and the raw idempotency key are never logged,
and neither are tokens, the `Authorization` header, the admin key, or request bodies.

Events worth grepping: `reservation.confirmed`, `reservation.declined`, `reservation.replayed`,
`reservation.cancelled`, `db.transient_failure`, `db.outcome_unknown`, `invariant.violation`.

> **Why the deployed logs are incomplete under load.** Two independent caps, both the platform's:
> Railway *drops* application logs above ~500/sec per replica (it says so inline), and `railway logs`
> will only ever *return* 5,000 lines — `--lines 10000` is rejected, and redirecting its streaming
> form to a file yields a 500-line snapshot. A 20,000-request burst therefore cannot be captured
> from the console at all. The metrics are aggregates and stay exact, which is why they, not the
> logs, are the authoritative count. A deployment that needs complete logs should ship them to an
> external sink via a log drain rather than read them from the console.

## Write-up

See [WRITEUP.md](WRITEUP.md).
