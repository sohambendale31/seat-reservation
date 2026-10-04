# Seat Reservation Service — engineering write-up

- **Live URL:** <https://seat-reservation-production-6d97.up.railway.app> (`/` opens an interactive
  Swagger UI; `POST /auth/token` issues a token with no credential)
- **Repository:** <https://github.com/sohambendale31/seat-reservation>
- **Stack:** Java 21, Spring Boot 3.5.16, PostgreSQL 16.15, Docker, deployed on Railway
- **Evidence:** every number below comes from a run whose raw output is committed under
  [`docs/evidence/`](docs/evidence/). Nothing here is estimated.

**The core mechanism in one sentence:** every allocation is decided inside one PostgreSQL
transaction that locks a per-(show, user) quota row and then the requested seat rows in ascending
id order, confirms them with a conditional `UPDATE`, and asserts the affected row counts — with
CHECK, unique and foreign-key constraints as a second line of defence if that logic is ever wrong.

**Headline results.** 20,000 concurrent-ish requests against the deployed service: **0 × 5xx, 0
timeouts**, 639 reservations holding 998 pairwise-disjoint seats, and the server's seat state
matching the client's view exactly. The hot-seat case — 500 users reaching for one seat — produced
**exactly one** 201 and 499 × `409 SEAT_UNAVAILABLE`. 283 automated tests pass, including seven
concurrency tests run five times consecutively with no deadlocks.

---

## 1. The atomic decision

### The mechanism

A reserve request runs as a single transaction at READ COMMITTED. The ordered steps are:

1. **Claim the idempotency key.** `INSERT INTO idempotency_records … ON CONFLICT (user_id, idem_key)
   DO NOTHING RETURNING id`. Returning a row means this transaction owns the key.
2. **Resolve labels to seat ids** (`SELECT id, label FROM seats WHERE show_id = ? AND label IN (…)`).
   No lock: labels and ids are immutable, so this read cannot go stale.
3. **Take the quota row.**
   ```sql
   INSERT INTO user_show_quotas (show_id, user_id, seats_held, seat_limit)
   VALUES (?, ?, 0, ?) ON CONFLICT (show_id, user_id) DO NOTHING;

   SELECT seats_held, seat_limit FROM user_show_quotas
   WHERE show_id = ? AND user_id = ? FOR UPDATE;
   ```
   Decline with `409 USER_LIMIT_EXCEEDED` if `seats_held + n > seat_limit`.
4. **Lock the seats, in ascending id order.**
   ```sql
   SELECT id, label, status, price_paise FROM seats
   WHERE show_id = ? AND id IN (…) ORDER BY id FOR UPDATE;
   ```
   Decline with `409 SEAT_UNAVAILABLE` if any is not `AVAILABLE`.
5. **Write**: insert the reservation and its seat links, then
   ```sql
   UPDATE seats SET status = 'CONFIRMED', reservation_id = ?
   WHERE show_id = ? AND id IN (…) AND status = 'AVAILABLE';
   -- assert affected rows = n
   UPDATE user_show_quotas SET seats_held = seats_held + ? WHERE show_id = ? AND user_id = ?;
   -- assert affected rows = 1
   ```
6. **Store the response bytes against the idempotency key**, then commit.

### Why it is race-free

Two reservations cannot hold the same seat. A seat records its owner in the single column
`seats.reservation_id`, and `seats_status_reservation_ck` enforces that `status = 'AVAILABLE'` is
true exactly when that column is null. To take a seat, step 5 must match `status = 'AVAILABLE'`
while holding that row's lock. While reservation R holds the seat it is `CONFIRMED`, so a competing
transaction's `UPDATE` matches zero rows, the row-count assertion fails, and the whole transaction
rolls back. Independently, a partial unique index allows at most one unreleased `reservation_seats`
link per seat, so even a logic bug cannot commit a second claim.

The per-user limit holds for the same reason one level up. Every allocation for a (show, user) pair
passes through `SELECT … FOR UPDATE` on that pair's single quota row, so those transactions are
totally ordered by that lock; each reads the latest committed `seats_held` after acquiring it, and
`user_show_quotas_held_ck` (`seats_held BETWEEN 0 AND seat_limit`) refuses any commit that would
exceed it.

