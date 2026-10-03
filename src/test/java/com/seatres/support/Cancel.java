package com.seatres.support;

import java.util.UUID;

/** Cancel takes no body and no content type, so the helper sends neither by default. */
public final class Cancel {

    private final Api api;

    public Cancel(Api api) {
        this.api = api;
    }

    public Api.Response of(UUID reservationId, String token) {
        return api.postWithoutContentType(path(reservationId), token, null);
    }

    public Api.Response withBody(UUID reservationId, String token, String body,
            String contentType) {
        return api.postWithContentType(path(reservationId), token, body, contentType);
    }

    public Api.Response ofRaw(String reservationId, String token) {
        return api.postWithoutContentType("/reservations/" + reservationId + "/cancel", token, null);
    }

    private static String path(UUID reservationId) {
        return "/reservations/" + reservationId + "/cancel";
    }
}
