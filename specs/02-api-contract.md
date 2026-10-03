# 02 — API Contract

All endpoints speak JSON (`application/json; charset=UTF-8`). Errors use `application/problem+json`
(RFC 9457). All timestamps in responses are ISO-8601 UTC (`2026-12-01T14:00:00Z`). All money is
integer **paise** (`long`) of INR.

## 1. Endpoint summary

| Method | Path | Auth | Idempotency-Key | Success |
|---|---|---|---|---|
| POST | `/auth/token` | Public. Requesting role `ADMIN` also requires `X-Admin-Key` | — | 200 |
| POST | `/shows` | JWT, role `ADMIN` | — | 201 |
| GET | `/shows/{showId}` | JWT, role `USER` or `ADMIN` | — | 200 |
| POST | `/shows/{showId}/reserve` | JWT, role `USER` | **Required** | 201 |
| POST | `/reservations/{reservationId}/cancel` | JWT, role `USER`, owner only | Not used | 200 |
| GET | `/livez` | Public | — | 200 |
| GET | `/readyz` | Public | — | 200 / 503 |
| GET | `/actuator/health` | Public (status only, no details) | — | 200 / 503 |
| GET | `/actuator/prometheus` | Public | — | 200 |

Any other path → 404 `NOT_FOUND` (problem JSON) for a caller with a valid token. Without a valid token
it's 401 `UNAUTHENTICATED`: security rules run before routing, and unmatched paths require
authentication (fail-closed, so a new endpoint is never public by accident). Wrong method → 405
`METHOD_NOT_ALLOWED`.

## 2. Authentication and authorization

### 2.1 Token format

`Authorization: Bearer <JWT>`: a compact JWS signed with **HS256 only**.

| Claim | Required | Rule |
|---|---|---|
| `alg` (header) | yes | Must be `HS256`; any other value (including `none`) → 401 |
| `iss` | yes | Must equal `seat-reservation` |
| `aud` | yes | Must contain `seat-reservation-api` |
| `sub` | yes | User id matching `^[A-Za-z0-9][A-Za-z0-9._:@-]{0,63}$`; otherwise 401 |
| `roles` | yes (a missing claim is treated as empty) | JSON array ⊆ `["USER","ADMIN"]`, mapped to `ROLE_*`. Unknown values are ignored; empty → no authorities (→ 403 on protected endpoints) |
| `exp` | yes | Must be in the future (30 s clock skew) |
| `iat` | yes | Informational |

Signing key: `APP_AUTH_JWT_SECRET`, as UTF-8 bytes, **≥ 32 bytes**. Startup fails if it is shorter.
In profile `prod`, startup also fails if the secret equals the committed dev default. The same two
rules apply to the admin key `APP_AUTH_ADMIN_KEY` used by `POST /auth/token` (§5.5): ≥ 32 bytes, and
not the dev default under `prod`.

The **only** source of user identity is `sub`. Request bodies never carry identity. Jackson runs with
`FAIL_ON_UNKNOWN_PROPERTIES=true`, so a body containing `"userId"` is rejected with 400
`MALFORMED_REQUEST`.

### 2.2 Roles

| Role | Grants |
|---|---|
| `USER` | `GET /shows/{id}`, reserve, cancel own reservations |
| `ADMIN` | `POST /shows`, `GET /shows/{id}` |

An `ADMIN`-only token cannot reserve (403). A token may carry both roles.

### 2.3 Token acquisition

The service includes a **demo token endpoint**, `POST /auth/token` (§5.5, ADR-021). It lets the
evaluator and the burst script get tokens for any number of users with a plain HTTP call. The
endpoint signs with the same HS256 key that the resource server verifies, and every other endpoint
still takes identity **only** from the verified token.

