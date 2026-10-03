package com.seatres;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatres.support.AbstractPostgresIT;
import com.seatres.support.Api;
import com.seatres.support.Races;
import com.seatres.support.Reconciliation;
import com.seatres.support.Reserve;
import com.seatres.support.Shows;
import com.seatres.support.TestTokens;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

class ReserveConcurrencyIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private Api api;
    private Shows shows;
    private Reserve reserve;
    private Reconciliation reconciliation;

    @BeforeEach
    void setUp() {
        api = new Api(port);
        shows = new Shows(api, TestTokens.admin());
        reserve = new Reserve(api);
        reconciliation = new Reconciliation(jdbc);
    }

    @Test
    void hundredsOfUsersChasingOneSeatProduceExactlyOneWinner() {
        UUID show = shows.create("ct-01", "A", 10, 25000, 4);
        int users = 200;

        List<Api.Response> responses = Races.runTogether(users, i ->
                reserve.seats(show, TestTokens.user("ct01-u" + i), Reserve.newKey(), "A1"));

        Map<String, Long> byOutcome = outcomes(responses);
        assertThat(byOutcome).containsEntry("201", 1L).containsEntry("409 SEAT_UNAVAILABLE",
                (long) users - 1);
        assertThat(serverErrors(responses)).isEmpty();
        assertThat(confirmedLabels(show)).containsExactly("A1");
        assertThat(reservationCount(show)).isEqualTo(1);
        reconciliation.assertHoldsForShow(show);
    }

    @Test
    void oneUserRacingTenRequestsStopsAtTheLimit() {
        UUID show = shows.create("ct-02", "A", 10, 100, 4);
        String alice = TestTokens.user("ct02-alice");
        List<String> labels = Shows.labels("A", 10);

        List<Api.Response> responses = Races.runTogether(10, i ->
                reserve.seats(show, alice, Reserve.newKey(), labels.get(i)));

        Map<String, Long> byOutcome = outcomes(responses);
        assertThat(byOutcome).containsEntry("201", 4L)
                .containsEntry("409 USER_LIMIT_EXCEEDED", 6L);
        assertThat(serverErrors(responses)).isEmpty();
        assertThat(seatsHeld(show, "ct02-alice")).isEqualTo(4);
        assertThat(confirmedLabels(show)).hasSize(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seats WHERE show_id = ? "
                + "AND status = 'CONFIRMED' AND reservation_id IN "
                + "(SELECT id FROM reservations WHERE user_id = 'ct02-alice')",
                Integer.class, show)).isEqualTo(4);
        reconciliation.assertHoldsForShow(show);
    }

    @Test
    void fiftyIdenticalRequestsWithOneKeyMakeOneReservation() {
        UUID show = shows.create("ct-03", "B", 10, 100, 4);
        String alice = TestTokens.user("ct03-alice");
        String key = Reserve.newKey();

        List<Api.Response> responses = Races.runTogether(50, i ->
                reserve.seats(show, alice, key, "B1", "B2"));

        assertThat(responses).allSatisfy(response ->
                assertThat(response.status()).as(response.body()).isEqualTo(201));
        assertThat(distinctReservationIds(responses)).hasSize(1);
        assertThat(responses.stream().filter(Reserve::wasReplayed).count()).isEqualTo(49);
        assertThat(reservationCount(show)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records "
                + "WHERE user_id = 'ct03-alice' AND idem_key = ?", Integer.class, key))
                .isEqualTo(1);
        assertThat(seatsHeld(show, "ct03-alice")).isEqualTo(2);
        reconciliation.assertHoldsForShow(show);
    }

    @Test
    void oneKeyUsedWithTwoDifferentBodiesAllocatesOnlyOne() {
        UUID show = shows.create("ct-04", "C", 10, 100, 4);
        String alice = TestTokens.user("ct04-alice");
        String key = Reserve.newKey();

        List<Api.Response> responses = Races.runTogether(50, i ->
                reserve.seats(show, alice, key, i % 2 == 0 ? "C1" : "C2"));

        long created = responses.stream().filter(r -> r.status() == 201).count();
        long reused = responses.stream()
                .filter(r -> r.status() == 409 && "IDEMPOTENCY_KEY_REUSED".equals(r.code()))
                .count();

        assertThat(created).isEqualTo(25);
        assertThat(reused).isEqualTo(25);
        assertThat(serverErrors(responses)).isEmpty();
        assertThat(distinctReservationIds(responses)).hasSize(1);
        assertThat(confirmedLabels(show)).hasSize(1).containsAnyOf("C1", "C2");
        assertThat(reservationCount(show)).isEqualTo(1);
        reconciliation.assertHoldsForShow(show);
    }

    @Test
    void overlappingMultiSeatRequestsInOppositeOrderNeverDeadlock() {
        int rounds = 100;
        long deadlocksBefore = deadlockCount();
        String userX = TestTokens.user("ct05-x");
        String userY = TestTokens.user("ct05-y");

        for (int round = 0; round < rounds; round++) {
            UUID show = shows.create("ct-05-" + round, "D", 4, 100, 4);

            List<Api.Response> responses = Races.runTogether(2, i -> i == 0
                    ? reserve.seats(show, userX, Reserve.newKey(), "D1", "D2", "D3")
                    : reserve.seats(show, userY, Reserve.newKey(), "D3", "D2", "D1"));

            assertThat(responses.stream().filter(r -> r.status() == 201).count())
                    .as("round %d: exactly one winner", round).isEqualTo(1);
            assertThat(responses.stream()
                    .filter(r -> r.status() == 409 && "SEAT_UNAVAILABLE".equals(r.code())).count())
                    .as("round %d: exactly one decline", round).isEqualTo(1);
            assertThat(serverErrors(responses)).as("round %d", round).isEmpty();
            assertThat(confirmedLabels(show)).containsExactly("D1", "D2", "D3");
            reconciliation.assertHoldsForShow(show);
        }

        assertThat(deadlockCount()).isEqualTo(deadlocksBefore);
    }

    private Map<String, Long> outcomes(List<Api.Response> responses) {
        return responses.stream().collect(Collectors.groupingBy(
                response -> response.status() == 201
                        ? "201"
                        : response.status() + " " + response.code(),
                Collectors.counting()));
    }

    private List<String> serverErrors(List<Api.Response> responses) {
        return responses.stream()
                .filter(response -> response.status() >= 500)
                .map(response -> response.status() + " " + response.body())
                .toList();
    }

    private List<String> distinctReservationIds(List<Api.Response> responses) {
        return responses.stream()
                .filter(response -> response.status() == 201)
                .map(response -> response.json().path("reservationId").asText())
                .distinct()
                .toList();
    }

    private List<String> confirmedLabels(UUID showId) {
        return jdbc.queryForList("SELECT label FROM seats WHERE show_id = ? "
                + "AND status = 'CONFIRMED' ORDER BY id", String.class, showId);
    }

    private int reservationCount(UUID showId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM reservations WHERE show_id = ?", Integer.class, showId);
        return count == null ? 0 : count;
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
