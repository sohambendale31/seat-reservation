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
> **Corrected (ADR-024):** an earlier note here claimed ADR-022 had changed a replayed success to
> 200. ADR-022 is about queueing overload and says nothing about replay status, while `04` §5,
> `02` §5.3 and CT-03 all require the original status. A replayed success is therefore **201**, and
> that is what the code does.
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
> **Superseded by ADR-026:** the host is Railway. The reasoning below (Docker deploys, managed
> Postgres in the same region, no tier assumed adequate) still applies; only the provider changed.
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
> **Scoped by ADR-026:** this holds for hosts that probe *continuously* and restart on failure.
> Railway probes only at deploy time and never afterwards, so on Railway the deploy check is
> `/readyz`. The principle is unchanged: never let a probe that depends on the database restart the
> only instance.
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

## ADR-024: Implementation decisions from P0–P3
- **Context:** Building P0–P3 settled choices the specification left open, and in a few places a
  library's actual behaviour overrode the spec's first draft. `00` §"Document map" requires a
  disagreement to be fixed in every affected file rather than resolved silently in code, so the
  decisions are recorded here and the affected sections have been updated.
- **Decision:**
  1. **Pinned versions.** Spring Boot `3.5.16`, Maven Wrapper `3.9.16` (`distributionType=only-script`,
     so no wrapper jar is committed), and the three base images pinned by digest (`01` §1.1). Spring
     Initializr no longer offers a 3.x line, so the parent is hand-pinned (risk R-10 realised).
  2. **One PostgreSQL container per test JVM, started by hand.** `@Testcontainers`/`@Container` stops
     a static container when its test class finishes, which strands the cached Spring context on a
     dead database. `AbstractPostgresIT` starts it in a static initializer and never stops it; Ryuk
     reaps it at JVM exit. The image is referenced digest-only, because Testcontainers rejects the
     combined `tag@digest` form when Boot derives a `@ServiceConnection` name from it, so the name is
     given explicitly as `@ServiceConnection("postgresql")`.
  3. **Integration tests carry `@AutoConfigureObservability`.** Boot disables metrics export in tests,
     so without it no `PrometheusMeterRegistry` exists and `/actuator/prometheus` answers 404 in tests
     while working in production.
  4. **Reconciliation R1–R6 are scoped to one show**, R7 stays global. Test classes share one database
     without truncation, and MIG-03 deliberately writes inconsistent rows to probe constraints, so an
     unscoped scan reports those fixtures as violations.
  5. **`GlobalExceptionHandler` extends `ResponseEntityExceptionHandler`.** With
     `spring.mvc.problemdetails.enabled=true`, Boot registers its own problem handler unless such a
     bean exists; extending it keeps one handler and one body shape instead of two advices whose
     ordering would become load-bearing.
  6. **The resource server gets the problem entry point as well.** The bearer-token filter uses the
     entry point configured inside `oauth2ResourceServer(...)`, not the one on `exceptionHandling(...)`,
     so without setting both an *invalid* token returned an empty 401 body while a *missing* one
     returned correct problem JSON.
  7. **`TxExecutor` wraps `getTransaction`**, because a Hikari pool timeout surfaces there and has to
     become a 503 rather than an unexpected 500 — which matters directly for the zero-5xx bar.
  8. **`TxContext.rollbackOnly()`** lets the replay path end its transaction without committing and
     without running after-commit hooks, which is what `04` §4's "roll back, nothing was written"
     requires. A replay therefore provably writes nothing.
  9. **`IN (:ids)` instead of `= ANY(:array)`** in S3, S5 and S8a: identical semantics in PostgreSQL,
     and the JDBC layer expands the list into bind parameters without a `java.sql.Array`. A request
     names at most 10 seats.
  10. **Reservations use `INSERT … RETURNING created_at`**, which keeps `now()` as the source of the
      timestamp while making it available for the stored response body without a re-select. Shows keep
      an application-supplied `created_at`, truncated to microseconds so the create response equals a
      later read, because they are written through JPA where `RETURNING` is not natural.
  11. **Identity reaches controllers as `@AuthenticationPrincipal Jwt`.** The planned `CurrentUser`
      record was not built: nothing needs the roles after authorization, so it would have carried an
      unused field.
  12. **`StartupChecks` also refuses a JWT secret equal to the admin key**, so one leaked value cannot
      grant both token signing and admin access.
  13. **Cross-field show validation lives in one class-level constraint** (`@ValidCreateShow`), which
      is what allows the name to be length-checked *after* trimming while still reporting on field
      `name`; reserve's duplicate-label rule is a field constraint (`@UniqueLabels`). Hibernate
      Validator instantiates validators reflectively, so those classes are public.
  14. **An unauthenticated request with the wrong method is 401, not 405**, because security runs
      before routing (consistent with ADR-018). 405 applies once the caller is authenticated.
  15. **`Idempotency-Key` is validated in the controller body**, after Spring has bound and validated
      the request body. A request wrong in both reports `VALIDATION_FAILED` first; a missing or
      malformed key with a valid body reports the key error as specified.
