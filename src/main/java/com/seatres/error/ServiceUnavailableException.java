package com.seatres.error;

public class ServiceUnavailableException extends ApiException {

    public ServiceUnavailableException(Throwable cause) {
        super(ErrorCode.SERVICE_UNAVAILABLE);
        initCause(cause);
    }
}
