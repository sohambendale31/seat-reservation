package com.seatres.web;

import com.seatres.config.AppProperties;
import com.seatres.error.ApiException;
import com.seatres.error.ErrorCode;
import com.seatres.security.TokenIssuer;
import com.seatres.web.dto.TokenRequest;
import com.seatres.web.dto.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo identity provider: anyone may obtain a USER token for any subject, so this is not real
 * authentication. ADMIN tokens require the admin key. Every other endpoint still takes identity
 * only from the verified token.
 */
@RestController
@Tag(name = "Tokens", description = "Demo identity provider")
public class AuthController {

    private static final String ADMIN_ROLE = "ADMIN";

    private final TokenIssuer tokenIssuer;
    private final byte[] adminKey;

    public AuthController(TokenIssuer tokenIssuer, AppProperties properties) {
        this.tokenIssuer = tokenIssuer;
        this.adminKey = properties.auth().adminKey().getBytes(StandardCharsets.UTF_8);
    }

    @Operation(summary = "Get a token to use this API",
            description = "Anyone can get an ordinary token: send any name as `sub`. Copy the "
                    + "accessToken into Authorize to call the rest. Admin rights additionally "
                    + "require the X-Admin-Key header.")
    @SecurityRequirements
    @PostMapping(path = "/auth/token", consumes = MediaType.APPLICATION_JSON_VALUE)
    TokenResponse issueToken(@Valid @RequestBody TokenRequest request,
            @RequestHeader(name = "X-Admin-Key", required = false) String suppliedAdminKey) {
        List<String> roles = request.rolesOrDefault();
        if (roles.contains(ADMIN_ROLE) && !adminKeyMatches(suppliedAdminKey)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "A valid admin key is required for role ADMIN.");
        }
        TokenIssuer.IssuedToken issued = tokenIssuer.issue(request.sub(), roles);
        return TokenResponse.bearer(issued.accessToken(), issued.expiresInSeconds());
    }

    private boolean adminKeyMatches(String supplied) {
        return supplied != null
                && MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), adminKey);
    }
}
