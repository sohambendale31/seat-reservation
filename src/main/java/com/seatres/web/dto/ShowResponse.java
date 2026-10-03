package com.seatres.web.dto;

import java.time.Instant;
import java.util.UUID;

public record ShowResponse(
        UUID id,
        String name,
        Instant startsAt,
        int perUserLimit,
        int totalSeats,
        Instant createdAt) {
}
