package com.seatres.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class RolesClaimConverterTest {

    private final RolesClaimConverter converter = new RolesClaimConverter();

    @Test
    void mapsKnownRolesToAuthorities() {
        assertThat(authorities(List.of("USER", "ADMIN"))).containsExactly("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void ignoresUnknownRoles() {
        assertThat(authorities(List.of("USER", "SUPERUSER", "root"))).containsExactly("ROLE_USER");
    }

    @Test
    void deduplicatesRepeatedRoles() {
        assertThat(authorities(List.of("USER", "USER"))).containsExactly("ROLE_USER");
    }

    @Test
    void missingClaimYieldsNoAuthorities() {
        assertThat(authorities(null)).isEmpty();
    }

    @Test
    void emptyClaimYieldsNoAuthorities() {
        assertThat(authorities(List.of())).isEmpty();
    }

    @Test
    void nonListClaimYieldsNoAuthorities() {
        assertThat(authorities("ADMIN")).isEmpty();
    }

    private List<String> authorities(Object rolesClaim) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("alice")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        if (rolesClaim != null) {
            builder.claim("roles", rolesClaim);
        }
        return converter.convert(builder.build()).stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }
}