The alternative that looks correct and is not: `SELECT count(*)` followed by `INSERT`. Under READ
COMMITTED two transactions both read the old count, both pass the check, and both insert — classic
write skew. The quota row exists specifically to turn that read-then-write into a serialized one.
The concurrency test for this sends 10 parallel requests from one user on a limit-4 show and gets
exactly 4 × 201 and 6 × 409; the deployed burst shows the same property at scale, with no user
holding more than 4 seats across 20,000 requests.

### Multi-seat: how deadlock is avoided

Locks are always taken in one global order: idempotency key → quota row → reservation row (cancel
only) → seat rows **by ascending `seats.id`**. `id` is `GENERATED ALWAYS AS IDENTITY`, assigned in
layout order at show creation, and never changes — so the sort key is stable, and PostgreSQL applies
`ORDER BY` before taking row locks. Labels are resolved to ids *before* locking, so the order the
caller lists seats in is irrelevant.

A deadlock needs a cycle of transactions each waiting on a lock held by the next. Here every wait
edge points from a transaction to a lock strictly greater than every lock it already holds, in the
lexicographic order (level, key). A cycle would require some lock to be greater than itself, which
cannot happen. Without the sorted locking the cycle is easy to construct: X holds A3 and waits for
A2 while Y holds A2 and waits for A3.

This is tested rather than argued: 100 rounds of two users requesting `[D1,D2,D3]` and `[D3,D2,D1]`
simultaneously on a fresh show, asserting exactly one 201 and one 409 per round and that
`pg_stat_database.deadlocks` does not increase. It did not increase, in five consecutive runs. The
retry for `40P01` exists as a safety net and has never been observed to fire.

### Why these choices

- **READ COMMITTED with explicit locks, not SERIALIZABLE.** SERIALIZABLE is also correct, but on a
  hot seat it converts contention into `40001` aborts that must be retried, which is an abort storm
  exactly when load is highest. Explicit locks give deterministic queueing and a deterministic
  winner.
- **No advisory locks.** They are invisible to constraints and easy to leak on a pooled connection.
  Row locks are attached to the data they protect.
- **No in-JVM locks.** The decision has to be atomic with the database write *and its rollback*,
  which an in-process lock cannot provide, and it would not survive the overlap between old and new
  containers during a redeploy.
- **No show-level counters.** `available`, `held` and `confirmed` are derived from the seat rows in a
  single statement. A stored counter would be both a drift risk and a single hot row serializing
  every reservation for a show.

---

## 2. Idempotency

**Where the key lives.** In `idempotency_records`, unique on `(user_id, idem_key)` — scoped per
user, so two users may use the same key string without colliding. The row also stores a
`request_fingerprint`, the response status, and the exact response body bytes.

**The fingerprint** is computed after validation from the validated command, never from raw bytes:

```
v1|RESERVE|show=<lowercase uuid>|seats=<labels sorted, comma-joined>   →   SHA-256, lowercase hex
```

Sorting means `["A2","A1"]` and `["A1","A2"]` are the same request. The `v1|` prefix lets the
canonical form change later without matching older fingerprints.

**How exactly-once is enforced.** The claim is inserted as the *first* statement of the same
transaction that does the work. Because the unique index allows one committed claim per
(user, key), at most one transaction can ever execute the business logic for that key — the claim
and the effect commit atomically, so there is no window where one exists without the other.

Concurrency falls out of the index. A second request with the same key blocks on the unique index
until the first transaction ends. If the first committed, the second's `ON CONFLICT DO NOTHING`
inserts nothing; it then re-reads the record in a new statement (hence a new snapshot), sees the
stored outcome, and replays it. If the first rolled back, the second's insert succeeds and it
executes normally. There is deliberately no "in progress" state to get stuck in: a crash before
commit rolls the claim back, so the next retry executes from scratch.

The test for this fires 50 identical requests with one key simultaneously: all 50 return **201**,
they carry **one** distinct `reservationId`, 49 of them carry `Idempotent-Replayed: true`, and the
database holds exactly one reservation and one idempotency row.

**Same key, different body.** The waiter compares its fingerprint with the stored one. If they
differ it returns `409 IDEMPOTENCY_KEY_REUSED` and allocates nothing — this is a client bug, and
silently treating it as a new request would be the dangerous behaviour. Tested by sending 50
concurrent requests under one key, half for seat C1 and half for C2: exactly one of the two seats is
confirmed, 25 responses are the winner's 201 and 25 are `409 IDEMPOTENCY_KEY_REUSED`, with one
reservation in the database.

