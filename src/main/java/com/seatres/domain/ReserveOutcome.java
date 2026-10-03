package com.seatres.domain;

/**
 * The decided outcome, carrying the exact response bytes. Declines are values, not exceptions, so
 * they commit the stored outcome instead of rolling it back.
 */
public sealed interface ReserveOutcome {

    int status();

    String body();

    record Confirmed(String body) implements ReserveOutcome {
        @Override
        public int status() {
            return 201;
        }
    }

    record Declined(int status, String body) implements ReserveOutcome {}

    record Replayed(int status, String body) implements ReserveOutcome {}
}
