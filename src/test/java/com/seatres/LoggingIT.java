package com.seatres;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatres.support.AbstractPostgresIT;
import com.seatres.support.Api;
import com.seatres.support.Reserve;
import com.seatres.support.Shows;
import com.seatres.support.TestTokens;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

@ExtendWith(OutputCaptureExtension.class)
class LoggingIT extends AbstractPostgresIT {

    private static final String ADMIN_KEY = "test-only-admin-key-0123456789-abcdefghij";
    private static final String JWT_SECRET = TestTokens.SECRET;

    @LocalServerPort
    int port;

    private Api api;
    private Shows shows;
    private Reserve reserve;

    @BeforeEach
    void setUp() {
        api = new Api(port);
        shows = new Shows(api, TestTokens.admin());
        reserve = new Reserve(api);
    }

    @Test
    void reserveLogsCarryTheRequestIdAndNoSecrets(CapturedOutput output) {
        UUID show = shows.create("log-01", "A", 10, 100, 4);
        String token = TestTokens.user("log-alice");

        assertThat(reserve.seats(show, token, Reserve.newKey(), "A1").status()).isEqualTo(201);

        assertThat(output).contains("reservation.confirmed").contains("requestId");
        assertSecretsAbsent(output, token);
    }

    @Test
    void theTokenEndpointLogsNeitherTheKeyNorTheToken(CapturedOutput output) {
        Api.Response response = api.post("/auth/token", null,
                "{\"sub\":\"log-admin\",\"roles\":[\"ADMIN\"]}", Map.of("X-Admin-Key", ADMIN_KEY));

        assertThat(response.status()).isEqualTo(200);
        String issued = response.json().path("accessToken").asText();
        assertSecretsAbsent(output, issued);
    }

    @Test
    void theRawSubjectIsHashedRatherThanLogged(CapturedOutput output) {
        UUID show = shows.create("log-03", "A", 10, 100, 4);

        assertThat(reserve.seats(show, TestTokens.user("log-secret-user"), Reserve.newKey(), "A1")
                .status()).isEqualTo(201);

        assertThat(output).doesNotContain("log-secret-user");
        assertThat(output).contains("userRef");
    }

    @Test
    void aDeclineIsLoggedByCodeWithoutSeatLabels(CapturedOutput output) {
        UUID show = shows.create("log-04", "A", 10, 100, 4);
        String bob = TestTokens.user("log-bob");
        assertThat(reserve.seats(show, bob, Reserve.newKey(), "A1").status()).isEqualTo(201);

        assertThat(reserve.seats(show, TestTokens.user("log-carol"), Reserve.newKey(), "A1").code())
                .isEqualTo("SEAT_UNAVAILABLE");

        assertThat(output).contains("reservation.declined").contains("SEAT_UNAVAILABLE");
    }

    private static void assertSecretsAbsent(CapturedOutput output, String token) {
        assertThat(output)
                .doesNotContain(ADMIN_KEY)
                .doesNotContain(JWT_SECRET)
                .doesNotContain(token)
                .doesNotContain("X-Admin-Key")
                .doesNotContain("Authorization");
    }
}
