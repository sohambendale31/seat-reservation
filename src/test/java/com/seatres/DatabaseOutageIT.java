package com.seatres;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatres.support.AbstractPostgresIT;
import com.seatres.support.Api;
import com.seatres.support.Reserve;
import com.seatres.support.Shows;
import com.seatres.support.TestTokens;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.testcontainers.DockerClientFactory;

/** Freezes the database container, so it is slow by nature and excluded with -DexcludedGroups=slow. */
@Tag("slow")
class DatabaseOutageIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    private Api api;

    /** While the database is frozen the server waits out its pool timeout before answering 503. */
    @BeforeEach
    void setUp() {
        api = new Api(port, Duration.ofMinutes(3));
    }

    @Test
    void livenessSurvivesADatabaseOutageWhileReadinessReportsItAndRecovers() {
        UUID show = new Shows(api, TestTokens.admin()).create("outage", "A", 4, 100, 4);
        assertThat(api.get("/readyz", null).status()).isEqualTo(200);

        pause();
        try {
            Api.Response liveness = api.get("/livez", null);
            assertThat(liveness.status()).as("liveness must not depend on the database").isEqualTo(200);
            assertThat(liveness.body()).contains("\"status\":\"UP\"");

            Api.Response readiness = api.get("/readyz", null);
            assertThat(readiness.status()).isEqualTo(503);
            assertThat(readiness.body()).contains("\"status\":\"DOWN\"");

            Api.Response reserved = new Reserve(api).seats(show, TestTokens.user("outage-alice"),
                    Reserve.newKey(), "A1");
            assertThat(reserved.status()).isEqualTo(503);
            assertThat(reserved.json().path("retryable").asBoolean()).isTrue();
        } finally {
            unpause();
        }

        assertThat(awaitReady()).as("readiness recovers once the database answers again").isTrue();
    }

    private static void pause() {
        DockerClientFactory.lazyClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();
    }

    private static void unpause() {
        DockerClientFactory.lazyClient().unpauseContainerCmd(POSTGRES.getContainerId()).exec();
    }

    private boolean awaitReady() {
        for (int attempt = 0; attempt < 30; attempt++) {
            if (api.get("/readyz", null).status() == 200) {
                return true;
            }
        }
        return false;
    }
}
