package com.seatres;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatres.support.AbstractPostgresIT;
import com.seatres.support.Api;
import com.seatres.support.Cancel;
import com.seatres.support.Races;
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

class CancelConcurrencyIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private Api api;
    private Shows shows;
    private Reserve reserve;
    private Cancel cancel;
    private Reconciliation reconciliation;

    @BeforeEach
    void setUp() {
        api = new Api(port);
        shows = new Shows(api, TestTokens.admin());
        reserve = new Reserve(api);
        cancel = new Cancel(api);
        reconciliation = new Reconciliation(jdbc);
    }

    @Test
    void aCancelRacingAnotherUsersReserveNeverLetsBothOwnTheSeat() {
        int rounds = 100;
        long deadlocksBefore = deadlockCount();
        String alice = TestTokens.user("ct06-alice");
        String bob = TestTokens.user("ct06-bob");

        for (int round = 0; round < rounds; round++) {
            UUID show = shows.create("ct-06-" + round, "E", 2, 100, 4);
            Api.Response held = reserve.seats(show, alice, Reserve.newKey(), "E1");
            assertThat(held.status()).isEqualTo(201);
            UUID aliceReservation = UUID.fromString(held.json().path("reservationId").asText());

            List<Api.Response> responses = Races.runTogether(2, i -> i == 0
                    ? cancel.of(aliceReservation, alice)
                    : reserve.seats(show, bob, Reserve.newKey(), "E1"));

            Api.Response cancelled = responses.get(0);
            Api.Response bobReserve = responses.get(1);

            assertThat(cancelled.status()).as("round %d cancel", round).isEqualTo(200);
            assertThat(bobReserve.status()).as("round %d reserve: %s", round, bobReserve.body())
                    .isIn(201, 409);
            if (bobReserve.status() == 409) {
                assertThat(bobReserve.code()).isEqualTo("SEAT_UNAVAILABLE");
            }

            assertThat(reservationStatus(aliceReservation)).isEqualTo("CANCELLED");
            assertThat(seatsHeld(show, "ct06-alice")).as("round %d alice quota", round).isZero();

            UUID owner = reservationHoldingSeat(show, "E1");
            if (bobReserve.status() == 201) {
                UUID bobReservation =
                        UUID.fromString(bobReserve.json().path("reservationId").asText());
                assertThat(owner).as("round %d", round).isEqualTo(bobReservation);
                assertThat(seatsHeld(show, "ct06-bob")).isEqualTo(1);
            } else {
                assertThat(owner).as("round %d", round).isNull();
                assertThat(seatsHeld(show, "ct06-bob")).isZero();
            }
            reconciliation.assertHoldsForShow(show);
        }

        assertThat(deadlockCount()).isEqualTo(deadlocksBefore);
    }

    @Test
    void twentyConcurrentCancelsOfOneReservationDecrementTheQuotaOnce() {
        UUID show = shows.create("ct-07", "F", 10, 100, 4);
        String alice = TestTokens.user("ct07-alice");
        Api.Response held = reserve.seats(show, alice, Reserve.newKey(), "F1", "F2");
        assertThat(held.status()).isEqualTo(201);
        UUID reservation = UUID.fromString(held.json().path("reservationId").asText());
        assertThat(seatsHeld(show, "ct07-alice")).isEqualTo(2);

        List<Api.Response> responses = Races.runTogether(20, i -> cancel.of(reservation, alice));

        assertThat(responses).allSatisfy(response ->
                assertThat(response.status()).as(response.body()).isEqualTo(200));
        assertThat(responses.stream()
                .map(response -> response.json().path("cancelledAt").asText())
                .distinct()
                .toList()).hasSize(1);
        assertThat(responses.stream().map(Api.Response::body).distinct().toList()).hasSize(1);
        assertThat(seatsHeld(show, "ct07-alice")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seats WHERE show_id = ? "
                + "AND status = 'CONFIRMED'", Integer.class, show)).isZero();
        reconciliation.assertHoldsForShow(show);
    }

    private String reservationStatus(UUID reservationId) {
        return jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String.class,
                reservationId);
    }

    private UUID reservationHoldingSeat(UUID showId, String label) {
        return jdbc.queryForObject("SELECT reservation_id FROM seats "
                + "WHERE show_id = ? AND label = ?", UUID.class, showId, label);
    }

    private long seatsHeld(UUID showId, String userId) {
        Integer held = jdbc.queryForObject(
                "SELECT coalesce(max(seats_held), 0) FROM user_show_quotas "
                        + "WHERE show_id = ? AND user_id = ?", Integer.class, showId, userId);
        return held == null ? 0 : held;
    }

    private long deadlockCount() {
        Long deadlocks = jdbc.queryForObject(
                "SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()",
                Long.class);
        return deadlocks == null ? 0 : deadlocks;
    }
}
