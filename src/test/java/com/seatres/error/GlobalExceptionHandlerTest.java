package com.seatres.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void apiExceptionsKeepTheirCodeDetailAndExtensions() {
        ApiException exception = new ApiException(ErrorCode.USER_LIMIT_EXCEEDED, "no room",
                Map.of("perUserLimit", 4, "seatsHeld", 4, "seatsRequested", 1));

        ResponseEntity<Object> response = handler.handleApiException(exception, request("/reserve"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(problem(response).getDetail()).isEqualTo("no room");
        assertThat(problem(response).getProperties())
                .containsEntry("code", "USER_LIMIT_EXCEEDED")
                .containsEntry("perUserLimit", 4);
    }

    @Test
    void retryableProblemsCarryRetryAfter() {
        ResponseEntity<Object> response = handler.handleApiException(
                new ApiException(ErrorCode.SERVICE_UNAVAILABLE), request("/reserve"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(problem(response).getProperties()).containsEntry("retryable", true);
    }

    @Test
    void unauthenticatedProblemsCarryWwwAuthenticate() {
        ResponseEntity<Object> response = handler.handleApiException(
                new ApiException(ErrorCode.UNAUTHENTICATED), request("/shows"));

        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
    }

    @Test
    void notFoundSubtypesCarryTheirIdentifier() {
        UUID showId = UUID.randomUUID();

        ResponseEntity<Object> response = handler.handleApiException(
                new ShowNotFoundException(showId), request("/shows/" + showId));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(problem(response).getProperties())
                .containsEntry("code", "SHOW_NOT_FOUND")
                .containsEntry("showId", showId.toString());
    }

    /** A path variable that will not convert, e.g. a malformed show UUID. */
    @Test
    void aTypeMismatchBecomesAValidationFailure() {
        MethodArgumentTypeMismatchException exception = new MethodArgumentTypeMismatchException(
                "not-a-uuid", UUID.class, "showId", null, new IllegalArgumentException());

        ResponseEntity<Object> response = handler.handleTypeMismatch(exception,
                request("/shows/not-a-uuid"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(problem(response).getProperties()).containsEntry("code", "VALIDATION_FAILED");

        Object errors = problem(response).getProperties().get("errors");
        assertThat(errors).isInstanceOf(List.class);
        assertThat(((List<?>) errors).getFirst().toString()).contains("showId");
    }

    @Test
    void unexpectedExceptionsBecomeAnInternalErrorWithoutDetail() {
        ResponseEntity<Object> response = handler.handleUnexpected(
                new IllegalStateException("seats table is on fire"), request("/shows"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(problem(response).getDetail()).doesNotContain("on fire");
        assertThat(problem(response).getProperties()).containsEntry("code", "INTERNAL_ERROR");
    }

    private static ServletWebRequest request(String uri) {
        return new ServletWebRequest(new MockHttpServletRequest("POST", uri));
    }

    private static ProblemDetail problem(ResponseEntity<Object> response) {
        return (ProblemDetail) response.getBody();
    }
}
