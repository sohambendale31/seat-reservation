# 09 — WRITEUP.md Outline

`WRITEUP.md` is the final engineering write-up. It contains only verified facts. Every number comes
from a recorded run whose raw output is committed under `docs/evidence/`, and every claim about
behaviour is backed by a named test or a reproducible command. Unknowns are stated as unknown.

Target length: 4–7 pages, in a precise first-person engineering voice.

## Section outline

### 1. Summary (≤ 10 lines)
- What was built, the deployed URL (a placeholder until it's real), and the headline guarantees.
- One sentence on the core mechanism: "PostgreSQL row locks on a per-(show, user) quota row and on
  seat rows (ascending id), plus constraints, decide every allocation."

### 2. Exact atomic decision mechanism
- The reserve transaction S1–S9 (condensed from `specs/04` §4), with the SQL for S4, S5, and S8a/S8b.
- The decision points: lock acquisition on the quota row (limit) and on the seat rows
  (availability); the commit for success.
- The per-user limit: the quota row as the serialization point and the CHECK constraint as a
  backstop; the CT-02 result (10 parallel requests, limit 4); why COUNT-then-insert fails (write
  skew), in a 3-line timeline.
- A table of database-enforced vs application-enforced guarantees (`04` §1).
- Why READ COMMITTED + explicit locks rather than SERIALIZABLE, advisory locks, or in-process locks.
- Why there are no show-level counters.
- How overload is absorbed by queueing (generous pool, lock, and statement timeouts) rather than
  shed with 503, and why that keeps 5xx at zero without changing any decision (ADR-022).

### 3. Idempotency implementation
- Scope `(user_id, idem_key)`, fingerprint canonicalization, examples.
- The claim-first-in-the-same-transaction design; how concurrent same-key requests behave
  (unique-index wait); why there's no stuck in-progress state.
- Which outcomes are stored and replayed; replay status semantics and the `Idempotent-Replayed`
  header.
- An honest statement: exactly-once **effect** per key, not exactly-once delivery; why clients must
  reuse keys after ambiguity; the 503 `OUTCOME_UNKNOWN` path.
- Retention (≥ 24 h) and cleanup.

### 4. Multi-seat locking and deadlock avoidance
- The global lock hierarchy L0 → L1 → L2 → L3 and the ascending-id rule.
- The deadlock-freedom argument (short).
- CT-05 evidence: number of rounds, `pg_stat_database.deadlocks` delta.
- What happens on a lock timeout (503, full rollback), and why `lock_timeout` is long enough that it
  isn't reached at evaluation load (ADR-022).

### 5. Cancellation and state transitions
- Seat and reservation state machines.
- Why cancellation can't resurrect or steal seats (the `reservation_id = :rid` predicate, locks, the
  terminal state).
- CT-06/CT-07 evidence.
- The `HELD` state: always 0 in v1, and why.

### 6. Consistency vs availability during DB/network partitions
- The system is CP. PostgreSQL is the single authority; when it's unreachable, writes fail with 503
  and `/readyz` reports DOWN. There's no local fallback that serves stale or optimistic results.
- An app↔DB partition with open transactions: locks are held until
  `idle_in_transaction_session_timeout`, and contenders see 503.
- A client↔app failure after commit: idempotent replay.

### 7. Observability and operational alerts
- The custom metrics, the confirmed vs replayed vs declined accounting, and why replays never count
  as confirmations.
- Cardinality controls: no user, key, reservation, or request ids as labels. `show_id` appears only on
  the per-show seats gauge, limited to the 20 newest shows (ADR-023).
- How `seatres_seats{show_id,status}` reconciles with `GET /shows/{id}`: both are read from
  PostgreSQL, the gauge at scrape time over a dedicated observability pool, so it stays live during
  a burst.
- Reconciliation queries R1–R7 and the metric-vs-DB relations.
- The paging policy: what pages at 2 AM (invariant violations, not ready, sustained 5xx) and what
  doesn't (409s).

### 8. Reproducible load-test evidence
- An environment table per run: machine/plan, CPU, RAM, DB plan, pool size, JVM flags, commit SHA,
  resolved dependency versions, and PostgreSQL `SELECT version()`.
- The exact commands used.
- Results per scenario: the burst script's outcome table copied verbatim (201 / 409 by code /
  other 4xx / 5xx / timeouts / transport errors / replays), logical vs HTTP outcomes, latency
  percentiles, wall time, and the reconcile result.
- Interpretation: correctness assertions (pass/fail); **the 5xx count, which must be 0 in every run,
  including the deployed 20k run** (NFR-1, ADR-022); timeouts; load-generator limits (concurrency ≠
  simultaneous connections; client CPU).
- An explicit statement of what was **not** tested.

### 9. AI assistance disclosure (honest and specific)
- **What AI drafted:** e.g. the specification documents (`specs/`), code scaffolding, test skeletons,
  the burst script structure, documentation prose. List the actual items.
- **What the developer verified:** e.g. SQL reviewed against PostgreSQL's locking/READ COMMITTED
  documentation; concurrency tests run N times; lock ordering reviewed by hand; deployment performed
  and checked manually; load numbers produced by actual runs.
- **What the developer decided independently:** list the actual decisions, including any deviations
  from the spec and why.
- **Corrections made to AI output:** concrete examples of AI suggestions that were rejected or fixed.
- Don't overstate or understate either side.

### 10. Known limitations and next steps
- Single application instance; horizontal scaling isn't part of v1 and hasn't been tested.
- Holds with expiry (the HELD flow) and payment integration.
- Replace the demo token endpoint (`POST /auth/token`, ADR-021) with an external IdP (RS256/JWKS);
  token revocation.
- Fairness, a waiting room, and bot/sybil resistance for on-sales.
- Read scaling for `GET /shows/{id}` (caching, pagination).
- Partitioning idempotency records; backups/PITR.

## Placeholder conventions

Use `⟪TBD: …⟫` for anything not yet measured or deployed, e.g. `⟪TBD: deployed URL⟫` or
`⟪TBD: p95 latency, run L-2⟫`. A finished WRITEUP has no `⟪TBD` markers, except in sections
explicitly marked as not performed.
