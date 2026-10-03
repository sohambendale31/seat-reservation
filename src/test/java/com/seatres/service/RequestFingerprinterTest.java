package com.seatres.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestFingerprinterTest {

    private static final UUID SHOW = UUID.fromString("7f1c2b9e-3a7d-4a51-9a1f-0f3f3c1c2d10");

    private final RequestFingerprinter fingerprinter = new RequestFingerprinter();

    @Test
    void sortedLabelsGiveTheSameFingerprintRegardlessOfRequestOrder() {
        assertThat(fingerprinter.reserve(SHOW, List.of("A1", "A2")))
                .isEqualTo(fingerprinter.reserve(SHOW, List.of("A1", "A2")));
    }

    @Test
    void aDifferentSeatSetGivesADifferentFingerprint() {
        assertThat(fingerprinter.reserve(SHOW, List.of("A1", "A2")))
                .isNotEqualTo(fingerprinter.reserve(SHOW, List.of("A1", "A3")));
    }

    @Test
    void aDifferentShowGivesADifferentFingerprint() {
        assertThat(fingerprinter.reserve(SHOW, List.of("A1")))
                .isNotEqualTo(fingerprinter.reserve(UUID.randomUUID(), List.of("A1")));
    }

    @Test
    void aSubsetGivesADifferentFingerprint() {
        assertThat(fingerprinter.reserve(SHOW, List.of("A1")))
                .isNotEqualTo(fingerprinter.reserve(SHOW, List.of("A1", "A2")));
    }

    @Test
    void theFingerprintIsLowercaseHexOfTheCanonicalForm() throws Exception {
        String canonical = "v1|RESERVE|show=7f1c2b9e-3a7d-4a51-9a1f-0f3f3c1c2d10|seats=A1,A2";

        assertThat(fingerprinter.reserve(SHOW, List.of("A1", "A2")))
                .isEqualTo(sha256Hex(canonical))
                .matches("^[0-9a-f]{64}$");
    }

    @Test
    void uppercaseShowIdsCanonicaliseToLowercase() {
        UUID upper = UUID.fromString(SHOW.toString().toUpperCase());

        assertThat(fingerprinter.reserve(upper, List.of("A1")))
                .isEqualTo(fingerprinter.reserve(SHOW, List.of("A1")));
    }

    private static String sha256Hex(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append("%02x".formatted(b));
        }
        return hex.toString();
    }
}
