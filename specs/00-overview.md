# 00 — Overview: Concurrency-Safe Seat Reservation Service

> Status: **Specification (pre-implementation)**. Nothing in this document claims that code, tests,
> benchmarks, or deployments exist. Results are produced later and recorded in `WRITEUP.md`.

Document map:

| File | Purpose |
|---|---|
| `00-overview.md` | Problem, scope, requirements, assumptions, acceptance criteria, threat model |
| `01-architecture.md` | Stack, versions, packages, components, request lifecycles, diagrams, failure modes |
| `02-api-contract.md` | Endpoints, auth, JSON schemas, status codes, error codes, examples |
| `03-data-model.md` | Executable DDL, state machines, invariants, idempotency storage, Flyway |
| `04-concurrency-and-correctness.md` | **Authoritative** locking/transaction design and race walkthroughs |
| `05-observability.md` | Health, metrics, logs, dashboards, alerts |
| `06-testing-and-load.md` | Test strategy, concurrency test matrix, burst script |
| `07-deployment.md` | Docker, Compose, env contract, hosting, secrets, verification |
| `08-implementation-plan.md` | One-day phased plan, checkpoints, risks |
| `09-writeup-outline.md` | Outline for the final `WRITEUP.md` |
| `10-decision-log.md` | Architecture decision records |

When documents disagree, precedence is `04` > `03` > `02` > the rest. A disagreement is a bug in the
spec and must be fixed in every affected file, not resolved silently in code.

---

## 1. Problem statement

Build a JSON HTTP API that lets authenticated users reserve **assigned seats** for **shows**. During
an on-sale event, thousands of users may try to reserve the same seats at the same time. The service
must never confirm a seat to more than one user. It must enforce a per-user seat limit per show under
concurrency, and it must make retries safe through idempotency keys.

## 2. Goals

1. **Safety:** no double-selling, no limit overrun, and no duplicated logical reservation under any
   interleaving of concurrent requests.
2. **Honest outcomes:** domain conflicts return `4xx` with stable machine-readable codes.
   Infrastructure failures return `5xx`/`503` and are never disguised as business conflicts.
3. **Safe retries:** repeating a request with the same `Idempotency-Key` never causes a second
   allocation, and the client receives the originally decided outcome.
4. **Operability:** liveness and readiness probes, Prometheus metrics, structured JSON logs.
5. **Reproducibility:** clean checkout → `docker compose up` → working service. The test suite runs
   on Testcontainers, and a reproducible burst script runs against local and deployed URLs.

## 3. Scope

In scope:

- Admin show creation with a seat layout (rows × seat counts, integer paise prices).
- Seat reservation (1–10 seats per request, all-or-nothing), with a per-user per-show seat limit
  (default 4).
- Cancellation of a reservation by its owner.
- A show read model with seat-level status and derived counts.
- JWT authentication (HS256) and role-based authorization (`USER`, `ADMIN`), plus a demo token
  endpoint `POST /auth/token` so callers can get tokens over HTTP (ADR-021).
- Health probes, Prometheus metrics, structured logs, and idempotency retention cleanup.
- A Docker image, Docker Compose, deployment to a public host, and a burst/load script.

## 4. Non-goals (out of scope for v1)

- Payments, refunds, taxes, and currencies other than INR. Prices are stored and summed in paise; no
  money moves.
- Temporary **holds with expiry**. The `HELD` seat state exists in the schema and is counted in the
  invariant, but **no v1 code path writes `HELD`** (A-2).
- Running and guaranteeing correctness across multiple application instances. v1 runs **one**
  instance.
- Waiting rooms, fairness guarantees, and bot protection.
- Show update/delete, seat map editing, and sale windows.
- User registration, passwords, and a real identity provider. The demo token endpoint
  (`POST /auth/token`, `02` §5.5) issues tokens for any user id; ADMIN tokens need the admin key.
- `GET /reservations/{id}` and reservation listing.
- Redis, Kafka, distributed locks, read replicas.
- Exactly-once network delivery, which is impossible. The service provides an exactly-once
  **effect** per idempotency key.

## 5. Functional requirements