| Environment | How tokens are obtained |
|---|---|
| Local (Compose, profile `local`) | `POST /auth/token`. The local profile has dev defaults for the signing secret and the admin key. `java scripts/MintToken.java --sub alice --roles USER` still works offline |
| Tests | `NimbusJwtEncoder` with the test secret (`TestTokens` helper); AUTH-11…13 exercise `POST /auth/token` itself |
| Burst script | Calls `POST /auth/token` for its USER tokens, and for its ADMIN token with `ADMIN_KEY` (`06` §7) |
| Deployed (evaluation) | `POST /auth/token`. USER tokens need no extra credential. For ADMIN tokens, the developer shares the **admin key** (`APP_AUTH_ADMIN_KEY`) privately with the evaluator for the evaluation window, then rotates it. The JWT signing secret is **never** shared |
| Production (beyond v1) | Tokens issued by an external identity provider. The demo endpoint is removed |

ADMIN tokens are the only admin credential. They're obtained from `POST /auth/token` with the admin
key, or minted offline with `MintToken.java` and the signing secret.

**What the demo endpoint does and doesn't change.** Anyone can obtain a USER token for any user id,
so it's a demo identity provider, not real authentication (threat T-12 in `00` §10). Identity is
still token-derived: a body field can never make a request act as a different user than the
token's `sub`, and a user can only cancel reservations owned by that `sub`.

## 3. Common headers

| Header | Direction | Rule |
|---|---|---|
| `Authorization` | request | `Bearer <jwt>` on protected endpoints |
| `X-Admin-Key` | request | Only on `POST /auth/token` when requesting role `ADMIN`; must equal `APP_AUTH_ADMIN_KEY` (constant-time comparison) |
| `Content-Type` | request | `application/json` for bodies; otherwise 415. Exception: cancel ignores any body and `Content-Type` (§5.4) |
| `Idempotency-Key` | request | `^[A-Za-z0-9_.:-]{1,128}$`; a UUID is recommended. Required on reserve. |
| `X-Request-Id` | both | Optional on requests (`^[A-Za-z0-9-]{8,64}$`, otherwise replaced); always present on responses |
| `Idempotent-Replayed` | response | `true` when the response replays a stored outcome; absent otherwise |
| `Retry-After` | response | Seconds; present on 503 |
| `Location` | response | `/shows/{id}` on 201 from `POST /shows` |

## 4. Error model (RFC 9457 Problem Details)

```json
{
  "type": "urn:seatres:problem:seat-unavailable",
  "title": "Seat unavailable",
  "status": 409,
  "detail": "One or more requested seats are not available. No seats were reserved.",
  "instance": "/shows/7f1c2b9e-3a7d-4a51-9a1f-0f3f3c1c2d10/reserve",
  "code": "SEAT_UNAVAILABLE",
  "requestId": "3b9f6a52-1c0e-4b7e-9d0a-8f4b2b1f0c11",
  "retryable": false,
  "unavailableSeats": ["A1"]
}
```

Standard members: `type`, `title`, `status`, `detail`, `instance`. Extension members that are always
present: `code` (stable, machine-readable), `requestId`, `retryable`. Code-specific extensions are
listed below. `type` is `urn:seatres:problem:` followed by the kebab-case `code`.

### 4.1 Error code catalogue

| `code` | HTTP | `retryable` | Extensions | Stored for idempotent replay? |
|---|---|---|---|---|
| `VALIDATION_FAILED` | 400 | false | `errors: [{field, message}]` | No |
| `MALFORMED_REQUEST` | 400 | false | — | No |
| `IDEMPOTENCY_KEY_MISSING` | 400 | false | — | No |
| `IDEMPOTENCY_KEY_INVALID` | 400 | false | — | No |
| `UNAUTHENTICATED` | 401 | false | — (+ `WWW-Authenticate: Bearer`) | No |
| `FORBIDDEN` | 403 | false | — | No |
| `NOT_FOUND` | 404 | false | — | No |
| `SHOW_NOT_FOUND` | 404 | false | `showId` | **Yes** (on reserve) |
| `RESERVATION_NOT_FOUND` | 404 | false | `reservationId` | n/a |
| `METHOD_NOT_ALLOWED` | 405 | false | — | No |
| `IDEMPOTENCY_KEY_REUSED` | 409 | false | — | No |
| `SEAT_UNAVAILABLE` | 409 | false | `unavailableSeats: [label]` | **Yes** |
| `USER_LIMIT_EXCEEDED` | 409 | false | `perUserLimit`, `seatsHeld`, `seatsRequested` | **Yes** |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | false | — | No |
| `UNKNOWN_SEAT` | 422 | false | `unknownSeats: [label]` | **Yes** |
| `INTERNAL_ERROR` | 500 | false | — | No (tx rolled back) |
| `SERVICE_UNAVAILABLE` | 503 | true | — (+ `Retry-After: 1`) | No (tx rolled back) |
| `OUTCOME_UNKNOWN` | 503 | true | — (+ `Retry-After: 1`) | No (commit outcome unknown) |

