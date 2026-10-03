# 04 — Concurrency and Correctness (authoritative)

This document is the contract for every concurrency-relevant decision. If code disagrees with it, the
code is wrong. If another spec disagrees with it, this document wins and the other spec must be fixed.

## 1. Guarantees and who enforces them

| Guarantee | Enforced by PostgreSQL | Enforced by application logic | Verified by |
|---|---|---|---|
| G-1 No seat confirmed to two reservations | Single `seats.reservation_id` column (D-1); `seats_status_reservation_ck` (D-2); partial unique index on active `reservation_seats` (D-4); row lock + `UPDATE ... WHERE status = 'AVAILABLE'` | Lock seats before deciding; assert the update row count = n | CT-01, CT-05, R3/R4/R6 |
| G-2 Per-user limit | `user_show_quotas_held_ck` (`seats_held BETWEEN 0 AND seat_limit`, D-6); row lock on the quota row | Check `held + n ≤ limit` under the lock; update the quota in the same tx | CT-02, R5 |
| G-3 Idempotency (no duplicate logical reservation per key) | Unique `(user_id, idem_key)` (D-7); claim row written in the same tx as the effect | Compare fingerprints; replay the stored response | CT-03, CT-04 |
| G-4 All-or-nothing multi-seat | Single-transaction atomicity | Lock and validate all seats before any write; row-count assertions; exception ⇒ rollback | IT-RES-04, CT-05 |
| G-5 `available + held + confirmed = total` | Each seat row has exactly one status (D-11); seats are never inserted or deleted after creation (X-1) | Counts derived from one statement | R1, burst reconcile |
| G-6 Safe cancellation (no resurrection or foreign release) | Row locks; `UPDATE ... WHERE reservation_id = :rid AND status = 'CONFIRMED'` | Ownership check; row-count assertion | CT-06, CT-07 |
| G-7 Domain conflicts → 4xx, infra failures → 5xx | — | `DbErrorClassifier`; declines are values, not SQL exceptions | UT-05/UT-06/UT-07 |

What is explicitly **not** used, and why:

- **JVM locks / `synchronized` / in-memory maps.** The allocation decision has to be atomic with the
  database write and its rollback, which an in-process lock can't provide. It also wouldn't cover the
  short overlap between the old and new process during a redeploy.
- **Read-then-write without locks** (e.g. `SELECT count(*)` then `INSERT`). Two transactions can both
  read the old count and both insert (write skew under READ COMMITTED).
- **SERIALIZABLE.** It is correct, but on a hot seat it turns contention into `40001` aborts that must
  be retried. Explicit locks give deterministic queueing and a deterministic winner.
- **Advisory locks.** They are invisible to constraints and easy to leak on pooled connections. Row
  locks on real rows are tied to the data they protect.

## 2. Isolation level and timeouts