- **Alternatives:** keep `@Container` (breaks the shared context); scan the whole database for
  reconciliation (collides with the constraint probes); register a second advice beside Boot's problem
  handler (ordering becomes significant); pass a `java.sql.Array` to keep `= ANY` (more JDBC plumbing,
  no behavioural gain); build `CurrentUser` anyway (dead field).
- **Rationale:** every item is either forced by how a library actually behaves or removes code that
  nothing uses. None of them changes a guarantee in `04` §1, a status code in `02` §4.1, or a
  constraint in `03` §3.
- **Consequences:** updated `00` §status/A-1, `01` §1/§1.1/§1.2/§2/§3/§4.2/§4.3/§7, `02` §1/§2.1,
  `03` §6.2/§7.3, `04` §4/§10, `06` §1/§2/§3.1/§3.2/§3.4/§3.7, `07` §2/§3/§4.1/§6, `08` P0–P3 and the
  risk register, and ADR-008 above. Risk R-5 is closed, and risk R-10 is realised with no impact
  beyond the hand-pinned parent.

## ADR-025: Socket timeouts, because a frozen database never errors
- **Context:** P5's outage test (OBS-03 / IT-FAIL-02) froze the PostgreSQL container with
  `docker pause` and `/readyz` never answered — it was still waiting after three minutes. The
  failure-mode table in `01` §9 assumed a dead database produces "Hikari connection errors", which
  is only true when the server **closes** its sockets. A frozen server, a blackholed network path,
  or a hung host answers nothing and closes nothing. Hikari's `connection-timeout` does not help:
  it bounds acquiring a connection from the pool, not a read on a connection already held, so the
  readiness query blocks indefinitely. Measured: with no socket timeout `/readyz` never returned;
  with one it returned 503.
- **Decision:** set JDBC socket-level timeouts on both pools.
  - Main pool: `socketTimeout=60` s, `connectTimeout=10` s, `tcpKeepAlive=true`. The socket timeout
    sits **above** `statement_timeout` (35 s), so a merely slow server still gets to cancel its own
    query and return a precise `57014` rather than having its connection torn down.
  - Observability pool: `socketTimeout=3` s, just above its own 2 s `statement_timeout`, so a scrape
    cannot hang either and the gauge falls back to its last snapshot.
  - Test profile: `socketTimeout=5` s, so the outage test fails fast instead of waiting out
    production-sized timeouts.
- **Alternatives:** rely on OS TCP keepalive alone (default retry windows are minutes to hours, far
  too slow to protect a request thread); shorten `connection-timeout` instead (wrong lever — it
  never covers an in-flight read); drop the test and leave the behaviour unverified (the hang is
  real in production, not an artefact of the test).
- **Rationale:** without this, an unreachable-but-not-closed database turns every request into an
  unbounded wait, and `/readyz` stops being able to report the outage at all. Since `/livez` does
  not touch the database it would keep reporting UP, so no automatic restart would occur either:
  the service would hang indefinitely with no signal. This strengthens ADR-019 rather than changing
  it — the host check stays `/livez`, and `/readyz` now actually reports DOWN.
- **Consequences:** a query that legitimately needs more than 60 s would be severed, which cannot
  happen here because `statement_timeout` is 35 s. Updated `01` §9, `05` §2, `07` §3/§4.1, and `06`
  §3.7. The gauge's failure path is now reachable in bounded time, which is what makes
  `metrics.seat_query_failed` more than a theoretical branch.

