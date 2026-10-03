package com.seatres.error;

import java.net.URI;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;

/** Builds RFC 9457 bodies. Every problem carries {@code code}, {@code requestId} and {@code retryable}. */
public final class Problems {

    public static final String REQUEST_ID_MDC_KEY = "requestId";

    private Problems() {
    }

    public static ProblemDetail of(ErrorCode code, String instance) {
        return of(code, code.detail(), instance, Map.of());
    }

    public static ProblemDetail of(ErrorCode code, String detail, String instance,
            Map<String, Object> extensions) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(code.type()));
        problem.setTitle(code.title());
        if (instance != null) {
            problem.setInstance(URI.create(instance));
        }
        problem.setProperty("code", code.name());
        problem.setProperty("requestId", MDC.get(REQUEST_ID_MDC_KEY));
        problem.setProperty("retryable", code.retryable());
        extensions.forEach(problem::setProperty);
        return problem;
    }
}
