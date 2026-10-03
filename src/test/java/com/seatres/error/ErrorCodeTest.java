package com.seatres.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ErrorCodeTest {

    private static final Set<ErrorCode> RETRYABLE =
            EnumSet.of(ErrorCode.SERVICE_UNAVAILABLE, ErrorCode.OUTCOME_UNKNOWN);

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyCodeHasAStatusTitleDetailAndTypeUrn(ErrorCode code) {
        assertThat(code.status()).isNotNull();
        assertThat(code.title()).isNotBlank();
        assertThat(code.detail()).isNotBlank();
        assertThat(code.type()).startsWith("urn:seatres:problem:");
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void typeUrnIsTheKebabCaseCode(ErrorCode code) {
        assertThat(code.type())
                .isEqualTo("urn:seatres:problem:" + code.name().toLowerCase().replace('_', '-'));
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void onlyInfrastructureFailuresAreRetryable(ErrorCode code) {
        assertThat(code.retryable()).isEqualTo(RETRYABLE.contains(code));
    }

    @Test
    void statusesMatchTheContract() {
        assertThat(ErrorCode.VALIDATION_FAILED.status().value()).isEqualTo(400);
        assertThat(ErrorCode.MALFORMED_REQUEST.status().value()).isEqualTo(400);
        assertThat(ErrorCode.IDEMPOTENCY_KEY_MISSING.status().value()).isEqualTo(400);
        assertThat(ErrorCode.IDEMPOTENCY_KEY_INVALID.status().value()).isEqualTo(400);
        assertThat(ErrorCode.UNAUTHENTICATED.status().value()).isEqualTo(401);
        assertThat(ErrorCode.FORBIDDEN.status().value()).isEqualTo(403);
        assertThat(ErrorCode.NOT_FOUND.status().value()).isEqualTo(404);
        assertThat(ErrorCode.SHOW_NOT_FOUND.status().value()).isEqualTo(404);
        assertThat(ErrorCode.RESERVATION_NOT_FOUND.status().value()).isEqualTo(404);
        assertThat(ErrorCode.METHOD_NOT_ALLOWED.status().value()).isEqualTo(405);
        assertThat(ErrorCode.IDEMPOTENCY_KEY_REUSED.status().value()).isEqualTo(409);
        assertThat(ErrorCode.SEAT_UNAVAILABLE.status().value()).isEqualTo(409);
        assertThat(ErrorCode.USER_LIMIT_EXCEEDED.status().value()).isEqualTo(409);
        assertThat(ErrorCode.UNSUPPORTED_MEDIA_TYPE.status().value()).isEqualTo(415);
        assertThat(ErrorCode.UNKNOWN_SEAT.status().value()).isEqualTo(422);
        assertThat(ErrorCode.INTERNAL_ERROR.status().value()).isEqualTo(500);
        assertThat(ErrorCode.SERVICE_UNAVAILABLE.status().value()).isEqualTo(503);
        assertThat(ErrorCode.OUTCOME_UNKNOWN.status().value()).isEqualTo(503);
    }

    @Test
    void problemBodyCarriesCodeRequestIdAndRetryable() {
        var problem = Problems.of(ErrorCode.SEAT_UNAVAILABLE, "/shows/1/reserve");

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getTitle()).isEqualTo("Seat unavailable");
        assertThat(problem.getType()).hasToString("urn:seatres:problem:seat-unavailable");
        assertThat(problem.getInstance()).hasToString("/shows/1/reserve");
        assertThat(problem.getProperties())
                .containsEntry("code", "SEAT_UNAVAILABLE")
                .containsEntry("retryable", false)
                .containsKey("requestId");
    }

    @Test
    void extensionsAreAddedToTheProblemBody() {
        var problem = Problems.of(ErrorCode.USER_LIMIT_EXCEEDED,
                ErrorCode.USER_LIMIT_EXCEEDED.detail(), "/shows/1/reserve",
                java.util.Map.of("perUserLimit", 4, "seatsHeld", 3, "seatsRequested", 2));

        assertThat(problem.getProperties())
                .containsEntry("perUserLimit", 4)
                .containsEntry("seatsHeld", 3)
                .containsEntry("seatsRequested", 2);
    }
}
