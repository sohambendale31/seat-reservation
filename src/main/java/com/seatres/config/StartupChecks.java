package com.seatres.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fails startup on configuration that would be unsafe or break a documented promise. */
@Component
public class StartupChecks implements InitializingBean {

    static final String DEV_JWT_SECRET = "local-dev-only-hs256-secret-change-me-0123456789";
    static final String DEV_ADMIN_KEY = "local-dev-only-admin-key-change-me-0123456789";

    private static final int MIN_SECRET_BYTES = 32;
    private static final Duration MIN_RETENTION = Duration.ofHours(24);
    private static final String PROD_PROFILE = "prod";

    private final AppProperties properties;
    private final Environment environment;

    public StartupChecks(AppProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        checkSecret("app.auth.jwt-secret", properties.auth().jwtSecret(), DEV_JWT_SECRET);
        checkSecret("app.auth.admin-key", properties.auth().adminKey(), DEV_ADMIN_KEY);
        checkRetention(properties.idempotency().retention());
        checkDistinct(properties.auth().jwtSecret(), properties.auth().adminKey());
    }

    private void checkSecret(String name, String value, String devDefault) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    name + " must be at least " + MIN_SECRET_BYTES + " bytes long");
        }
        if (isProd() && devDefault.equals(value)) {
            throw new IllegalStateException(
                    name + " must not use the dev default when the prod profile is active");
        }
    }

    private void checkRetention(Duration retention) {
        if (retention == null || retention.compareTo(MIN_RETENTION) < 0) {
            throw new IllegalStateException(
                    "app.idempotency.retention must be at least " + MIN_RETENTION);
        }
    }

    private void checkDistinct(String jwtSecret, String adminKey) {
        if (jwtSecret.equals(adminKey)) {
            throw new IllegalStateException(
                    "app.auth.jwt-secret and app.auth.admin-key must be different values");
        }
    }

    private boolean isProd() {
        return List.of(environment.getActiveProfiles()).contains(PROD_PROFILE);
    }
}
