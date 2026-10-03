package com.seatres.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ShowDetailsResponse(
        UUID id,
        String name,
        Instant startsAt,
        int perUserLimit,
        int totalSeats,
        Instant createdAt,
        SeatCounts seatCounts,
        List<SeatView> seats) {
}
