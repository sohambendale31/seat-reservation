package com.seatres.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class StartupChecksTest {

    private static final String GOOD_SECRET = "a-good-long-hs256-secret-0123456789-abcd";
    private static final String GOOD_ADMIN_KEY = "a-good-long-admin-key-0123456789-abcdef";

    @Configuration
    @EnableConfigurationProperties(AppProperties.class)
    static class TestConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class, StartupChecks.class)
            .withPropertyValues(
                    "app.auth.jwt-secret=" + GOOD_SECRET,
                    "app.auth.admin-key=" + GOOD_ADMIN_KEY,
                    "app.idempotency.retention=PT24H");

    @Test
    void startsWithAcceptableConfiguration() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void shortJwtSecretIsRejected() {
        runner.withPropertyValues("app.auth.jwt-secret=sixteen-bytes-ab")
                .run(context -> assertThat(context).getFailure()
                        .hasMessageContaining("app.auth.jwt-secret")
                        .hasMessageContaining("at least 32 bytes"));
    }

    @Test
    void shortAdminKeyIsRejected() {
        runner.withPropertyValues("app.auth.admin-key=sixteen-bytes-ab")
                .run(context -> assertThat(context).getFailure()
                        .hasMessageContaining("app.auth.admin-key")
                        .hasMessageContaining("at least 32 bytes"));
    }

    @Test
    void retentionBelowTwentyFourHoursIsRejected() {
        runner.withPropertyValues("app.idempotency.retention=PT1H")
                .run(context -> assertThat(context).getFailure()
                        .hasMessageContaining("app.idempotency.retention"));
    }

    @Test
    void devJwtSecretIsAcceptedOutsideProd() {
        runner.withPropertyValues("app.auth.jwt-secret=" + StartupChecks.DEV_JWT_SECRET)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void devJwtSecretIsRejectedUnderProd() {
        prod().withPropertyValues("app.auth.jwt-secret=" + StartupChecks.DEV_JWT_SECRET)
                .run(context -> assertThat(context).getFailure()
                        .hasMessageContaining("app.auth.jwt-secret")
                        .hasMessageContaining("dev default"));
    }

    @Test
    void devAdminKeyIsRejectedUnderProd() {
        prod().withPropertyValues("app.auth.admin-key=" + StartupChecks.DEV_ADMIN_KEY)
                .run(context -> assertThat(context).getFailure()
                        .hasMessageContaining("app.auth.admin-key")
                        .hasMessageContaining("dev default"));
    }

    @Test
    void reusingTheJwtSecretAsTheAdminKeyIsRejected() {
        runner.withPropertyValues("app.auth.admin-key=" + GOOD_SECRET)
                .run(context -> assertThat(context).getFailure()
                        .hasMessageContaining("must be different"));
    }

    private ApplicationContextRunner prod() {
        return runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"));
    }
}
