package com.seatres.web.dto;

import com.seatres.domain.ReservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(
        UUID reservationId,
        UUID showId,
        ReservationStatus status,
        List<ReservedSeat> seats,
        long totalPaise,
        Instant createdAt) {
}
