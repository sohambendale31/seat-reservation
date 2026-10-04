package com.seatres.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads values straight out of the Prometheus exposition text, as a scraper would. */
public final class Metrics {

    private final Api api;

    public Metrics(Api api) {
        this.api = api;
    }

    public String scrape() {
        Api.Response response = api.get("/actuator/prometheus", null);
        assertThat(response.status()).isEqualTo(200);
        return response.body();
    }

    public double value(String scrape, String series) {
        Matcher matcher = Pattern.compile("^" + Pattern.quote(series) + "\\s+([0-9.eE+-]+)$",
                Pattern.MULTILINE).matcher(scrape);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : 0;
    }

    public double seats(String scrape, String showId, String status) {
        return value(scrape, "seatres_seats{show_id=\"%s\",status=\"%s\"}".formatted(showId, status));
    }
}
