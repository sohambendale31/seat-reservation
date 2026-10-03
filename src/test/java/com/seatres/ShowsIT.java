package com.seatres;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatres.support.AbstractPostgresIT;
import com.seatres.support.Api;
import com.seatres.support.Reconciliation;
import com.seatres.support.TestTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

class ShowsIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private Api api;
    private Reconciliation reconciliation;
    private String admin;
    private String alice;

    @BeforeEach
    void setUp() {
        api = new Api(port);
        reconciliation = new Reconciliation(jdbc);
        admin = TestTokens.admin();
        alice = TestTokens.user("alice");
    }

    @Test
    void createsAShowWithEverySeatInLayoutOrder() {
        Api.Response created = api.post("/shows", admin, """
                {"name":"Evening Show","startsAt":"2026-12-01T19:30:00+05:30","perUserLimit":4,
                 "rows":[{"row":"A","seatCount":20,"pricePaise":250000},
                         {"row":"B","seatCount":20,"pricePaise":150000}]}
                """);

        assertThat(created.status()).isEqualTo(201);
        String showId = created.json().path("id").asText();
        assertThat(created.header("Location")).contains("/shows/" + showId);
        assertThat(created.json().path("name").asText()).isEqualTo("Evening Show");
        assertThat(created.json().path("perUserLimit").asInt()).isEqualTo(4);
        assertThat(created.json().path("totalSeats").asInt()).isEqualTo(40);
        assertThat(created.json().path("startsAt").asText()).isEqualTo("2026-12-01T14:00:00Z");

        Api.Response details = api.get("/shows/" + showId, alice);
        assertThat(details.status()).isEqualTo(200);
        assertThat(labelsOf(details.json())).hasSize(40)
                .startsWith("A1", "A2", "A3")
                .containsSequence("A20", "B1")
                .endsWith("B20");
        assertThat(details.json().path("seats")).allMatch(seat ->
                "AVAILABLE".equals(seat.path("status").asText()));
        assertThat(priceOf(details.json(), "A1")).isEqualTo(250000);
        assertThat(priceOf(details.json(), "B1")).isEqualTo(150000);
        reconciliation.assertHoldsForShow(showId);
        reconciliation.assertEveryCommittedIdempotencyRecordHasAResponse();
    }

    @Test
    void theCreateResponseMatchesALaterRead() {
        Api.Response created = api.post("/shows", admin, showBody("Exact", 1));
        String showId = created.json().path("id").asText();

        JsonNode details = api.get("/shows/" + showId, alice).json();

        for (String field : List.of("id", "name", "startsAt", "perUserLimit", "totalSeats",
                "createdAt")) {
            assertThat(details.path(field)).as(field).isEqualTo(created.json().path(field));
        }
    }

    @Test
    void createsTheMaximumTenThousandSeats() {
        List<String> rows = IntStream.range(0, 20)
                .mapToObj(i -> "{\"row\":\"R%c\",\"seatCount\":500,\"pricePaise\":1000}"
                        .formatted((char) ('A' + i)))
                .toList();
        Api.Response created = api.post("/shows", admin,
                "{\"name\":\"Big\",\"startsAt\":\"2026-12-01T19:30:00+05:30\",\"rows\":["
                        + String.join(",", rows) + "]}");

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().path("totalSeats").asInt()).isEqualTo(10_000);

        String showId = created.json().path("id").asText();
        assertThat(api.get("/shows/" + showId, alice).json().path("seats")).hasSize(10_000);
        reconciliation.assertHoldsForShow(showId);
    }

    @Test
    void perUserLimitDefaultsToFourWhenOmitted() {
        Api.Response created = api.post("/shows", admin, """
                {"name":"Default limit","startsAt":"2026-12-01T19:30:00+05:30",
                 "rows":[{"row":"A","seatCount":2,"pricePaise":100}]}
                """);

        assertThat(created.json().path("perUserLimit").asInt()).isEqualTo(4);
    }

    @Test
    void seatCountsAlwaysPartitionTheSeatList() {
        String showId = createShow("Counts", 7);

        JsonNode details = api.get("/shows/" + showId, alice).json();
        JsonNode counts = details.path("seatCounts");

        assertThat(counts.path("total").asInt()).isEqualTo(7);
        assertThat(counts.path("available").asInt()).isEqualTo(7);
        assertThat(counts.path("held").asInt()).isZero();
        assertThat(counts.path("confirmed").asInt()).isZero();
        assertThat(counts.path("available").asInt() + counts.path("held").asInt()
                + counts.path("confirmed").asInt()).isEqualTo(counts.path("total").asInt());
        assertThat(counts.path("total").asInt()).isEqualTo(details.path("seats").size());
        assertThat(counts.path("total").asInt()).isEqualTo(details.path("totalSeats").asInt());
    }

    @Test
    void anUnknownShowIsNotFound() {
        UUID unknown = UUID.randomUUID();

        Api.Response response = api.get("/shows/" + unknown, alice);

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.code()).isEqualTo("SHOW_NOT_FOUND");
        assertThat(response.json().path("showId").asText()).isEqualTo(unknown.toString());
    }

    @Test
    void aMalformedShowIdIsAValidationFailure() {
        Api.Response response = api.get("/shows/not-a-uuid", alice);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.json().path("errors").get(0).path("field").asText())
                .isEqualTo("showId");
    }

    @Test
    void anAdminMayAlsoReadAShow() {
        String showId = createShow("Admin readable", 2);

        assertThat(api.get("/shows/" + showId, admin).status()).isEqualTo(200);
    }

    @Test
    void duplicateRowsAndOversizedLayoutsAreRejected() {
        Api.Response duplicate = api.post("/shows", admin, """
                {"name":"Dup","startsAt":"2026-12-01T19:30:00+05:30",
                 "rows":[{"row":"A","seatCount":2,"pricePaise":100},
                         {"row":"A","seatCount":2,"pricePaise":100}]}
                """);

        assertThat(duplicate.status()).isEqualTo(400);
        assertThat(duplicate.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(duplicate.json().path("errors").get(0).path("field").asText()).isEqualTo("rows");
    }

    @Test
    void fractionalAndStringPricesAreMalformed() {
        assertThat(api.post("/shows", admin, """
                {"name":"Frac","startsAt":"2026-12-01T19:30:00+05:30",
                 "rows":[{"row":"A","seatCount":1,"pricePaise":2500.5}]}
                """).code()).isEqualTo("MALFORMED_REQUEST");

        assertThat(api.post("/shows", admin, """
                {"name":"Str","startsAt":"2026-12-01T19:30:00+05:30",
                 "rows":[{"row":"A","seatCount":1,"pricePaise":"2500"}]}
                """).code()).isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void identityInTheBodyIsRejected() {
        Api.Response response = api.post("/shows", admin, """
                {"name":"Spoof","startsAt":"2026-12-01T19:30:00+05:30","userId":"mallory",
                 "rows":[{"row":"A","seatCount":1,"pricePaise":100}]}
                """);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shows WHERE name = 'Spoof'",
                Integer.class)).isZero();
    }

    @Test
    void theShowNameIsStoredTrimmed() {
        Api.Response created = api.post("/shows", admin, """
                {"name":"   Padded   ","startsAt":"2026-12-01T19:30:00+05:30",
                 "rows":[{"row":"A","seatCount":1,"pricePaise":100}]}
                """);

        assertThat(created.json().path("name").asText()).isEqualTo("Padded");
    }

    @Test
    void eachCreateMakesANewShow() {
        String first = createShow("Not idempotent", 1);
        String second = createShow("Not idempotent", 1);

        assertThat(first).isNotEqualTo(second);
    }

    private String createShow(String name, int seatCount) {
        Api.Response created = api.post("/shows", admin, showBody(name, seatCount));
        assertThat(created.status()).isEqualTo(201);
        return created.json().path("id").asText();
    }

    private static String showBody(String name, int seatCount) {
        return """
                {"name":"%s","startsAt":"2026-12-01T19:30:00+05:30","perUserLimit":4,
                 "rows":[{"row":"A","seatCount":%d,"pricePaise":25000}]}
                """.formatted(name, seatCount);
    }

    private static List<String> labelsOf(JsonNode details) {
        List<String> labels = new ArrayList<>();
        details.path("seats").forEach(seat -> labels.add(seat.path("label").asText()));
        return labels;
    }

    private static long priceOf(JsonNode details, String label) {
        for (JsonNode seat : details.path("seats")) {
            if (label.equals(seat.path("label").asText())) {
                return seat.path("pricePaise").asLong();
            }
        }
        throw new IllegalArgumentException("no seat " + label);
    }
}
