package com.seatres.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

@ValidCreateShow
public record CreateShowRequest(
        @NotNull
        String name,

        @NotNull
        OffsetDateTime startsAt,

        @Min(value = 1, message = "must be between 1 and 10")
        @Max(value = 10, message = "must be between 1 and 10")
        Integer perUserLimit,

        @NotEmpty(message = "must contain at least one row")
        @Size(max = 200, message = "must not contain more than 200 rows")
        List<@NotNull @Valid RowSpec> rows) {

    private static final int DEFAULT_PER_USER_LIMIT = 4;

    public String trimmedName() {
        return name == null ? null : name.trim();
    }

    public int perUserLimitOrDefault() {
        return perUserLimit == null ? DEFAULT_PER_USER_LIMIT : perUserLimit;
    }

    public int totalSeats() {
        return rows.stream().mapToInt(RowSpec::seatCount).sum();
    }
}
