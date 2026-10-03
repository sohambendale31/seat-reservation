package com.seatres.security;

import com.seatres.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

@Configuration
public class JwtConfig {

    public static final String ISSUER = "seat-reservation";
    public static final String AUDIENCE = "seat-reservation-api";
    public static final String SUB_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._:@-]{0,63}$";

    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private static final Pattern SUB = Pattern.compile(SUB_PATTERN);

    @Bean
    SecretKey jwtSigningKey(AppProperties properties) {
        return new SecretKeySpec(
                properties.auth().jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** Pinned to HS256, so tokens using any other alg (including none) are rejected. */
    @Bean
    NimbusJwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(List.of(
                new JwtTimestampValidator(CLOCK_SKEW),
                new JwtIssuerValidator(ISSUER),
                audienceValidator(),
                subjectValidator())));
        return decoder;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter(RolesClaimConverter rolesClaimConverter) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(rolesClaimConverter);
        return converter;
    }

    private static OAuth2TokenValidator<Jwt> audienceValidator() {
        return token -> token.getAudience() != null && token.getAudience().contains(AUDIENCE)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        invalidToken("The required audience is missing"));
    }

    private static OAuth2TokenValidator<Jwt> subjectValidator() {
        return token -> {
            String subject = token.getClaimAsString(JwtClaimNames.SUB);
            return subject != null && SUB.matcher(subject).matches()
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(invalidToken("The subject is not acceptable"));
        };
    }

    private static OAuth2Error invalidToken(String description) {
        return new OAuth2Error("invalid_token", description,
                "https://tools.ietf.org/html/rfc6750#section-3.1");
    }
}
