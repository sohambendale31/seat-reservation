package com.seatres.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ShowDetailsResponse(
        UUID id,
        @Schema(example = "Evening Show - Screen 1") String name,
        Instant startsAt,
        @Schema(example = "4") int perUserLimit,
        @Schema(example = "40") int totalSeats,
        Instant createdAt,
        SeatCounts seatCounts,
        @Schema(description = "Every seat, in the order it was laid out.") List<SeatView> seats) {
}
