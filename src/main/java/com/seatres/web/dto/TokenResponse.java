package com.seatres.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record TokenResponse(
        @Schema(description = "Paste this into Authorize at the top of the page.")
        String accessToken,
        @Schema(example = "Bearer") String tokenType,
        @Schema(example = "3600", description = "Seconds until the token expires.")
        long expiresIn) {

    public static TokenResponse bearer(String accessToken, long expiresIn) {
        return new TokenResponse(accessToken, "Bearer", expiresIn);
    }
}
