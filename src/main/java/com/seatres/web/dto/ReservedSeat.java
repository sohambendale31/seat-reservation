package com.seatres.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ReservedSeat(
        @Schema(example = "A1") String label,
        @Schema(example = "25000", description = "Price in paise.") long pricePaise) {
}
