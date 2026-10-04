package com.seatres.web.dto;

import com.seatres.security.JwtConfig;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record TokenRequest(
        @Schema(example = "alice",
                description = "Any name you choose. It identifies whoever makes the bookings.")
        @NotBlank
        @Pattern(regexp = JwtConfig.SUB_PATTERN, message = "must match " + JwtConfig.SUB_PATTERN)
        String sub,

        @ArraySchema(arraySchema = @Schema(
                description = "Defaults to USER. ADMIN also requires the X-Admin-Key header."),
                schema = @Schema(example = "USER", allowableValues = {"USER", "ADMIN"}))
        @Size(min = 1, message = "must not be empty")
        List<@Pattern(regexp = "USER|ADMIN", message = "must be USER or ADMIN") String> roles) {

    private static final List<String> DEFAULT_ROLES = List.of("USER");

    public List<String> rolesOrDefault() {
        return roles == null ? DEFAULT_ROLES : List.copyOf(roles);
    }
}