**What is stored.** Outcomes *decided inside* the transaction — 201, `404 SHOW_NOT_FOUND`,
`422 UNKNOWN_SEAT`, `409 SEAT_UNAVAILABLE`, `409 USER_LIMIT_EXCEEDED` — are stored and replayed
byte-for-byte, including the original `requestId` inside a stored problem body. Outcomes decided
*before* it (400/401/403/415) and all 5xx are not stored, so a retry after one of those executes
normally. A replayed decline stays a decline even if the seat has since been freed; the client must
use a **new** key for a new attempt. This is why the handler returns pre-serialized bytes rather
than a typed object: re-serializing could not guarantee identity.

**Honest limit.** This is exactly-once *effect*, not exactly-once delivery, which is impossible. A
response can be lost after commit and the client cannot distinguish "not executed" from "executed,
reply lost". So the contract is: on a timeout, a transport error or a 503, retry with the **same**
key. If the connection drops during `commit()` the outcome is genuinely unknown, and the service
says so with `503 OUTCOME_UNKNOWN` rather than guessing; the client's retry then either replays the
committed 201 or executes fresh.

I verified the recovery path rather than trusting it: restarting PostgreSQL three seconds into a
20,000-request burst produced 20 × 503, all of which the client resolved by retrying with the same
key, and the client's final set of reservations still matched the server's exactly
([`local-fault-injection-2026-10-04.txt`](docs/evidence/local-fault-injection-2026-10-04.txt)).

**Retention.** Keys are honoured for at least 24 hours; a scheduled job deletes expired records in
batches of 5,000 using `FOR UPDATE SKIP LOCKED`, so cleanup never blocks a live claim.

---

## 3. Holds and expiry

**There are no holds in v1, and that is a deliberate choice rather than an omission.** A reserve
confirms immediately: `AVAILABLE → CONFIRMED` inside the one transaction.

`HELD` exists as a legal value of `seats.status` and is counted in the invariant
`available + held + confirmed = total`, so the invariant is well-defined if holds are added later —
but **no code path writes it**, and a reconciliation query asserts it stays zero. Every scrape and
every show read in all the evidence shows `held = 0`.

The reasoning: a hold with a TTL needs an expiry sweeper, and that sweeper introduces races that the
current design does not have — a hold expiring at the same moment the holder confirms, a sweeper
competing with a cancel for the same row, and a decision about whether an expired-but-unswept hold
blocks a new reservation. None of that is justified without a payment step to hold seats *for*,
and the assignment requires no confirm endpoint. Adding holds later means a new status transition
plus a sweeper that takes the same locks in the same order; the schema and the invariant already
accommodate it.

What exists instead is **cancellation**, which releases seats explicitly. It cannot resurrect or
steal a seat because the release is predicated on ownership:

```sql
UPDATE seats SET status = 'AVAILABLE', reservation_id = NULL
WHERE reservation_id = :rid AND status = 'CONFIRMED';   -- assert rows = seat_count
```

A seat released by R and then taken by someone else has `reservation_id = R'`, so a repeat cancel of
R cannot match it. Cancelling twice is a no-op returning the original `cancelledAt`. Tested with 20
simultaneous cancels of one reservation — all 20 return 200 with an identical body, and the quota
decrements exactly once — and with 100 rounds of a cancel racing another user's reserve for the same
seat, where the seat always ends up owned by exactly one of them.

---

## 4. Consistency versus availability under a partition

The service is **CP**. PostgreSQL is the only authority on who holds a seat; there is no cache, no
queue and no local fallback that could serve an optimistic answer. When the database is unreachable
the service refuses to allocate rather than guess, because a wrong 201 is unrecoverable — somebody
arrives at the venue and finds their seat taken — whereas a 503 costs a retry.

Concretely:

- **Database stopped.** Sockets close, Hikari reports connection errors, `/readyz` goes DOWN, writes
  return `503 SERVICE_UNAVAILABLE` with `Retry-After: 1`. Verified by stopping PostgreSQL under the
  running stack: `/livez` stayed 200 and `/readyz` returned 503, recovering automatically.
