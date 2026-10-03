package com.seatres.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatres.error.ErrorCode;
import com.seatres.error.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/** Writes problem+json straight to the response for failures raised inside the filter chain. */
@Component
class ProblemResponseWriter {

    private final ObjectMapper objectMapper;

    ProblemResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code)
            throws IOException {
        ProblemDetail problem = Problems.of(code, request.getRequestURI());
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
