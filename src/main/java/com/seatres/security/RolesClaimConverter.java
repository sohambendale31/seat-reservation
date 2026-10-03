package com.seatres.security;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Maps the {@code roles} claim to authorities. A missing claim and unknown values yield none. */
@Component
public class RolesClaimConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String CLAIM = "roles";
    private static final Set<String> KNOWN_ROLES = Set.of("USER", "ADMIN");

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object claim = jwt.getClaim(CLAIM);
        if (!(claim instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(KNOWN_ROLES::contains)
                .distinct()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
