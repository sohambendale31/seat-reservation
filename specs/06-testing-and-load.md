# 06 — Testing and Load

## 1. Strategy

| Layer | Tooling | Scope | Runs in |
|---|---|---|---|
| Unit (`*Test`) | JUnit 5, AssertJ, Mockito | Fingerprinting, validation, error mapping, classifier, JWT role mapping, TxExecutor retry/commit-phase logic | `./mvnw test` (no Docker) |
| Integration (`*IT`) | Spring Boot Test (`RANDOM_PORT`), Testcontainers PostgreSQL 16 (`@ServiceConnection`), JDK `HttpClient` | Real HTTP → real DB: migrations, SQL, security | `./mvnw verify` (Docker required) |
| Concurrency (`*ConcurrencyIT`) | Same + `Executors.newVirtualThreadPerTaskExecutor()`, `CountDownLatch` start gate | Races from `04` §8 with exact expected results | `./mvnw verify` |
| End-to-end / load | `scripts/BurstTest.java` against Compose or the deployed URL | Black-box correctness under load + reconciliation | Manual |

Rules:

- Concurrency tests go through **real HTTP** (embedded Tomcat on a random port), not MockMvc, so
  filters, security, the connection pool, and transactions behave as in production.
- One PostgreSQL container per test JVM, declared in an abstract `AbstractPostgresIT` and started
  from a static initializer, so it is never stopped and every test class shares one cached Spring
  context (Ryuk removes it at JVM exit). It is deliberately **not** managed by
  `@Testcontainers`/`@Container`: that extension stops a static container when its test class
  finishes, which leaves the cached context on a dead database. The image is referenced digest-only
  (`postgres@sha256:…`) with an explicit `@ServiceConnection("postgresql")`, because Testcontainers
  rejects the combined `tag@digest` form when Boot derives the connection name from it (ADR-024).
- The base class also carries `@AutoConfigureObservability`: Spring Boot disables metrics export in
  tests, so without it no `PrometheusMeterRegistry` exists and `/actuator/prometheus` answers 404 in
  tests while working in production.
- Each test creates its **own show(s)**, so tests are isolated without truncation. After each
  concurrency test, reconciliation R1–R6 (03 §7.3) must return zero rows **for that test's show**,
  and R7 is asserted globally. A database-wide R1–R6 would flag MIG-03's deliberately inconsistent
  constraint-probe rows.
- Test profile `test`: `DB_POOL_MAX_SIZE=10`, `DB_LOCK_TIMEOUT_MS=2000`, a test JWT secret, and a test
  admin key. The short lock timeout is deliberate, so IT-FAIL-01 can force a timeout quickly; the
  production defaults are long (ADR-022).
- Helpers in `src/test/java/com/seatres/support/`: `TestTokens` mints HS256 tokens with
  `NimbusJwtEncoder`, including the malformed variants the auth tests reject; `Api` wraps
  `HttpClient` and returns status, headers and parsed JSON; `Shows` and `Reserve` drive those
  endpoints; `Races` releases N tasks through a `CountDownLatch` start gate on virtual threads;
  `Reconciliation` runs the invariant queries.
- Races start deterministically: all tasks block on a `CountDownLatch(1)`, then are released
  together.
- No `Thread.sleep`-based assertions; waits use latches/futures with timeouts (30 s per test).

## 2. Unit test matrix

