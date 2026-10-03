package com.seatres.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binding target for the {@code app.*} configuration tree. Values are validated by StartupChecks. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Auth auth, Idempotency idempotency) {

    public record Auth(String jwtSecret, String adminKey) {}

    public record Idempotency(Duration retention) {}
}
