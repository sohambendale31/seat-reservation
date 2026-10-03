package com.seatres.error;

import java.util.Map;
import java.util.UUID;

public class ReservationNotFoundException extends ApiException {

    public ReservationNotFoundException(UUID reservationId) {
        super(ErrorCode.RESERVATION_NOT_FOUND, ErrorCode.RESERVATION_NOT_FOUND.detail(),
                Map.of("reservationId", reservationId.toString()));
    }
}
