package com.seatres.error;

import java.util.Map;

/** Carries an {@link ErrorCode} and any code-specific problem extensions. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> extensions;

    public ApiException(ErrorCode code) {
        this(code, code.detail(), Map.of());
    }

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, Map.of());
    }

    public ApiException(ErrorCode code, String detail, Map<String, Object> extensions) {
        super(detail);
        this.code = code;
        this.extensions = Map.copyOf(extensions);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> extensions() {
        return extensions;
    }

    public String detail() {
        return getMessage();
    }
}
