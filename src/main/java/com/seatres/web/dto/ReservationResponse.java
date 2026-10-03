package com.seatres.web.dto;

import com.seatres.domain.ReservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code cancelledAt} is null on a reserve response and omitted by non-null inclusion. */
public record ReservationResponse(
        UUID reservationId,
        UUID showId,
        ReservationStatus status,
        List<ReservedSeat> seats,
        long totalPaise,
        Instant createdAt,
        Instant cancelledAt) {

    public static ReservationResponse confirmed(UUID reservationId, UUID showId,
            List<ReservedSeat> seats, long totalPaise, Instant createdAt) {
        return new ReservationResponse(reservationId, showId, ReservationStatus.CONFIRMED, seats,
                totalPaise, createdAt, null);
    }
}
