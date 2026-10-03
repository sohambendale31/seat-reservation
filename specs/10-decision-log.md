# 10 — Decision Log (ADRs)

Format: Decision · Context · Alternatives · Rationale · Consequences. The stack (Java 21, Spring Boot
3, PostgreSQL) is a fixed requirement and isn't revisited here.

## ADR-001: PostgreSQL decides every allocation
- **Context:** Thousands of concurrent requests against the same seats.
- **Alternatives:** Redis locks/counters; in-JVM locks; a queue-based single writer.
- **Rationale:** One transactional store gives atomicity across seats, quota, and idempotency in a
  single commit, and the decision rolls back together with the writes. Extra infrastructure adds
  failure modes without a demonstrated need.
- **Consequences:** The DB is the throughput ceiling and a single point of failure (CP behaviour).

## ADR-002: Single application instance in v1
- **Context:** Running multiple instances isn't a v1 requirement.
- **Rationale:** It keeps deployment, pool sizing, and testing simple.
- **Consequences:** Scaling is vertical. Correctness still rests only on PostgreSQL transactions and
  constraints (no in-process state), but multi-instance operation isn't claimed or tested.

## ADR-003: READ COMMITTED with explicit row locks
- **Alternatives:** SERIALIZABLE (SSI) with retries; optimistic version columns.
- **Rationale:** Deterministic queueing on contended rows and no abort storms on hot seats. The
  required predicates are expressed directly as locks and conditional updates.
- **Consequences:** Correctness depends on disciplined lock order (ADR-005), backed by constraints
  and tests.

## ADR-004: Per-user limit via a quota row with a CHECK constraint
- **Alternatives:** `COUNT(*)` under SERIALIZABLE; an advisory lock on hash(show, user) plus a count;
  a trigger-maintained counter.
- **Rationale:** A real row is a visible, stable serialization point. The CHECK enforces the limit in
  the database even if application logic regresses, without triggers.
- **Consequences:** One redundant counter (`seats_held`), kept correct transactionally and verified
  by reconciliation R5.

## ADR-005: Global lock order: idempotency key → quota → reservation → seats by ascending id
- **Alternatives:** Lock in label order; `NOWAIT`/`SKIP LOCKED`; rely on deadlock detection plus
  retry.
- **Rationale:** A total order rules out deadlocks by construction, and `seats.id` is immutable.
  `NOWAIT` would turn transient contention into declines that might be false (the lock holder may
  roll back). `SKIP LOCKED` is wrong when a request names specific seats.
- **Consequences:** Hot-seat waiters queue, bounded by the pool size and `lock_timeout`.

## ADR-006: Idempotency claim inserted first, in the same transaction
- **Alternatives:** A separate "in-progress" record committed before the work (needs a recovery
  protocol); inserting the record at the end (concurrent duplicates would run the business logic).
- **Rationale:** The unique index both serializes same-key requests and makes the claim atomic with
  the effect, so no keys get stuck after a crash.
- **Consequences:** Concurrent same-key requests hold a connection while they wait (bounded by
  timeouts).

## ADR-007: Store and replay in-transaction outcomes, including 404/409/422 declines
- **Alternatives:** Store only successes, so a retry after a decline might later succeed under the
  same key.
- **Rationale:** Same key ⇒ same answer. This stops a "retry" from silently becoming a new attempt.
- **Consequences:** Clients must use a new key for a new attempt (documented in `02`).

## ADR-008: Replays return the original status plus `Idempotent-Replayed: true`
> **Superseded in part by ADR-022:** a replayed *success* now returns 200, not 201.
- **Alternatives:** Return 200 for replays; use a dedicated replay status.
- **Rationale:** Clients handle one response shape per outcome, while the header and the metrics
  separate replays from new confirmations.

## ADR-009: Idempotency scope `(user_id, key)`, fingerprint over target show + canonical seat set
- **Alternatives:** A global key scope; a scope per (user, show).
- **Rationale:** Per-user scope avoids cross-user collisions and leakage. Including the show in the
  fingerprint makes reusing a key for another show a detectable client error.