Rules:

- Domain conflicts are always 4xx. 5xx is reserved for infrastructure failures and bugs. The server
  **never** turns an unexpected DB error into a 409.
- `detail` is human-readable and may change; clients must branch on `code`.
- 500 responses never include exception messages, SQL, or constraint names.

### 4.2 Decline precedence for reserve

Checks run in this order inside the transaction, and the first failing check determines the
response:

1. `SHOW_NOT_FOUND` (404)
2. `UNKNOWN_SEAT` (422): a label that doesn't exist in the show
3. `USER_LIMIT_EXCEEDED` (409): under the quota lock, `seatsHeld + seatsRequested > perUserLimit`
4. `SEAT_UNAVAILABLE` (409): under seat locks, a requested seat that isn't `AVAILABLE`

If a request both exceeds the limit and asks for a taken seat, the response is
`USER_LIMIT_EXCEEDED`.

## 5. Endpoints

### 5.1 `POST /shows` (create show, ADMIN)

Request:

```json
{
  "name": "Evening Show — Screen 1",
  "startsAt": "2026-12-01T19:30:00+05:30",
  "perUserLimit": 4,
  "rows": [
    { "row": "A", "seatCount": 20, "pricePaise": 250000 },
    { "row": "B", "seatCount": 20, "pricePaise": 150000 }
  ]
}
```

| Field | Type | Rule |
|---|---|---|
| `name` | string | required; 1–200 chars after trim; not blank |
| `startsAt` | string | required; ISO-8601 with an offset (`OffsetDateTime`); stored as a UTC instant. Past values are allowed. |
| `perUserLimit` | integer | optional; 1–10; default 4 |
| `rows` | array | required; 1–200 items; `row` values unique |
| `rows[].row` | string | `^[A-Z]{1,3}$` |
| `rows[].seatCount` | integer | 1–500 |
| `rows[].pricePaise` | integer | 0–100,000,000; JSON integer only (a fraction or string → 400 `MALFORMED_REQUEST`) |
| *(sum of seatCount)* | — | ≤ 10,000, else 400 `VALIDATION_FAILED` (`field: "rows"`) |

Seats are generated row by row in request order. Each row gets labels `row + n` for
`n = 1..seatCount` (e.g. `A1..A20`), priced at the row's `pricePaise`, with status `AVAILABLE`.

Response 201 (`Location: /shows/{id}`):

```json
{
  "id": "7f1c2b9e-3a7d-4a51-9a1f-0f3f3c1c2d10",
  "name": "Evening Show — Screen 1",
  "startsAt": "2026-12-01T14:00:00Z",
  "perUserLimit": 4,
  "totalSeats": 40,
  "createdAt": "2026-10-02T13:00:00.123456Z"
}
```

Each call creates a new show; this endpoint is not idempotent.

Errors: 400, 401, 403, 415, 503, 500.

### 5.2 `GET /shows/{showId}` (show details, USER or ADMIN)

Response 200:

