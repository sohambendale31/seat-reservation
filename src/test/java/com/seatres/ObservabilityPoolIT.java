package com.seatres;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatres.support.AbstractPostgresIT;
import com.seatres.support.Api;
import com.seatres.support.Metrics;
import com.seatres.support.Reserve;
import com.seatres.support.Shows;
import com.seatres.support.TestTokens;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** The gauge has its own pool, so it must answer even when the reservation pool is fully taken. */
class ObservabilityPoolIT extends AbstractPostgresIT {

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    private Api api;
    private Metrics metrics;

    @BeforeEach
    void setUp() {
        api = new Api(port);
        metrics = new Metrics(api);
    }

    @Test
    void theSeatsGaugeStillAnswersWhileTheMainPoolIsExhausted() throws Exception {
        Shows shows = new Shows(api, TestTokens.admin());
        UUID show = shows.create("obs-pool", "A", 6, 100, 4);
        assertThat(new Reserve(api).seats(show, TestTokens.user("pool-alice"), Reserve.newKey(),
                "A1", "A2").status()).isEqualTo(201);

        int poolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
        List<Connection> held = new ArrayList<>(poolSize);
        try {
            for (int i = 0; i < poolSize; i++) {
                held.add(dataSource.getConnection());
            }

            long startedAt = System.nanoTime();
            String scrape = metrics.scrape();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
            assertThat(metrics.seats(scrape, show.toString(), "confirmed")).isEqualTo(2);
            assertThat(metrics.seats(scrape, show.toString(), "available")).isEqualTo(4);
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }

        assertThat(metrics.seats(metrics.scrape(), show.toString(), "confirmed")).isEqualTo(2);
    }
}
