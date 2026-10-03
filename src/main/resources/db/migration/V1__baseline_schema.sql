-- Baseline schema. Do not edit once applied anywhere; add V2__... instead.

CREATE TABLE shows (
    id              uuid         PRIMARY KEY,
    name            varchar(200) NOT NULL,
    starts_at       timestamptz  NOT NULL,
    per_user_limit  integer      NOT NULL,
    total_seats     integer      NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT shows_name_not_blank_ck     CHECK (length(btrim(name)) > 0),
    CONSTRAINT shows_per_user_limit_ck     CHECK (per_user_limit BETWEEN 1 AND 10),
    CONSTRAINT shows_total_seats_ck        CHECK (total_seats BETWEEN 1 AND 10000)
);

CREATE TABLE seats (
    id              bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    show_id         uuid         NOT NULL REFERENCES shows (id),
    label           varchar(6)   NOT NULL,
    price_paise     bigint       NOT NULL,
    status          varchar(16)  NOT NULL DEFAULT 'AVAILABLE',
    reservation_id  uuid         NULL,
    CONSTRAINT seats_show_label_uk          UNIQUE (show_id, label),
    CONSTRAINT seats_id_show_uk             UNIQUE (id, show_id),
    CONSTRAINT seats_label_ck               CHECK (label ~ '^[A-Z]{1,3}[1-9][0-9]{0,2}$'),
    CONSTRAINT seats_price_ck               CHECK (price_paise BETWEEN 0 AND 100000000),
    CONSTRAINT seats_status_ck              CHECK (status IN ('AVAILABLE', 'HELD', 'CONFIRMED')),
    -- A seat is AVAILABLE exactly when it belongs to no reservation.
    CONSTRAINT seats_status_reservation_ck  CHECK ((status = 'AVAILABLE') = (reservation_id IS NULL))
);

-- Find a reservation's current seats on cancel.
CREATE INDEX seats_reservation_idx ON seats (reservation_id) WHERE reservation_id IS NOT NULL;

CREATE TABLE reservations (
    id              uuid         PRIMARY KEY,
    show_id         uuid         NOT NULL REFERENCES shows (id),
    user_id         varchar(64)  NOT NULL,
    status          varchar(16)  NOT NULL,
    seat_count      integer      NOT NULL,
    total_paise     bigint       NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    cancelled_at    timestamptz  NULL,
    CONSTRAINT reservations_id_show_uk      UNIQUE (id, show_id),
    CONSTRAINT reservations_status_ck       CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    CONSTRAINT reservations_seat_count_ck   CHECK (seat_count BETWEEN 1 AND 10),
    CONSTRAINT reservations_total_ck        CHECK (total_paise >= 0),
    CONSTRAINT reservations_cancelled_ck    CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);

-- A seat can only point at a reservation of the SAME show.
ALTER TABLE seats
    ADD CONSTRAINT seats_reservation_fk
    FOREIGN KEY (reservation_id, show_id) REFERENCES reservations (id, show_id);

CREATE TABLE reservation_seats (
    reservation_id  uuid         NOT NULL,
    seat_id         bigint       NOT NULL,
    show_id         uuid         NOT NULL,
    price_paise     bigint       NOT NULL,
    released_at     timestamptz  NULL,
    CONSTRAINT reservation_seats_pk          PRIMARY KEY (reservation_id, seat_id),
    CONSTRAINT reservation_seats_res_fk      FOREIGN KEY (reservation_id, show_id) REFERENCES reservations (id, show_id),
    CONSTRAINT reservation_seats_seat_fk     FOREIGN KEY (seat_id, show_id)        REFERENCES seats (id, show_id),
    CONSTRAINT reservation_seats_price_ck    CHECK (price_paise BETWEEN 0 AND 100000000)
);

-- Independent guard against double-selling: at most one unreleased link per seat.
CREATE UNIQUE INDEX reservation_seats_one_active_per_seat_uk
    ON reservation_seats (seat_id) WHERE released_at IS NULL;

CREATE TABLE user_show_quotas (
    show_id         uuid         NOT NULL REFERENCES shows (id),
    user_id         varchar(64)  NOT NULL,
    seats_held      integer      NOT NULL DEFAULT 0,
    seat_limit      integer      NOT NULL,
    CONSTRAINT user_show_quotas_pk           PRIMARY KEY (show_id, user_id),
    CONSTRAINT user_show_quotas_limit_ck     CHECK (seat_limit BETWEEN 1 AND 10),
    -- No transaction can commit a quota above the limit or below zero.
    CONSTRAINT user_show_quotas_held_ck      CHECK (seats_held BETWEEN 0 AND seat_limit)
);

CREATE TABLE idempotency_records (
    id                   uuid         PRIMARY KEY,
    user_id              varchar(64)  NOT NULL,
    idem_key             varchar(128) NOT NULL,
    request_fingerprint  varchar(64)  NOT NULL,
    response_status      smallint     NULL,
    response_body        text         NULL,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    completed_at         timestamptz  NULL,
    expires_at           timestamptz  NOT NULL,
    CONSTRAINT idempotency_records_user_key_uk     UNIQUE (user_id, idem_key),
    CONSTRAINT idempotency_records_fp_ck           CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT idempotency_records_key_ck          CHECK (idem_key ~ '^[A-Za-z0-9_.:-]{1,128}$'),
    CONSTRAINT idempotency_records_status_ck       CHECK (response_status IS NULL OR response_status BETWEEN 200 AND 499),
    -- These three are set together on finalize.
    CONSTRAINT idempotency_records_completion_ck   CHECK (
        (response_status IS NULL) = (completed_at IS NULL)
        AND (response_status IS NULL) = (response_body IS NULL)
    ),
    CONSTRAINT idempotency_records_expiry_ck       CHECK (expires_at > created_at)
);

CREATE INDEX idempotency_records_expires_idx ON idempotency_records (expires_at);