- **Database frozen, or the network path blackholed.** Nothing is closed and nothing answers. This
  turned out to be the more dangerous case: a pool timeout does **not** bound a read on a connection
  the caller already holds, so without a socket-level timeout the readiness probe never returns at
  all. I measured both: with no `socketTimeout`, `/readyz` was still waiting after three minutes;
  with one, it answered 503. The fix is `socketTimeout=60` on the main pool (above the 35 s
  `statement_timeout`, so a merely slow server still cancels its own query first) and 3 s on the
  metrics pool.
- **App↔DB partition with a transaction open.** Row locks are held until the server ends the session,
  bounded by `idle_in_transaction_session_timeout` (30 s). Contenders wait up to `lock_timeout`
  (30 s) and then get a retryable 503 — never a false 409. Verified by holding a seat lock from an
  outside connection: the reserve returned 503 with `Retry-After`, left **no** idempotency row and
  **no** quota row behind, and the same key then succeeded once the lock was released.
- **Client↔app failure after commit.** The effect is durable and the reply is lost; the client's
  retry with the same key replays it.

**Liveness is deliberately independent of the database.** `/livez` never touches it, because
restarting the process cannot fix a database outage and doing so on a single instance would turn an
outage into a crash loop.

**Overload is queued, not shed.** The pool is bounded (20 connections), and excess requests wait for
a connection or a row lock rather than being rejected. The timeouts are long on purpose — 60 s for a
pool connection, 30 s for a lock — so that a burst appears as latency rather than as errors. A 503
means a real dependency failure or a last-resort timeout, and in the measured runs none occurred.

---

## 5. Observability, and what would page me at 2am

Five custom meters, all with enumerated labels so cardinality cannot grow with traffic:

| Metric | Meaning |
|---|---|
| `seatres_reservations_confirmed_total` | New logical reservations committed. Never incremented for a replay |
| `seatres_reservation_declines_total{reason}` | First-time declines: `seat_unavailable`, `user_limit_exceeded` |
| `seatres_idempotency_replays_total{status}` | Responses served from a stored outcome |
| `seatres_invariant_violations_total{check}` | `db_constraint` or `rowcount_assertion` — a safety net fired |
| `seatres_seats{show_id,status}` | Seats per status for the 20 newest shows |

Counters move in after-commit hooks, so nothing is counted that later rolls back. Replays are the
one exception: a replay commits nothing at all, so its counter is incremented once the transaction
has ended. The accounting is therefore honest in a specific way — `confirmed` may *undercount* after
an ambiguous commit, but it can never *overcount*, and PostgreSQL remains authoritative.

`seatres_seats` is read from PostgreSQL **at scrape time**, over a separate two-connection pool, so
it stays accurate during a burst instead of queueing behind it. On the deployed service it matches
`GET /shows/{id}` exactly: available 8, confirmed 2, held 0 for a freshly reserved show.

### What pages

**1. `increase(seatres_invariant_violations_total[5m]) > 0` — page immediately.** This counter moves
only when a database constraint fired or a row-count assertion failed, which means a seat may have
been sold twice or a limit breached. Everything else in this system is designed so that this is
unreachable; if it moves, the design is wrong rather than the load being high. The runbook is to run
the seven reconciliation queries and stop the sale if they confirm it.

**2. Readiness failing for 2 minutes** — nobody can reserve. The `for: 2m` window matters because
`/readyz` can legitimately take tens of seconds while queueing for a pool connection during a burst,
and waking someone for that would train them to ignore the alert. This one carries extra weight on
Railway, which health-checks only at deploy time and never afterwards, so nothing will restart a
wedged instance on its own.

**3. Sustained 5xx above 5% for 5 minutes** — most users are failing.

### What does not page

**409s never alert.** During a hot on-sale they are the *expected* majority — 17,032 of 20,000
responses in the deployed burst. They are the system working. An alert on 409 rate would fire every
time the product succeeded.

Isolated 500s, pool saturation and a slow p99 open a ticket for business hours instead.

### Logs

ECS JSON on stdout, one event per line, correlated by `requestId`, which is also returned in the
`X-Request-Id` header and repeated inside every error body. `userRef` and `idemKeyRef` are SHA-256
prefixes — the raw subject and the raw idempotency key are never written, and neither are tokens,
the `Authorization` header, the admin key, or request bodies.