## ADR-010: Immediate confirmation; `HELD` reserved
- **Alternatives:** Hold with a TTL, plus a confirm endpoint and an expiry sweeper.
- **Rationale:** No confirm/payment endpoint is required, and holds would add expiry races and a
  sweeper. The schema keeps `HELD` so the required invariant is well-defined.

## ADR-011: Derived seat counts; no show-level counters
- **Rationale:** No drift and no hot row, and `available + held + confirmed = total` holds by
  construction.

## ADR-012: JdbcTemplate for concurrency-critical paths; JPA only for shows
- **Rationale:** Lock statements, `ON CONFLICT`, and row-count assertions stay explicit and
  reviewable, with no interference from Hibernate's flush ordering or caching.

## ADR-013: HS256 JWTs via Spring Security Resource Server; tokens minted by a script
> **Superseded in part by ADR-021:** tokens are now issued by `POST /auth/token`; the script remains
> an offline tool, and the JWT secret is no longer shared.
- **Alternatives:** RS256 + JWKS (needs an identity provider); a token-issuing endpoint; API keys.
- **Rationale:** Practical for a one-day build, with no extra libraries (Nimbus ships with Spring
  Security) and no extra API surface. The local dev secret is refused in `prod`.
- **Consequences:** A shared-secret model. For evaluation, the secret or pre-minted tokens are shared
  privately and the secret is rotated afterwards.

## ADR-014: 503 for infrastructure failures; never a fake 409
- **Rationale:** Domain conflicts are detected explicitly before any write, so a DB integrity error
  indicates a bug (500), and timeouts or unavailability are retryable (503). This keeps alerts and
  client logic honest.

## ADR-015: Virtual threads + bounded Hikari pool as admission control
> **Amended by ADR-022:** the pool still bounds DB concurrency, but overload now *queues* (60 s pool
> timeout) instead of being shed with 503.
- **Alternatives:** A platform thread pool (200) with an accept queue; an explicit semaphore
  bulkhead.
- **Rationale:** Waiting is cheap for many in-flight requests, and the pool's connection timeout
  bounds the queueing and turns overload into explicit 503s.
- **Consequences:** Pinning risk on Java 21 (fallback documented in `08` R-4).

## ADR-016: Public, aggregate-only Prometheus endpoint
> **Amended by ADR-023:** the seats gauge carries a bounded `show_id` label (20 newest shows). Show
> ids aren't personal data; no user, key, reservation, or request ids are ever labels.
- **Alternatives:** Protect it with a JWT role or basic auth.
- **Rationale:** The metrics carry only bounded, non-identifying labels, and a public endpoint keeps
  scraping and evaluation simple. Production would restrict it at the network level.

## ADR-017: Render as the primary host
> **Amended by ADR-022:** the plan is chosen by measurement. It must pass the deployed 20k run (L-4)
> with 0 × 5xx.
- **Alternatives:** Railway, Fly.io.
- **Rationale:** Docker deploys with health-gated releases and a managed Postgres in the same region.
  No tier is assumed adequate for 20k concurrent requests; a paid tier is recommended for the
  evaluation window.

## ADR-018: Auth edge cases: fail-closed unmatched paths, missing `roles` = empty, cancel ignores bodies
- **Context:** Specs review found three ambiguities: an unauthenticated request to an unknown path
  (`02` said 404, but security runs before routing); a token without a `roles` claim (`02` marked it
  required, while UT-08 and the 403 rule treated it as empty); and cancel's "body is ignored" vs the
  general 415 rule.
- **Decision:** (1) Unmatched paths require authentication: 401 without a token, 404 with one.
  (2) A missing `roles` claim is treated as empty, so the request is authenticated with no
  authorities (403 on protected endpoints). (3) Cancel ignores any body and `Content-Type`.
- **Alternatives:** `anyRequest().permitAll()` so unknown paths always give 404 (fail-open: a new
  endpoint without a rule would be public); a validator rejecting tokens without `roles` (401).
