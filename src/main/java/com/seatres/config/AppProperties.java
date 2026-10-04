package com.seatres.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binding target for the {@code app.*} configuration tree. Values are validated by StartupChecks. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Auth auth, Idempotency idempotency, Observability observability) {

    public record Auth(String jwtSecret, String adminKey) {}

    public record Idempotency(Duration retention) {}

    public record Observability(Pool pool) {}

    /** The seats gauge's own pool, kept away from the pool that serves reservations. */
    public record Pool(int maximumPoolSize, Duration connectionTimeout, Duration socketTimeout,
            String connectionInitSql) {}
}
