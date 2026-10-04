package com.seatres;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatres.jobs.IdempotencyCleanupJob;
import com.seatres.support.AbstractPostgresIT;
import com.seatres.support.Api;
import com.seatres.support.Cancel;
import com.seatres.support.Metrics;
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

class ObservabilityIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IdempotencyCleanupJob cleanupJob;

    private Api api;
    private Shows shows;
    private Reserve reserve;
    private Cancel cancel;
    private Metrics metrics;
    private String alice;
    private String bob;

    @BeforeEach
    void setUp() {
        api = new Api(port);
        shows = new Shows(api, TestTokens.admin());
        reserve = new Reserve(api);
        cancel = new Cancel(api);
        metrics = new Metrics(api);
        alice = TestTokens.user("obs-alice");
        bob = TestTokens.user("obs-bob");
    }

    @Test
    void everyCustomMetricNameIsExposed() {
        String scrape = metrics.scrape();

        assertThat(scrape)
                .contains("seatres_reservations_confirmed_total")
                .contains("seatres_reservation_declines_total")
                .contains("seatres_idempotency_replays_total")
                .contains("seatres_invariant_violations_total")
                .contains("http_server_requests_seconds")
                .contains("hikaricp_connections_active")
                .contains("jvm_memory_used_bytes");
    }

    @Test
    void countersExistAtZeroBeforeAnyTraffic() {
        String scrape = metrics.scrape();

        for (String reason : List.of("seat_unavailable", "user_limit_exceeded")) {
            assertThat(scrape).contains(
                    "seatres_reservation_declines_total{reason=\"%s\"}".formatted(reason));
        }
        for (String status : List.of("201", "404", "409", "422")) {
            assertThat(scrape).contains(
                    "seatres_idempotency_replays_total{status=\"%s\"}".formatted(status));
        }
        for (String check : List.of("db_constraint", "rowcount_assertion")) {
            assertThat(scrape).contains(
                    "seatres_invariant_violations_total{check=\"%s\"}".formatted(check));
        }
    }

    @Test
    void noInvariantViolationHasEverFired() {
        String scrape = metrics.scrape();

        assertThat(metrics.value(scrape,
                "seatres_invariant_violations_total{check=\"db_constraint\"}")).isZero();
        assertThat(metrics.value(scrape,
                "seatres_invariant_violations_total{check=\"rowcount_assertion\"}")).isZero();
    }

    @Test
    void eachOutcomeMovesExactlyItsOwnCounter() {
        UUID show = shows.create("obs-counts", "A", 10, 100, 4);
        String before = metrics.scrape();
        double confirmedBefore = metrics.value(before, "seatres_reservations_confirmed_total");
        double seatDeclineBefore = metrics.value(before,
                "seatres_reservation_declines_total{reason=\"seat_unavailable\"}");
        double limitDeclineBefore = metrics.value(before,
                "seatres_reservation_declines_total{reason=\"user_limit_exceeded\"}");
        double replay201Before = metrics.value(before,
                "seatres_idempotency_replays_total{status=\"201\"}");
        double replay409Before = metrics.value(before,
                "seatres_idempotency_replays_total{status=\"409\"}");

        String key = Reserve.newKey();
        assertThat(reserve.seats(show, alice, key, "A1").status()).isEqualTo(201);
        assertThat(reserve.seats(show, alice, key, "A1").status()).isEqualTo(201);
        assertThat(reserve.seats(show, bob, Reserve.newKey(), "A1").code())
                .isEqualTo("SEAT_UNAVAILABLE");
        assertThat(reserve.seats(show, alice, Reserve.newKey(), "A2", "A3", "A4", "A5").code())
                .isEqualTo("USER_LIMIT_EXCEEDED");
        assertThat(reserve.seats(show, alice, key, "A6").code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

        String after = metrics.scrape();
        assertThat(metrics.value(after, "seatres_reservations_confirmed_total"))
                .isEqualTo(confirmedBefore + 1);
        assertThat(metrics.value(after,
                "seatres_reservation_declines_total{reason=\"seat_unavailable\"}"))
                .isEqualTo(seatDeclineBefore + 1);
        assertThat(metrics.value(after,
                "seatres_reservation_declines_total{reason=\"user_limit_exceeded\"}"))
                .isEqualTo(limitDeclineBefore + 1);
        assertThat(metrics.value(after, "seatres_idempotency_replays_total{status=\"201\"}"))
                .isEqualTo(replay201Before + 1);
        assertThat(metrics.value(after, "seatres_idempotency_replays_total{status=\"409\"}"))
                .isEqualTo(replay409Before);
    }

    @Test
    void aDeclineThatIsReplayedIsNotCountedTwice() {
        UUID show = shows.create("obs-replayed-decline", "A", 10, 100, 4);
        assertThat(reserve.seats(show, bob, Reserve.newKey(), "A1").status()).isEqualTo(201);
        String key = Reserve.newKey();
        assertThat(reserve.seats(show, alice, key, "A1").code()).isEqualTo("SEAT_UNAVAILABLE");

        String before = metrics.scrape();
        double declines = metrics.value(before,
                "seatres_reservation_declines_total{reason=\"seat_unavailable\"}");
        double replays = metrics.value(before,
                "seatres_idempotency_replays_total{status=\"409\"}");

        assertThat(reserve.seats(show, alice, key, "A1").code()).isEqualTo("SEAT_UNAVAILABLE");

        String after = metrics.scrape();
        assertThat(metrics.value(after,
                "seatres_reservation_declines_total{reason=\"seat_unavailable\"}"))
                .isEqualTo(declines);
        assertThat(metrics.value(after, "seatres_idempotency_replays_total{status=\"409\"}"))
                .isEqualTo(replays + 1);
    }

    @Test
    void theSeatsGaugeMatchesTheShowRead() {
        UUID show = shows.create("obs-gauge", "A", 10, 100, 4);
        assertThat(reserve.seats(show, alice, Reserve.newKey(), "A1", "A2").status()).isEqualTo(201);

        String scrape = metrics.scrape();
        JsonNode details = api.get("/shows/" + show, alice).json();
        JsonNode counts = details.path("seatCounts");

        assertThat(metrics.seats(scrape, show.toString(), "available"))
                .isEqualTo(counts.path("available").asInt()).isEqualTo(8);
        assertThat(metrics.seats(scrape, show.toString(), "confirmed"))
                .isEqualTo(counts.path("confirmed").asInt()).isEqualTo(2);
        assertThat(metrics.seats(scrape, show.toString(), "held"))
                .isEqualTo(counts.path("held").asInt()).isZero();
    }

    @Test
    void theSeatsGaugeFollowsCancellation() {
        UUID show = shows.create("obs-gauge-cancel", "A", 5, 100, 4);
        Api.Response held = reserve.seats(show, alice, Reserve.newKey(), "A1");
        UUID reservation = UUID.fromString(held.json().path("reservationId").asText());
        assertThat(metrics.seats(metrics.scrape(), show.toString(), "confirmed")).isEqualTo(1);

        assertThat(cancel.of(reservation, alice).status()).isEqualTo(200);

        String scrape = metrics.scrape();
        assertThat(metrics.seats(scrape, show.toString(), "confirmed")).isZero();
        assertThat(metrics.seats(scrape, show.toString(), "available")).isEqualTo(5);
    }

    @Test
    void theGaugeTracksOnlyTheNewestShowsAndExposesAllThreeStatuses() {
        UUID oldest = shows.create("obs-window-0", "A", 1, 100, 4);
        for (int i = 1; i <= 20; i++) {
            shows.create("obs-window-" + i, "A", 1, 100, 4);
        }

        String scrape = metrics.scrape();

        assertThat(scrape).doesNotContain("show_id=\"" + oldest + "\"");
        long trackedShows = scrape.lines()
                .filter(line -> line.startsWith("seatres_seats{"))
                .map(line -> line.replaceAll(".*show_id=\"([^\"]+)\".*", "$1"))
                .distinct()
                .count();
        assertThat(trackedShows).isEqualTo(20);
        assertThat(scrape.lines().filter(line -> line.startsWith("seatres_seats{")).count())
                .isEqualTo(60);
    }

    @Test
    void theScrapeNeverLeaksIdentifiers() {
        UUID show = shows.create("obs-labels", "A", 4, 100, 4);
        String key = Reserve.newKey();
        Api.Response held = reserve.seats(show, alice, key, "A1");
        String reservationId = held.json().path("reservationId").asText();

        String scrape = metrics.scrape();

        assertThat(scrape)
                .doesNotContain("obs-alice")
                .doesNotContain("obs-bob")
                .doesNotContain(reservationId)
                .doesNotContain(key);
        assertThat(labelKeysOf(scrape)).doesNotContain("user_id", "sub", "idem_key",
                "reservation_id", "request_id", "label", "seat");
        assertThat(scrape.lines().filter(line -> line.contains("show_id="))
                .allMatch(line -> line.startsWith("seatres_seats{"))).isTrue();
    }

    private static List<String> labelKeysOf(String scrape) {
        return scrape.lines()
                .filter(line -> !line.startsWith("#") && line.contains("{"))
                .map(line -> line.substring(line.indexOf('{') + 1, line.lastIndexOf('}')))
                .flatMap(labels -> java.util.Arrays.stream(labels.split(",")))
                .filter(label -> label.contains("="))
                .map(label -> label.substring(0, label.indexOf('=')).trim())
                .distinct()
                .toList();
    }

    @Test
    void expiredRecordsAreDeletedAndTheirKeysBecomeReusable() {
        UUID show = shows.create("obs-cleanup", "A", 10, 100, 4);
        String key = Reserve.newKey();
        assertThat(reserve.seats(show, alice, key, "A1").status()).isEqualTo(201);
        jdbc.update("UPDATE idempotency_records "
                + "SET created_at = now() - interval '48 hours', "
                + "    expires_at = now() - interval '24 hours' "
                + "WHERE user_id = 'obs-alice' AND idem_key = ?", key);

        assertThat(cleanupJob.deleteExpired()).isPositive();

        assertThat(recordsForKey("obs-alice", key)).isZero();
        Api.Response afterCleanup = reserve.seats(show, alice, key, "A2");
        assertThat(afterCleanup.status()).isEqualTo(201);
        assertThat(Reserve.wasReplayed(afterCleanup)).isFalse();
    }

    @Test
    void unexpiredRecordsSurviveCleanup() {
        UUID show = shows.create("obs-cleanup-keep", "A", 10, 100, 4);
        String key = Reserve.newKey();
        assertThat(reserve.seats(show, alice, key, "A1").status()).isEqualTo(201);

        cleanupJob.deleteExpired();

        assertThat(recordsForKey("obs-alice", key)).isEqualTo(1);
    }

    private int recordsForKey(String userId, String key) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM idempotency_records "
                + "WHERE user_id = ? AND idem_key = ?", Integer.class, userId, key);
        return count == null ? 0 : count;
    }
}