- **READ COMMITTED** (the PostgreSQL default; the application doesn't override it).
- READ COMMITTED properties this design relies on:
  1. Each **statement** sees a fresh snapshot of committed data. A statement that runs after a lock
     wait sees everything committed before it started.
  2. `SELECT ... FOR UPDATE` and `UPDATE`, when they block on a row locked by another transaction,
     wait for it to finish and then operate on the **latest committed version** of the row,
     re-checking the `WHERE` clause.
  3. An `INSERT` that conflicts with an **uncommitted** row on a unique index waits for the other
     transaction. With `ON CONFLICT DO NOTHING`, it then does nothing if the other transaction
     committed, or inserts if it rolled back.
- Per-connection settings (Hikari `connection-init-sql`, values from env, see `07` §4):
  - `lock_timeout = DB_LOCK_TIMEOUT_MS` (default **30000 ms**)
  - `statement_timeout = DB_STATEMENT_TIMEOUT_MS` (default **35000 ms**, longer than `lock_timeout` so a
    lock wait fails with its own, more specific error)
  - `idle_in_transaction_session_timeout = 30000 ms` (frees locks held by a stalled or partitioned
    app; it only counts *idle* time, not time spent waiting on a lock)
- **Overload policy (ADR-022):** the assignment requires zero 5xx under a ~20k burst. So the timeouts
  are deliberately long: under load, requests **queue** for a connection (pool timeout 60 s) and for
  hot-row locks instead of being shed with 503. A timeout 503 remains possible, but only as a last
  resort, and L-4 must show it doesn't happen at evaluation load. Each waiter's transaction is still
  short, so queues drain quickly.

## 3. Lock hierarchy (global order)

Every transaction acquires locks in this order and never in reverse:

| Level | Resource | Acquired by | How |
|---|---|---|---|
| L0 | Idempotency key `(user_id, idem_key)` | reserve | `INSERT ... ON CONFLICT DO NOTHING` (unique-index insertion lock) |
| L1 | Quota row `(show_id, user_id)` | reserve, cancel | `SELECT ... FOR UPDATE` |
| L2 | Reservation row `R` | cancel only | `SELECT ... FOR UPDATE` |
| L3 | Seat rows, **ascending `seats.id`** | reserve, cancel | `SELECT ... ORDER BY id FOR UPDATE` |

Rows a transaction inserts (new `reservations`, `reservation_seats`, idempotency or quota rows) stay
invisible to others until commit and can't take part in cycles. FK checks take `FOR KEY SHARE` locks
on referenced rows (`shows`, `seats`, `reservations`). Referenced `shows` rows are never updated, and
the referenced seats and reservations are already locked by the same transaction, so FK checks add no
new wait edges between transactions.

**Why ascending `seats.id`:** `id` is an immutable identity value. PostgreSQL applies `ORDER BY`
before taking row locks, so rows are locked in sorted order. Because the sort key never changes, the
PostgreSQL docs' caveat about rows being returned out of order after a lock wait can't apply. Labels
are resolved to ids *before* locking, so the order of labels in the request doesn't matter.

**Deadlock-freedom argument.** A deadlock needs a cycle of transactions, each waiting for a lock held
by the next. Within one level, all transactions acquire locks in the same total order (L0: one key
per tx; L1: one quota row per tx; L2: one reservation per tx; L3: ascending id). Across levels, every
transaction acquires in increasing level order. Every wait edge therefore goes from a transaction to
a lock strictly greater, in the lexicographic order `(level, key)`, than every lock it already holds.
A cycle would need some lock to be greater than itself, which is impossible, so these transactions
**cannot deadlock**. The deadlock retry (§10) remains as a safety net, and CT-05 asserts that
`pg_stat_database.deadlocks` doesn't increase.

## 4. Reserve transaction (exact)

Input: `ReserveCommand(showId, userId, labels[] (validated, unique, sorted), idemKey, fingerprint)`,
with `n = labels.length` (1–10).

```sql
-- BEGIN (READ COMMITTED), opened by TxExecutor

-- S1  [L0] claim idempotency key
INSERT INTO idempotency_records (id, user_id, idem_key, request_fingerprint, created_at, expires_at)
VALUES (:idemId, :userId, :idemKey, :fingerprint, now(), now() + :retention::interval)
ON CONFLICT (user_id, idem_key) DO NOTHING
RETURNING id;
```

`:retention` is the ISO-8601 duration from `APP_IDEMPOTENCY_RETENTION` (e.g. `PT24H`), which
PostgreSQL accepts as interval input.

- 1 row returned → this transaction owns the key; continue to S2.
- 0 rows → another **committed** transaction owns it. (If that transaction was still in flight, S1
  waited for it.)

```sql
-- S1b
SELECT request_fingerprint, response_status, response_body
FROM idempotency_records
WHERE user_id = :userId AND idem_key = :idemKey;
```

  - `request_fingerprint = :fingerprint` → **Replayed**(status, body). Roll back (nothing was
    written) and return.
  - Otherwise → throw `IdempotencyKeyReusedException` → 409 `IDEMPOTENCY_KEY_REUSED`; roll back.
  - Row not found (deleted by cleanup between S1 and S1b, which is only possible after ≥ 24 h) →
    repeat S1 once. If it still races, respond 503 `SERVICE_UNAVAILABLE`.

```sql
-- S2  show
SELECT per_user_limit FROM shows WHERE id = :showId;
```

- Not found → **Declined** 404 `SHOW_NOT_FOUND` → finalize (S9 with the 404 body) → COMMIT.

```sql
-- S3  resolve labels to seat ids (no locks)
SELECT id, label FROM seats
WHERE show_id = :showId AND label = ANY(:labels)
ORDER BY id;
```

- Fewer than n rows → **Declined** 422 `UNKNOWN_SEAT` (`unknownSeats` = the labels that weren't
  returned) → S9 → COMMIT.
- Otherwise keep `seatIds` (ascending). Labels and ids are immutable, so this unlocked read is safe.

```sql
-- S4  [L1] ensure + lock the quota row
INSERT INTO user_show_quotas (show_id, user_id, seats_held, seat_limit)
VALUES (:showId, :userId, 0, :perUserLimit)
ON CONFLICT (show_id, user_id) DO NOTHING;

SELECT seats_held, seat_limit
FROM user_show_quotas
WHERE show_id = :showId AND user_id = :userId
FOR UPDATE;
```

- `seats_held + n > seat_limit` → **Declined** 409 `USER_LIMIT_EXCEEDED` (`perUserLimit = seat_limit`,
  `seatsHeld`, `seatsRequested = n`) → S9 → COMMIT. If S4's insert created the row, committing a
  zero-quota row is harmless and keeps R5 consistent.

```sql
-- S5  [L3] lock seats in ascending id order
SELECT id, label, status, price_paise
FROM seats
WHERE show_id = :showId AND id = ANY(:seatIds)
ORDER BY id
FOR UPDATE;
```

- Rows ≠ n → `IllegalStateException` (seats are never deleted, so this is a bug) → rollback → 500.
- Any `status <> 'AVAILABLE'` → **Declined** 409 `SEAT_UNAVAILABLE` (`unavailableSeats` = those
  labels) → S9 → COMMIT, which releases the locks.
- Otherwise all n seats are locked and AVAILABLE, and no other transaction can change them until
  this one commits.

```sql
-- S6  write reservation
INSERT INTO reservations (id, show_id, user_id, status, seat_count, total_paise, created_at)
VALUES (:reservationId, :showId, :userId, 'CONFIRMED', :n, :totalPaise, now());

-- S7  write links (JDBC batch, n rows)
INSERT INTO reservation_seats (reservation_id, seat_id, show_id, price_paise)
VALUES (:reservationId, :seatId, :showId, :pricePaise);

-- S8a confirm seats (defence in depth: the predicate re-checks AVAILABLE)
UPDATE seats
SET status = 'CONFIRMED', reservation_id = :reservationId
WHERE show_id = :showId AND id = ANY(:seatIds) AND status = 'AVAILABLE';
-- assert updated rows = n, else IllegalStateException → rollback → 500

-- S8b quota (the DB CHECK enforces seats_held ≤ seat_limit even if S4's check were buggy)
UPDATE user_show_quotas
SET seats_held = seats_held + :n
WHERE show_id = :showId AND user_id = :userId;
-- assert updated rows = 1

-- S9  finalize idempotency record (used by every committing path above)
UPDATE idempotency_records
SET response_status = :status, response_body = :body, completed_at = now()
WHERE id = :idemId;
-- assert updated rows = 1

-- COMMIT
```

`totalPaise` is computed in Java with `Math.addExact` over the locked rows' `price_paise`. The 201
body and the decline bodies are serialized **before** S9, so the stored bytes are exactly the bytes
sent.

**Rollback behaviour:** any exception in S1–S9 (SQL error, assertion, serialization error) rolls back
the entire transaction, including the idempotency claim. Nothing partial is ever committed. Declines
are not exceptions: they are normal return values that commit only the idempotency record (plus,
possibly, a new zero-valued quota row).

## 5. Idempotency under concurrency

| Case | Mechanism | Result |
|---|---|---|
| Same key, sequential | S1 conflict → S1b → same fingerprint | Replay with the original status/body and `Idempotent-Replayed: true` |
| Same key, concurrent, same body | The second S1 **blocks** on the unique index until the first ends. If the first commits, the second hits the conflict, and S1b (a new statement, so a new snapshot) sees the committed row and replays it. If the first rolls back, the second's insert succeeds and it executes normally. | Business logic runs to a committed outcome exactly once per key |
| Same key, concurrent, different body | Same blocking; after the commit, the S1b fingerprint differs | 409 `IDEMPOTENCY_KEY_REUSED`, no allocation |
| Same key, different user | Different `(user_id, idem_key)` rows | Independent |

There are no "stuck in-progress" keys: the claim row only becomes visible when its transaction
commits, and every committing path finalizes it first (X-6). A crash before commit rolls the claim
back, so the next retry executes from scratch.

What idempotency guarantees, precisely: **for a given (user, key), at most one canonical request is
ever executed to a committed outcome, and every later request with that key receives that outcome
(or `IDEMPOTENCY_KEY_REUSED`) for at least the retention period.** It does **not** give exactly-once
network delivery. A response can be lost after commit, and the client can't tell "not executed" from
"executed, response lost". So the client **must** retry with the same key after a timeout, a
transport error, or a `503`, and must never generate a new key for a retry.

## 6. Cancel transaction (exact)

```sql
-- BEGIN (READ COMMITTED)

-- C1  locate + ownership (no lock; show_id and user_id are immutable)
SELECT show_id, user_id FROM reservations WHERE id = :reservationId;
-- not found OR user_id <> :callerId → 404 RESERVATION_NOT_FOUND (rollback; nothing written)

-- C2  [L1] lock the quota row (it exists: the reserve that created this reservation created it)
SELECT seats_held FROM user_show_quotas
WHERE show_id = :showId AND user_id = :callerId
FOR UPDATE;
-- not found → IllegalStateException → 500

-- C3  [L2] lock the reservation and read its current status
SELECT status, seat_count, total_paise, created_at, cancelled_at
FROM reservations WHERE id = :reservationId
FOR UPDATE;
-- status = 'CANCELLED' → no-op: load seats from reservation_seats, return 200 (commit, nothing written)

-- C4  [L3] lock this reservation's seats in ascending id order
SELECT id FROM seats WHERE reservation_id = :reservationId ORDER BY id FOR UPDATE;
-- assert rows = seat_count (X-2), else IllegalStateException → 500

-- C5  release ONLY seats that still belong to this reservation
UPDATE seats
SET status = 'AVAILABLE', reservation_id = NULL
WHERE reservation_id = :reservationId AND status = 'CONFIRMED';
-- assert rows = seat_count

-- C6
UPDATE reservation_seats SET released_at = now()
WHERE reservation_id = :reservationId AND released_at IS NULL;
-- assert rows = seat_count

-- C7
UPDATE reservations SET status = 'CANCELLED', cancelled_at = now()
WHERE id = :reservationId AND status = 'CONFIRMED';
-- assert rows = 1

-- C8  (the DB CHECK enforces seats_held ≥ 0)
UPDATE user_show_quotas SET seats_held = seats_held - :seatCount
WHERE show_id = :showId AND user_id = :callerId;
-- assert rows = 1

-- COMMIT
```

The response's seats come from `reservation_seats JOIN seats` (labels and prices), which survive
cancellation.

**Why cancellation can't resurrect or steal seats:** C5's predicate is `reservation_id = :rid`. A
seat released by R and then reserved by another user has `reservation_id = R'` and doesn't match. A
repeated or concurrent cancel of R blocks at C2/C3, then sees `CANCELLED` (C3 reads the latest
committed version after the wait) and writes nothing. The only code path that sets a seat to
`CONFIRMED` is S8a, which requires `status = 'AVAILABLE'` under the lock.

## 7. Mapping outcomes to HTTP

| Outcome | Source | HTTP | Stored for replay |
|---|---|---|---|
| Confirmed | S9 after S8 | 201 | yes |
| Show missing | S2 | 404 `SHOW_NOT_FOUND` | yes |
| Unknown label | S3 | 422 `UNKNOWN_SEAT` | yes |
| Limit | S4 | 409 `USER_LIMIT_EXCEEDED` | yes |
| Seat taken | S5 | 409 `SEAT_UNAVAILABLE` | yes |
| Replay | S1b, same fingerprint | original status | — |
| Key reuse | S1b, different fingerprint | 409 `IDEMPOTENCY_KEY_REUSED` | no |
| `40P01` / `40001` after max attempts | classifier | 503 `SERVICE_UNAVAILABLE` | no |
| `55P03` lock timeout, `57014` statement timeout, pool timeout, `08xxx`, `57P01`, `53300` | classifier | 503 `SERVICE_UNAVAILABLE` | no |
| Exception thrown by `commit()` with a connection-class failure | TxExecutor | 503 `OUTCOME_UNKNOWN` | unknown |
| `23xxx` integrity violation, assertion failure, anything else | classifier | 500 `INTERNAL_ERROR` | no |

Integrity violations (`23505`, `23514`, `23503`) are **never** turned into 409. Every domain conflict
is detected by an explicit check under a lock **before** any write, so a constraint firing means the
locking logic is broken. It surfaces as a 500 with an ERROR log and increments
`seatres_invariant_violations_total{check="db_constraint"}` (row-count assertion failures increment
`check="rowcount_assertion"`). Either one pages (05 §8).

## 8. Race scenario walkthroughs

### 8.1 Hundreds of users request the same seat (A1)

1. All requests pass S1 (distinct users and keys, no contention), S2, and S3.
2. Each request takes its **own** quota row at S4 (different users, so no contention) and passes the
   limit check.
3. All of them queue at S5 on A1's row lock. At most `DB_POOL_MAX_SIZE` transactions can be queued at
   once, because each one holds a connection. The rest wait in the Hikari pool.
4. The first to lock A1 sees AVAILABLE, confirms, and commits.
5. Each waiter then acquires the lock on the latest version, sees `CONFIRMED`, declines with 409,
   finalizes, and commits, which releases the lock to the next waiter. The queue drains serially,
   one short transaction per waiter.
6. **Result:** exactly one 201; every other request returns 409 `SEAT_UNAVAILABLE`, which is the
   assignment's "exactly one 201, everyone else 409". Lock waits are bounded by `lock_timeout` (30 s)
   and connection waits by `DB_POOL_CONNECTION_TIMEOUT_MS` (60 s). Both are sized so that they are
   **not** reached at evaluation load (verified by L-4). If one ever were, the result would be a
   retryable 503, never a wrong 201 or a fake 409.

### 8.2 One user sends ten parallel requests with different keys, limit 4 (one distinct seat each)

1. All ten S1 claims succeed (different keys).
2. All ten reach S4. One S4 insert creates the quota row; the others' inserts wait on the unique
   index until that transaction commits, then do nothing. All ten then serialize on
   `SELECT ... FOR UPDATE` of the **same** quota row.
3. Holder #1 sees `held=0`, locks its seat, confirms, sets `held=1`, and commits. #2 then reads
   `held=1` (the latest committed version), and so on until #4 commits `held=4`.
4. #5–#10 read `held=4`; `4 + 1 > 4`, so they return 409 `USER_LIMIT_EXCEEDED`.
5. **Result:** exactly 4 × 201 and 6 × 409, and the user holds exactly 4 seats. Even if the S4 check
   had a bug, S8b would violate `seats_held ≤ seat_limit`, the transaction would roll back (500), and
   the limit would still hold in the database.
6. With the broken alternative (`SELECT count(*)` then reserve, with no quota lock), all ten read 0
   concurrently and all succeed. This design prevents exactly that.

### 8.3 Identical requests arrive concurrently with the same key

1. Request A inserts the claim row. B, C, … block at S1 on the unique index.
2. A runs S2–S9 and commits.
3. B, C, … wake up with a conflict, do nothing, and S1b sees A's committed record with the same
   fingerprint, so each one replays A's status and body.
4. **Result:** one logical reservation, N HTTP 201 responses with the same `reservationId`, and N−1
   of them carrying `Idempotent-Replayed: true`. If A's outcome was a 409 decline, everyone gets that
   same 409. If A rolled back (5xx), B executes as the new owner.

### 8.4 The same key is used concurrently with different seat lists

As in 8.3, but the waiters' fingerprints differ from the winner's, so they get 409
`IDEMPOTENCY_KEY_REUSED`. A waiter whose seat list matches the winner's (same set, any order) gets
the replay. Exactly one seat list is ever allocated for that key. Which list wins depends on which S1
insert happens first, and the loser always learns that the key is taken.

### 8.5 Two users request overlapping multi-seat sets in different orders

User X requests `[A3, A1, A2]` and user Y requests `[A2, A3, A4]`. Labels resolve to ids, and S5 locks
in ascending order: X locks A1→A2→A3, and Y locks A2→A3→A4.

- If X gets A2 first, Y waits on A2 while holding no seat lock (A2 is Y's smallest id). X then takes
  A3, confirms, and commits. Y gets A2, sees `CONFIRMED`, and the whole request returns 409. A4 is
  **not** reserved for Y (all-or-nothing).
- No interleaving produces a cycle (§3 argument). Without sorted locking, X holding A3 and waiting
  for A2 while Y holds A2 and waits for A3 would deadlock. PostgreSQL would then abort one with
  `40P01` after `deadlock_timeout` (default 1 s).
- If a lock wait exceeds `lock_timeout`, the transaction rolls back entirely, including the seats it
  had locked, and returns 503. Locks are never partially kept.

### 8.6 Cancellation races with another user's reservation

User A cancels R, which holds seat B7. User U requests B7.

- **The cancel commits first.** U, possibly waiting at S5 behind C4's lock, then locks B7, sees
  AVAILABLE, and confirms. That's correct: the seat was free.
- **U locks B7 first (the cancel waits at C4).** U sees `CONFIRMED` (owned by R), returns 409, and
  commits. The cancel then proceeds and releases B7. U's 409 was true at the moment U held the lock.
  U may retry with a **new** key.
- **A retries the cancel after U has re-reserved B7.** C3 sees R `CANCELLED`, so it's a no-op. Even
  hypothetically, C5's `reservation_id = R` can't match B7 (now `R'`). No resurrection, no theft.
- **A cancels while also reserving in the same show.** Both transactions take A's quota row first
  (L1) and serialize. The reserve sees either the pre-cancel `seats_held` (conservative: it may
  decline on the limit) or the post-cancel value. It can never exceed the limit.

### 8.7 The database connection drops during commit and the client retries

- If the failure happens **before** COMMIT is sent, the server-side session rolls back. TxExecutor
  sees an exception in the callback and returns 503 `SERVICE_UNAVAILABLE`.
- If it happens **during** `commit()`, the outcome is unknown: PostgreSQL may or may not have
  committed. TxExecutor detects that the exception came from `commit()` with a connection-class
  failure and returns 503 `OUTCOME_UNKNOWN`. It does **not** retry internally: the connection is
  suspect, and the client owns retries.
- The client retries with the **same** key:
  - Committed → S1 conflict → replay of the 201 with the original `reservationId`. No double
    allocation.
  - Not committed → S1 inserts → executes fresh. That may now produce a 409 if someone else took the
    seat meanwhile, which is true.
- **Metric caveat:** the confirmed counter increments only in after-commit hooks, so a reservation
  that committed with an unknown outcome isn't counted at the time. Its replay is counted as a
  replay, not a confirmation. So `seatres_reservations_confirmed_total` may **undercount** but never
  overcounts. PostgreSQL is authoritative (05 §6).

### 8.8 The application crashes before or after transaction commit

- **Before commit** (process killed, OOM): the TCP connection closes, and PostgreSQL aborts the
  transaction and releases all locks. If the host disappears without closing sockets,
  `idle_in_transaction_session_timeout` (30 s) or TCP keepalive ends the session. Until then,
  contenders on those rows wait up to `lock_timeout` and get 503. The client sees a timeout or a
  transport error, retries with the same key, and the request executes fresh.
- **After commit, before the response:** the effect is durable. The client retries with the same key
  and gets a replay.
- **Cleanup job:** each batch runs in its own transaction. A crash mid-job rolls back that batch, and
  the next run continues.

## 9. Unknown outcomes: client contract

1. Generate one `Idempotency-Key` (UUID) per **logical** reservation attempt.
2. On 201, 404, 409, or 422: done. The outcome is final for that key.
3. On a timeout, a transport error, or a `503` (`SERVICE_UNAVAILABLE` or `OUTCOME_UNKNOWN`): retry the
   **same** request with the **same** key, using exponential backoff with jitter (e.g.
   100 ms × 2^k, capped at 5 s, ≤ 6 attempts) and honoring `Retry-After`.
4. On `409 IDEMPOTENCY_KEY_REUSED`: this is a client bug; don't retry.
5. Retries must happen within the retention window (≥ 24 h).

## 10. Retry policy inside the server (`TxExecutor`)

```text
attempt = 1
loop:
  status = txManager.getTransaction(PROPAGATION_REQUIRED, default isolation, timeout = 45s)  // > statement_timeout
  try:
     result = callback()
  catch (Throwable t):
     txManager.rollback(status)
     cat = classifier.classify(t)
     if cat == RETRYABLE (40P01, 40001) and attempt < 3:
         sleep(random(5..25 ms) × attempt); attempt++; continue
     throw mapped(cat)          // RETRYABLE-exhausted/BUSY/UNAVAILABLE → 503; BUG → 500; ApiException → as is
  try:
     txManager.commit(status)
  catch (Throwable t):
     if classifier.isConnectionFailure(t): throw OutcomeUnknownException   // 503 OUTCOME_UNKNOWN
     throw mapped(classifier.classify(t))                                  // definite failure → 503/500
  runAfterCommitHooks()         // metrics only; never throws to the client
  return result
```

- Retrying is safe because the failed attempt rolled back **everything**, including the idempotency
  claim.
- Business declines are return values and are never retried.
- `IdempotencyService` and the repositories never open their own transactions. They assert
  `TransactionSynchronizationManager.isActualTransactionActive()`.

`DbErrorClassifier` walks the cause chain to the first `java.sql.SQLException` and switches on
`getSQLState()`:

| SQLState | Meaning | Category |
|---|---|---|
| `40P01` | deadlock_detected | RETRYABLE |
| `40001` | serialization_failure | RETRYABLE |
| `55P03` | lock_not_available (lock_timeout) | BUSY → 503 |
| `57014` | query_canceled (statement_timeout) | BUSY → 503 |
| `53300` | too_many_connections | UNAVAILABLE → 503 |
| `57P01`, `57P02`, `57P03` | admin/crash shutdown, cannot connect now | UNAVAILABLE → 503 |
| `08***` | connection exception | UNAVAILABLE → 503 (or OUTCOME_UNKNOWN in the commit phase) |
| `23***` | integrity constraint violation | BUG → 500 + invariant metric |
| other / no SQLState | — | BUG → 500 |

A `java.sql.SQLTransientConnectionException` from Hikari (pool timeout) is classified as
UNAVAILABLE.

## 11. Correctness arguments per invariant

- **G-1 (no double-sell).** Suppose two committed reservations R1 ≠ R2 both hold seat s. A seat holds
  a reservation only through `seats.reservation_id`, which has one value, so at most one of them
  currently points at s (D-1). Could R2 have taken s while R1 still held it? Taking s requires S8a
  with `status = 'AVAILABLE'` under a row lock. While R1 holds s, s is `CONFIRMED` (D-2), so S8a
  can't match and its row-count assertion aborts R2. Independently, D-4 forbids two active links
  for s. ∎
- **G-2 (limit).** Every allocation for (show, user) executes S8b on the same quota row while holding
  its lock, acquired at S4. These transactions are totally ordered by that lock, and each one reads
  the latest committed `seats_held` after acquiring it (READ COMMITTED, new statement). A transaction
  allocates only if `seats_held + n ≤ seat_limit`, and D-6 rejects any commit that violates it.
  Cancels only decrease the value, under the same lock. X-4 holds because the quota and the seat
  changes happen in the same transaction. So the user's actual seats are always ≤
  `seat_limit = per_user_limit`. ∎
- **G-3 (idempotency).** The claim insert and the business effect commit atomically. D-7 allows one
  committed claim per (user, key). Any transaction that runs the business logic owns an inserted
  claim, so at most one committed execution per key exists. Every other request observes the
  committed claim through S1b and either replays it or is rejected. ∎
- **G-4 (all-or-nothing).** All n seats are locked and validated (S5) before any write. The writes
  (S6–S8) happen in one transaction with row-count assertions, and any failure rolls everything
  back. A decline commits no seat changes. ∎
- **G-5 (count invariant).** D-11 gives each seat exactly one status, so the per-status counts
  partition the seat set in any snapshot. X-1 fixes the seat set at creation. `GET` derives the
  counts from one statement. ∎
- **G-6 (safe cancel).** See §6. Only seats with `reservation_id = R` are released, under locks, and
  only from a `CONFIRMED` R, exactly once (C7 is guarded by `status = 'CONFIRMED'`). ∎
- **Deadlock freedom.** See §3.
- **Liveness.** Every lock wait is bounded by `lock_timeout`. Every transaction is short (≤ ~12
  statements, no external calls inside a transaction), and no transaction waits on anything except
  PostgreSQL.

## 12. Known residual risks

| Risk | Impact | Mitigation |
|---|---|---|
| Hot-seat waiter chain | Tail latency under the burst; a 503 only if the chain exceeds `lock_timeout` (30 s) | The pool bounds the chain; timeouts are long and tunable; L-4 proves 0 × 5xx at evaluation load. **Fallback if L-4 shows otherwise:** decline obviously-taken seats on a fast path (look up the stored idempotent answer first; then an unlocked read seeing `CONFIRMED` → 409) so losers never queue on the hot row's lock. A declined seat never becomes a confirmation, so double-selling stays impossible |
| Evaluator client timeouts | If queueing makes a response slower than the evaluator's client timeout, they see a timeout (not a 5xx, but not a clean answer either) | Size the plan so p99 under the 20k burst stays well below common client timeouts; report the measured percentiles |
| Every request, including declines, writes and fsyncs an idempotency record | Lower maximum throughput | Accepted, for correct replays |
| Commit-unknown undercounts metrics | Dashboards briefly read low | PostgreSQL is authoritative |
| PgBouncer in transaction mode (some hosts) | Server-side prepared statements and session `SET`s may break | Use a direct (non-pooled) connection string; see `07` |
