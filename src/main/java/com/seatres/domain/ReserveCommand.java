package com.seatres.domain;

import java.util.List;
import java.util.UUID;

/** Validated reserve input. Labels are sorted, and the fingerprint is derived from them. */
public record ReserveCommand(
        UUID showId,
        String userId,
        List<String> labels,
        String idemKey,
        String fingerprint) {

    public int seatCount() {
        return labels.size();
    }
}