## ADR-026: Railway as the host, with four platform defaults that must be overridden
- **Context:** the deployment target changed from Render to Railway. Railway's defaults differ from
  Render's in ways that would quietly break guarantees this specification makes, so the switch is
  more than a provider swap. Facts below were read from Railway's documentation on 2026-10-04 and
  must be re-checked if the platform changes.
- **Decision:** deploy one Docker service plus a Railway PostgreSQL service in the same project, and
  override these defaults, each of which would otherwise break something specific:
  1. **`drainingSeconds` (default 0).** Railway sends SIGTERM and then, by default, "0 seconds to
     gracefully shutdown before being forcefully stopped with a SIGKILL". That would kill in-flight
     reserve transactions on every redeploy, which `07` §9 explicitly budgets 20 s for. Set
     `RAILWAY_DEPLOYMENT_DRAINING_SECONDS` to **25**, comfortably above
     `spring.lifecycle.timeout-per-shutdown-phase` (20 s). A killed transaction is rolled back by
     PostgreSQL and the client's retry replays it, so this costs latency, not correctness — but
     there is no reason to accept it.
  2. **Serverless / app sleeping — off.** A slept service "may return a 502 Bad Gateway response" on
     the first request. A 502 is a 5xx, so a single cold start would fail NFR-1 and the evaluator's
     burst. Set `sleepApplication: false` in `railway.json`, which is stronger than the dashboard
     toggle because config-as-code always wins.
  3. **`overlapSeconds` (zero-downtime overlap).** Railway keeps the old and new deployments running
     together briefly on every redeploy. Correctness survives it — no allocation state lives in the
     process, which is the whole point of ADR-001/ADR-003 — but **connection count does not**: the
     overlap needs `2 × (DB_POOL_MAX_SIZE + 2)` connections, which can exceed a small plan's
     `max_connections`. Set `RAILWAY_DEPLOYMENT_OVERLAP_SECONDS=0` to keep the single-instance
     assumption of ADR-002, or size the pool for double and say so.
  4. **`healthcheckPath = /readyz`, `healthcheckTimeout = 300`.** Railway's healthcheck runs **only
     at deploy time**: the documentation states it "does not monitor the healthcheck endpoint after
     the deployment has gone live" and is "not used for continuous monitoring". The hazard that made
     `/readyz` unsafe on Render — a continuous, 5-second, database-dependent probe restarting the
     only instance mid-burst — therefore does not exist here, so the stronger gate is free: a
     release goes live only once it can actually serve. `PORT` is also what the healthcheck targets.
  5. **`numReplicas: 1`**, which pins ADR-002's single instance instead of trusting a default, and
     **`restartPolicyType: ON_FAILURE`**, which is what a failed migration or startup check produces
     and which also recovers from a database that was briefly absent at boot.
  6. **`PORT` set explicitly to 8080**, and `DB_URL` assembled from the database service's reference
     variables. Railway's `DATABASE_URL` is a `postgres://` URL, which PgJDBC does not accept, so the
     JDBC URL is built from `PGHOST`/`PGPORT`/`PGDATABASE` with the credentials passed separately.
- **Alternatives:** Render (ADR-017; no longer the chosen target); Fly.io (equivalent, same class of
  per-platform defaults to check); leaving the defaults alone and discovering the SIGKILL and the
  cold-start 502 during the evaluated burst.
- **Rationale:** every item above is a platform default that silently violates a stated guarantee.
  Writing them down in `railway.json`, where config-as-code overrides the dashboard, is cheaper than
  rediscovering them under load and stops them drifting when someone clicks something. Every key was
  validated against the published `railway.schema.json`.
- **Consequences:** because nothing probes the service after deploy, **nothing will restart a wedged
  instance**. That raises the value of the external `/readyz` monitor (`05` §8, `SeatresNotReady`)
  and of the socket timeouts in ADR-025, which are now the only thing that keeps an unreachable
  database from hanging the service indefinitely. Updated `00` A-12, `01` §1/§8, `05` §2, `06` §6,
  `07` §1/§5/§7/§9/§11, `08` P7 and R-2, and the README. One residual note: environments created
  before 2025-10-16 have IPv6-only private networking, where a JVM may need
  `-Djava.net.preferIPv6Addresses=true`; newer environments resolve `.railway.internal` to both
  families, so a project created now is unaffected.
