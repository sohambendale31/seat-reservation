package com.seatres.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/** Signs demo tokens with the same HS256 key the resource server verifies. */
@Component
public class TokenIssuer {

    public static final Duration TTL = Duration.ofHours(1);

    private final JwtEncoder encoder;

    public TokenIssuer(SecretKey jwtSigningKey) {
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    public IssuedToken issue(String subject, List<String> roles) {
        Instant issuedAt = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .audience(List.of(JwtConfig.AUDIENCE))
                .subject(subject)
                .claim("roles", roles)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(TTL))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, TTL.toSeconds());
    }

    public record IssuedToken(String accessToken, long expiresInSeconds) {}
}
