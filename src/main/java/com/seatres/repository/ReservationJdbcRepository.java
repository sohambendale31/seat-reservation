package com.seatres.repository;

import com.seatres.domain.ReservationStatus;
import com.seatres.domain.SeatRow;
import com.seatres.service.tx.Tx;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationJdbcRepository {

    private static final String INSERT = """
            INSERT INTO reservations
                (id, show_id, user_id, status, seat_count, total_paise, created_at)
            VALUES (:id, :showId, :userId, :status, :seatCount, :totalPaise, now())
            RETURNING created_at
            """;

    private static final String INSERT_LINK = """
            INSERT INTO reservation_seats (reservation_id, seat_id, show_id, price_paise)
            VALUES (:reservationId, :seatId, :showId, :pricePaise)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ReservationJdbcRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns the stored timestamp, so the response body matches the row exactly. */
    public Instant insertConfirmed(UUID id, UUID showId, String userId, int seatCount,
            long totalPaise) {
        Tx.requireActive();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("showId", showId)
                .addValue("userId", userId)
                .addValue("status", ReservationStatus.CONFIRMED.name())
                .addValue("seatCount", seatCount)
                .addValue("totalPaise", totalPaise);
        Instant createdAt = jdbc.query(INSERT, parameters, rs -> rs.next()
                ? rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()
                : null);
        if (createdAt == null) {
            throw new IllegalStateException("reservation insert returned no row");
        }
        return createdAt;
    }

    public void insertLinks(UUID reservationId, UUID showId, List<SeatRow> seats) {
        Tx.requireActive();
        SqlParameterSource[] parameters = seats.stream()
                .map(seat -> (SqlParameterSource) new MapSqlParameterSource()
                        .addValue("reservationId", reservationId)
                        .addValue("seatId", seat.id())
                        .addValue("showId", showId)
                        .addValue("pricePaise", seat.pricePaise()))
                .toArray(SqlParameterSource[]::new);
        int inserted = 0;
        for (int rows : jdbc.batchUpdate(INSERT_LINK, parameters)) {
            inserted += rows;
        }
        if (inserted != seats.size()) {
            throw new IllegalStateException(
                    "expected to link " + seats.size() + " seats but linked " + inserted);
        }
    }
}
