package com.seatres.error;

import java.util.Map;
import java.util.UUID;

public class ShowNotFoundException extends ApiException {

    public ShowNotFoundException(UUID showId) {
        super(ErrorCode.SHOW_NOT_FOUND, ErrorCode.SHOW_NOT_FOUND.detail(),
                Map.of("showId", showId.toString()));
    }
}
