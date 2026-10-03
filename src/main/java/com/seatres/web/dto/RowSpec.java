package com.seatres.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record RowSpec(
        @NotNull
        @Pattern(regexp = "^[A-Z]{1,3}$", message = "must match ^[A-Z]{1,3}$")
        String row,

        @NotNull
        @Min(value = 1, message = "must be between 1 and 500")
        @Max(value = 500, message = "must be between 1 and 500")
        Integer seatCount,

        @NotNull
        @Min(value = 0, message = "must be between 0 and 100000000")
        @Max(value = 100_000_000, message = "must be between 0 and 100000000")
        Long pricePaise) {
}
