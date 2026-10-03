package com.seatres.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Invariant checks. Each query returns the violating rows, so an empty result means the invariant
 * holds. Scoped to one show because tests share a database and some deliberately write
 * inconsistent fixtures to probe constraints.
 */
public final class Reconciliation {

    private static final Map<String, String> PER_SHOW_CHECKS = new LinkedHashMap<>();

    private static final String COMMITTED_RECORDS_HAVE_A_RESPONSE =
            "SELECT id FROM idempotency_records WHERE response_status IS NULL";

    static {
        PER_SHOW_CHECKS.put("R1 seat count matches total_seats", """
                SELECT s.id FROM shows s LEFT JOIN seats st ON st.show_id = s.id
                WHERE s.id = :showId
                GROUP BY s.id, s.total_seats HAVING count(st.id) <> s.total_seats
                """);
        PER_SHOW_CHECKS.put("R2 no seat is HELD", """
                SELECT id FROM seats WHERE show_id = :showId AND status = 'HELD'
                """);
        PER_SHOW_CHECKS.put("R3 a seat's reservation is CONFIRMED", """
                SELECT st.id FROM seats st JOIN reservations r ON r.id = st.reservation_id
                WHERE st.show_id = :showId AND r.status <> 'CONFIRMED'
                """);
        PER_SHOW_CHECKS.put("R4 reservations hold exactly their seat_count", """
                SELECT r.id FROM reservations r LEFT JOIN seats st ON st.reservation_id = r.id
                WHERE r.show_id = :showId
                GROUP BY r.id, r.status, r.seat_count
                HAVING (r.status = 'CONFIRMED' AND count(st.id) <> r.seat_count)
                    OR (r.status = 'CANCELLED' AND count(st.id) <> 0)
                """);
        PER_SHOW_CHECKS.put("R5 quota counters equal confirmed seats", """
                SELECT coalesce(q.show_id, a.show_id) AS show_id,
                       coalesce(q.user_id, a.user_id) AS user_id
                FROM (SELECT show_id, user_id, seats_held FROM user_show_quotas
                      WHERE show_id = :showId) q
                FULL OUTER JOIN (
                    SELECT show_id, user_id, sum(seat_count) AS held FROM reservations
                    WHERE status = 'CONFIRMED' AND show_id = :showId
                    GROUP BY show_id, user_id
                ) a ON a.show_id = q.show_id AND a.user_id = q.user_id
                WHERE coalesce(q.seats_held, 0) <> coalesce(a.held, 0)
                """);
        PER_SHOW_CHECKS.put("R6 active links agree with seats.reservation_id", """
                SELECT rs.seat_id FROM reservation_seats rs JOIN seats st ON st.id = rs.seat_id
                WHERE rs.show_id = :showId AND rs.released_at IS NULL
                  AND st.reservation_id IS DISTINCT FROM rs.reservation_id
                UNION ALL
                SELECT st.id FROM seats st
                LEFT JOIN reservation_seats rs ON rs.seat_id = st.id AND rs.released_at IS NULL
                WHERE st.show_id = :showId AND st.reservation_id IS NOT NULL
                  AND rs.reservation_id IS DISTINCT FROM st.reservation_id
                """);
    }

    private final NamedParameterJdbcTemplate jdbc;

    public Reconciliation(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    public void assertHoldsForShow(UUID showId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("showId", showId);
        PER_SHOW_CHECKS.forEach((name, sql) ->
                assertThat(jdbc.queryForList(sql, parameters)).as(name).isEmpty());
    }

    public void assertHoldsForShow(String showId) {
        assertHoldsForShow(UUID.fromString(showId));
    }

    /** Not show-scoped: idempotency records reference neither a show nor a reservation. */
    public void assertEveryCommittedIdempotencyRecordHasAResponse() {
        assertThat(jdbc.queryForList(COMMITTED_RECORDS_HAVE_A_RESPONSE, new MapSqlParameterSource()))
                .as("R7 committed idempotency records carry a response")
                .isEmpty();
    }
}
