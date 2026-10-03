package com.seatres.domain;

/** HELD is a legal status so the count invariant is well defined, but no v1 path writes it. */
public enum SeatStatus {
    AVAILABLE,
    HELD,
    CONFIRMED
}
