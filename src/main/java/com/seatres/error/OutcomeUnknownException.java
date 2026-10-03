package com.seatres.error;

public class OutcomeUnknownException extends ApiException {

    public OutcomeUnknownException(Throwable cause) {
        super(ErrorCode.OUTCOME_UNKNOWN);
        initCause(cause);
    }
}