**A finding worth recording: under burst, logs are lossy and metrics are not.** The service emits one
INFO line per decline, and declines dominate an on-sale. Railway drops application logs above
~500/sec per replica and said so inline 17 times during the 20k runs; it also refuses to return more
than 5,000 lines, so a 20,000-request run cannot be read back from its console at all. The metrics
were unaffected and stayed exact. This is the practical demonstration of why metrics are the
authoritative count and the log stream is a convenience. A deployment that needs complete logs
should ship them to an external sink through a log drain; a complete 20,000-line capture from the
local stack, verified line-for-line against the server's counters, is committed at
[`local-20k-full-logs-2026-10-04.txt`](docs/evidence/local-20k-full-logs-2026-10-04.txt).

---

## 6. Load evidence

Burst script: `java scripts/BurstTest.java --scenario all --base-url <url>` — one file, no
dependencies, no build step. Every scenario creates its own show, reconciles against both
`GET /shows/{id}` and the `seatres_seats` gauge, and exits non-zero if any assertion fails.

| Environment | Spec |
|---|---|
| Local | Apple Silicon laptop, 12 CPUs, app + PostgreSQL 16.15 in Docker on the same machine |
| Deployed | Railway, 1 replica, `DB_POOL_MAX_SIZE=20`, Railway PostgreSQL, same project |
| Generator | Same laptop; for deployed runs, over the public internet |
| Commit | `9415d66`, Spring Boot 3.5.16, Maven 3.9.16, springdoc 2.9.1 |

**Deployed** ([`deployed-2026-10-04.txt`](docs/evidence/deployed-2026-10-04.txt)):

| Scenario | Requests | 201 | 409 seat | 409 limit | 5xx | Timeouts | p50 / p95 / p99 | Rate |
|---|---|---|---|---|---|---|---|---|
| hot-seat | 500 | **1** | 499 | 0 | **0** | 0 | 1469 / 1975 / 2175 ms | 121/s |
| pool-burst | 20,000 | 650 | 17,074 | 2,276 | **0** | 0 | 321 / 479 / 688 ms | 576/s |
| same-key | 70 | 60 | 0 | 0 | **0** | 0 | 398 / 410 / 415 ms | 91/s |
| user-limit | 10 | **4** | 0 | **6** | **0** | 0 | 385 / 416 / 416 ms | 24/s |
| **pool-burst @300** | **20,000** | 639 | 17,032 | 2,329 | **0** | 0 | 363 / 572 / 1223 ms | 754/s |

**Local** ([`local-2026-10-04.txt`](docs/evidence/local-2026-10-04.txt)): same assertions, 20,000
requests at 4,202 req/s with p99 120 ms, 0 × 5xx.

Every run reconciles three ways — the client's logical reservations, `GET /shows/{id}`, and the
per-show gauge all agree — and asserts that seats across different reservations are pairwise
disjoint and that no user exceeds the limit.

### How to read these numbers honestly

- **They are not a capacity measurement.** The generator shares a laptop with the local service, and
  reaches the deployed one over the public internet. Deployed latency is dominated by WAN
  round-trips; during the 20k runs the server's pool sat at zero active and zero pending connections
  between scenarios. The figures bound the *client*, not the service.
- **`--concurrency` means in-flight requests**, not simultaneous connections. 20,000 requests at
  concurrency 300 is 20,000 requests with at most 300 outstanding.
- **A client-side gotcha that looked like a server problem.** The first deployed 20k run crawled at
  about 1 req/s while the server was idle. Over TLS the JDK HTTP client negotiates HTTP/2 and
  multiplexes every request onto one connection, so 200 "concurrent" requests serialized behind that
  connection's stream limit. Locally it had been plain HTTP, hence HTTP/1.1 and real parallelism, so
  the problem only appeared after deployment. Pinning HTTP/1.1 took it from ~1 req/s to 754 req/s.
  Worth stating because the obvious diagnosis — "the instance is too small" — would have been wrong.
- **What was not tested:** multi-instance operation, sustained load over hours, failover of the
  managed database, and any load generated from more than one machine.

### Test suite

283 automated tests: 157 unit (no Docker) and 126 integration against a real PostgreSQL via
Testcontainers, all over real HTTP. Seven concurrency tests cover the races above and were run five
times consecutively with no failures and no deadlocks. One test is tagged `slow` because it freezes
the database container; `./mvnw verify -DexcludedGroups=slow` skips it.

