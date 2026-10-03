package com.seatres.error;

public class IdempotencyKeyReusedException extends ApiException {

    public IdempotencyKeyReusedException() {
        super(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }
}
