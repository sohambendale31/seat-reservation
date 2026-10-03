package com.seatres.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CreateShowRequestValidationTest {

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
    void aMinimalValidRequestPasses() {
        assertThat(violations(request("Demo", 4, List.of(row("A", 10, 25000))))).isEmpty();
    }

    @Test
    void perUserLimitIsOptionalAndDefaultsToFour() {
        CreateShowRequest request = request("Demo", null, List.of(row("A", 10, 25000)));

        assertThat(violations(request)).isEmpty();
        assertThat(request.perUserLimitOrDefault()).isEqualTo(4);
    }

    @Test
    void aBlankOrWhitespaceOnlyNameIsRejected() {
        assertThat(fields(request("", 4, List.of(row("A", 1, 0))))).contains("name");
        assertThat(fields(request("   ", 4, List.of(row("A", 1, 0))))).contains("name");
    }

    @Test
    void aNameLongerThan200CharactersAfterTrimmingIsRejected() {
        assertThat(fields(request("x".repeat(201), 4, List.of(row("A", 1, 0))))).contains("name");
        assertThat(violations(request("  " + "x".repeat(200) + "  ", 4, List.of(row("A", 1, 0)))))
                .isEmpty();
    }

    @Test
    void aMissingNameStartsAtOrRowsIsRejected() {
        assertThat(fields(new CreateShowRequest(null, OffsetDateTime.now(), 4,
                List.of(row("A", 1, 0))))).contains("name");
        assertThat(fields(new CreateShowRequest("Demo", null, 4, List.of(row("A", 1, 0)))))
                .contains("startsAt");
        assertThat(fields(new CreateShowRequest("Demo", OffsetDateTime.now(), 4, null)))
                .contains("rows");
    }

    @Test
    void anEmptyRowListIsRejected() {
        assertThat(fields(request("Demo", 4, List.of()))).contains("rows");
    }

    @Test
    void moreThan200RowsIsRejected() {
        List<RowSpec> rows = IntStream.range(0, 201)
                .mapToObj(i -> row("R" + i, 1, 0))
                .toList();

        assertThat(fields(request("Demo", 4, rows))).contains("rows");
    }

    @Test
    void duplicateRowNamesAreRejected() {
        Set<ConstraintViolation<CreateShowRequest>> violations =
                violations(request("Demo", 4, List.of(row("A", 10, 0), row("A", 5, 0))));

        assertThat(fieldsOf(violations)).contains("rows");
        assertThat(messagesOf(violations)).anyMatch(message -> message.contains("duplicate"));
    }

    @Test
    void moreThan10000SeatsInTotalIsRejected() {
        List<RowSpec> rows = List.of(row("A", 500, 0), row("B", 500, 0), row("C", 500, 0),
                row("D", 500, 0), row("E", 500, 0), row("F", 500, 0), row("G", 500, 0),
                row("H", 500, 0), row("I", 500, 0), row("J", 500, 0), row("K", 500, 0),
                row("L", 500, 0), row("M", 500, 0), row("N", 500, 0), row("O", 500, 0),
                row("P", 500, 0), row("Q", 500, 0), row("R", 500, 0), row("S", 500, 0),
                row("T", 500, 0), row("U", 1, 0));
        Set<ConstraintViolation<CreateShowRequest>> violations = violations(request("Demo", 4, rows));

        assertThat(fieldsOf(violations)).contains("rows");
        assertThat(messagesOf(violations)).anyMatch(message -> message.contains("10000"));
    }

    @Test
    void exactly10000SeatsIsAccepted() {
        List<RowSpec> rows = IntStream.range(0, 20)
                .mapToObj(i -> row("R" + (char) ('A' + i), 500, 0))
                .toList();

        assertThat(violations(request("Demo", 4, rows))).isEmpty();
    }

    @Test
    void rowNamesOutsideThePatternAreRejected() {
        assertThat(fields(request("Demo", 4, List.of(row("a", 1, 0))))).anyMatch(f -> f.contains("row"));
        assertThat(fields(request("Demo", 4, List.of(row("ABCD", 1, 0))))).anyMatch(f -> f.contains("row"));
        assertThat(fields(request("Demo", 4, List.of(row("A1", 1, 0))))).anyMatch(f -> f.contains("row"));
        assertThat(fields(request("Demo", 4, List.of(row("", 1, 0))))).anyMatch(f -> f.contains("row"));
    }

    @Test
    void seatCountsOutsideOneToFiveHundredAreRejected() {
        assertThat(fields(request("Demo", 4, List.of(row("A", 0, 0))))).anyMatch(f -> f.contains("seatCount"));
        assertThat(fields(request("Demo", 4, List.of(row("A", 501, 0))))).anyMatch(f -> f.contains("seatCount"));
        assertThat(violations(request("Demo", 4, List.of(row("A", 500, 0))))).isEmpty();
    }

    @Test
    void pricesOutsideZeroToOneHundredMillionAreRejected() {
        assertThat(fields(request("Demo", 4, List.of(row("A", 1, -1))))).anyMatch(f -> f.contains("pricePaise"));
        assertThat(fields(request("Demo", 4, List.of(row("A", 1, 100_000_001L))))).anyMatch(f -> f.contains("pricePaise"));
        assertThat(violations(request("Demo", 4, List.of(row("A", 1, 100_000_000L))))).isEmpty();
        assertThat(violations(request("Demo", 4, List.of(row("A", 1, 0))))).isEmpty();
    }

    @Test
    void perUserLimitOutsideOneToTenIsRejected() {
        assertThat(fields(request("Demo", 0, List.of(row("A", 1, 0))))).contains("perUserLimit");
        assertThat(fields(request("Demo", 11, List.of(row("A", 1, 0))))).contains("perUserLimit");
    }

    @Test
    void aPastStartsAtIsAccepted() {
        CreateShowRequest request = new CreateShowRequest("Demo",
                OffsetDateTime.parse("2001-01-01T10:00:00+05:30"), 4, List.of(row("A", 1, 0)));

        assertThat(violations(request)).isEmpty();
    }

    @Test
    void totalSeatsSumsEveryRow() {
        assertThat(request("Demo", 4, List.of(row("A", 20, 0), row("B", 15, 0))).totalSeats())
                .isEqualTo(35);
    }

    private static CreateShowRequest request(String name, Integer perUserLimit, List<RowSpec> rows) {
        return new CreateShowRequest(name, OffsetDateTime.parse("2026-12-01T19:30:00+05:30"),
                perUserLimit, rows);
    }

    private static RowSpec row(String name, int seatCount, long pricePaise) {
        return new RowSpec(name, seatCount, pricePaise);
    }

    private static Set<ConstraintViolation<CreateShowRequest>> violations(CreateShowRequest request) {
        return validator.validate(request);
    }

    private static Set<String> fields(CreateShowRequest request) {
        return fieldsOf(violations(request));
    }

    private static Set<String> fieldsOf(Set<ConstraintViolation<CreateShowRequest>> violations) {
        return violations.stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    private static Set<String> messagesOf(Set<ConstraintViolation<CreateShowRequest>> violations) {
        return violations.stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }
}
