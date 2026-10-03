# 05 — Observability

## 1. Principles

- PostgreSQL is the truth; metrics are a fast, approximate view of it. Counters may **undercount**
  after crashes or ambiguous commits, but they never overcount logical outcomes.
- Every custom meter has a bounded label set, enumerated below. No user ids, idempotency keys,
  reservation ids, request ids, or seat labels are used as label values. Show ids appear **only** on
  the `seatres_seats` gauge, limited to the 20 most recently created shows (ADR-023).
- Logs are structured JSON, one event per line, correlated by `requestId`.

## 2. Health: liveness and readiness

| Endpoint | Group | Contributors | Semantics | DB touched? |
|---|---|---|---|---|
| `GET /livez` | liveness | `livenessState` | The process is alive; restart only if DOWN | **No** (a DB outage must not trigger restarts) |
| `GET /readyz` | readiness | `readinessState`, `db` | The app can serve traffic: started, not shutting down, and PostgreSQL answers a validation query | Yes |

Configuration (`application.yml`):

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  endpoint:
    health:
      probes:
        enabled: true
        add-additional-paths: true     # exposes /livez and /readyz on the main port
      show-details: never
      group:
        liveness:
          include: livenessState
        readiness:
          include: readinessState,db
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
```

- Readiness is DOWN (HTTP 503) when PostgreSQL is unreachable. The `db` indicator borrows a Hikari
  connection and runs `Connection.isValid`. When the pool is saturated it can wait up to the Hikari
  connection timeout. So `/readyz` is **not** the hosting platform's health check on a single-instance
  host with short check timeouts that restarts failing instances (Render: 5 s timeout, restart after
  60 s of failures). The host checks `/livez` instead (ADR-019). During a burst, `/readyz` can wait
  in line for a pool connection for up to the 60 s pool timeout (ADR-022). The `SeatresNotReady`
  alert's 2-minute `for:` window absorbs that, so a short burst doesn't page.
- During graceful shutdown, Spring sets readiness to `REFUSING_TRAFFIC`, so `/readyz` returns 503
  and the host stops routing to the container.

## 3. Actuator exposure and security

| Path | Access |
|---|---|
| `/livez`, `/readyz` (and `/actuator/health`) | Public; status only (`show-details: never`) |
| `/actuator/prometheus` | Public. It exposes only aggregate counters and gauges with the bounded labels below: no identifiers, no request data. In a real production setup, restrict it at the network level (internal port or IP allow-list). |
| All other actuator endpoints | Not exposed (`env`, `beans`, `heapdump`, `threaddump`, `loggers`, `configprops`, …) |

## 4. Metric catalogue

Micrometer names (dot form) are what the code registers; Prometheus names are what is exposed. All
custom meters are registered eagerly at startup (counters at 0), so the names exist before traffic.
Test OBS-05 asserts that every Prometheus name below appears in `/actuator/prometheus`.

### 4.1 Custom metrics

| Micrometer name | Prometheus name | Type | Labels (allowed values) | Meaning |
|---|---|---|---|---|
| `seatres.reservations.confirmed` | `seatres_reservations_confirmed_total` | Counter | — | **New logical reservations committed.** Incremented once, after commit. Never incremented for replays. |
| `seatres.reservation.declines` | `seatres_reservation_declines_total` | Counter | `reason` ∈ {`seat_unavailable`, `user_limit_exceeded`} | **First-time** domain declines, decided by this request and incremented after commit. Replayed declines aren't counted here. |
| `seatres.idempotency.replays` | `seatres_idempotency_replays_total` | Counter | `status` ∈ {`201`, `404`, `409`, `422`} | Reserve responses served from stored outcomes. **Not** new reservations. |
| `seatres.invariant.violations` | `seatres_invariant_violations_total` | Counter | `check` ∈ {`db_constraint`, `rowcount_assertion`} | A safety net fired (04 §7). **Any increase pages.** |
| `seatres.seats` | `seatres_seats` | Gauge | `show_id` (the 20 most recently created shows), `status` ∈ {`available`, `held`, `confirmed`} | Seat counts **per show**, read from PostgreSQL **at scrape time** over the dedicated observability pool (§4.4) |

### 4.2 Built-in metrics used by dashboards and alerts

| Prometheus name | Labels | Use |
|---|---|---|
| `http_server_requests_seconds` (`_bucket`, `_count`, `_sum`) | `method`, `uri` (template; bounded), `status`, `outcome`, `exception` | Latency and 4xx/5xx rates. Under ADR-022 any 5xx during a burst is a failure to investigate, not designed load shedding |
| `hikaricp_connections_active`, `hikaricp_connections_pending`, `hikaricp_connections_max`, `hikaricp_connections_timeout_total` | `pool` | Pool saturation |
| `jvm_*`, `process_*` | — | Runtime health |

### 4.3 Replay semantics

A replay returns the stored outcome of an earlier request without touching seats or quotas. It uses
the **original HTTP status** (e.g. 201) and the original body, plus the header
`Idempotent-Replayed: true`. It is counted in `seatres_idempotency_replays_total` and **never** in
`seatres_reservations_confirmed_total` or `seatres_reservation_declines_total`. Therefore:

```
logical reservations created  = seatres_reservations_confirmed_total            (≤ truth in DB)
HTTP 201 from reserve         = seatres_reservations_confirmed_total + seatres_idempotency_replays_total{status="201"}
```

### 4.4 Available-seat gauge semantics and cardinality

- `seatres_seats{show_id,status}` is computed **at scrape time** (ADR-023). `SeatGaugeCollector` is
  registered with the Prometheus registry, and every scrape of `/actuator/prometheus` runs one query:

  ```sql
  SELECT st.show_id, st.status, count(*)
  FROM seats st
  JOIN (SELECT id FROM shows ORDER BY created_at DESC LIMIT 20) recent ON recent.id = st.show_id
  GROUP BY st.show_id, st.status;
  ```

  Statuses with no seats are exposed as 0, so each tracked show always has all three series.
- The query runs on a **dedicated observability pool** (2 connections, connection timeout 2 s,
  `statement_timeout` 2 s), never the reservation pool. During a 20k burst the main pool has a long
  queue (ADR-022). A gauge sharing that pool would stall exactly while the evaluator is watching.
- If the query fails or times out, the collector serves the **last successful snapshot** and logs
  `metrics.seat_query_failed` (WARN), so a scrape never errors.
- Why `show_id` is acceptable here: show ids aren't personal data, and only the 20 newest shows are
  tracked, so the series count is bounded (≤ 60). For older shows, use `GET /shows/{id}`, which is
  always exact.
- Cardinality of all custom metrics: 1 + 2 + 4 + 2 + 60 = at most 69 series.

## 5. Counting rules (exactly when each counter moves)

| Event | confirmed | declines{reason} | replays{status} | invariant.violations |
|---|---|---|---|---|
| New reservation committed | +1 | — | — | — |
| New seat decline committed | — | seat_unavailable +1 | — | — |
| New limit decline committed | — | user_limit_exceeded +1 | — | — |
| Replay of a stored outcome | — | — | +1 {original status} | — |
| Key reuse (409 `IDEMPOTENCY_KEY_REUSED`) | — | — | — | — |
| 404 / 422 (first time) | — | — | — | — |
| 503 | — | — | — | — |
| Constraint violation / row-count assertion | — | — | — | +1 {check} |

Increments happen in after-commit hooks (or, for invariant violations, on the error path after
rollback). Nothing is incremented inside a transaction that might roll back.

## 6. Metric reconciliation against PostgreSQL

Authoritative queries (run manually, or implicitly through the burst script's reconcile step):

```sql
-- Logical reservations created (lifetime)
SELECT count(*) FROM reservations;
-- Seats by status for one show (compare with seatres_seats{show_id=...} and GET /shows/{id})
SELECT status, count(*) FROM seats WHERE show_id = :showId GROUP BY status;
-- Outcomes recorded within the idempotency retention window
SELECT response_status, count(*) FROM idempotency_records GROUP BY response_status;
```

Expected relations since the process started (counters reset on restart; use
`increase()`/`rate()` in PromQL):

- `seatres_reservations_confirmed_total` ≤ the number of reservations created in the same window,
  with equality unless an `OUTCOME_UNKNOWN` or a crash occurred (04 §8.7).
- For a show among the 20 newest, `seatres_seats{show_id="S",status="…"}` equals the matching count
  from `GET /shows/S` and from the query above. All three read committed data, so they agree exactly
  once the burst settles. During a burst they can differ only by transactions that commit between
  the two reads.
- `available + held + confirmed` across a show's three series equals `total_seats` in every scrape,
  because one query produces all three (the same single-snapshot argument as `GET /shows/{id}`).
- `seatres_seats{status="held"}` = 0 (v1).
- The invariant queries R1–R7 (03 §7.3) return no rows. Run them after any invariant alert.

## 7. Structured logging

- Format: Spring Boot built-in structured logging, ECS JSON on stdout
  (`logging.structured.format.console: ecs`) in all profiles.
- MDC keys (added by `RequestIdFilter` and the services, cleared in `finally`):

| Key | Value | Notes |
|---|---|---|
| `requestId` | `X-Request-Id` (validated) or a generated UUID | Echoed in the response header and the problem body |
| `userRef` | first 12 hex chars of SHA-256(`sub`) | Correlatable without logging the raw subject |
| `showId` | UUID | |
| `reservationId` | UUID | |
| `idemKeyRef` | first 12 hex chars of SHA-256(`idem_key`) | Raw keys are never logged |

- Log events:

| Event | Level | Fields |
|---|---|---|
| `reservation.confirmed` | INFO | showId, reservationId, seatCount, totalPaise, durationMs |
| `reservation.declined` | INFO | showId, code, seatCount |
| `reservation.replayed` | INFO | showId, originalStatus |
| `reservation.cancelled` | INFO | reservationId, noop (true/false) |
| `db.transient_failure` | WARN | sqlstate, kind (lock_timeout / statement_timeout / pool_timeout / connection / deadlock_retry) |
| `db.outcome_unknown` | ERROR | showId, idemKeyRef |
| `metrics.seat_query_failed` | WARN | sqlstate (the gauge served its last snapshot) |
| `invariant.violation` | ERROR | check, sqlstate/constraint (server-side only), stack trace |

### 7.1 Redaction and sensitive data

Never logged: the `Authorization` header, JWTs (including tokens issued by `POST /auth/token`),
`APP_AUTH_JWT_SECRET`, `APP_AUTH_ADMIN_KEY` and the `X-Admin-Key` header, DB credentials, raw
idempotency keys, raw request/response bodies, and the raw `sub`. JDBC driver exception messages can
contain SQL parameter values. They are logged only for the `BUG` category, where they're needed for
diagnosis and contain only seat ids/labels and UUIDs (never secrets). Problem responses never contain
driver messages.

## 8. Dashboards and alerts

### 8.1 Dashboard "Seat Reservation — On-sale"

- Outcomes: `rate(seatres_reservations_confirmed_total[1m])`,
  `sum by (reason) (rate(seatres_reservation_declines_total[1m]))`,
  `sum by (status) (rate(seatres_idempotency_replays_total[1m]))`.
- Inventory: `seatres_seats{show_id="<show>"}` by status for the show on sale; `sum by (status) (seatres_seats)` across the tracked shows.
- Latency: `histogram_quantile(0.5|0.95|0.99, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/shows/{showId}/reserve"}[5m])))`.
- Errors: `sum by (status) (rate(http_server_requests_seconds_count{status=~"5.."}[1m]))`. Expected to stay at 0 during a burst (ADR-022).
- Capacity: `hikaricp_connections_active / hikaricp_connections_max`, `hikaricp_connections_pending`.
- Integrity: `increase(seatres_invariant_violations_total[1h])`.

### 8.2 Alert rules

| Alert | Expression | For | Severity |
|---|---|---|---|
| `SeatresInvariantViolation` | `increase(seatres_invariant_violations_total[5m]) > 0` | 0m | **page** |
| `SeatresNotReady` | external probe of `/readyz` failing, or `up{job="seatres"} == 0` | 2m | **page** |
| `SeatresHigh5xx` | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m])) > 0.05` | 5m | **page** |
| `SeatresInternalErrors` | `sum(rate(http_server_requests_seconds_count{status="500"}[5m])) > 0` | 5m | ticket |
| `SeatresPoolSaturated` | `max(hikaricp_connections_pending) > 0` and `active / max > 0.9` | 10m | ticket |
| `SeatresSlowReserve` | p99 reserve latency > 2 s | 10m | ticket |

### 8.3 What pages an on-call engineer at 2 AM

Page only for things that threaten **correctness** or **total availability**:

1. Any invariant violation: a possible double-sell or limit breach. Run R1–R7, and stop the sale if
   it's confirmed.
2. The service is not ready or unreachable for 2 minutes, so nobody can reserve.
3. A sustained 5xx rate above 5% for 5 minutes, so most users are failing.

Everything else (isolated 500s, pool saturation, slow p99) opens a ticket for business hours. 409s
are **not** errors and never alert: during a hot on-sale they're expected to dominate.
