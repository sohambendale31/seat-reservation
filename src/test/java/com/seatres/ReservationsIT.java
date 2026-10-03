package com.seatres;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatres.support.AbstractPostgresIT;
import com.seatres.support.Api;
import com.seatres.support.Reconciliation;
import com.seatres.support.Reserve;
import com.seatres.support.Shows;
import com.seatres.support.TestTokens;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

class ReservationsIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private Api api;
    private Shows shows;
    private Reserve reserve;
    private Reconciliation reconciliation;
    private String alice;
    private String bob;

    @BeforeEach
    void setUp() {
        api = new Api(port);
        shows = new Shows(api, TestTokens.admin());
        reserve = new Reserve(api);
        reconciliation = new Reconciliation(jdbc);
        alice = TestTokens.user("alice");
        bob = TestTokens.user("bob");
    }

    @Test
    void reservesTwoSeatsAndReportsTheExactTotal() {
        UUID show = shows.create("res-01", "A", 10, 25000, 4);

        Api.Response response = reserve.seats(show, alice, Reserve.newKey(), "A1", "A2");

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.contentType()).startsWith("application/json");
        JsonNode body = response.json();
        assertThat(body.path("reservationId").asText()).isNotBlank();
        assertThat(body.path("showId").asText()).isEqualTo(show.toString());
        assertThat(body.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(labelsOf(body)).containsExactly("A1", "A2");
        assertThat(body.path("totalPaise").asLong()).isEqualTo(50000);
        assertThat(body.path("createdAt").asText()).isNotBlank();
        assertThat(Reserve.wasReplayed(response)).isFalse();

        JsonNode details = api.get("/shows/" + show, alice).json();
        assertThat(statusOf(details, "A1")).isEqualTo("CONFIRMED");
        assertThat(statusOf(details, "A2")).isEqualTo("CONFIRMED");
        assertThat(statusOf(details, "A3")).isEqualTo("AVAILABLE");
        assertThat(details.path("seatCounts").path("confirmed").asInt()).isEqualTo(2);
        assertThat(details.path("seatCounts").path("available").asInt()).isEqualTo(8);
        assertThat(seatsHeld(show, "alice")).isEqualTo(2);
        reconciliation.assertHoldsForShow(show);
    }

    @Test
    void seatsAreReturnedInLayoutOrderNotRequestOrder() {
        UUID show = shows.create("res-01b", "A", 12, 100, 4);

        Api.Response response = reserve.seats(show, alice, Reserve.newKey(), "A10", "A2");

        assertThat(labelsOf(response.json())).containsExactly("A2", "A10");
    }

    @Test
    void theIdempotencyKeyIsRequiredAndMustBeWellFormed() {
        UUID show = shows.create("res-02", "A", 4, 100, 4);

        Api.Response missing = reserve.withoutKey(show, alice, "A1");
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.code()).isEqualTo("IDEMPOTENCY_KEY_MISSING");

        Api.Response blank = reserve.seats(show, alice, "   ", List.of("A1"));
        assertThat(blank.status()).isEqualTo(400);
        assertThat(blank.code()).isIn("IDEMPOTENCY_KEY_MISSING", "IDEMPOTENCY_KEY_INVALID");

        String spaced = "has space";
        Api.Response invalid = reserve.seats(show, alice, spaced, List.of("A1"));
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.code()).isEqualTo("IDEMPOTENCY_KEY_INVALID");

        String tooLongKey = "k".repeat(129);
        Api.Response tooLong = reserve.seats(show, alice, tooLongKey, List.of("A1"));
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.code()).isEqualTo("IDEMPOTENCY_KEY_INVALID");

        assertThat(confirmedCount(show)).isZero();
        assertThat(recordsForKey("alice", spaced)).isZero();
        assertThat(recordsForKey("alice", tooLongKey)).isZero();
    }

    @Test
    void aTakenSeatIsDeclined() {
        UUID show = shows.create("res-03", "A", 10, 100, 4);
        assertThat(reserve.seats(show, bob, Reserve.newKey(), "A1").status()).isEqualTo(201);

        Api.Response response = reserve.seats(show, alice, Reserve.newKey(), "A1");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("SEAT_UNAVAILABLE");
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.json().path("unavailableSeats")).hasSize(1);
        assertThat(response.json().path("unavailableSeats").get(0).asText()).isEqualTo("A1");
        reconciliation.assertHoldsForShow(show);
    }

    @Test
    void aMultiSeatRequestIsAllOrNothing() {
        UUID show = shows.create("res-04", "A", 10, 100, 4);
        assertThat(reserve.seats(show, bob, Reserve.newKey(), "A1").status()).isEqualTo(201);

        Api.Response response = reserve.seats(show, alice, Reserve.newKey(), "A1", "A2", "A3");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("SEAT_UNAVAILABLE");

        JsonNode details = api.get("/shows/" + show, alice).json();
        assertThat(statusOf(details, "A2")).isEqualTo("AVAILABLE");
        assertThat(statusOf(details, "A3")).isEqualTo("AVAILABLE");
        assertThat(seatsHeld(show, "alice")).isZero();
        reconciliation.assertHoldsForShow(show);
    }

    @Test
    void anUnknownLabelIsUnprocessable() {
        UUID show = shows.create("res-05", "A", 4, 100, 4);

        Api.Response response = reserve.seats(show, alice, Reserve.newKey(), "A1", "Z9");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("UNKNOWN_SEAT");
        assertThat(response.json().path("unknownSeats")).hasSize(1);
        assertThat(response.json().path("unknownSeats").get(0).asText()).isEqualTo("Z9");
        assertThat(confirmedCount(show)).isZero();
        reconciliation.assertHoldsForShow(show);
    }

    @Test
    void anUnknownShowIsNotFoundAndTheSameKeyReplaysIt() {
        UUID missing = UUID.randomUUID();
        String key = Reserve.newKey();

        Api.Response first = reserve.seats(missing, alice, key, "A1");
        assertThat(first.status()).isEqualTo(404);
        assertThat(first.code()).isEqualTo("SHOW_NOT_FOUND");
        assertThat(first.json().path("showId").asText()).isEqualTo(missing.toString());

        Api.Response replay = reserve.seats(missing, alice, key, "A1");
        assertThat(replay.status()).isEqualTo(404);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(Reserve.wasReplayed(replay)).isTrue();
    }

    @Test
    void theLimitCountsSeatsAcrossReservations() {
        UUID show = shows.create("res-07", "A", 10, 100, 4);
        assertThat(reserve.seats(show, alice, Reserve.newKey(), "A1", "A2", "A3").status())
                .isEqualTo(201);

        Api.Response response = reserve.seats(show, alice, Reserve.newKey(), "A4", "A5");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("USER_LIMIT_EXCEEDED");
        assertThat(response.json().path("perUserLimit").asInt()).isEqualTo(4);
        assertThat(response.json().path("seatsHeld").asInt()).isEqualTo(3);
        assertThat(response.json().path("seatsRequested").asInt()).isEqualTo(2);
        assertThat(seatsHeld(show, "alice")).isEqualTo(3);
        reconciliation.assertHoldsForShow(show);
    }

    @Test
    void aSingleRequestLargerThanTheLimitIsDeclined() {
        UUID show = shows.create("res-08", "A", 10, 100, 4);

        Api.Response response = reserve.seats(show, alice, Reserve.newKey(),
                "A1", "A2", "A3", "A4", "A5");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("USER_LIMIT_EXCEEDED");
        assertThat(response.json().path("seatsRequested").asInt()).isEqualTo(5);
        assertThat(confirmedCount(show)).isZero();
    }

    @Test
    void theLimitIsPerShow() {
        UUID showX = shows.create("res-09-x", "A", 10, 100, 4);
        UUID showY = shows.create("res-09-y", "A", 10, 100, 4);
        assertThat(reserve.seats(showX, alice, Reserve.newKey(), "A1", "A2", "A3", "A4").status())
                .isEqualTo(201);

        assertThat(reserve.seats(showY, alice, Reserve.newKey(), "A1").status()).isEqualTo(201);
        assertThat(seatsHeld(showX, "alice")).isEqualTo(4);
        assertThat(seatsHeld(showY, "alice")).isEqualTo(1);
    }

    @Test
    void aCustomPerUserLimitIsHonoured() {
        UUID show = shows.create("res-10", "A", 10, 100, 2);
        assertThat(reserve.seats(show, alice, Reserve.newKey(), "A1", "A2").status()).isEqualTo(201);

        Api.Response response = reserve.seats(show, alice, Reserve.newKey(), "A3");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("USER_LIMIT_EXCEEDED");
        assertThat(response.json().path("perUserLimit").asInt()).isEqualTo(2);
    }

    @Test
    void theLimitIsReportedBeforeSeatAvailability() {
        UUID show = shows.create("res-11", "A", 10, 100, 4);
        assertThat(reserve.seats(show, bob, Reserve.newKey(), "A9").status()).isEqualTo(201);
        assertThat(reserve.seats(show, alice, Reserve.newKey(), "A1", "A2", "A3", "A4").status())
                .isEqualTo(201);

        Api.Response response = reserve.seats(show, alice, Reserve.newKey(), "A9");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("USER_LIMIT_EXCEEDED");
    }

    @Test
    void largePricesSumExactly() {
        UUID show = shows.create("res-12", "A", 10, 100_000_000L, 10);

        Api.Response response = reserve.seats(show, alice, Reserve.newKey(),
                Shows.labels("A", 10));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.json().path("totalPaise").asLong()).isEqualTo(1_000_000_000L);
    }

    @Test
    void aDeclineLeavesNoQuotaInflation() {
        UUID show = shows.create("res-quota", "A", 10, 100, 4);
        assertThat(reserve.seats(show, bob, Reserve.newKey(), "A1").status()).isEqualTo(201);

        assertThat(reserve.seats(show, alice, Reserve.newKey(), "A1").status()).isEqualTo(409);

        assertThat(seatsHeld(show, "alice")).isZero();
        reconciliation.assertHoldsForShow(show);
    }

    @Test
    void theReservationOwnerIsTheTokenSubject() {
        UUID show = shows.create("res-owner", "A", 4, 100, 4);

        Api.Response response = reserve.seats(show, alice, Reserve.newKey(), "A1");

        UUID reservationId = UUID.fromString(response.json().path("reservationId").asText());
        assertThat(jdbc.queryForObject("SELECT user_id FROM reservations WHERE id = ?",
                String.class, reservationId)).isEqualTo("alice");
    }

    @Test
    void identityInTheBodyIsRejectedAndReservesNothing() {
        UUID show = shows.create("res-spoof", "A", 4, 100, 4);

        Api.Response response = reserve.rawBody(show, alice, Reserve.newKey(),
                "{\"seats\":[\"A1\"],\"userId\":\"mallory\"}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(confirmedCount(show)).isZero();
    }

    @Test
    void invalidSeatListsAreRejected() {
        UUID show = shows.create("res-badseats", "A", 4, 100, 4);

        assertThat(reserve.rawBody(show, alice, Reserve.newKey(), "{\"seats\":[]}").code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(reserve.seats(show, alice, Reserve.newKey(), "A1", "A1").code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(reserve.seats(show, alice, Reserve.newKey(), "a1").code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(confirmedCount(show)).isZero();
    }

    private long seatsHeld(UUID showId, String userId) {
        Integer held = jdbc.queryForObject(
                "SELECT coalesce(max(seats_held), 0) FROM user_show_quotas "
                        + "WHERE show_id = ? AND user_id = ?", Integer.class, showId, userId);
        return held == null ? 0 : held;
    }

    private int confirmedCount(UUID showId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM seats WHERE show_id = ? AND status = 'CONFIRMED'",
                Integer.class, showId);
        return count == null ? 0 : count;
    }

    private int recordsForKey(String userId, String key) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM idempotency_records "
                + "WHERE user_id = ? AND idem_key = ?", Integer.class, userId, key);
        return count == null ? 0 : count;
    }

    private static List<String> labelsOf(JsonNode body) {
        return body.path("seats").findValuesAsText("label");
    }

    private static String statusOf(JsonNode details, String label) {
        for (JsonNode seat : details.path("seats")) {
            if (label.equals(seat.path("label").asText())) {
                return seat.path("status").asText();
            }
        }
        throw new IllegalArgumentException("no seat " + label);
    }
}
