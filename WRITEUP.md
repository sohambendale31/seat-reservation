# WRITEUP — Concurrency-Safe Seat Reservation Service

> **DRAFT OUTLINE.** This is a placeholder structure (see `specs/09-writeup-outline.md`). Nothing
> below claims that code, tests, deployments, or benchmarks exist yet. Every `⟪TBD⟫` must be replaced
> with verified facts backed by committed evidence (`docs/evidence/`) before submission.

## 1. Summary

- Service: a JSON HTTP API for reserving assigned seats (Java 21, Spring Boot 3.5.x, PostgreSQL 16).
- Deployed URL: ⟪TBD: deployed URL⟫
- Repository: ⟪TBD: repository URL⟫
- Core mechanism: every allocation is decided inside one PostgreSQL transaction (READ COMMITTED). The
  transaction locks a per-(show, user) quota row, then the requested seat rows in ascending id order,
  and CHECK, unique, and FK constraints back it up.
- Headline results: ⟪TBD: one line per scenario, from verified runs⟫

## 2. Exact atomic decision mechanism

⟪TBD: condensed S1–S9 from specs/04 §4, with SQL for S4, S5, S8a/S8b; decision points; per-user
limit via quota row + CHECK (CT-02 result, write-skew counterexample); table of DB-enforced vs
app-enforced guarantees; why READ COMMITTED + explicit locks; why no show-level counters.⟫

## 3. Idempotency implementation

⟪TBD: scope (user_id, key); fingerprint canonicalization; claim-first in the same transaction;
concurrent same-key behaviour; stored outcomes; replay status + `Idempotent-Replayed`; exactly-once
effect vs delivery; `OUTCOME_UNKNOWN`; retention.⟫

## 4. Multi-seat locking and deadlock avoidance

⟪TBD: lock hierarchy; ascending-id rule; proof sketch; CT-05 evidence (rounds, deadlocks delta).⟫

## 5. Cancellation and state transitions

⟪TBD: state machines; `reservation_id = :rid` guard; CT-06/CT-07 evidence; HELD always 0 in v1.⟫

## 6. Consistency vs availability during partitions

⟪TBD: CP behaviour; readiness; app↔DB partition with open transactions; client↔app failure after
commit.⟫

## 7. Observability and operational alerts

⟪TBD: the five custom metrics; confirmed vs replayed vs declined accounting; cardinality limits;
reconciliation queries; paging policy.⟫

## 8. Load-test evidence

| Run | Environment | Command | Evidence file |
|---|---|---|---|
| L-1 | ⟪TBD⟫ | ⟪TBD⟫ | ⟪TBD⟫ |
| L-2 | ⟪TBD⟫ | ⟪TBD⟫ | ⟪TBD⟫ |
| L-3 | ⟪TBD⟫ | ⟪TBD⟫ | ⟪TBD⟫ |
| L-4 | ⟪TBD, or "not performed" with the reason⟫ | | |

Environment details (machine/plan, DB plan, pool size, JVM flags, commit SHA, resolved dependency
versions, PostgreSQL version): ⟪TBD⟫

Per-scenario outcome tables (copied verbatim from the script output): ⟪TBD⟫

Interpretation and load-generator limits: ⟪TBD⟫

## 9. AI assistance disclosure

- What AI drafted: ⟪TBD: concrete list⟫
- What I verified, and how: ⟪TBD: concrete list⟫
- What I decided independently: ⟪TBD: concrete list⟫
- AI output I corrected or rejected: ⟪TBD: concrete examples⟫

## 10. Known limitations and next steps

⟪TBD: finalize from specs/09 §10, plus anything discovered during implementation.⟫