| ID | Requirement |
|---|---|
| FR-1 | `POST /shows` (ADMIN) creates a show and all its seats atomically. |
| FR-2 | `POST /shows/{id}/reserve` (USER) reserves 1–10 seats by label, all-or-nothing, immediately `CONFIRMED`. |
| FR-3 | A user may hold at most `perUserLimit` seats per show (default 4, range 1–10) across all their active reservations. |
| FR-4 | `Idempotency-Key` is **required** on reserve. |
| FR-5 | Same key + same canonical request → replay of the originally decided response; no new allocation. |
| FR-6 | Same key + different canonical request → `409 IDEMPOTENCY_KEY_REUSED`; no allocation. |
| FR-7 | `POST /reservations/{id}/cancel` (owner only) releases exactly that reservation's seats. Repeat cancels are a no-op `200`. |
| FR-8 | `GET /shows/{id}` returns show details, derived counts (`total/available/held/confirmed`), and per-seat status. |
| FR-9 | Liveness `/livez`, readiness `/readyz` (fails when PostgreSQL is unreachable), metrics `/actuator/prometheus`. |
| FR-10 | Identity (user id, roles) comes **only** from the verified JWT. Unknown body fields (e.g. `userId`) are rejected with 400. |
| FR-11 | Money is integer paise (`bigint`/`long`). JSON numbers with fractions in money fields are rejected. |
| FR-12 | `POST /auth/token` (public) issues an HS256 JWT for a given `sub` with role `USER`; role `ADMIN` additionally requires the `X-Admin-Key` header. |

## 6. Non-functional requirements

| ID | Requirement | Measure |
|---|---|---|
| NFR-1 | **Zero 5xx** across the evaluator's ~20k burst | CT-01: 0 × 5xx. Every burst scenario, local and deployed, including the 20k `pool-burst` runs L-2 and L-4: 0 × 5xx on the first attempt (ADR-022) |
| NFR-2 | Bounded resource use under bursts, without shedding load | Bounded DB pool; excess requests **wait in line** for a connection or a lock. The pool, lock, and statement timeouts are generous last-resort limits, sized so they aren't reached at the evaluated load. A 503 means a dependency failure or an exceeded last-resort timeout, and either one fails the burst (ADR-022) |
| NFR-3 | Observability | Required metrics present with bounded label cardinality |
| NFR-4 | Startup from clean checkout | `docker compose up --build` reaches ready with no manual steps |
| NFR-5 | Testability | `./mvnw verify` runs unit + Testcontainers integration + concurrency tests |
| NFR-6 | Security | No real secrets in the repo (dev-only defaults are refused in the `prod` profile); actuator locked down |
| NFR-7 | Performance | **No pre-committed throughput or latency number.** Measured by the burst script and reported honestly in `WRITEUP.md`. Zero 5xx (NFR-1) is a hard requirement, not a soft one |

## 7. Assumptions and constraints

