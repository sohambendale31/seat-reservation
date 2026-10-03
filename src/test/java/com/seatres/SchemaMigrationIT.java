package com.seatres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatres.support.AbstractPostgresIT;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SchemaMigrationIT extends AbstractPostgresIT {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayAppliedTheBaselineMigrationExactlyOnce() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
                String.class);
        assertThat(versions).containsExactly("1");

        Integer failed = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false", Integer.class);
        assertThat(failed).isZero();
    }

    @Test
    void baselineSchemaCreatedAllTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history' "
                        + "ORDER BY table_name",
                String.class);
        assertThat(tables).containsExactly(
                "idempotency_records", "reservation_seats", "reservations", "seats", "shows",
                "user_show_quotas");
    }

    /** The context started with ddl-auto=validate, so Hibernate already agreed with the DDL. */
    @Test
    void hibernateValidationPassedAndShowsTableIsUsable() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM shows", Integer.class);
        assertThat(count).isNotNull();
    }

    @Test
    void seatStatusAndReservationMustAgree() {
        UUID showId = insertShow(10, 1);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO seats (show_id, label, price_paise, status, reservation_id) "
                        + "VALUES (?, 'A1', 100, 'CONFIRMED', NULL)", showId))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
    }

    @Test
    void quotaAboveSeatLimitIsRejected() {
        UUID showId = insertShow(10, 4);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO user_show_quotas (show_id, user_id, seats_held, seat_limit) "
                        + "VALUES (?, 'alice', 5, 4)", showId))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
    }

    @Test
    void secondActiveLinkForOneSeatIsRejected() {
        UUID showId = insertShow(1, 4);
        jdbc.update("INSERT INTO seats (show_id, label, price_paise) VALUES (?, 'A1', 100)", showId);
        Long seatId = jdbc.queryForObject(
                "SELECT id FROM seats WHERE show_id = ? AND label = 'A1'", Long.class, showId);

        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        insertReservation(first, showId, 1);
        insertReservation(second, showId, 1);

        jdbc.update("INSERT INTO reservation_seats (reservation_id, seat_id, show_id, price_paise) "
                + "VALUES (?, ?, ?, 100)", first, seatId, showId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO reservation_seats (reservation_id, seat_id, show_id, price_paise) "
                        + "VALUES (?, ?, ?, 100)", second, seatId, showId))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"));

        // The index is partial, so releasing the first link frees the seat for a new active link.
        jdbc.update("UPDATE reservation_seats SET released_at = now() WHERE reservation_id = ?", first);
        jdbc.update("INSERT INTO reservation_seats (reservation_id, seat_id, show_id, price_paise) "
                + "VALUES (?, ?, ?, 100)", second, seatId, showId);
    }

    @Test
    void seatCannotPointAtAnotherShowsReservation() {
        UUID showA = insertShow(1, 4);
        UUID showB = insertShow(1, 4);
        jdbc.update("INSERT INTO seats (show_id, label, price_paise) VALUES (?, 'A1', 100)", showA);
        UUID reservationInB = UUID.randomUUID();
        insertReservation(reservationInB, showB, 1);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE seats SET status = 'CONFIRMED', reservation_id = ? "
                        + "WHERE show_id = ? AND label = 'A1'", reservationInB, showA))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23503"));
    }

    private UUID insertShow(int totalSeats, int perUserLimit) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shows (id, name, starts_at, per_user_limit, total_seats, created_at) "
                + "VALUES (?, 'mig-test', now(), ?, ?, now())", id, perUserLimit, totalSeats);
        return id;
    }

    private void insertReservation(UUID id, UUID showId, int seatCount) {
        jdbc.update("INSERT INTO reservations (id, show_id, user_id, status, seat_count, total_paise) "
                + "VALUES (?, ?, 'alice', 'CONFIRMED', ?, 100)", id, showId, seatCount);
    }

    private static String sqlState(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