---

## 7. AI assistance

This project was built with Claude Code as the primary author, under my direction. Being precise
about the split matters more than being flattering to either side.

**What the AI drafted.** Essentially all of the artifacts: the eleven specification documents in
`specs/`, the entire implementation, all 283 tests, the burst script, the Dockerfile and Compose
setup, the Railway configuration, and this write-up. I did not hand-write the production code.

**What I directed.** The process, which shaped the result more than any individual file:

- I required the work to proceed **phase by phase** (P0 scaffold → P7 deploy), with the project
  compiling, running and tested at the end of each phase, and I reviewed and committed each phase
  myself. The twelve commits are that cadence, not a retrofit.
- I set the **code standards**: short, crisp comments that explain *why* and never cite the spec
  documents; and no dead code — unused imports, unreferenced methods, or helpers written ahead of
  the phase that needs them.
- I required the **specifications to stay in sync** with the implementation after every phase, so a
  decision taken in code is recorded as an ADR rather than left to drift. ADR-024 through ADR-027
  exist because of that rule.
- I chose the **host** (Railway), asked for **Swagger** so the live URL is usable by someone who has
  not read the repo, and insisted on **complete log evidence** rather than a curated sample.

**What I caught in review.** Specific defects I found and sent back: an unused import left behind by
a refactor; a resource-leak warning on the Testcontainers container; Swagger documentation whose
opening paragraph was written in implementation jargon rather than describing what the service does;
and a `sub` example field showing a random regex-satisfying string (`Y0uhrTQQHx0QQhY@hV`) that would
have been pre-filled into every visitor's first request.

**What the agent found, and I accepted.** The HTTP/2 multiplexing problem above; a missing
`forward-headers-strategy` that made the deployed Swagger UI advertise an `http://` server URL,
which browsers block as mixed content; the socket-timeout gap that let a frozen database hang
readiness indefinitely; Railway's log caps; and a stale note in the decision log claiming a replayed
success returns 200 when the authoritative document requires 201.

**Where the AI was corrected by reality rather than by me.** Several early attempts were wrong and
were fixed only because they were executed: the Testcontainers lifecycle (the first version stopped
the container between test classes and stranded the Spring context), a database-wide reconciliation
query that collided with deliberately inconsistent constraint-probe fixtures, and `ConstraintValidator`
classes left package-private, which compiles and then fails at runtime.

**The honest summary:** the design quality here comes from the specification documents, which the AI
also wrote; my contribution was insisting on a process — incremental phases, tests before
implementation on the critical path, specs kept in sync, evidence for every claim — and reviewing
what came back. A reader should weigh this as directed-and-reviewed AI work, not as hand-written
code, and not as unsupervised generation either.

---

## 8. What I would do next

**Before taking real money**

1. **Replace the demo identity provider.** `POST /auth/token` issues a token for any subject with no
   credential. It exists so an evaluator can use the service, and it is the single thing that makes
   this unsuitable for production. It would be replaced by an external IdP with RS256 and JWKS, and
   the endpoint removed.
2. **Ship logs to an external sink.** Demonstrated above: the platform console drops logs under load
   and cannot return a full burst. A log drain is required before anyone could investigate an
   incident from the logs.
3. **Holds with expiry and a payment step**, which is the real reason to have a `HELD` state. The
   schema and the invariant already allow for it; the work is the sweeper and its races.

**To scale beyond one instance**

4. Multi-instance operation is *expected* to be correct, because no allocation state lives in the
   process — but it is neither claimed nor tested, and "expected to be correct" is not a guarantee I
   would make about seat allocation. It needs the concurrency suite run against two instances behind
   a load balancer before the claim is made.
5. PostgreSQL is the throughput ceiling. The next real limit is commit fsync latency, since every
   request — including every decline — commits durably in order to store its idempotent outcome.
6. Read scaling for `GET /shows/{id}`, which returns up to 10,000 seats in one response.

**Operational polish**

7. Partition `idempotency_records` by expiry so cleanup is a partition drop rather than a delete.
8. Sample or demote the per-decline log line, which is the dominant log volume during an on-sale.
9. Migrate `railway.json` to Railway's `.railway/railway.ts`, which replaces config-as-code from
   2026-12-01.