| ID | Assumption / constraint | Rationale |
|---|---|---|
| A-1 | The stack is fixed: Java 21, Spring Boot 3.x (3.5.x line), PostgreSQL 16, Maven, Docker. | Assignment mandate. |
| A-2 | Reserve **confirms immediately** (`AVAILABLE → CONFIRMED`). `HELD` exists in the seat status domain and in `available + held + confirmed = total`, but is always 0 in v1. | No confirm/payment endpoint is required. |
| A-3 | The per-user limit counts **seats** (not reservations) in `CONFIRMED` state for that user in that show. Cancelled seats no longer count. | Matches "seat limit". |
| A-4 | `perUserLimit` is set at show creation and is immutable. The quota row copies it (`seat_limit`) so a DB CHECK can enforce it. | Shows are immutable in v1. |
| A-5 | A user is identified by JWT `sub`: an opaque string, 1–64 chars, matching `^[A-Za-z0-9][A-Za-z0-9._:@-]{0,63}$`. There is no users table. | Identity is external. |
| A-6 | Seats are addressed by label `<ROW><NUMBER>` (e.g. `A1`). Rows match `^[A-Z]{1,3}$`; numbers are 1–500. | Human-friendly and unique per show. |
| A-7 | A reserve request with duplicate labels is invalid (400). Seat order in the request is irrelevant; the fingerprint sorts labels. | Avoids ambiguity. |
| A-8 | Idempotency keys are scoped per **user** (JWT `sub`). The fingerprint binds the key to the target show and the canonical seat set. Retention is ≥ 24 h. | Prevents cross-user collisions and leakage; detects reuse strictly. |
| A-9 | Outcomes decided **inside** the reservation transaction (201, 404, 409 seat/limit, 422) are stored and replayed. Outcomes decided **before** the transaction (400/401/403/415) and 5xx are not stored. | Deterministic replays; a 5xx leaves no trace, so retries re-execute. |
| A-10 | All amounts are INR, in paise. | Scope. |
| A-11 | Max 10,000 seats per show, 200 rows, and 500 seats per row. | Bounded creation transaction and response size. |
| A-12 | The hosting plan is chosen **by measurement**: it must absorb a ~20k burst with 0 × 5xx (run L-4). A free tier is not assumed to be always-on, persistent, or big enough. | The assignment grades zero 5xx under the evaluator's own burst; see `07` §7. |
| A-13 | Tokens come from the demo endpoint `POST /auth/token`. USER tokens are free to obtain; ADMIN tokens need the admin key (`APP_AUTH_ADMIN_KEY`), which the developer shares privately with the evaluator and rotates afterwards. The JWT signing secret is never shared. `scripts/MintToken.java` remains for offline use. | The evaluator must be able to get tokens for many users with plain HTTP (ADR-021). |

## 8. Acceptance criteria

Hard (must pass; correctness):

| ID | Criterion | Verified by |
|---|---|---|
| AC-1 | No seat is ever referenced by more than one active reservation | DB constraints + CT-01/CT-05 + reconciliation R3/R4/R6 |
| AC-2 | For every (show, user): confirmed seats ≤ `perUserLimit` | DB CHECK + CT-02 + reconciliation R5 |
| AC-3 | Same key + same body → exactly one logical reservation; all responses carry the same `reservationId` | CT-03, burst `same-key` |
| AC-4 | Same key + different body → 409 `IDEMPOTENCY_KEY_REUSED`, no allocation | CT-04 |
| AC-5 | Seat conflict → 409 `SEAT_UNAVAILABLE`; limit → 409 `USER_LIMIT_EXCEEDED`; never 5xx, including under the 20k burst | CT-01/02, burst (0 × 5xx, ADR-022) |
| AC-6 | `available + held + confirmed = total` for every show at all times | Derived by construction; R1; burst reconcile |
| AC-7 | Multi-seat requests are all-or-nothing | IT-RES-04, CT-05 |
| AC-8 | Cancel releases only the reservation's own seats; repeat cancel is a no-op; non-owner gets 404 | IT-CAN-*, CT-06/07 |
| AC-9 | Identity only from JWT; body `userId` rejected | AUTH-06 |
| AC-10 | Readiness fails when PostgreSQL is unreachable; liveness does not | OBS-02/03 |
| AC-11 | `/actuator/prometheus` exposes the metric names in `05` §4 | OBS-05 |
| AC-12 | Clean checkout: `./mvnw verify` green (Docker available) and `docker compose up --build` becomes ready | MIG-01, `07` §11 |
| AC-13 | Deployed public URL serves `/readyz` = UP and passes the burst script's correctness assertions, including the 20k `pool-burst` with 0 × 5xx | `07` §11, `06` §8 |
| AC-14 | `POST /auth/token` issues working USER tokens; an ADMIN token is issued only with the correct admin key | AUTH-11…13 |

Soft (measured and reported, not promised): p50/p95/p99 latency and throughput under the 20k burst,
for each environment tested. The 5xx count is **not** soft: it must be 0 (NFR-1).

## 9. Architecture summary and key decisions

A **modular monolith** (one Spring Boot app instance) in front of **one PostgreSQL** database that
decides every allocation.

```
Client ──HTTPS──▶ Host edge / TLS proxy ──HTTP──▶ App [Spring MVC, virtual threads]
                                                     │  HikariCP (bounded pool)
                                                     ▼
                                               PostgreSQL 16 (source of truth)
```

Key decisions (full ADRs in `10-decision-log.md`):

