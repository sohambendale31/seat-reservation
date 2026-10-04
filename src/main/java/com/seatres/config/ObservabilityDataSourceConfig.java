package com.seatres.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A second, tiny pool for the seats gauge. During a burst the main pool has a long queue, and a
 * gauge sharing it would stall exactly when someone is watching.
 *
 * <p>Exposed as {@link ObservabilityJdbc} rather than a {@code DataSource}, so it can never be
 * mistaken for the primary one by JPA, Flyway or the auto-configuration.
 */
@Configuration
public class ObservabilityDataSourceConfig {

    @Bean(destroyMethod = "close")
    ObservabilityJdbc observabilityJdbc(JdbcConnectionDetails connection, AppProperties properties) {
        AppProperties.Pool pool = properties.observability().pool();
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("seatres-observability");
        dataSource.setJdbcUrl(connection.getJdbcUrl());
        dataSource.setUsername(connection.getUsername());
        dataSource.setPassword(connection.getPassword());
        dataSource.setMaximumPoolSize(pool.maximumPoolSize());
        dataSource.setConnectionTimeout(pool.connectionTimeout().toMillis());
        dataSource.setConnectionInitSql(pool.connectionInitSql());
        // Bounds a read against a frozen server, which no pool timeout covers.
        dataSource.addDataSourceProperty("socketTimeout",
                (int) pool.socketTimeout().toSeconds());
        return new ObservabilityJdbc(dataSource);
    }

    public static final class ObservabilityJdbc implements AutoCloseable {

        private final HikariDataSource dataSource;
        private final JdbcTemplate jdbc;

        ObservabilityJdbc(HikariDataSource dataSource) {
            this.dataSource = dataSource;
            this.jdbc = new JdbcTemplate(dataSource);
        }

        public JdbcTemplate jdbc() {
            return jdbc;
        }

        @Override
        public void close() {
            dataSource.close();
        }
    }
}
