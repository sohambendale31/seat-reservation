package com.seatres.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** available + held + confirmed always equals total. */
public record SeatCounts(
        @Schema(example = "40") int total,
        @Schema(example = "38") int available,
        @Schema(example = "0") int held,
        @Schema(example = "2") int confirmed) {
}
