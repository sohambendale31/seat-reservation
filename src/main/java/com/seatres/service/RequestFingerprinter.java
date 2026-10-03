package com.seatres.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Binds an idempotency key to one canonical request. Computed from validated input, never from raw
 * bytes, so formatting and seat order cannot change it. The v1 prefix lets the form evolve without
 * matching older fingerprints.
 */
@Component
public class RequestFingerprinter {

    public String reserve(UUID showId, List<String> sortedLabels) {
        String canonical = "v1|RESERVE|show=" + showId.toString().toLowerCase()
                + "|seats=" + String.join(",", sortedLabels);
        return sha256Hex(canonical);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }
}
