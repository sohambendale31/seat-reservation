package com.seatres.web.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Comparator;
import java.util.List;

public record ReserveRequest(
        @ArraySchema(arraySchema = @Schema(
                description = "The seats you want, by label. You get all of them or none."),
                schema = @Schema(example = "A1"))
        @NotNull
        @Size(min = 1, max = 10, message = "must contain between 1 and 10 seat labels")
        @UniqueLabels
        List<@NotNull @Pattern(regexp = SEAT_LABEL_PATTERN,
                message = "must match " + SEAT_LABEL_PATTERN) String> seats) {

    public static final String SEAT_LABEL_PATTERN = "^[A-Z]{1,3}[1-9][0-9]{0,2}$";

    /** Request order is irrelevant; the fingerprint and the lock order both use sorted labels. */
    public List<String> sortedSeats() {
        return seats.stream().sorted(Comparator.naturalOrder()).toList();
    }
}
