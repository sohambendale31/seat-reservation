package com.seatres.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ReserveRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void startValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void oneToTenLabelsAreAccepted() {
        assertThat(violations(List.of("A1"))).isEmpty();
        assertThat(violations(List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A10")))
                .isEmpty();
    }

    @Test
    void anEmptyOrMissingSeatListIsRejected() {
        assertThat(fields(List.of())).contains("seats");
        assertThat(fieldsOf(validator.validate(new ReserveRequest(null)))).contains("seats");
    }

    @Test
    void moreThanTenLabelsIsRejected() {
        List<String> eleven = List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A10",
                "A11");

        assertThat(fields(eleven)).contains("seats");
    }

    @Test
    void duplicateLabelsAreRejected() {
        Set<ConstraintViolation<ReserveRequest>> violations = violations(List.of("A1", "A1"));

        assertThat(fieldsOf(violations)).contains("seats");
        assertThat(violations).anyMatch(v -> v.getMessage().contains("duplicate"));
    }

    @Test
    void labelsOutsideThePatternAreRejected() {
        assertThat(fields(List.of("a1"))).anyMatch(field -> field.startsWith("seats["));
        assertThat(fields(List.of("A0"))).anyMatch(field -> field.startsWith("seats["));
        assertThat(fields(List.of("A"))).anyMatch(field -> field.startsWith("seats["));
        assertThat(fields(List.of("1A"))).anyMatch(field -> field.startsWith("seats["));
        assertThat(fields(List.of("ABCD1"))).anyMatch(field -> field.startsWith("seats["));
        assertThat(fields(List.of("A1000"))).anyMatch(field -> field.startsWith("seats["));
    }

    @Test
    void aNullLabelIsRejected() {
        assertThat(fields(Arrays.asList("A1", null))).anyMatch(field -> field.startsWith("seats["));
    }

    /** A999 is a legal label; whether the seat exists is decided by the reserve transaction. */
    @Test
    void aWellFormedButProbablyUnknownLabelPassesValidation() {
        assertThat(violations(List.of("A999"))).isEmpty();
        assertThat(violations(List.of("ABC1"))).isEmpty();
    }

    @Test
    void sortedSeatsIgnoresRequestOrder() {
        assertThat(new ReserveRequest(List.of("A2", "A1")).sortedSeats()).containsExactly("A1", "A2");
        assertThat(new ReserveRequest(List.of("B1", "A2")).sortedSeats()).containsExactly("A2", "B1");
    }

    private static Set<ConstraintViolation<ReserveRequest>> violations(List<String> seats) {
        return validator.validate(new ReserveRequest(seats));
    }

    private static Set<String> fields(List<String> seats) {
        return fieldsOf(violations(seats));
    }

    private static Set<String> fieldsOf(Set<ConstraintViolation<ReserveRequest>> violations) {
        return violations.stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }
}