1. READ COMMITTED + explicit row locks (`FOR UPDATE`) + conditional updates + constraints.
2. The per-user limit is serialized through a **quota row** per (show, user), with a DB CHECK
   `seats_held BETWEEN 0 AND seat_limit` as a backstop.
3. Global lock order: **idempotency key → quota row → reservation row → seat rows by ascending id**.
4. The idempotency record is inserted **first, in the same transaction** as the business effect. A
   unique index on `(user_id, idem_key)` serializes concurrent same-key requests.
5. Seat counts are **derived** from seat rows (no show-level counters).
6. `JdbcTemplate` (native SQL) for all concurrency-critical paths; JPA only for the `shows` table.
7. HS256 JWTs validated by Spring Security's OAuth2 Resource Server; tokens issued by the demo
   endpoint `POST /auth/token`, with ADMIN gated by an admin key (ADR-021).
8. Spring Boot built-in structured logging (ECS JSON); Micrometer + Prometheus registry.
9. Overload is absorbed by **queueing** (bounded pool, generous timeouts), not by shedding with 503;
   the target is 0 × 5xx under the 20k burst (ADR-022).
10. The seats gauge is **per show** (`show_id` label, 20 most recent shows), read from PostgreSQL at
    scrape time over a dedicated observability connection pool (ADR-023).

## 10. Threat model and trust boundaries

### Trust boundaries

| Boundary | Trusted side | Untrusted side | Control |
|---|---|---|---|
| TB-1 Internet → host edge | Host TLS termination | Any client | HTTPS only on the public host |
| TB-2 Edge → app | App | Request headers/body/path | JWT verification, Bean Validation, strict Jackson |
| TB-3 App → PostgreSQL | PostgreSQL | — | Credentials via env; TLS (`sslmode=require`) on the hosted DB |
| TB-4 Operator → config | Host secret store | Repo contents | No real secrets in Git; `prod` refuses dev defaults |

### Threats and mitigations

| # | Threat | Mitigation | Residual risk |
|---|---|---|---|
| T-1 | Forged JWT | HS256 with a ≥ 32-byte secret; decoder pinned to HS256 (`none`/other algorithms rejected); `iss`, `aud`, `exp` validated with 30 s skew | Leaking the secret compromises everything; rotate by redeploying |
| T-2 | Impersonation via `userId` in the body | The body never carries identity; unknown JSON fields are rejected (400) | — |
| T-3 | Admin key shared for evaluation is misused | Share it privately, only for the evaluation window; rotate afterwards. The JWT signing secret is never shared | Holder of the admin key can obtain ADMIN tokens (create shows) until rotation |
| T-4 | IDOR: cancelling another user's reservation | Ownership check; non-owner gets **404** `RESERVATION_NOT_FOUND` (no existence oracle); random UUIDv4 ids | — |
| T-5 | Idempotency key collision or leakage across users | Key scope is `(user_id, idem_key)` | — |
| T-6 | Resource exhaustion | Validation caps (≤ 10 seats/request, ≤ 10k seats/show), bounded Hikari pool + connection timeout, `lock_timeout`, `statement_timeout`, `idle_in_transaction_session_timeout` | Volumetric DDoS is the host's concern |
| T-7 | SQL injection | Only parameterized SQL (`NamedParameterJdbcTemplate`, JPA) | — |
| T-8 | Metrics cardinality blow-up | No user/key/reservation/request ids as labels. Show ids appear **only** on the `seatres_seats` gauge, limited to the 20 most recently created shows (≤ 60 series) | — |
| T-9 | Sensitive data in logs | Tokens, the Authorization header, raw idempotency keys, and bodies are never logged; user ids are logged as a hashed `userRef` | — |
| T-10 | Information disclosure in errors | Problem Details with stable codes; no stack traces, SQL, or constraint names | — |
| T-11 | Actuator exposure | Only `health` and `prometheus` exposed; `prometheus` carries aggregate counts only | Metrics are publicly readable (`05` §3) |
| T-12 | Anyone obtains a USER token for any user id via the demo endpoint | Documented as a demo identity provider; ADMIN tokens gated by the admin key; every other endpoint takes identity only from the verified token | User impersonation is possible **by design** in this demo. Production must replace `POST /auth/token` with a real identity provider |