- **Rationale:** Secure default for new endpoints; matches the majority of existing spec text and
  Spring's default behaviour; cancel needs no body.
- **Consequences:** Updated `02` §1/§2.1/§3/§5.4, `06` AUTH-09, plus new AUTH-10 and IT-CAN-07,
  `07` §11 item 7, and `08` P1/P4 test ranges.

## ADR-019: Host health check uses `/livez`, not `/readyz`
- **Context:** Render's health-check docs (checked 2026-10-03) say checks time out after 5 s.
  Render stops routing to an instance after 15 s of consecutive failures and restarts it after
  60 s. `/readyz` borrows a pool connection and can wait up to the 10 s pool timeout under a
  burst, and it fails whenever PostgreSQL is down. The original plan (`/readyz`, timeout ≥ 10 s)
  could therefore restart the only instance mid-burst, or crash-loop it during a DB outage.
- **Decision:** The hosting platform's health check is `/livez`. `/readyz` stays for external
  monitoring (`SeatresNotReady`) and for humans.
- **Alternatives:** Keep `/readyz` (the restart and crash-loop risk above); give readiness its own
  dedicated connection (more complexity, and it still fails during a DB outage).
- **Rationale:** Startup already proves DB connectivity, because Flyway must succeed before Tomcat
  accepts traffic. With a single instance there's no healthy target to reroute to. Restarting the
  app can't fix the database.
- **Consequences:** A DB that fails after startup doesn't mark the instance unhealthy. Clients get
  the app's 503 + `Retry-After`, and the `/readyz` alert still fires. Updated `01` §8, `05` §2,
  `07` §7.1/§7.2/§9, and the README.

## ADR-020: Spec-review corrections (retention floor, hot-seat 503 wording, R5, reserve diagram)
> **Item 2 (hot-seat 503 wording) superseded by ADR-022:** the assignment requires zero 5xx across
> the whole burst, so TA-3 is back to 0 × 5xx. Items 1, 3 and 4 stand.
- **Context:** The specs review found these contradictions and defects:
  1. `07` allowed `APP_IDEMPOTENCY_RETENTION` ≥ `PT1H`, while `00`, `02`, and `04` promise ≥ 24 h.
  2. TA-3, AC-5, and NFR-1 demanded 0 × 5xx in hot-seat bursts, while `04` §8.1/§12 and `06` §6
     define timeout 503s as correct load shedding.
  3. Reconciliation R5 checked only quota rows → reservations.
  4. The `01` §5.1 diagram committed on replay and key-reuse paths, while `04` §4 rolls back.
- **Decision:**
  1. Retention must be ≥ `PT24H`, enforced by `StartupChecks` and tested by MIG-04.
  2. CT-01 stays at 0 × 5xx. TA-3 is now "0 × 500; 503s reported and resolved by same-key
     retry". AC-5 and NFR-1 state that a timeout 503 is an infrastructure outcome, not a conflict
     outcome.
  3. R5 uses a `FULL OUTER JOIN`.
  4. The diagram shows ROLLBACK on replay and key reuse, and COMMIT only in the claimed branch.
- **Rationale:** `04` has precedence, so the other documents were aligned to it. Reconciliation
  must catch "can't happen" states in both directions, as R6 already does.
- **Consequences:** Edits in `00` §6/§8, `01` §2/§5.1, `03` §7.3, `06` MIG-04/TA-3, and `07` §3.

## ADR-021: Demo token endpoint `POST /auth/token`
- **Context:** The assignment's evaluator runs their own ~20k burst with many users and must create
  fresh shows as an admin. The original design had no token endpoint: it shared the HS256 secret or
  pre-minted tokens, and minting needed a Java 21 tool. That's a real barrier to the evaluator
  testing the service at all.