| ID | Unit | Cases |
|---|---|---|
| UT-01 | `RequestFingerprinter` | Label order is irrelevant; a different show gives a different fingerprint; `v1` prefix present |
| UT-02 | `ReserveRequest` validation | 0 seats, 11 seats, duplicates, lowercase, `A0`, null; `A999` passes the pattern (it's rejected later as unknown) |
| UT-03 | `CreateShowRequest` validation | Blank name, 201 rows, duplicate rows, seatCount 0 and 501, total 10,001, `pricePaise` negative and > 1e8 |
| UT-04 | Strict Jackson | Asserted on the configured `ObjectMapper` (`@JsonTest`): a fractional value, a quoted number and an unknown field are all rejected. The same rules are re-asserted over real HTTP on `CreateShowRequest` (IT-SHOW) and `ReserveRequest` (IT-RES) |
| UT-05 | `DbErrorClassifier` | Each SQLState in `04` §10 → its category; nested causes; Hikari `SQLTransientConnectionException` |
| UT-06 | `TxExecutor` (mocked `PlatformTransactionManager`) | Retries 40P01 up to 3 attempts, then 503; no retry for 23505 (→ 500) or for a lock timeout; an exception from `commit()` with SQLState 08006 → `OutcomeUnknownException`, while a definite commit failure is not; commits are never retried; after-commit hooks run only on success, never carry over into a retry, and a throwing hook never reaches the caller; `rollbackOnly` work rolls back and skips the hooks; a pool timeout from `getTransaction` → 503 |
| UT-07 | `ErrorCode` → Problem | Every code has a status, type URN, and title; `retryable` flags match `02` §4.1 |
| UT-08 | `RolesClaimConverter` | Roles → authorities; unknown values ignored; missing claim → none |
| UT-09 | `TokenIssuer` | Issued tokens carry `iss`, `aud`, `sub`, `roles`, `iat`, and `exp = iat + 3600`, and are accepted by `JwtConfig`'s decoder; the admin-key comparison is constant-time (`MessageDigest.isEqual`) |

## 3. Integration tests (Testcontainers)

### 3.1 Migrations and startup

| ID | Test | Expected |
|---|---|---|
| MIG-01 | Start the context on an empty DB | Flyway applies `V1`; `flyway_schema_history` has 1 successful row; Hibernate `validate` passes |
| MIG-02 | Restart the context on the same DB | No new migrations; starts cleanly |
| MIG-03 | Direct SQL constraint probes | A seat with `status = 'CONFIRMED'` and a null reservation fails `23514`; quota `seats_held = seat_limit + 1` fails `23514`; a second active link for a seat fails `23505`; a seat pointing at another show's reservation fails `23503`. These fixtures are intentionally inconsistent, which is why reconciliation is show-scoped |
| MIG-04 | Startup guard (implemented as a `*Test` with `ApplicationContextRunner`, since it needs no database) | A context with a 16-byte JWT secret fails to start; the `prod` profile with the dev secret fails; `APP_IDEMPOTENCY_RETENTION=PT1H` fails; a 16-byte admin key fails; the `prod` profile with the dev admin key fails; a JWT secret equal to the admin key fails |
| MIG-05 | `connection-init-sql` is actually executed by PgJDBC | `SHOW lock_timeout`, `SHOW statement_timeout` and `SHOW idle_in_transaction_session_timeout` on a pooled connection return the configured values, so the `options=` URL fallback in `07` §4.1 is not needed (closes risk R-5) |

### 3.2 Authentication and authorization

| ID | Test | Expected |
|---|---|---|
| AUTH-01 | No token on reserve / GET show / POST shows | 401 `UNAUTHENTICATED`, `WWW-Authenticate: Bearer` |
| AUTH-02 | Wrong signature, expired, wrong `iss`, wrong `aud`, `alg=none`, `alg=HS512` | 401 |
| AUTH-03 | `sub` violating the pattern (e.g. 65 chars, or containing a space) | 401 |
| AUTH-04 | USER token → `POST /shows` | 403 `FORBIDDEN` |
| AUTH-05 | ADMIN-only token → reserve | 403 |
| AUTH-06 | Reserve body with `"userId":"mallory"` | 400 `MALFORMED_REQUEST`; no reservation created. Lives with the reserve tests, since it needs that endpoint |
| AUTH-07 | The reservation owner is the token `sub` | DB row `reservations.user_id` = the token's sub. Lives with the reserve tests, since it needs that endpoint |
| AUTH-08 | `/livez`, `/readyz`, `/actuator/prometheus` without a token | 200 |
| AUTH-09 | `/actuator/env` without a token / with a valid token | 401 / 404 (unmatched paths require authentication) |
| AUTH-10 | Valid token with no `roles` claim → `GET /shows/{id}` | 403 `FORBIDDEN` (a missing claim is treated as empty) |
| AUTH-11 | `POST /auth/token {"sub":"alice"}` (no auth header) | 200 with `accessToken`; that token reserves successfully and `reservations.user_id` = `alice` |
| AUTH-12 | `POST /auth/token` with `roles:["ADMIN"]`: no `X-Admin-Key` / a wrong key / the correct key | 403 `FORBIDDEN` and no token / 403 / 200, and that token can `POST /shows` |
| AUTH-13 | `POST /auth/token` with an invalid `sub` (65 chars, or containing a space) or an unknown role | 400 `VALIDATION_FAILED` |
| AUTH-14 | `GET /auth/token` (only `POST` is public) without a token / with a valid token | 401 / 405, because security runs before routing (`02` §1) |

### 3.3 Shows

| ID | Test | Expected |
|---|---|---|
| IT-SHOW-01 | Create 2 rows × 20 | 201, `Location`, `totalSeats=40`; GET shows 40 AVAILABLE seats labelled A1..A20, B1..B20, priced per row |
| IT-SHOW-02 | Create 10,000 seats | 201 within the statement timeout; R1 holds |
| IT-SHOW-03 | GET an unknown id / a malformed id | 404 `SHOW_NOT_FOUND` / 400 |
| IT-SHOW-04 | GET counts | `seatCounts.total = available + held + confirmed = seats.length` |

### 3.4 Reservations

`Idempotency-Key` is read after the body has been bound and validated, so a request that is invalid
in both reports `VALIDATION_FAILED` rather than the key error (`01` §4.3).

| ID | Test | Expected |
|---|---|---|
| IT-RES-01 | Reserve `[A1,A2]` | 201; `totalPaise` = sum; GET shows A1, A2 CONFIRMED; quota `seats_held=2`; seats listed in layout order regardless of request order |
| IT-RES-02 | Missing / invalid `Idempotency-Key` | 400 `IDEMPOTENCY_KEY_MISSING` / `IDEMPOTENCY_KEY_INVALID` |
| IT-RES-03 | Reserve a taken seat | 409 `SEAT_UNAVAILABLE`, `unavailableSeats=["A1"]` |
| IT-RES-04 | All-or-nothing: A1 taken by bob; alice requests `[A1,A2,A3]` | 409; A2 and A3 still AVAILABLE; alice's quota unchanged |
| IT-RES-05 | Unknown label `Z9` | 422 `UNKNOWN_SEAT`, `unknownSeats=["Z9"]`; no changes |
| IT-RES-06 | Unknown show | 404 `SHOW_NOT_FOUND`; the same key again replays the 404 with the replay header |
| IT-RES-07 | Limit: hold 3, request 2 | 409 `USER_LIMIT_EXCEEDED` (`perUserLimit=4, seatsHeld=3, seatsRequested=2`) |
| IT-RES-08 | Request 5 seats on a limit-4 show | 409 `USER_LIMIT_EXCEEDED` |
| IT-RES-09 | The limit is per show | User holds 4 in show X; reserving in show Y succeeds |
| IT-RES-10 | Custom `perUserLimit=2` | Third seat → 409 |
| IT-RES-11 | Precedence: user at limit requests a taken seat | 409 `USER_LIMIT_EXCEEDED` |
| IT-RES-12 | Large `pricePaise` (1e8) × 10 seats | `totalPaise=1000000000` exactly |

### 3.5 Idempotency

| ID | Test | Expected |
|---|---|---|
| IT-IDEM-01 | Same key, same body, sequential | 201, then a 201 replay with identical body bytes and `Idempotent-Replayed: true`; 1 reservation in the DB |
| IT-IDEM-02 | Same key, seats in a different order | Replay (same fingerprint) |
| IT-IDEM-03 | Same key, different seats | 409 `IDEMPOTENCY_KEY_REUSED` |
| IT-IDEM-04 | Same key, different show | 409 `IDEMPOTENCY_KEY_REUSED` |
| IT-IDEM-05 | Same key, different users | Both execute independently |
| IT-IDEM-06 | A stored 409 decline is replayed even after the seat frees up | Seat taken → 409; the holder cancels; the same key again → **the same 409 replay**; a new key → 201 |
| IT-IDEM-07 | A 400 isn't stored | Invalid body with key K → 400; a valid body with K → 201 |
| IT-IDEM-08 | Retention cleanup | Insert a record with `expires_at` in the past; run the job; the row is gone and the key is reusable |
| IT-IDEM-09 | Replay of a 201 after cancellation | Still returns the original 201 body (`status: CONFIRMED`) with the replay header |

### 3.6 Cancellation

| ID | Test | Expected |
|---|---|---|
| IT-CAN-01 | Owner cancels | 200 `CANCELLED` with `cancelledAt` set; seats AVAILABLE; quota decremented; `reservation_seats.released_at` set; the response's seats come from the surviving links |
| IT-CAN-02 | Cancel twice | The second call returns 200 with the same `cancelledAt` |
| IT-CAN-03 | Non-owner cancels | 404 `RESERVATION_NOT_FOUND`; nothing changes (the reservation stays CONFIRMED and the owner's quota is untouched) |
| IT-CAN-04 | Unknown / malformed id | 404 / 400 |
| IT-CAN-05 | Cancel, someone else reserves the seat, then the original owner cancels again | The third party keeps the seat; the owner gets a 200 no-op |
| IT-CAN-06 | Cancel restores quota | Hold 4 → cancel a 2-seat reservation → reserve 2 more → 201 |
| IT-CAN-07 | Cancel with a `text/plain` body | 200 `CANCELLED`; the body and `Content-Type` are ignored (no 415) |
| IT-CAN-08 | Cancel one of a user's two reservations in the same show | Only that reservation's seats are released; the other stays CONFIRMED; the quota drops by exactly the cancelled seat count |
| IT-CAN-09 | Cancel without a token / with an ADMIN-only token | 401 / 403; the reservation stays CONFIRMED |

### 3.7 Failure handling

| ID | Test | Expected |
|---|---|---|
| IT-FAIL-01 | Lock timeout and rollback: a separate JDBC connection (opened outside the pool, so holding a lock cannot starve the app) runs `BEGIN; SELECT ... FROM seats WHERE label='A1' FOR UPDATE` and holds it; then reserve A1 (test `lock_timeout=2s`) | 503 `SERVICE_UNAVAILABLE` with `Retry-After`; no idempotency row and no quota row (full rollback); the seat is still AVAILABLE; after the lock is released, the same key → 201. `SHOW lock_timeout` is asserted by MIG-05. |
| IT-FAIL-02 | Readiness with the DB paused (`docker pause` through the Testcontainers client; **tagged `@Tag("slow")`**, and it takes about a minute because the server waits out its pool timeout) | `/readyz` 503 `DOWN`, `/livez` 200 `UP`; reserve → 503 with `retryable: true`; after unpausing, `/readyz` 200. Requires a JDBC `socketTimeout`: without one the probe never answers at all, which is what ADR-025 records |
| IT-FAIL-03 | Error bodies | 500/503 bodies contain no SQL, class names, or constraint names |

Scenario 7 (connection drops during commit) is covered by UT-06 (commit-phase failure →
`OUTCOME_UNKNOWN`) together with IT-IDEM-01 (a retry with the same key replays). Scenario 8 (a crash
before or after commit) is covered by IT-FAIL-01 (an aborted transaction leaves no claim, and the
retry executes) and IT-IDEM-01 (a committed outcome is replayed).

## 4. Concurrency test matrix (exact expectations)

Every test uses fresh shows; N tasks are released simultaneously; the test pool size is 10. After
each test, R1–R7 return 0 rows.

| ID | Scenario (04 §8) | Setup | Expected |
|---|---|---|---|
| CT-01 | 8.1 Hot seat | Show 1 row × 10; 200 distinct users each reserve `[A1]` with a unique key | Exactly 1 × 201; 199 × 409 `SEAT_UNAVAILABLE`; 0 × 5xx; DB: A1 CONFIRMED, 1 reservation |
| CT-02 | 8.2 One user, 10 parallel, limit 4 | User alice, 10 requests for seats A1..A10 (one each), unique keys | Exactly 4 × 201; 6 × 409 `USER_LIMIT_EXCEEDED`; quota `seats_held=4`; 4 CONFIRMED seats owned by alice |
| CT-03 | 8.3 Same key, identical, concurrent | alice, key K, `[B1,B2]`, 50 parallel | 50 × 201; one distinct `reservationId`; 49 with `Idempotent-Replayed: true`; 1 reservation; 1 idempotency row |
| CT-04 | 8.4 Same key, different bodies, concurrent | alice, key K: 25 × `[C1]` and 25 × `[C2]` | Exactly one of C1/C2 CONFIRMED; 25 × 201 (matching the winner) + 25 × 409 `IDEMPOTENCY_KEY_REUSED`; 1 reservation |
| CT-05 | 8.5 Overlapping multi-seat, opposite order | 100 rounds: user X `[D1,D2,D3]` and user Y `[D3,D2,D1]`, released together (fresh show per round) | Each round exactly 1 × 201 + 1 × 409; 0 × 5xx; `pg_stat_database.deadlocks` doesn't increase |
| CT-06 | 8.6 Cancel vs another user's reserve | alice holds E1; release together: alice cancels, bob reserves `[E1]` (repeat 100×) | Each round: cancel returns 200; bob gets 201 (E1 now bob's) or 409; never 5xx; never both alice and bob own E1 |
| CT-07 | 8.6 Concurrent cancels of the same reservation | 20 parallel cancels by the owner | 20 × 200; all return the same `cancelledAt`; quota decremented exactly once |

## 5. Observability tests

| ID | Test | Expected |
|---|---|---|
| OBS-01 | `/livez` | 200 `{"status":"UP"}` |
| OBS-02 | `/readyz` with the DB up | 200 |
| OBS-03 | `/readyz` with the DB paused (IT-FAIL-02) | 503 |
| OBS-04 | Counting rules (05 §5) | After 1 confirm, 1 replay of it, 1 seat decline, 1 limit decline, and 1 key reuse, the exact counter deltas are: confirmed +1, declines{seat_unavailable} +1, declines{user_limit_exceeded} +1, replays{201} +1, everything else unchanged |
| OBS-11 | A replayed decline is not counted twice | Replaying a stored 409 leaves `declines{seat_unavailable}` unchanged and moves only `replays{409}` |
| OBS-12 | The gauge follows a cancellation | After cancelling, the show's `confirmed` series drops and `available` rises to the full seat count |
| OBS-05 | Metric names present | Every Prometheus name in 05 §4.1 appears in `/actuator/prometheus` |
| OBS-06 | No forbidden labels | The scrape output contains no user sub, idempotency key, or reservation id. Show ids appear **only** as the `show_id` label of `seatres_seats`, for at most 20 shows |
| OBS-07 | Request id | Responses carry `X-Request-Id`; a valid supplied id is echoed; an invalid one is replaced; the problem body's `requestId` matches the header |
| OBS-08 | Logs | Captured log JSON (Boot `OutputCaptureExtension`) contains `requestId` and `userRef` and no `Authorization`/token substrings, no admin key, no JWT secret, and never the raw `sub` (including after AUTH-11/12 calls) |
| OBS-09 | Seats gauge reconciles per show (ADR-023) | Create a show with n seats, reserve 2, then scrape: `seatres_seats{show_id=S}` shows available n−2, held 0, confirmed 2, exactly matching `GET /shows/S` |
| OBS-10 | Seats gauge stays live under a saturated main pool | Borrow every main-pool connection and hold them, then scrape: it answers promptly with current counts, because it uses the observability pool. Borrowing directly is deterministic, where blocking reserve transactions would depend on timing |

## 6. Performance and load test plan

Purpose: show correctness under load and **measure** (not promise) latency and throughput.

| Run | Environment | Command | Recorded |
|---|---|---|---|
| L-1 | Local Compose (record CPU, RAM, Docker resources) | `java scripts/BurstTest.java --scenario all --base-url http://localhost:8080` | Outcome table, latency percentiles, reconcile result |
| L-2 | Local Compose, 20k burst | `--scenario pool-burst --requests 20000 --concurrency 500` | Same |
| L-3 | Deployed URL | `--base-url https://<deployed-host> --scenario all --concurrency 200` | Same, plus host plan/size. Warm up first and confirm Serverless is off: a cold start answers **502**, which would fail the run |
| L-4 | Deployed URL, 20k burst (**mandatory**, ADR-022) | `--scenario pool-burst --requests 20000 --concurrency 300` | Same. Must show 0 × 5xx; if it doesn't, resize the plan or pool and rerun |

Load-generator limits (state these in the report):

- `--concurrency` bounds **in-flight requests**, not total requests. 20,000 requests at concurrency
  500 means 20,000 requests with at most 500 outstanding at a time. It does **not** mean 20,000
  simultaneous connections, and the report must not claim that.
- JDK `HttpClient` (HTTP/1.1) opens up to one connection per in-flight request and reuses them
  (keep-alive). Check `ulimit -n` (≥ 4× concurrency), the ephemeral port range, and the laptop's CPU.
  If the generator's CPU is above 80 %, the measurement reflects the client, not the server.
- Hosted platforms have edge proxies with their own connection limits, rate limits, and request
  timeouts. 502/503/504/429 from the edge are counted under "5xx/other 4xx" and annotated. On
  Railway a 502 can also mean the service was asleep (ADR-026), so Serverless must be off before a
  run and the warm-up must be recorded.
- Server capacity is bounded by `DB_POOL_MAX_SIZE` concurrent transactions. Excess requests **queue**
  in Hikari for up to `DB_POOL_CONNECTION_TIMEOUT_MS` (60 s) instead of being shed (ADR-022). Under
  the 20k burst this shows up as latency, not errors. A 503 from the app means a last-resort timeout
  was reached, and that **fails** the run (NFR-1). The fix is capacity: a bigger plan or a tuned pool,
  or the fast-path fallback in `04` §12.

## 7. Burst script specification: `scripts/BurstTest.java`

A single-file Java 21 program with no dependencies, run with `java scripts/BurstTest.java [options]`
(JEP 330 source launcher). It uses `java.net.http.HttpClient`, virtual threads, a `Semaphore` to
bound concurrency, and a minimal hand-written JSON field extractor (it reads only `id`,
`reservationId`, `code`, `status`, `label`, `accessToken`, and the `seatCounts` object). It gets its
tokens from `POST /auth/token` (ADR-021), so it doesn't need the JWT signing secret.

`--help` prints the option list. An unknown scenario or a flag without a value exits 2 with a usage
message, so a typo fails immediately rather than part-way through a run.

### 7.1 Configuration

| Option | Env | Default | Meaning |
|---|---|---|---|
| `--base-url` | `BASE_URL` | `http://localhost:8080` | Target |
| `--scenario` | `SCENARIO` | `all` | `hot-seat`, `pool-burst`, `same-key`, `user-limit`, `all` |
| `--concurrency` | `CONCURRENCY` | `200` | Max in-flight requests |
| `--requests` | `REQUESTS` | `20000` | Requests for `pool-burst` |
| `--users` | `USERS` | `2000` | Distinct users for `pool-burst` |
| `--seats` | `SEATS` | `1000` | Seats in the `pool-burst` show (rows of 50) |
| `--hot-users` | `HOT_USERS` | `500` | Users in `hot-seat` |
| `--timeout-ms` | `TIMEOUT_MS` | `120000` | Per-request timeout. Longer than the server's 60 s pool wait, so a slow answer isn't misread as a failure |
| `--seed` | `SEED` | `42` | RNG seed for reproducible seat choices |
| — | `ADMIN_KEY` | the local dev admin key | Admin key of the target deployment, used only to get the ADMIN token that creates the burst shows |

Token setup: before timing starts, the script calls `POST /auth/token` once for an ADMIN token
(`burst-<runId>-admin`, with `X-Admin-Key: $ADMIN_KEY`) and once per user for USER tokens
(`burst-<runId>-u<i>`). Tokens last 1 h, and token calls aren't counted in the burst's outcome table
or latency. `runId` is a UTC timestamp plus 4 random hex chars, so runs never collide.

### 7.2 Scenarios

Every scenario creates its own show and ends with a **reconcile** step: it runs
`GET /shows/{id}` and checks `available + held + confirmed == total == seats.length`, `held == 0`,
and that the set of CONFIRMED seats equals the seats in the client-known logical reservations. It
then scrapes `/actuator/prometheus` and checks that `seatres_seats{show_id=<its show>}` equals the
GET counts (ADR-023). Every scenario also asserts **0 × 5xx on the first attempt** (NFR-1, ADR-022).

| Scenario | Steps | Assertions (exit code 1 if any fails) |
|---|---|---|
| `hot-seat` | Show "burst-<runId>-hot" (1 row × 10). `HOT_USERS` users each request `[A1]` with a unique key, released together through the semaphore. Resolve ambiguous outcomes (§7.3). | Exactly 1 logical confirmation; A1 CONFIRMED; reconcile OK |
| `pool-burst` | Show with `SEATS` seats, limit 4. `REQUESTS` requests; each picks a random user and 1–4 random distinct seats with a fresh key. | Seats in different `reservationId`s are pairwise disjoint; per user, Σ confirmed seats ≤ 4; reconcile OK |
| `same-key` | (a) 50 identical concurrent requests with one key; (b) 20 concurrent requests with one new key, alternating two seat lists | (a) all 201, one `reservationId`, 49 replay headers; (b) exactly one logical reservation; non-matching bodies → 409 `IDEMPOTENCY_KEY_REUSED` |
| `user-limit` | One user sends 10 parallel requests, one distinct seat each, on a limit-4 show | Exactly 4 × 201 and 6 × 409 `USER_LIMIT_EXCEEDED` (after resolution); GET shows 4 confirmed |

### 7.3 Ambiguity resolution (HTTP responses vs logical reservations)

Requests that ended in a **timeout, a transport error, or a 503** are re-sent with the **same key and
body** up to 3 times (backoff 500 ms, 1 s, 2 s). The final classification uses the resolved
response, and the raw first-attempt counts are still reported. This follows the client contract
(04 §9) and lets the script compute the exact set of logical reservations, which it then compares
with the server. Unresolved requests are reported; in that case the reconcile assertion becomes
`server_confirmed ≥ client_known_confirmed`, with the difference shown as "unresolved". Resolution
only recovers the logical outcome: any first-attempt 5xx still **fails** the 0 × 5xx assertion.

Multiple 201 responses can represent a single logical reservation (replays). The script counts
logical reservations by distinct `reservationId`.

### 7.4 Output format

```
=== scenario: hot-seat  show=7f1c…  users=500  concurrency=200 ===
HTTP outcomes (first attempt)
  201 Created ............................ 1
  409 SEAT_UNAVAILABLE ................... 499
  409 USER_LIMIT_EXCEEDED ................ 0
  409 IDEMPOTENCY_KEY_REUSED ............. 0
  other 4xx .............................. 0   {}
  5xx .................................... 0   {}
  timeouts ............................... 0
  transport errors ....................... 0
  replayed (Idempotent-Replayed: true) ... 0
After ambiguity resolution
  resolved ............................... 0   unresolved ... 0
Logical outcome
  distinct reservations (client) ......... 1
  seats confirmed (client) ............... 1
  seats confirmed (server GET) ........... 1
Latency (ms, first attempt, all responses)  p50=…  p95=…  p99=…  max=…
Wall time .............................. … s   achieved rate … req/s
Reconcile: total=10 available=9 held=0 confirmed=1  invariant=OK
ASSERTIONS: PASS
```

Apart from the values that correctness guarantees, the numbers above only illustrate the **format**.
The script also prints a one-line JSON summary per scenario (prefix `SUMMARY `) for pasting into
`WRITEUP.md`.

### 7.5 Safety and cleanup

- The script only **creates** shows (names prefixed `burst-<runId>-`) and reservations on them. It
  never deletes anything, cancels other users' reservations, or touches pre-existing shows.
- No cleanup is needed for correctness. Local full reset: `docker compose down -v`. On the deployed
  DB, burst shows accumulate; the deliberate manual purge is in `07` §10.
- The default concurrency against deployed URLs is 200; raise it only after checking the plan's
  limits.

### 7.6 `scripts/MintToken.java`

An **offline** helper; neither the burst script nor the evaluator needs it, since both use
`POST /auth/token`. `java scripts/MintToken.java --sub alice --roles USER[,ADMIN] [--ttl 3600] [--secret <secret>]`
prints a compact HS256 JWT (`iss=seat-reservation`, `aud=seat-reservation-api`). `--secret` defaults
to the `JWT_SECRET` env var, or to the local dev secret if that is unset. It uses `javax.crypto.Mac`
(HmacSHA256) and `Base64.getUrlEncoder().withoutPadding()`.

## 8. Acceptance criteria (measurable)

| ID | Criterion | Threshold |
|---|---|---|
| TA-1 | `./mvnw verify` on a clean checkout with Docker | All tests pass |
| TA-2 | CT-01..CT-07 | Exact expectations in §4; no deadlocks |
| TA-3 | Burst `hot-seat` (local) | 1 logical confirmation; 0 × 5xx; reconcile OK |
| TA-4 | Burst `user-limit` (local and deployed) | Exactly 4 confirmed seats for the user |
| TA-5 | Burst `same-key` (local and deployed) | One logical reservation per key |
| TA-6 | Burst `pool-burst` 20k (local) | All correctness assertions pass and **0 × 5xx**. Timeouts and latency percentiles are **reported** (no fixed threshold). |
| TA-7 | Deployed runs L-3 and L-4 | Correctness assertions pass and **0 × 5xx**, including the 20k `pool-burst`; the per-show seats gauge matches GET; the report states the plan/instance size |
| TA-8 | After the runs | `seatres_invariant_violations_total` = 0 |

## 9. Running the tests

```bash
./mvnw test                         # unit only, no Docker
./mvnw verify                       # unit + integration + concurrency (Docker required)
./mvnw verify -DexcludedGroups=slow # skip the docker-pause test
docker compose up -d --build
java scripts/BurstTest.java --scenario all --base-url http://localhost:8080
BASE_URL=https://<deployed-host> ADMIN_KEY='<deployment admin key>' java scripts/BurstTest.java --scenario all --concurrency 200
BASE_URL=https://<deployed-host> ADMIN_KEY='<deployment admin key>' java scripts/BurstTest.java --scenario pool-burst --requests 20000 --concurrency 300
```
