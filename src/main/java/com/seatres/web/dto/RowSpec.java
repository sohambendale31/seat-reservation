package com.seatres.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record RowSpec(
        @Schema(example = "A", description = "Row letter. Seats become A1, A2, and so on.")
        @NotNull
        @Pattern(regexp = "^[A-Z]{1,3}$", message = "must match ^[A-Z]{1,3}$")
        String row,

        @Schema(example = "20", description = "How many seats in this row.")
        @NotNull
        @Min(value = 1, message = "must be between 1 and 500")
        @Max(value = 500, message = "must be between 1 and 500")
        Integer seatCount,

        @Schema(example = "25000", description = "Price per seat in paise, so 25000 is Rs 250.")
        @NotNull
        @Min(value = 0, message = "must be between 0 and 100000000")
        @Max(value = 100_000_000, message = "must be between 0 and 100000000")
        Long pricePaise) {
}
