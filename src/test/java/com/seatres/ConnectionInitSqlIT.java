package com.seatres;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatres.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves PgJDBC executes the multi-statement connection-init-sql. If this fails, move the timeouts
 * into the JDBC URL's options parameter. Values are the test profile's.
 */
class ConnectionInitSqlIT extends AbstractPostgresIT {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void allThreeSessionTimeoutsAreAppliedToPooledConnections() {
        assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("2s");
        assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("35s");
        assertThat(jdbc.queryForObject("SHOW idle_in_transaction_session_timeout", String.class))
                .isEqualTo("30s");
    }
}
