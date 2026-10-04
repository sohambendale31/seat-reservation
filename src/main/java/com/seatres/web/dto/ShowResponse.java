package com.seatres.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record ShowResponse(
        @Schema(description = "Use this as showId on the other endpoints.") UUID id,
        @Schema(example = "Evening Show - Screen 1") String name,
        Instant startsAt,
        @Schema(example = "4") int perUserLimit,
        @Schema(example = "40") int totalSeats,
        Instant createdAt) {
}
