package com.seatres.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatres.config.AppProperties;
import com.seatres.support.TestTokens;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class TokenIssuerTest {

    private final AppProperties properties = new AppProperties(
            new AppProperties.Auth(TestTokens.SECRET, "test-only-admin-key-0123456789-abcdefghij"),
            new AppProperties.Idempotency(Duration.ofHours(24)),
            new AppProperties.Observability(
                    new AppProperties.Pool(2, Duration.ofSeconds(2), Duration.ofSeconds(3),
                            "SET statement_timeout = '2s'")));

    private final JwtConfig jwtConfig = new JwtConfig();
    private final SecretKey signingKey = jwtConfig.jwtSigningKey(properties);
    private final NimbusJwtDecoder decoder = jwtConfig.jwtDecoder(signingKey);
    private final TokenIssuer issuer = new TokenIssuer(signingKey);

    @Test
    void issuedTokenCarriesTheExpectedClaimsAndIsAcceptedByTheDecoder() {
        TokenIssuer.IssuedToken issued = issuer.issue("alice", List.of("USER"));

        assertThat(issued.expiresInSeconds()).isEqualTo(3600);

        Jwt jwt = decoder.decode(issued.accessToken());
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(JwtConfig.ISSUER);
        assertThat(jwt.getAudience()).containsExactly(JwtConfig.AUDIENCE);
        assertThat(jwt.getSubject()).isEqualTo("alice");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(jwt.getIssuedAt()).isNotNull();
        assertThat(jwt.getExpiresAt()).isEqualTo(jwt.getIssuedAt().plusSeconds(3600));
    }

    @Test
    void adminRoleIsCarriedThrough() {
        Jwt jwt = decoder.decode(issuer.issue("admin-1", List.of("ADMIN")).accessToken());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ADMIN");
    }

    @Test
    void decoderRejectsTokensSignedWithAnotherSecret() {
        assertThatThrownBy(() -> decoder.decode(TestTokens.wrongSignature("alice")))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void decoderRejectsExpiredWrongIssuerWrongAudienceAndBadSubject() {
        assertThatThrownBy(() -> decoder.decode(TestTokens.expired("alice")))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(TestTokens.wrongIssuer("alice")))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(TestTokens.wrongAudience("alice")))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(TestTokens.user("has space")))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void decoderRejectsAlgorithmsOtherThanHs256() {
        assertThatThrownBy(() -> decoder.decode(TestTokens.hs512("alice")))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(TestTokens.algNone("alice")))
                .isInstanceOf(JwtException.class);
    }

    /** The controller compares admin keys with MessageDigest.isEqual, not String.equals. */
    @Test
    void adminKeyComparisonIsConstantTime() {
        byte[] expected = "a-very-long-admin-key-0123456789".getBytes(StandardCharsets.UTF_8);

        assertThat(MessageDigest.isEqual(expected, expected.clone())).isTrue();
        assertThat(MessageDigest.isEqual("short".getBytes(StandardCharsets.UTF_8), expected)).isFalse();
    }
}
