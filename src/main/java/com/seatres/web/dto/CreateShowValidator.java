package com.seatres.web.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.HashSet;
import java.util.Set;

public class CreateShowValidator implements ConstraintValidator<ValidCreateShow, CreateShowRequest> {

    static final int MAX_NAME_LENGTH = 200;
    static final int MAX_TOTAL_SEATS = 10_000;

    @Override
    public boolean isValid(CreateShowRequest request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        boolean valid = checkName(request, context);
        return checkRows(request, context) && valid;
    }

    private static boolean checkName(CreateShowRequest request, ConstraintValidatorContext context) {
        if (request.name() == null) {
            return true;
        }
        int length = request.trimmedName().length();
        if (length < 1 || length > MAX_NAME_LENGTH) {
            reject(context, "name",
                    "must be between 1 and " + MAX_NAME_LENGTH + " characters after trimming");
            return false;
        }
        return true;
    }

    private static boolean checkRows(CreateShowRequest request, ConstraintValidatorContext context) {
        if (request.rows() == null) {
            return true;
        }
        boolean valid = true;

        Set<String> seen = new HashSet<>();
        boolean duplicates = request.rows().stream()
                .filter(row -> row != null && row.row() != null)
                .anyMatch(row -> !seen.add(row.row()));
        if (duplicates) {
            reject(context, "rows", "must not contain duplicate row names");
            valid = false;
        }

        long total = request.rows().stream()
                .filter(row -> row != null && row.seatCount() != null)
                .mapToLong(RowSpec::seatCount)
                .sum();
        if (total > MAX_TOTAL_SEATS) {
            reject(context, "rows",
                    "must not define more than " + MAX_TOTAL_SEATS + " seats in total");
            valid = false;
        }
        return valid;
    }

    private static void reject(ConstraintValidatorContext context, String field, String message) {
        context.buildConstraintViolationWithTemplate(message)
                .addPropertyNode(field)
                .addConstraintViolation();
    }
}
