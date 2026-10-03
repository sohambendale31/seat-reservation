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

class AuthTokenEndpointIT extends AbstractPostgresIT {

    private static final String ADMIN_KEY = "test-only-admin-key-0123456789-abcdefghij";

    @LocalServerPort
    int port;

    private Api api;

    @BeforeEach
    void setUp() {
        api = new Api(port);
    }

    @Test
    void issuesAUserTokenWithoutAnyCredential() {
        Api.Response response = api.post("/auth/token", null, "{\"sub\":\"alice\"}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(response.json().path("expiresIn").asLong()).isEqualTo(3600);

        String token = response.json().path("accessToken").asText();
        assertThat(token).isNotBlank();
        assertThat(api.get("/shows/" + UUID.randomUUID(), token).status()).isEqualTo(404);
    }

    @Test
    void anExplicitUserRoleBehavesTheSameAndIgnoresAnAdminKey() {
        Api.Response response = api.post("/auth/token", null,
                "{\"sub\":\"bob\",\"roles\":[\"USER\"]}", Map.of("X-Admin-Key", "irrelevant"));

        assertThat(response.status()).isEqualTo(200);
        String token = response.json().path("accessToken").asText();
        assertThat(api.post("/shows", token, "{}").status()).isEqualTo(403);
    }

    @Test
    void adminRoleWithoutTheKeyIsRefusedAndIssuesNoToken() {
        Api.Response response = api.post("/auth/token", null,
                "{\"sub\":\"admin-1\",\"roles\":[\"ADMIN\"]}");

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.code()).isEqualTo("FORBIDDEN");
        assertThat(response.json().has("accessToken")).isFalse();
    }

    @Test
    void adminRoleWithTheWrongKeyIsRefused() {
        Api.Response response = api.post("/auth/token", null,
                "{\"sub\":\"admin-1\",\"roles\":[\"ADMIN\"]}",
                Map.of("X-Admin-Key", ADMIN_KEY + "-wrong"));

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.json().has("accessToken")).isFalse();
    }

    @Test
    void adminRoleWithTheCorrectKeyIsIssuedAndPassesAdminAuthorization() {
        Api.Response response = api.post("/auth/token", null,
                "{\"sub\":\"admin-1\",\"roles\":[\"ADMIN\"]}", Map.of("X-Admin-Key", ADMIN_KEY));

        assertThat(response.status()).isEqualTo(200);
        String token = response.json().path("accessToken").asText();
        assertThat(api.post("/shows", token, "{}").status()).isNotIn(401, 403);
    }

    @Test
    void invalidSubjectsAreRejected() {
        assertThat(api.post("/auth/token", null, "{\"sub\":\"has space\"}").code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(api.post("/auth/token", null,
                "{\"sub\":\"" + "a".repeat(65) + "\"}").code()).isEqualTo("VALIDATION_FAILED");
        assertThat(api.post("/auth/token", null, "{\"sub\":\"\"}").code())
                .isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void unknownRolesAreRejected() {
        Api.Response response = api.post("/auth/token", null,
                "{\"sub\":\"alice\",\"roles\":[\"SUPERUSER\"]}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.json().path("errors").toString()).contains("roles[0]");
    }

    @Test
    void anEmptyRolesArrayIsRejected() {
        assertThat(api.post("/auth/token", null, "{\"sub\":\"alice\",\"roles\":[]}").code())
                .isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void unknownBodyFieldsAreRejected() {
        Api.Response response = api.post("/auth/token", null,
                "{\"sub\":\"alice\",\"userId\":\"mallory\"}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(response.json().path("detail").asText()).contains("userId");
    }

    @Test
    void aNonJsonContentTypeIsRejected() {
        Api.Response response = api.postWithContentType("/auth/token", null, "sub=alice",
                "text/plain");

        assertThat(response.status()).isEqualTo(415);
        assertThat(response.code()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    /** Only POST is public, and security runs before routing, so an anonymous GET is 401 not 405. */
    @Test
    void theWrongMethodNeedsATokenAndIsThen405() {
        Api.Response anonymous = api.get("/auth/token", null);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.code()).isEqualTo("UNAUTHENTICATED");

        Api.Response authenticated = api.get("/auth/token", TestTokens.user("alice"));
        assertThat(authenticated.status()).isEqualTo(405);
        assertThat(authenticated.code()).isEqualTo("METHOD_NOT_ALLOWED");
    }
}
