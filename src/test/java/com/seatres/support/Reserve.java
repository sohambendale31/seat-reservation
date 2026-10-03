package com.seatres.support;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Issues reserve calls with an explicit idempotency key. */
public final class Reserve {

    public static final String KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final Api api;

    public Reserve(Api api) {
        this.api = api;
    }

    public Api.Response seats(UUID showId, String token, String key, List<String> labels) {
        return api.post("/shows/" + showId + "/reserve", token, Shows.seatsBody(labels),
                Map.of(KEY_HEADER, key));
    }

    public Api.Response seats(UUID showId, String token, String key, String... labels) {
        return seats(showId, token, key, List.of(labels));
    }

    public Api.Response withoutKey(UUID showId, String token, String... labels) {
        return api.post("/shows/" + showId + "/reserve", token, Shows.seatsBody(labels));
    }

    public Api.Response rawBody(UUID showId, String token, String key, String body) {
        return api.post("/shows/" + showId + "/reserve", token, body, Map.of(KEY_HEADER, key));
    }

    public static String newKey() {
        return UUID.randomUUID().toString();
    }

    public static boolean wasReplayed(Api.Response response) {
        return response.header(REPLAYED_HEADER).map("true"::equals).orElse(false);
    }
}
