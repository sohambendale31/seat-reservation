package com.seatres.web.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.HashSet;
import java.util.List;

public class UniqueLabelsValidator implements ConstraintValidator<UniqueLabels, List<String>> {

    @Override
    public boolean isValid(List<String> labels, ConstraintValidatorContext context) {
        if (labels == null) {
            return true;
        }
        return new HashSet<>(labels).size() == labels.size();
    }
}
