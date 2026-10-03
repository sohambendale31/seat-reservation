package com.seatres;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatres.support.AbstractPostgresIT;
import com.seatres.support.Api;
import com.seatres.support.Reconciliation;
import com.seatres.support.Reserve;
import com.seatres.support.Shows;
import com.seatres.support.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

class IdempotencyIT extends AbstractPostgresIT {

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
    void theSameKeyAndBodyReplaysTheOriginalResponseByteForByte() {
        UUID show = shows.create("idem-01", "A", 10, 100, 4);
        String key = Reserve.newKey();

        Api.Response first = reserve.seats(show, alice, key, "A1", "A2");
        Api.Response replay = reserve.seats(show, alice, key, "A1", "A2");

        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(Reserve.wasReplayed(first)).isFalse();
        assertThat(Reserve.wasReplayed(replay)).isTrue();
        assertThat(reservationCount(show)).isEqualTo(1);
        assertThat(recordsForKey("alice", key)).isEqualTo(1);
        assertThat(seatsHeld(show, "alice")).isEqualTo(2);
        reconciliation.assertHoldsForShow(show);
    }

    @Test
    void seatOrderDoesNotChangeTheCanonicalRequest() {
        UUID show = shows.create("idem-02", "A", 10, 100, 4);
        String key = Reserve.newKey();

        Api.Response first = reserve.seats(show, alice, key, "A1", "A2");
        Api.Response reordered = reserve.seats(show, alice, key, "A2", "A1");

        assertThat(reordered.status()).isEqualTo(201);
        assertThat(reordered.body()).isEqualTo(first.body());
        assertThat(Reserve.wasReplayed(reordered)).isTrue();
        assertThat(reservationCount(show)).isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentSeatSetIsRefused() {
        UUID show = shows.create("idem-03", "A", 10, 100, 4);
        String key = Reserve.newKey();
        assertThat(reserve.seats(show, alice, key, "A1").status()).isEqualTo(201);

        Api.Response reused = reserve.seats(show, alice, key, "A2");

        assertThat(reused.status()).isEqualTo(409);
        assertThat(reused.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(Reserve.wasReplayed(reused)).isFalse();
        assertThat(reservationCount(show)).isEqualTo(1);
        assertThat(seatsHeld(show, "alice")).isEqualTo(1);
    }

    @Test
    void theSameKeyOnADifferentShowIsRefused() {
        UUID showOne = shows.create("idem-04-a", "A", 10, 100, 4);
        UUID showTwo = shows.create("idem-04-b", "A", 10, 100, 4);
        String key = Reserve.newKey();
        assertThat(reserve.seats(showOne, alice, key, "A1").status()).isEqualTo(201);

        Api.Response reused = reserve.seats(showTwo, alice, key, "A1");

        assertThat(reused.status()).isEqualTo(409);
        assertThat(reused.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(reservationCount(showTwo)).isZero();
    }

    @Test
    void keysAreScopedPerUser() {
        UUID show = shows.create("idem-05", "A", 10, 100, 4);
        String key = Reserve.newKey();

        Api.Response aliceFirst = reserve.seats(show, alice, key, "A1");
        Api.Response bobSame = reserve.seats(show, bob, key, "A2");

        assertThat(aliceFirst.status()).isEqualTo(201);
        assertThat(bobSame.status()).isEqualTo(201);
        assertThat(Reserve.wasReplayed(bobSame)).isFalse();
        assertThat(aliceFirst.json().path("reservationId").asText())
                .isNotEqualTo(bobSame.json().path("reservationId").asText());
        assertThat(reservationCount(show)).isEqualTo(2);
        reconciliation.assertHoldsForShow(show);
    }

    @Test
    void aStoredDeclineIsReplayedEvenAfterTheSeatIsFreed() {
        UUID show = shows.create("idem-06", "A", 10, 100, 4);
        Api.Response bobHold = reserve.seats(show, bob, Reserve.newKey(), "A1");
        String bobReservation = bobHold.json().path("reservationId").asText();

        String key = Reserve.newKey();
        Api.Response declined = reserve.seats(show, alice, key, "A1");
        assertThat(declined.status()).isEqualTo(409);
        assertThat(declined.code()).isEqualTo("SEAT_UNAVAILABLE");

        releaseDirectly(show, bobReservation);

        Api.Response replay = reserve.seats(show, alice, key, "A1");
        assertThat(replay.status()).isEqualTo(409);
        assertThat(replay.body()).isEqualTo(declined.body());
        assertThat(Reserve.wasReplayed(replay)).isTrue();

        Api.Response fresh = reserve.seats(show, alice, Reserve.newKey(), "A1");
        assertThat(fresh.status()).isEqualTo(201);
        reconciliation.assertHoldsForShow(show);
    }

    @Test
    void aRejectedRequestDoesNotConsumeTheKey() {
        UUID show = shows.create("idem-07", "A", 10, 100, 4);
        String key = Reserve.newKey();

        assertThat(reserve.rawBody(show, alice, key, "{\"seats\":[]}").status()).isEqualTo(400);

        Api.Response valid = reserve.seats(show, alice, key, "A1");
        assertThat(valid.status()).isEqualTo(201);
        assertThat(Reserve.wasReplayed(valid)).isFalse();
    }

    @Test
    void aReplayedSuccessStillDescribesTheReservationAsCreated() {
        UUID show = shows.create("idem-09", "A", 10, 100, 4);
        String key = Reserve.newKey();
        Api.Response created = reserve.seats(show, alice, key, "A1");
        String reservationId = created.json().path("reservationId").asText();

        releaseDirectly(show, reservationId);

        Api.Response replay = reserve.seats(show, alice, key, "A1");
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.json().path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(replay.body()).isEqualTo(created.body());
        assertThat(Reserve.wasReplayed(replay)).isTrue();
    }

    @Test
    void everyStoredRecordCarriesAResponse() {
        UUID show = shows.create("idem-x7", "A", 10, 100, 4);
        reserve.seats(show, alice, Reserve.newKey(), "A1");
        reserve.seats(show, bob, Reserve.newKey(), "A1");
        reserve.seats(show, alice, Reserve.newKey(), "Z9");

        reconciliation.assertEveryCommittedIdempotencyRecordHasAResponse();
    }

    /** Frees seats without going through cancel, which does not exist yet. */
    private void releaseDirectly(UUID showId, String reservationId) {
        UUID reservation = UUID.fromString(reservationId);
        int seatCount = jdbc.queryForObject(
                "SELECT seat_count FROM reservations WHERE id = ?", Integer.class, reservation);
        String userId = jdbc.queryForObject(
                "SELECT user_id FROM reservations WHERE id = ?", String.class, reservation);
        jdbc.update("UPDATE seats SET status = 'AVAILABLE', reservation_id = NULL "
                + "WHERE reservation_id = ?", reservation);
        jdbc.update("UPDATE reservation_seats SET released_at = now() WHERE reservation_id = ?",
                reservation);
        jdbc.update("UPDATE reservations SET status = 'CANCELLED', cancelled_at = now() "
                + "WHERE id = ?", reservation);
        jdbc.update("UPDATE user_show_quotas SET seats_held = seats_held - ? "
                + "WHERE show_id = ? AND user_id = ?", seatCount, showId, userId);
    }

    private int reservationCount(UUID showId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM reservations WHERE show_id = ?", Integer.class, showId);
        return count == null ? 0 : count;
    }

    private int recordsForKey(String userId, String key) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM idempotency_records "
                + "WHERE user_id = ? AND idem_key = ?", Integer.class, userId, key);
        return count == null ? 0 : count;
    }

    private long seatsHeld(UUID showId, String userId) {
        Integer held = jdbc.queryForObject(
                "SELECT coalesce(max(seats_held), 0) FROM user_show_quotas "
                        + "WHERE show_id = ? AND user_id = ?", Integer.class, showId, userId);
        return held == null ? 0 : held;
    }
}
