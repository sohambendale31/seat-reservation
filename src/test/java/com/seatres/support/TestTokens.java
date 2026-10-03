package com.seatres.support;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Mints tokens for tests, including the malformed variants the auth tests must reject. */
public final class TestTokens {

    public static final String SECRET = "test-only-hs256-secret-0123456789-abcdefghij";
    public static final String ISSUER = "seat-reservation";
    public static final String AUDIENCE = "seat-reservation-api";

    private static final String OTHER_SECRET = "another-test-secret-0123456789-abcdefghijkl";
    private static final Duration ONE_HOUR = Duration.ofHours(1);

    private TestTokens() {
    }

    public static String user(String sub) {
        return valid(sub, List.of("USER"));
    }

    public static String admin() {
        return valid("admin-1", List.of("ADMIN"));
    }

    public static String userAndAdmin(String sub) {
        return valid(sub, List.of("USER", "ADMIN"));
    }

    public static String withoutRolesClaim(String sub) {
        return valid(sub, null);
    }

    public static String unknownRoleOnly(String sub) {
        return valid(sub, List.of("SUPERUSER"));
    }

    public static String wrongSignature(String sub) {
        Instant now = Instant.now();
        return token(sub, List.of("USER"), ISSUER, AUDIENCE, now, now.plus(ONE_HOUR), OTHER_SECRET,
                MacAlgorithm.HS256);
    }

    /** Issued and expired in the past, so exp stays after iat. */
    public static String expired(String sub) {
        Instant now = Instant.now();
        return token(sub, List.of("USER"), ISSUER, AUDIENCE, now.minus(Duration.ofHours(2)),
                now.minus(ONE_HOUR), SECRET, MacAlgorithm.HS256);
    }

    public static String wrongIssuer(String sub) {
        Instant now = Instant.now();
        return token(sub, List.of("USER"), "someone-else", AUDIENCE, now, now.plus(ONE_HOUR), SECRET,
                MacAlgorithm.HS256);
    }

    public static String wrongAudience(String sub) {
        Instant now = Instant.now();
        return token(sub, List.of("USER"), ISSUER, "another-api", now, now.plus(ONE_HOUR), SECRET,
                MacAlgorithm.HS256);
    }

    public static String hs512(String sub) {
        Instant now = Instant.now();
        return token(sub, List.of("USER"), ISSUER, AUDIENCE, now, now.plus(ONE_HOUR),
                SECRET + SECRET, MacAlgorithm.HS512);
    }

    /** Unsigned token, which a decoder pinned to HS256 must reject. */
    public static String algNone(String sub) {
        long now = Instant.now().getEpochSecond();
        String header = base64("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64(("{\"iss\":\"%s\",\"aud\":\"%s\",\"sub\":\"%s\","
                + "\"roles\":[\"USER\"],\"iat\":%d,\"exp\":%d}")
                .formatted(ISSUER, AUDIENCE, sub, now, now + 3600));
        return header + "." + payload + ".";
    }

    private static String valid(String sub, List<String> roles) {
        Instant now = Instant.now();
        return token(sub, roles, ISSUER, AUDIENCE, now, now.plus(ONE_HOUR), SECRET,
                MacAlgorithm.HS256);
    }

    private static String token(String sub, List<String> roles, String issuer, String audience,
            Instant issuedAt, Instant expiresAt, String secret, MacAlgorithm algorithm) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject(sub)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt);
        if (roles != null) {
            claims.claim("roles", roles);
        }
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(algorithm).build(), claims.build())).getTokenValue();
    }

    private static String base64(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