- **Decision:** `POST /auth/token {"sub": "...", "roles": ["USER"]}` returns
  `{accessToken, tokenType, expiresIn}` (`02` §5.5). USER tokens are free to obtain for any valid
  `sub`. Requesting `ADMIN` requires `X-Admin-Key` equal to `APP_AUTH_ADMIN_KEY` (constant-time
  comparison; ≥ 32 bytes; `prod` refuses the dev default). Tokens carry exactly the `02` §2.1 claims,
  TTL 1 h, and are verified by the unchanged decoder. The request uses `sub`/`roles`, the JWT claim
  names, so it matches the claims it produces and the API's existing camelCase style.
- **Alternatives:** Keep sharing the secret plus `MintToken.java`; ship a file of pre-minted tokens;
  an external identity provider.
- **Rationale:** The evaluator gets tokens for any number of users with plain HTTP, and the JWT
  signing secret is never shared (only the admin key is). Identity is still derived only from the
  verified token.
- **Consequences:** Anyone can obtain a USER token for any user id, so this is a demo identity
  provider (threat T-12); production must replace it. The admin key is shared privately and
  rotated after evaluation. `MintToken.java` remains an offline tool. New tests AUTH-11…13; updated
  `00`, `01`, `02`, `06`, `07`, `08`, and the README.

## ADR-022: Queue overload instead of shedding it; zero 5xx is required
- **Context:** The assignment's correctness bar requires "zero 5xx across the whole burst" for ~20k
  concurrent reservations, with "exactly one 201, everyone else 409" per hot seat. The original design
  deliberately answered 503 once the pool wait (10 s) or a lock wait (3 s) ran out.
- **Decision:** Long last-resort timeouts: pool connection 60 s, `lock_timeout` 30 s,
  `statement_timeout` 35 s, transaction 45 s; Tomcat `max-connections` 25,000. Overload **waits in
  line** instead of being shed. 503 stays only for a dependency failure or a last-resort timeout,
  and either one counts as a failed burst. Every burst scenario must show 0 × 5xx, and the deployed
  20k run (L-4) is mandatory. A fast-path decline (stored-answer lookup, then an unlocked read
  seeing `CONFIRMED` → 409) is documented as the fallback if L-4 still shows 5xx.
- **Alternatives:** 503 load shedding (honest, but fails the bar); 429 (still not the expected
  201/409).
- **Rationale:** Each transaction is short, so queues drain. Waiting changes no decision, so
  correctness is unaffected.
- **Consequences:** Higher tail latency under a burst, and a risk that a client times out first, so
  latency is measured and reported. Capacity must be sized by measurement; a free tier may not be
  enough. Supersedes ADR-020 item 2 and amends ADR-015 and ADR-017. Updated `00`, `01`, `04`, `06`,
  `07`, `08`, `09`, and the README.

## ADR-023: Per-show seats gauge, read at scrape time over a dedicated pool
- **Context:** The assignment requires a seats-available gauge whose values "reconcile with the API
  state and with what we observe". The original `seatres_seats{status}` summed all shows (old burst
  shows included) and was refreshed every 15 s, so it couldn't match `GET /shows/{id}` for the
  evaluator's fresh show. The refresher also used the main pool, so during a burst it would queue
  behind thousands of requests.
- **Decision:** `seatres_seats{show_id,status}` for the **20 most recently created shows** (≤ 60
  series). It's produced by `SeatGaugeCollector`, which runs one `GROUP BY` query **at scrape time**,
  using a **separate 2-connection observability pool** (connection timeout 2 s, `statement_timeout`
  2 s). If the query fails, the collector serves the last successful snapshot and logs
  `metrics.seat_query_failed` at WARN.
- **Alternatives:** A global gauge without `show_id` (doesn't reconcile per show); a scheduled
  refresher (stale between refreshes); a larger show window (more series and more scrape cost).
- **Rationale:** The evaluator can compare the gauge for their show with `GET /shows/{id}` during and
  after the burst, and the gauge keeps updating because it never waits on the main pool.
- **Consequences:** `show_id` becomes a label on this one gauge (bounded, not personal data); T-8,
  ADR-016, and OBS-06 are relaxed for it. The pool-sizing rule adds 2 connections. Updated `00`,
  `01`, `05`, `06`, `07`, `08`, `09`, and the README.
