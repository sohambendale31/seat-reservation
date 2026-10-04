package com.seatres.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The custom meters. Every label set is enumerated here, so cardinality cannot grow with traffic.
 * Counters are created at startup, so the names exist at 0 before any request.
 */
@Component
public class ReservationMetrics {

    public enum DeclineReason {
        SEAT_UNAVAILABLE("seat_unavailable"),
        USER_LIMIT_EXCEEDED("user_limit_exceeded");

        private final String label;

        DeclineReason(String label) {
            this.label = label;
        }
    }

    public enum InvariantCheck {
        DB_CONSTRAINT("db_constraint"),
        ROWCOUNT_ASSERTION("rowcount_assertion");

        private final String label;

        InvariantCheck(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Only the statuses a stored reserve outcome can have. */
    private static final int[] REPLAYABLE_STATUSES = {201, 404, 409, 422};

    private final Counter confirmed;
    private final Map<DeclineReason, Counter> declines;
    private final Map<Integer, Counter> replays;
    private final Map<InvariantCheck, Counter> invariantViolations;

    public ReservationMetrics(MeterRegistry registry) {
        this.confirmed = Counter.builder("seatres.reservations.confirmed")
                .description("New logical reservations committed, never replays")
                .register(registry);

        this.declines = new java.util.EnumMap<>(DeclineReason.class);
        for (DeclineReason reason : DeclineReason.values()) {
            declines.put(reason, Counter.builder("seatres.reservation.declines")
                    .description("First-time domain declines decided by this request")
                    .tag("reason", reason.label)
                    .register(registry));
        }

        this.replays = new java.util.LinkedHashMap<>();
        for (int status : REPLAYABLE_STATUSES) {
            replays.put(status, Counter.builder("seatres.idempotency.replays")
                    .description("Reserve responses served from a stored outcome")
                    .tag("status", String.valueOf(status))
                    .register(registry));
        }

        this.invariantViolations = new java.util.EnumMap<>(InvariantCheck.class);
        for (InvariantCheck check : InvariantCheck.values()) {
            invariantViolations.put(check, Counter.builder("seatres.invariant.violations")
                    .description("A safety net fired, which means the logic is wrong")
                    .tag("check", check.label)
                    .register(registry));
        }
    }

    public void reservationConfirmed() {
        confirmed.increment();
    }

    public void reservationDeclined(DeclineReason reason) {
        declines.get(reason).increment();
    }

    /** Unknown statuses are ignored rather than creating a new series. */
    public void idempotentReplay(int originalStatus) {
        Counter counter = replays.get(originalStatus);
        if (counter != null) {
            counter.increment();
        }
    }

    public void invariantViolation(InvariantCheck check) {
        invariantViolations.get(check).increment();
    }
}
