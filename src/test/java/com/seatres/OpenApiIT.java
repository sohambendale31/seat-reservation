package com.seatres;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatres.support.AbstractPostgresIT;
import com.seatres.support.Api;
import com.seatres.support.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

class OpenApiIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    private Api api;

    @BeforeEach
    void setUp() {
        api = new Api(port);
    }

    @Test
    void theBareUrlRedirectsToTheDocsWithoutAToken() {
        Api.Response response = api.get("/", null);

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.header("Location").orElseThrow()).endsWith("/swagger-ui.html");
    }

    @Test
    void theDocsAndTheUiAreReachableAnonymously() {
        assertThat(api.get("/v3/api-docs", null).status()).isEqualTo(200);
        assertThat(api.get("/swagger-ui/index.html", null).status()).isEqualTo(200);
        assertThat(api.get("/v3/api-docs/swagger-config", null).status()).isEqualTo(200);
    }

    @Test
    void theDocumentDescribesEveryEndpoint() {
        JsonNode paths = api.get("/v3/api-docs", null).json().path("paths");

        assertThat(paths.has("/auth/token")).isTrue();
        assertThat(paths.has("/shows")).isTrue();
        assertThat(paths.has("/shows/{showId}")).isTrue();
        assertThat(paths.has("/shows/{showId}/reserve")).isTrue();
        assertThat(paths.has("/reservations/{reservationId}/cancel")).isTrue();
    }

    @Test
    void reserveDocumentsTheIdempotencyKeyAndTheRealResponseShape() {
        JsonNode reserve = api.get("/v3/api-docs", null).json()
                .path("paths").path("/shows/{showId}/reserve").path("post");

        assertThat(reserve.path("parameters").toString()).contains("Idempotency-Key");
        assertThat(reserve.path("responses").path("201").path("content")
                .path("application/json").path("schema").toString())
                .contains("ReservationResponse");
    }

    @Test
    void theTokenEndpointIsDocumentedAsNeedingNoBearerToken() {
        JsonNode document = api.get("/v3/api-docs", null).json();

        assertThat(document.path("components").path("securitySchemes").path("bearerAuth")
                .path("scheme").asText()).isEqualTo("bearer");
        assertThat(document.path("paths").path("/auth/token").path("post").path("security"))
                .isEmpty();
    }

    @Test
    void theDocsNeverCarryASecret() {
        String document = api.get("/v3/api-docs", null).body();

        assertThat(document)
                .doesNotContain("test-only-admin-key")
                .doesNotContain(TestTokens.SECRET)
                .doesNotContain("local-dev-only");
    }

    /** Opening the docs must not open anything else. */
    @Test
    void everyOtherPathStillFailsClosed() {
        assertThat(api.get("/nope", null).status()).isEqualTo(401);
        assertThat(api.get("/actuator/env", null).status()).isEqualTo(401);
        assertThat(api.get("/shows/" + UUID.randomUUID(), null).status()).isEqualTo(401);
        assertThat(api.post("/shows", null, "{}").status()).isEqualTo(401);
        assertThat(api.get("/nope", TestTokens.user("docs-alice")).status()).isEqualTo(404);
    }
}
