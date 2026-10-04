package com.seatres.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            ProblemAuthenticationEntryPoint entryPoint,
            ProblemAccessDeniedHandler accessDeniedHandler) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/auth/token").permitAll()
                        .requestMatchers("/livez", "/readyz", "/actuator/health",
                                "/actuator/health/**", "/actuator/prometheus").permitAll()
                        // The only paths exempted from the fail-closed rule below: the API docs and
                        // the redirect that takes the bare URL to them.
                        .requestMatchers(HttpMethod.GET, "/", "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/shows").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/shows/*").hasAnyRole("USER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/shows/*/reserve").hasRole("USER")
                        .requestMatchers(HttpMethod.POST, "/reservations/*/cancel").hasRole("USER")
                        // Fail closed: an unmatched path needs a token, so a new endpoint is never
                        // public by accident.
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        // Also set here: the bearer filter has its own entry point, which would
                        // otherwise answer an invalid token with an empty 401 body.
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .build();
    }
}
