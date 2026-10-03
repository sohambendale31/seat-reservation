package com.seatres.domain;

/** A seat as stored. {@code id} is assigned in layout order and is the lock-ordering key. */
public record SeatRow(long id, String label, SeatStatus status, long pricePaise) {
}
