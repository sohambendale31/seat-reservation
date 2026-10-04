package com.seatres.observability;

import com.seatres.config.ObservabilityDataSourceConfig.ObservabilityJdbc;
import com.seatres.domain.SeatStatus;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Seat counts per show, read from PostgreSQL at scrape time so they always agree with
 * {@code GET /shows/{id}}. Registered as a Prometheus collector, which is what makes the query run
 * on the scrape rather than on a timer.
 *
 * <p>Bounded to the newest shows, so the series count cannot grow with traffic. If the query fails
 * the last good snapshot is served, so a scrape never errors.
 */
@Component
public class SeatGaugeCollector implements MultiCollector {

    static final String METRIC_NAME = "seatres_seats";
    static final int TRACKED_SHOWS = 20;

    private static final String QUERY = """
            SELECT st.show_id, st.status, count(*) AS seat_count
            FROM seats st
            JOIN (SELECT id FROM shows ORDER BY created_at DESC LIMIT %d) recent
              ON recent.id = st.show_id
            GROUP BY st.show_id, st.status
            """.formatted(TRACKED_SHOWS);

    private static final Logger log = LoggerFactory.getLogger(SeatGaugeCollector.class);

    private final ObservabilityJdbc observability;
    private final PrometheusMeterRegistry registry;

    private volatile MetricSnapshots lastGood = new MetricSnapshots();

    public SeatGaugeCollector(ObservabilityJdbc observability, PrometheusMeterRegistry registry) {
        this.observability = observability;
        this.registry = registry;
    }

    @PostConstruct
    void register() {
        registry.getPrometheusRegistry().register(this);
    }

    @Override
    public List<String> getPrometheusNames() {
        return List.of(METRIC_NAME);
    }

    @Override
    public MetricSnapshots collect() {
        try {
            MetricSnapshots snapshots = new MetricSnapshots(build(countsByShow()));
            lastGood = snapshots;
            return snapshots;
        } catch (DataAccessException e) {
            log.warn("metrics.seat_query_failed sqlstate={}", sqlStateOf(e));
            return lastGood;
        }
    }

    private Map<String, Map<SeatStatus, Long>> countsByShow() {
        Map<String, Map<SeatStatus, Long>> counts = new LinkedHashMap<>();
        observability.jdbc().query(QUERY, rs -> {
            counts.computeIfAbsent(rs.getString("show_id"), key -> new LinkedHashMap<>())
                    .put(SeatStatus.valueOf(rs.getString("status")), rs.getLong("seat_count"));
        });
        return counts;
    }

    /** Every tracked show gets all three series, so a status with no seats reads 0 rather than absent. */
    private static GaugeSnapshot build(Map<String, Map<SeatStatus, Long>> counts) {
        GaugeSnapshot.Builder gauge = GaugeSnapshot.builder()
                .name(METRIC_NAME)
                .help("Seats per status for the most recently created shows");
        counts.forEach((showId, byStatus) -> {
            for (SeatStatus status : SeatStatus.values()) {
                gauge.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder()
                        .labels(Labels.of("show_id", showId,
                                "status", status.name().toLowerCase(Locale.ROOT)))
                        .value(byStatus.getOrDefault(status, 0L))
                        .build());
            }
        });
        return gauge.build();
    }

    private static String sqlStateOf(DataAccessException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
