package com.seatres.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Creates shows over HTTP so tests exercise the real create path. */
public final class Shows {

    private final Api api;
    private final String adminToken;

    public Shows(Api api, String adminToken) {
        this.api = api;
        this.adminToken = adminToken;
    }

    public UUID create(String name, String row, int seatCount, long pricePaise, int perUserLimit) {
        return create(name, perUserLimit, List.of(rowSpec(row, seatCount, pricePaise)));
    }

    private UUID create(String name, int perUserLimit, List<String> rowSpecs) {
        Api.Response created = api.post("/shows", adminToken, """
                {"name":"%s","startsAt":"2026-12-01T19:30:00+05:30","perUserLimit":%d,"rows":[%s]}
                """.formatted(name, perUserLimit, String.join(",", rowSpecs)));
        assertThat(created.status()).as("create show %s: %s", name, created.body()).isEqualTo(201);
        return UUID.fromString(created.json().path("id").asText());
    }

    private static String rowSpec(String row, int seatCount, long pricePaise) {
        return "{\"row\":\"%s\",\"seatCount\":%d,\"pricePaise\":%d}"
                .formatted(row, seatCount, pricePaise);
    }

    /** Labels for a single-row show, in layout order. */
    public static List<String> labels(String row, int seatCount) {
        return IntStream.rangeClosed(1, seatCount).mapToObj(n -> row + n).toList();
    }

    public static String seatsBody(String... labels) {
        return seatsBody(List.of(labels));
    }

    public static String seatsBody(List<String> labels) {
        return "{\"seats\":[%s]}".formatted(
                labels.stream().map(l -> "\"" + l + "\"").collect(Collectors.joining(",")));
    }
}
