package com.seatres.web.dto;

import com.seatres.domain.SeatStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public record SeatView(
        @Schema(example = "A1") String label,
        @Schema(description = "HELD exists for future use and is always 0 today.")
        SeatStatus status,
        @Schema(example = "25000", description = "Price in paise.") long pricePaise) {
}
