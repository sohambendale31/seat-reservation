package com.seatres.repository;

import com.seatres.domain.SeatRow;
import com.seatres.domain.SeatStatus;
import com.seatres.service.tx.Tx;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class SeatJdbcRepository {

    private static final int INSERT_BATCH_SIZE = 1_000;

    private static final String INSERT = """
            INSERT INTO seats (show_id, label, price_paise)
            VALUES (:showId, :label, :pricePaise)
            """;

    private static final String FIND_BY_SHOW = """
            SELECT id, label, status, price_paise
            FROM seats
            WHERE show_id = :showId
            ORDER BY id
            """;

    private static final String RESOLVE_LABELS = """
            SELECT id, label
            FROM seats
            WHERE show_id = :showId AND label IN (:labels)
            ORDER BY id
            """;

    private static final String LOCK_BY_IDS = """
            SELECT id, label, status, price_paise
            FROM seats
            WHERE show_id = :showId AND id IN (:seatIds)
            ORDER BY id
            FOR UPDATE
            """;

    private static final String LOCK_BY_RESERVATION = """
            SELECT id
            FROM seats
            WHERE reservation_id = :reservationId
            ORDER BY id
            FOR UPDATE
            """;

    private static final String RELEASE = """
            UPDATE seats
            SET status = 'AVAILABLE', reservation_id = NULL
            WHERE reservation_id = :reservationId AND status = 'CONFIRMED'
            """;

    private static final String CONFIRM = """
            UPDATE seats
            SET status = 'CONFIRMED', reservation_id = :reservationId
            WHERE show_id = :showId AND id IN (:seatIds) AND status = 'AVAILABLE'
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public SeatJdbcRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts in layout order, so ascending id is layout order and the lock order. */
    public void insertAll(UUID showId, List<NewSeat> seats) {
        Tx.requireActive();
        int inserted = 0;
        for (int from = 0; from < seats.size(); from += INSERT_BATCH_SIZE) {
            List<NewSeat> batch = seats.subList(from, Math.min(from + INSERT_BATCH_SIZE, seats.size()));
            SqlParameterSource[] parameters = batch.stream()
                    .map(seat -> (SqlParameterSource) new MapSqlParameterSource()
                            .addValue("showId", showId)
                            .addValue("label", seat.label())
                            .addValue("pricePaise", seat.pricePaise()))
                    .toArray(SqlParameterSource[]::new);
            for (int rows : jdbc.batchUpdate(INSERT, parameters)) {
                inserted += rows;
            }
        }
        if (inserted != seats.size()) {
            throw new IllegalStateException(
                    "expected to insert " + seats.size() + " seats but inserted " + inserted);
        }
    }

    /** One statement, so derived counts and the seat list always agree. */
    public List<SeatRow> findByShow(UUID showId) {
        return jdbc.query(FIND_BY_SHOW, new MapSqlParameterSource("showId", showId),
                (rs, rowNum) -> new SeatRow(
                        rs.getLong("id"),
                        rs.getString("label"),
                        SeatStatus.valueOf(rs.getString("status")),
                        rs.getLong("price_paise")));
    }

    /** Labels and ids are immutable, so resolving them without a lock is safe. */
    public List<SeatRef> resolveLabels(UUID showId, List<String> labels) {
        return jdbc.query(RESOLVE_LABELS, new MapSqlParameterSource()
                        .addValue("showId", showId)
                        .addValue("labels", labels),
                (rs, rowNum) -> new SeatRef(rs.getLong("id"), rs.getString("label")));
    }

    /** Ascending id is the global lock order, which is what keeps these transactions deadlock-free. */
    public List<SeatRow> lockByIds(UUID showId, List<Long> seatIds) {
        Tx.requireActive();
        return jdbc.query(LOCK_BY_IDS, new MapSqlParameterSource()
                        .addValue("showId", showId)
                        .addValue("seatIds", seatIds),
                (rs, rowNum) -> new SeatRow(
                        rs.getLong("id"),
                        rs.getString("label"),
                        SeatStatus.valueOf(rs.getString("status")),
                        rs.getLong("price_paise")));
    }

    /** The AVAILABLE predicate re-checks under the lock; the caller asserts the row count. */
    public int confirm(UUID showId, List<Long> seatIds, UUID reservationId) {
        Tx.requireActive();
        return jdbc.update(CONFIRM, new MapSqlParameterSource()
                .addValue("showId", showId)
                .addValue("seatIds", seatIds)
                .addValue("reservationId", reservationId));
    }

    public List<Long> lockByReservation(UUID reservationId) {
        Tx.requireActive();
        return jdbc.queryForList(LOCK_BY_RESERVATION,
                new MapSqlParameterSource("reservationId", reservationId), Long.class);
    }

    /** Releases only seats this reservation still holds, so a re-reserved seat is never stolen. */
    public int release(UUID reservationId) {
        Tx.requireActive();
        return jdbc.update(RELEASE, new MapSqlParameterSource("reservationId", reservationId));
    }

    public record NewSeat(String label, long pricePaise) {}

    public record SeatRef(long id, String label) {}
}