```json
{
  "id": "7f1c2b9e-3a7d-4a51-9a1f-0f3f3c1c2d10",
  "name": "Evening Show — Screen 1",
  "startsAt": "2026-12-01T14:00:00Z",
  "perUserLimit": 4,
  "totalSeats": 40,
  "createdAt": "2026-10-02T13:00:00.123456Z",
  "seatCounts": { "total": 40, "available": 38, "held": 0, "confirmed": 2 },
  "seats": [
    { "label": "A1", "status": "CONFIRMED", "pricePaise": 250000 },
    { "label": "A2", "status": "CONFIRMED", "pricePaise": 250000 },
    { "label": "A3", "status": "AVAILABLE", "pricePaise": 250000 }
  ]
}
```

- Seats are listed in layout order (row creation order, then number).
- `seatCounts` is derived from the same single-statement snapshot as `seats`, so
  `total = available + held + confirmed` always holds. Holder identity is never exposed.
- Errors: 400 (malformed UUID → `VALIDATION_FAILED`, field `showId`), 401, 403, 404 `SHOW_NOT_FOUND`,
  503.

### 5.3 `POST /shows/{showId}/reserve` (reserve seats, USER)

Headers: `Authorization`, `Content-Type: application/json`, **`Idempotency-Key`**.

Request:

```json
{ "seats": ["A1", "A2"] }
```

| Field | Rule |
|---|---|
| `seats` | required array of 1–10 unique labels, each matching `^[A-Z]{1,3}[1-9][0-9]{0,2}$` |

**All-or-nothing semantics:** either every requested seat moves `AVAILABLE → CONFIRMED` under one new
reservation, or none does. Partial reservations never exist, never become visible, and are never
returned. A decline response guarantees that **no** seat changed state because of this request.

Response 201:

```json
{
  "reservationId": "c4a0c7f6-5a8e-4a7b-a3a5-9d1f0e6b2a77",
  "showId": "7f1c2b9e-3a7d-4a51-9a1f-0f3f3c1c2d10",
  "status": "CONFIRMED",
  "seats": [
    { "label": "A1", "pricePaise": 250000 },
    { "label": "A2", "pricePaise": 250000 }
  ],
  "totalPaise": 500000,
  "createdAt": "2026-10-02T13:05:00.000001Z"
}
```

`seats` are listed in layout order. `totalPaise` is the exact `long` sum (`Math.addExact`).

Idempotency behaviour:

