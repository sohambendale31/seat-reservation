package com.seatres.web.dto;

import com.seatres.security.JwtConfig;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record TokenRequest(
        @NotBlank
        @Pattern(regexp = JwtConfig.SUB_PATTERN, message = "must match " + JwtConfig.SUB_PATTERN)
        String sub,

        @Size(min = 1, message = "must not be empty")
        List<@Pattern(regexp = "USER|ADMIN", message = "must be USER or ADMIN") String> roles) {

    private static final List<String> DEFAULT_ROLES = List.of("USER");

    public List<String> rolesOrDefault() {
        return roles == null ? DEFAULT_ROLES : List.copyOf(roles);
    }
}
