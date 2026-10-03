package com.seatres.repository;

import com.seatres.service.tx.Tx;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class QuotaJdbcRepository {

    private static final String ENSURE = """
            INSERT INTO user_show_quotas (show_id, user_id, seats_held, seat_limit)
            VALUES (:showId, :userId, 0, :seatLimit)
            ON CONFLICT (show_id, user_id) DO NOTHING
            """;

    private static final String LOCK = """
            SELECT seats_held, seat_limit
            FROM user_show_quotas
            WHERE show_id = :showId AND user_id = :userId
            FOR UPDATE
            """;

    private static final String ADJUST = """
            UPDATE user_show_quotas
            SET seats_held = seats_held + :delta
            WHERE show_id = :showId AND user_id = :userId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public QuotaJdbcRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ensure(UUID showId, String userId, int seatLimit) {
        Tx.requireActive();
        jdbc.update(ENSURE, new MapSqlParameterSource()
                .addValue("showId", showId)
                .addValue("userId", userId)
                .addValue("seatLimit", seatLimit));
    }

    /** Serialization point for one user in one show: every allocation queues on this row. */
    public Optional<Quota> lockForUpdate(UUID showId, String userId) {
        Tx.requireActive();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("showId", showId)
                .addValue("userId", userId);
        return jdbc.query(LOCK, parameters, rs -> rs.next()
                ? Optional.of(new Quota(rs.getInt("seats_held"), rs.getInt("seat_limit")))
                : Optional.empty());
    }

    public void adjust(UUID showId, String userId, int delta) {
        Tx.requireActive();
        int updated = jdbc.update(ADJUST, new MapSqlParameterSource()
                .addValue("showId", showId)
                .addValue("userId", userId)
                .addValue("delta", delta));
        if (updated != 1) {
            throw new IllegalStateException("expected to adjust 1 quota row but updated " + updated);
        }
    }

    public record Quota(int seatsHeld, int seatLimit) {}
}