| Situation | Response |
|---|---|
| New key | Executes; the outcome (201, 404, 409 seat or limit, 422) is stored with the key |
| Same key, same canonical request, outcome already stored | Stored status + stored body, with header `Idempotent-Replayed: true` |
| Same key, same request, original still in flight | Waits for the original transaction to finish, then replays its outcome (or executes, if the original rolled back) |
| Same key, different canonical request (different show or seat set) | 409 `IDEMPOTENCY_KEY_REUSED`; nothing stored or allocated |
| Same key after a 400/401/403/415 or a 5xx | Executes as new (those outcomes aren't stored) |
| Same key after retention expiry (≥ 24 h) and cleanup | Executes as new |

The canonical request is the `showId` (lowercase UUID) plus the sorted labels, so `["A2","A1"]` and
`["A1","A2"]` are the same request.

Replays return the **original** status and body exactly as stored, including the original
`requestId` inside a stored problem body. The `X-Request-Id` response header carries the current
request's id. A replayed 201 describes the reservation as it was created: if the reservation has
since been cancelled, the replay still shows `CONFIRMED`, because it records that request's outcome.

Clients **must** reuse the same key when retrying after a timeout, a transport error, or a 503.
Clients must use a **new** key for a new logical reservation attempt, including after a 409 decline.

Errors: 400 (`VALIDATION_FAILED`, `MALFORMED_REQUEST`, `IDEMPOTENCY_KEY_MISSING`,
`IDEMPOTENCY_KEY_INVALID`), 401, 403, 404 `SHOW_NOT_FOUND`, 409 (`SEAT_UNAVAILABLE`,
`USER_LIMIT_EXCEEDED`, `IDEMPOTENCY_KEY_REUSED`), 415, 422 `UNKNOWN_SEAT`, 503
(`SERVICE_UNAVAILABLE`, `OUTCOME_UNKNOWN`), 500.

### 5.4 `POST /reservations/{reservationId}/cancel` (cancel, USER, owner)

No request body. Any body and any `Content-Type` are ignored (the 415 rule in §3 doesn't apply to
this endpoint). `Idempotency-Key` isn't used: cancellation is idempotent by
its state machine.

Response 200:

```json
{
  "reservationId": "c4a0c7f6-5a8e-4a7b-a3a5-9d1f0e6b2a77",
  "showId": "7f1c2b9e-3a7d-4a51-9a1f-0f3f3c1c2d10",
  "status": "CANCELLED",
  "seats": [
    { "label": "A1", "pricePaise": 250000 },
    { "label": "A2", "pricePaise": 250000 }
  ],
  "totalPaise": 500000,
  "createdAt": "2026-10-02T13:05:00.000001Z",
  "cancelledAt": "2026-10-02T13:10:00.000002Z"
}
```

Ownership: only the user whose `sub` created the reservation may cancel it. A missing reservation
**or** one owned by someone else → 404 `RESERVATION_NOT_FOUND`, so the response doesn't reveal
whether it exists.

Semantics: the call releases exactly the seats that belong to this reservation
(`seats.reservation_id = id`). Those seats return to `AVAILABLE`, and the user's quota decreases by
the reservation's seat count. Cancelling an already-cancelled reservation returns 200 with the same
representation (original `cancelledAt`) and changes nothing.

Errors: 400 (malformed UUID), 401, 403, 404 `RESERVATION_NOT_FOUND`, 503, 500.

### 5.5 `POST /auth/token` (demo token endpoint, public)

Issues a signed HS256 JWT so callers can authenticate without a separate identity provider
(ADR-021). Stateless: nothing is stored.

Request:

```json
{ "sub": "alice", "roles": ["USER"] }
```

| Field | Rule |
|---|---|
| `sub` | required; must match the `sub` pattern in §2.1 (`^[A-Za-z0-9][A-Za-z0-9._:@-]{0,63}$`) |
| `roles` | optional; non-empty array ⊆ `["USER","ADMIN"]`; default `["USER"]` |

If `roles` contains `ADMIN`, the request must carry `X-Admin-Key` equal to `APP_AUTH_ADMIN_KEY`.
Comparison is constant-time (`MessageDigest.isEqual`). A missing or wrong key → 403 `FORBIDDEN`, and
no token is issued. USER-only requests ignore the header.

Response 200:

```json
{ "accessToken": "eyJhbGciOiJIUzI1NiJ9…", "tokenType": "Bearer", "expiresIn": 3600 }
```

The token carries exactly the claims in §2.1: `iss=seat-reservation`, `aud=seat-reservation-api`,
`sub`, `roles`, `iat`, `exp = iat + 3600 s`. It's verified by the same decoder as every other token.
The issued token, the admin key, and the request body are never logged (`05` §7.1).

Errors: 400 (`VALIDATION_FAILED`, `MALFORMED_REQUEST`), 403 `FORBIDDEN` (admin key missing or
wrong), 415, 500.

## 6. Example responses

**400 validation**

```json
{
  "type": "urn:seatres:problem:validation-failed",
  "title": "Validation failed",
  "status": 400,
  "detail": "Request validation failed.",
  "instance": "/shows/7f1c2b9e-3a7d-4a51-9a1f-0f3f3c1c2d10/reserve",
  "code": "VALIDATION_FAILED",
  "requestId": "0d6c1f0e-2c55-4c0f-a3a9-2f8f3f8e9b10",
  "retryable": false,
  "errors": [
    { "field": "seats", "message": "must contain between 1 and 10 unique seat labels" },
    { "field": "seats[2]", "message": "must match ^[A-Z]{1,3}[1-9][0-9]{0,2}$" }
  ]
}
```

**400 unknown field (identity in body)**

```json
{
  "type": "urn:seatres:problem:malformed-request",
  "title": "Malformed request",
  "status": 400,
  "detail": "Request body could not be parsed: unrecognized field 'userId'.",
  "instance": "/shows/7f1c2b9e-3a7d-4a51-9a1f-0f3f3c1c2d10/reserve",
  "code": "MALFORMED_REQUEST",
  "requestId": "6a1b0c2d-3e4f-5a6b-7c8d-9e0f1a2b3c4d",
  "retryable": false
}
```

**401**

```json
{
  "type": "urn:seatres:problem:unauthenticated",
  "title": "Unauthenticated",
  "status": 401,
  "detail": "A valid bearer token is required.",
  "instance": "/shows/7f1c2b9e-3a7d-4a51-9a1f-0f3f3c1c2d10/reserve",
  "code": "UNAUTHENTICATED",
  "requestId": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
  "retryable": false
}
```

**409 seat unavailable**: see §4.

**409 user limit**

```json
{
  "type": "urn:seatres:problem:user-limit-exceeded",
  "title": "Per-user seat limit exceeded",
  "status": 409,
  "detail": "This request would exceed the per-user seat limit for this show. No seats were reserved.",
  "instance": "/shows/7f1c2b9e-3a7d-4a51-9a1f-0f3f3c1c2d10/reserve",
  "code": "USER_LIMIT_EXCEEDED",
  "requestId": "9f8e7d6c-5b4a-4392-8170-6f5e4d3c2b1a",
  "retryable": false,
  "perUserLimit": 4,
  "seatsHeld": 3,
  "seatsRequested": 2
}
```

**409 idempotency key reuse**

```json
{
  "type": "urn:seatres:problem:idempotency-key-reused",
  "title": "Idempotency key reused",
  "status": 409,
  "detail": "This Idempotency-Key was already used with a different request.",
  "instance": "/shows/7f1c2b9e-3a7d-4a51-9a1f-0f3f3c1c2d10/reserve",
  "code": "IDEMPOTENCY_KEY_REUSED",
  "requestId": "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e",
  "retryable": false
}
```

**422 unknown seat**

```json
{
  "type": "urn:seatres:problem:unknown-seat",
  "title": "Unknown seat",
  "status": 422,
  "detail": "One or more requested seats do not exist in this show. No seats were reserved.",
  "instance": "/shows/7f1c2b9e-3a7d-4a51-9a1f-0f3f3c1c2d10/reserve",
  "code": "UNKNOWN_SEAT",
  "requestId": "3c4d5e6f-7a8b-4c9d-0e1f-2a3b4c5d6e7f",
  "retryable": false,
  "unknownSeats": ["Z99"]
}
```

**503 outcome unknown**

```json
{
  "type": "urn:seatres:problem:outcome-unknown",
  "title": "Outcome unknown",
  "status": 503,
  "detail": "The database connection failed while committing. Retry with the same Idempotency-Key to obtain the outcome.",
  "instance": "/shows/7f1c2b9e-3a7d-4a51-9a1f-0f3f3c1c2d10/reserve",
  "code": "OUTCOME_UNKNOWN",
  "requestId": "4d5e6f7a-8b9c-4d0e-1f2a-3b4c5d6e7f80",
  "retryable": true
}
```

## 7. curl examples

```bash
# Get tokens from the demo token endpoint (local profile: ADMIN_KEY is the dev default in application-local.yml)
ADMIN=$(curl -s localhost:8080/auth/token -H 'Content-Type: application/json' -H "X-Admin-Key: $ADMIN_KEY" \
  -d '{"sub":"admin-1","roles":["ADMIN"]}' | jq -r .accessToken)
ALICE=$(curl -s localhost:8080/auth/token -H 'Content-Type: application/json' \
  -d '{"sub":"alice"}' | jq -r .accessToken)

SHOW=$(curl -s localhost:8080/shows -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"name":"Demo","startsAt":"2026-12-01T19:30:00+05:30","rows":[{"row":"A","seatCount":10,"pricePaise":25000}]}' | jq -r .id)

curl -s -X POST localhost:8080/shows/$SHOW/reserve -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -d '{"seats":["A1","A2"]}'

curl -s localhost:8080/shows/$SHOW -H "Authorization: Bearer $ALICE"
```
