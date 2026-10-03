package com.seatres.web.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Checks that need more than one field, or the trimmed form of one. */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CreateShowValidator.class)
public @interface ValidCreateShow {

    String message() default "is not a valid show";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
