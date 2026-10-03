package com.seatres;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatres.support.AbstractPostgresIT;
import com.seatres.support.Api;
import com.seatres.support.TestTokens;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

class AuthenticationIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    private Api api;
    private String showPath;

    @BeforeEach
    void setUp() {
        api = new Api(port);
        showPath = "/shows/" + UUID.randomUUID();
    }

    @Test
    void protectedEndpointsRejectMissingTokens() {
        for (Api.Response response : new Api.Response[] {
                api.post("/shows", null, "{}"),
                api.get(showPath, null),
                api.post(showPath + "/reserve", null, "{\"seats\":[\"A1\"]}"),
                api.post("/reservations/" + UUID.randomUUID() + "/cancel", null, null) }) {
            assertThat(response.status()).isEqualTo(401);
            assertThat(response.code()).isEqualTo("UNAUTHENTICATED");
            assertThat(response.contentType()).startsWith("application/problem+json");
            assertThat(response.header("WWW-Authenticate")).contains("Bearer");
        }
    }

    @Test
    void malformedAndUntrustworthyTokensAreRejected() {
        String[] tokens = {
                TestTokens.wrongSignature("alice"),
                TestTokens.expired("alice"),
                TestTokens.wrongIssuer("alice"),
                TestTokens.wrongAudience("alice"),
                TestTokens.algNone("alice"),
                TestTokens.hs512("alice"),
                "not-a-jwt" };

        for (String token : tokens) {
            Api.Response response = api.get(showPath, token);
            assertThat(response.status()).as(token).isEqualTo(401);
            assertThat(response.code()).isEqualTo("UNAUTHENTICATED");
        }
    }

    @Test
    void subjectsOutsideThePatternAreRejected() {
        assertThat(api.get(showPath, TestTokens.user("has space")).status()).isEqualTo(401);
        assertThat(api.get(showPath, TestTokens.user("a".repeat(65))).status()).isEqualTo(401);
        assertThat(api.get(showPath, TestTokens.user("-leading-dash")).status()).isEqualTo(401);
    }

    @Test
    void userTokenCannotCreateShows() {
        Api.Response response = api.post("/shows", TestTokens.user("alice"), "{}");

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.code()).isEqualTo("FORBIDDEN");
        assertThat(response.contentType()).startsWith("application/problem+json");
    }

    @Test
    void adminOnlyTokenCannotReserve() {
        Api.Response response = api.post(showPath + "/reserve", TestTokens.admin(),
                "{\"seats\":[\"A1\"]}");

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.code()).isEqualTo("FORBIDDEN");
    }

    @Test
    void tokenWithoutRolesClaimIsAuthenticatedButNotAuthorized() {
        Api.Response response = api.get(showPath, TestTokens.withoutRolesClaim("alice"));

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.code()).isEqualTo("FORBIDDEN");
    }

    @Test
    void tokenWithOnlyUnknownRolesIsNotAuthorized() {
        assertThat(api.get(showPath, TestTokens.unknownRoleOnly("alice")).status()).isEqualTo(403);
    }

    /** Only authorization is asserted here; the handlers themselves arrive in later phases. */
    @Test
    void tokenCarryingBothRolesPassesAuthorizationOnBothSurfaces() {
        String token = TestTokens.userAndAdmin("alice");

        assertThat(api.get(showPath, token).status()).isNotIn(401, 403);
        assertThat(api.post("/shows", token, "{}").status()).isNotIn(401, 403);
    }

    @Test
    void probesAndMetricsArePublic() {
        assertThat(api.get("/livez", null).status()).isEqualTo(200);
        assertThat(api.get("/readyz", null).status()).isEqualTo(200);
        assertThat(api.get("/actuator/health", null).status()).isEqualTo(200);
        assertThat(api.get("/actuator/prometheus", null).status()).isEqualTo(200);
    }

    @Test
    void unexposedActuatorEndpointsNeedATokenAndThenDoNotExist() {
        Api.Response anonymous = api.get("/actuator/env", null);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.code()).isEqualTo("UNAUTHENTICATED");

        Api.Response authenticated = api.get("/actuator/env", TestTokens.user("alice"));
        assertThat(authenticated.status()).isEqualTo(404);
        assertThat(authenticated.code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void unknownPathsFailClosed() {
        assertThat(api.get("/nope", null).status()).isEqualTo(401);
        assertThat(api.get("/nope", TestTokens.user("alice")).status()).isEqualTo(404);
    }

    @Test
    void problemBodyRequestIdMatchesTheResponseHeader() {
        Api.Response response = api.get(showPath, null);

        assertThat(response.header("X-Request-Id")).isPresent();
        assertThat(response.json().path("requestId").asText())
                .isEqualTo(response.header("X-Request-Id").orElseThrow());
    }

    @Test
    void aValidSuppliedRequestIdIsEchoedAndAnInvalidOneIsReplaced() {
        String valid = "abcdefgh-1234-5678";
        assertThat(api.get("/livez", null, Map.of("X-Request-Id", valid)).header("X-Request-Id"))
                .contains(valid);

        String tooShort = "abc";
        assertThat(api.get("/livez", null, Map.of("X-Request-Id", tooShort)).header("X-Request-Id"))
                .isPresent()
                .get()
                .isNotEqualTo(tooShort);
    }
}
