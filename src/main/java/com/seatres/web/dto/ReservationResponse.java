package com.seatres.web.dto;

import com.seatres.domain.ReservationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code cancelledAt} is null on a reserve response and omitted by non-null inclusion. */
public record ReservationResponse(
        @Schema(description = "Use this to cancel the booking later.") UUID reservationId,
        UUID showId,
        ReservationStatus status,
        List<ReservedSeat> seats,
        @Schema(example = "50000", description = "Sum of the seat prices, in paise.")
        long totalPaise,
        Instant createdAt,
        @Schema(description = "Only present once the booking has been cancelled.")
        Instant cancelledAt) {

    public static ReservationResponse confirmed(UUID reservationId, UUID showId,
            List<ReservedSeat> seats, long totalPaise, Instant createdAt) {
        return new ReservationResponse(reservationId, showId, ReservationStatus.CONFIRMED, seats,
                totalPaise, createdAt, null);
    }
}
