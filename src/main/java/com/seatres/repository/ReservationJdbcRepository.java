package com.seatres.repository;

import com.seatres.domain.ReservationStatus;
import com.seatres.domain.SeatRow;
import com.seatres.service.tx.Tx;
import com.seatres.web.dto.ReservedSeat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
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

    private static final String FIND_OWNER = """
            SELECT show_id, user_id
            FROM reservations
            WHERE id = :id
            """;

    private static final String LOCK = """
            SELECT status, seat_count, total_paise, created_at, cancelled_at
            FROM reservations
            WHERE id = :id
            FOR UPDATE
            """;

    private static final String RELEASE_LINKS = """
            UPDATE reservation_seats
            SET released_at = now()
            WHERE reservation_id = :id AND released_at IS NULL
            """;

    private static final String MARK_CANCELLED = """
            UPDATE reservations
            SET status = 'CANCELLED', cancelled_at = now()
            WHERE id = :id AND status = 'CONFIRMED'
            RETURNING cancelled_at
            """;

    private static final String FIND_RESERVED_SEATS = """
            SELECT s.label, rs.price_paise
            FROM reservation_seats rs
            JOIN seats s ON s.id = rs.seat_id
            WHERE rs.reservation_id = :id
            ORDER BY rs.seat_id
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
                ? instantOf(rs.getObject("created_at", OffsetDateTime.class))
                : null);
        if (createdAt == null) {
            throw new IllegalStateException("reservation insert returned no row");
        }
        return createdAt;
    }

    /** No lock: show_id and user_id never change, so this read cannot go stale. */
    public Optional<Owner> findOwner(UUID reservationId) {
        return jdbc.query(FIND_OWNER, new MapSqlParameterSource("id", reservationId),
                rs -> rs.next()
                        ? Optional.of(new Owner(rs.getObject("show_id", UUID.class),
                                rs.getString("user_id")))
                        : Optional.empty());
    }

    public Optional<State> lockForUpdate(UUID reservationId) {
        Tx.requireActive();
        return jdbc.query(LOCK, new MapSqlParameterSource("id", reservationId), rs -> rs.next()
                ? Optional.of(new State(
                        ReservationStatus.valueOf(rs.getString("status")),
                        rs.getInt("seat_count"),
                        rs.getLong("total_paise"),
                        instantOf(rs.getObject("created_at", OffsetDateTime.class)),
                        instantOf(rs.getObject("cancelled_at", OffsetDateTime.class))))
                : Optional.empty());
    }

    public int releaseLinks(UUID reservationId) {
        Tx.requireActive();
        return jdbc.update(RELEASE_LINKS, new MapSqlParameterSource("id", reservationId));
    }

    /** Guarded by status = CONFIRMED, so a concurrent cancel cannot cancel twice. */
    public Instant markCancelled(UUID reservationId) {
        Tx.requireActive();
        Instant cancelledAt = jdbc.query(MARK_CANCELLED,
                new MapSqlParameterSource("id", reservationId),
                rs -> rs.next() ? instantOf(rs.getObject("cancelled_at", OffsetDateTime.class)) : null);
        if (cancelledAt == null) {
            throw new IllegalStateException("expected to cancel 1 confirmed reservation but updated 0");
        }
        return cancelledAt;
    }

    /** Links survive cancellation, so they carry the labels and prices for the response. */
    public List<ReservedSeat> findReservedSeats(UUID reservationId) {
        return jdbc.query(FIND_RESERVED_SEATS, new MapSqlParameterSource("id", reservationId),
                (rs, rowNum) -> new ReservedSeat(rs.getString("label"), rs.getLong("price_paise")));
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

    private static Instant instantOf(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public record Owner(UUID showId, String userId) {}

    public record State(ReservationStatus status, int seatCount, long totalPaise, Instant createdAt,
            Instant cancelledAt) {}
}
