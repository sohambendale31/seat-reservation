package com.seatres;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatres.support.AbstractPostgresIT;
import com.seatres.support.Api;
import com.seatres.support.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

class ProblemDetailsIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    private Api api;

    @BeforeEach
    void setUp() {
        api = new Api(port);
    }

    @Test
    void everyProblemCarriesTheRequiredMembers() {
        Api.Response response = api.post("/auth/token", null, "{\"sub\":\"has space\"}");

        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.json().path("type").asText())
                .isEqualTo("urn:seatres:problem:validation-failed");
        assertThat(response.json().path("title").asText()).isEqualTo("Validation failed");
        assertThat(response.json().path("status").asInt()).isEqualTo(400);
        assertThat(response.json().path("detail").asText()).isNotBlank();
        assertThat(response.json().path("instance").asText()).isEqualTo("/auth/token");
        assertThat(response.json().path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.json().path("requestId").asText()).isNotBlank();
        assertThat(response.json().path("retryable").asBoolean()).isFalse();
    }

    @Test
    void validationProblemsListTheOffendingFields() {
        Api.Response response = api.post("/auth/token", null, "{\"sub\":\"has space\"}");

        assertThat(response.json().path("errors")).hasSize(1);
        assertThat(response.json().path("errors").get(0).path("field").asText()).isEqualTo("sub");
        assertThat(response.json().path("errors").get(0).path("message").asText()).isNotBlank();
    }

    @Test
    void unparseableJsonIsAMalformedRequest() {
        Api.Response response = api.post("/auth/token", null, "{not json");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void errorBodiesNeverLeakInternals() {
        Api.Response[] responses = {
                api.post("/auth/token", null, "{not json"),
                api.post("/auth/token", null, "{\"sub\":\"has space\"}"),
                api.get("/shows/not-a-uuid", TestTokens.user("alice")),
                api.get("/shows/" + UUID.randomUUID(), null),
                api.get("/nope", TestTokens.user("alice")) };

        for (Api.Response response : responses) {
            assertThat(response.body())
                    .doesNotContain("org.springframework")
                    .doesNotContain("com.seatres")
                    .doesNotContain("java.lang")
                    .doesNotContain("Exception")
                    .doesNotContain("SELECT")
                    .doesNotContain("_ck")
                    .doesNotContain("_uk");
        }
    }
}
