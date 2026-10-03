package com.seatres;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatres.support.AbstractPostgresIT;
import com.seatres.support.Api;
import com.seatres.support.Reconciliation;
import com.seatres.support.Reserve;
import com.seatres.support.Shows;
import com.seatres.support.TestTokens;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

class FailureHandlingIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private Api api;
    private Shows shows;
    private Reserve reserve;
    private Reconciliation reconciliation;
    private String alice;

    @BeforeEach
    void setUp() {
        api = new Api(port);
        shows = new Shows(api, TestTokens.admin());
        reserve = new Reserve(api);
        reconciliation = new Reconciliation(jdbc);
        alice = TestTokens.user("fail-alice");
    }

    @Test
    void aLockTimeoutRollsEverythingBackAndTheSameKeySucceedsAfterwards() throws Exception {
        UUID show = shows.create("fail-01", "A", 10, 100, 4);
        String key = Reserve.newKey();

        Api.Response blocked;
        try (Connection holder = independentConnection()) {
            holder.setAutoCommit(false);
            lockSeat(holder, show, "A1");

            blocked = reserve.seats(show, alice, key, "A1");

            assertThat(blocked.status()).isEqualTo(503);
            assertThat(blocked.code()).isEqualTo("SERVICE_UNAVAILABLE");
            assertThat(blocked.json().path("retryable").asBoolean()).isTrue();
            assertThat(blocked.header("Retry-After")).contains("1");
            assertThat(blocked.body()).doesNotContain("55P03").doesNotContain("lock_timeout");

            assertThat(recordsForKey("fail-alice", key)).isZero();
            assertThat(quotaRows(show, "fail-alice")).isZero();
            assertThat(seatStatus(show, "A1")).isEqualTo("AVAILABLE");

            holder.rollback();
        }

        Api.Response retried = reserve.seats(show, alice, key, "A1");

        assertThat(retried.status()).isEqualTo(201);
        assertThat(Reserve.wasReplayed(retried)).isFalse();
        assertThat(seatStatus(show, "A1")).isEqualTo("CONFIRMED");
        reconciliation.assertHoldsForShow(show);
    }

    @Test
    void errorBodiesCarryNoDatabaseDetail() throws Exception {
        UUID show = shows.create("fail-03", "A", 4, 100, 4);

        try (Connection holder = independentConnection()) {
            holder.setAutoCommit(false);
            lockSeat(holder, show, "A1");

            Api.Response blocked = reserve.seats(show, alice, Reserve.newKey(), "A1");

            assertThat(blocked.status()).isEqualTo(503);
            assertThat(blocked.body())
                    .doesNotContain("org.postgresql")
                    .doesNotContain("org.springframework")
                    .doesNotContain("SELECT")
                    .doesNotContain("FOR UPDATE")
                    .doesNotContain("Exception");
            holder.rollback();
        }
    }

    /** Outside the pool, so holding a lock here cannot starve the application of connections. */
    private static Connection independentConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    private static void lockSeat(Connection connection, UUID showId, String label)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM seats WHERE show_id = ? AND label = ? FOR UPDATE")) {
            statement.setObject(1, showId);
            statement.setString(2, label);
            assertThat(statement.executeQuery().next()).isTrue();
        }
    }

    private String seatStatus(UUID showId, String label) {
        return jdbc.queryForObject("SELECT status FROM seats WHERE show_id = ? AND label = ?",
                String.class, showId, label);
    }

    private int recordsForKey(String userId, String key) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM idempotency_records "
                + "WHERE user_id = ? AND idem_key = ?", Integer.class, userId, key);
        return count == null ? 0 : count;
    }

    private int quotaRows(UUID showId, String userId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM user_show_quotas "
                + "WHERE show_id = ? AND user_id = ?", Integer.class, showId, userId);
        return count == null ? 0 : count;
    }
}
