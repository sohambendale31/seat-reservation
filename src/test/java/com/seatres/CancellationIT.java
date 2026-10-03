package com.seatres;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatres.support.AbstractPostgresIT;
import com.seatres.support.Api;
import com.seatres.support.Cancel;
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

class CancellationIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private Api api;
    private Shows shows;
    private Reserve reserve;
    private Cancel cancel;
    private Reconciliation reconciliation;
    private String alice;
    private String bob;

    @BeforeEach
    void setUp() {
        api = new Api(port);
        shows = new Shows(api, TestTokens.admin());
        reserve = new Reserve(api);
        cancel = new Cancel(api);
        reconciliation = new Reconciliation(jdbc);
        alice = TestTokens.user("can-alice");
        bob = TestTokens.user("can-bob");
    }

    @Test
    void theOwnerCancelsAndTheSeatsComeBack() {
        UUID show = shows.create("can-01", "A", 10, 25000, 4);
        UUID reservation = reserveSeats(show, alice, "A1", "A2");

        Api.Response response = cancel.of(reservation, alice);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.contentType()).startsWith("application/json");
        JsonNode body = response.json();
        assertThat(body.path("reservationId").asText()).isEqualTo(reservation.toString());
        assertThat(body.path("showId").asText()).isEqualTo(show.toString());
        assertThat(body.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(body.path("cancelledAt").asText()).isNotBlank();
        assertThat(body.path("createdAt").asText()).isNotBlank();
        assertThat(labelsOf(body)).containsExactly("A1", "A2");
        assertThat(body.path("totalPaise").asLong()).isEqualTo(50000);

        JsonNode details = api.get("/shows/" + show, alice).json();
        assertThat(statusOf(details, "A1")).isEqualTo("AVAILABLE");
        assertThat(statusOf(details, "A2")).isEqualTo("AVAILABLE");
        assertThat(details.path("seatCounts").path("available").asInt()).isEqualTo(10);
        assertThat(details.path("seatCounts").path("confirmed").asInt()).isZero();
        assertThat(seatsHeld(show, "can-alice")).isZero();
        assertThat(unreleasedLinks(reservation)).isZero();
        reconciliation.assertHoldsForShow(show);
    }

    @Test
    void cancellingTwiceIsANoOpWithTheSameTimestamp() {
        UUID show = shows.create("can-02", "A", 10, 100, 4);
        UUID reservation = reserveSeats(show, alice, "A1");

        Api.Response first = cancel.of(reservation, alice);
        Api.Response second = cancel.of(reservation, alice);

        assertThat(first.status()).isEqualTo(200);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.json().path("cancelledAt").asText())
                .isEqualTo(first.json().path("cancelledAt").asText());
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(seatsHeld(show, "can-alice")).isZero();
        reconciliation.assertHoldsForShow(show);
    }

    @Test
    void aNonOwnerCannotCancelAndLearnsNothing() {
        UUID show = shows.create("can-03", "A", 10, 100, 4);
        UUID reservation = reserveSeats(show, alice, "A1");

        Api.Response response = cancel.of(reservation, bob);

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.code()).isEqualTo("RESERVATION_NOT_FOUND");
        assertThat(response.json().path("reservationId").asText())
                .isEqualTo(reservation.toString());

        assertThat(statusOf(api.get("/shows/" + show, alice).json(), "A1")).isEqualTo("CONFIRMED");
        assertThat(seatsHeld(show, "can-alice")).isEqualTo(1);
        assertThat(reservationStatus(reservation)).isEqualTo("CONFIRMED");
        reconciliation.assertHoldsForShow(show);
    }

    @Test
    void anUnknownReservationIsIndistinguishableFromSomeoneElses() {
        UUID unknown = UUID.randomUUID();

        Api.Response response = cancel.of(unknown, alice);

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.code()).isEqualTo("RESERVATION_NOT_FOUND");
    }

    @Test
    void aMalformedReservationIdIsAValidationFailure() {
        Api.Response response = cancel.ofRaw("not-a-uuid", alice);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.json().path("errors").get(0).path("field").asText())
                .isEqualTo("reservationId");
    }

    @Test
    void aReleasedSeatTakenBySomeoneElseIsNotStolenBackByARepeatCancel() {
        UUID show = shows.create("can-05", "A", 10, 100, 4);
        UUID aliceReservation = reserveSeats(show, alice, "A1");
        assertThat(cancel.of(aliceReservation, alice).status()).isEqualTo(200);

        UUID bobReservation = reserveSeats(show, bob, "A1");

        Api.Response repeat = cancel.of(aliceReservation, alice);

        assertThat(repeat.status()).isEqualTo(200);
        assertThat(repeat.json().path("status").asText()).isEqualTo("CANCELLED");
        assertThat(statusOf(api.get("/shows/" + show, alice).json(), "A1")).isEqualTo("CONFIRMED");
        assertThat(reservationHoldingSeat(show, "A1")).isEqualTo(bobReservation);
        assertThat(seatsHeld(show, "can-bob")).isEqualTo(1);
        assertThat(seatsHeld(show, "can-alice")).isZero();
        reconciliation.assertHoldsForShow(show);
    }

    @Test
    void cancellingRestoresTheQuotaSoTheUserCanReserveAgain() {
        UUID show = shows.create("can-06", "A", 10, 100, 4);
        UUID pair = reserveSeats(show, alice, "A1", "A2");
        reserveSeats(show, alice, "A3", "A4");
        assertThat(seatsHeld(show, "can-alice")).isEqualTo(4);
        assertThat(reserve.seats(show, alice, Reserve.newKey(), "A5").code())
                .isEqualTo("USER_LIMIT_EXCEEDED");

        assertThat(cancel.of(pair, alice).status()).isEqualTo(200);

        assertThat(seatsHeld(show, "can-alice")).isEqualTo(2);
        assertThat(reserve.seats(show, alice, Reserve.newKey(), "A5", "A6").status())
                .isEqualTo(201);
        assertThat(seatsHeld(show, "can-alice")).isEqualTo(4);
        reconciliation.assertHoldsForShow(show);
    }

    @Test
    void anyBodyAndContentTypeAreIgnored() {
        UUID show = shows.create("can-07", "A", 10, 100, 4);
        UUID reservation = reserveSeats(show, alice, "A1");

        Api.Response response = cancel.withBody(reservation, alice, "this is not json",
                "text/plain");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("status").asText()).isEqualTo("CANCELLED");
        reconciliation.assertHoldsForShow(show);
    }

    @Test
    void cancellingOneReservationLeavesTheUsersOtherSeatsAlone() {
        UUID show = shows.create("can-isolated", "A", 10, 100, 4);
        UUID first = reserveSeats(show, alice, "A1", "A2");
        UUID second = reserveSeats(show, alice, "A3");

        assertThat(cancel.of(first, alice).status()).isEqualTo(200);

        JsonNode details = api.get("/shows/" + show, alice).json();
        assertThat(statusOf(details, "A1")).isEqualTo("AVAILABLE");
        assertThat(statusOf(details, "A2")).isEqualTo("AVAILABLE");
        assertThat(statusOf(details, "A3")).isEqualTo("CONFIRMED");
        assertThat(reservationStatus(second)).isEqualTo("CONFIRMED");
        assertThat(seatsHeld(show, "can-alice")).isEqualTo(1);
        reconciliation.assertHoldsForShow(show);
    }

    @Test
    void cancellingNeedsAUserToken() {
        UUID show = shows.create("can-auth", "A", 4, 100, 4);
        UUID reservation = reserveSeats(show, alice, "A1");

        assertThat(cancel.of(reservation, null).status()).isEqualTo(401);
        assertThat(cancel.of(reservation, TestTokens.admin()).status()).isEqualTo(403);
        assertThat(reservationStatus(reservation)).isEqualTo("CONFIRMED");
    }

    private UUID reserveSeats(UUID show, String token, String... labels) {
        Api.Response response = reserve.seats(show, token, Reserve.newKey(), labels);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return UUID.fromString(response.json().path("reservationId").asText());
    }

    private long seatsHeld(UUID showId, String userId) {
        Integer held = jdbc.queryForObject(
                "SELECT coalesce(max(seats_held), 0) FROM user_show_quotas "
                        + "WHERE show_id = ? AND user_id = ?", Integer.class, showId, userId);
        return held == null ? 0 : held;
    }

    private String reservationStatus(UUID reservationId) {
        return jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String.class,
                reservationId);
    }

    private int unreleasedLinks(UUID reservationId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM reservation_seats "
                + "WHERE reservation_id = ? AND released_at IS NULL", Integer.class, reservationId);
        return count == null ? 0 : count;
    }

    private UUID reservationHoldingSeat(UUID showId, String label) {
        return jdbc.queryForObject("SELECT reservation_id FROM seats "
                + "WHERE show_id = ? AND label = ?", UUID.class, showId, label);
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
