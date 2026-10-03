package com.seatres.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/** The configured mapper must reject identity in bodies and any non-integer money value. */
@JsonTest
class StrictJacksonTest {

    record Money(String name, long pricePaise) {}

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void acceptsIntegerMoney() throws Exception {
        assertThat(objectMapper.readValue("{\"name\":\"A\",\"pricePaise\":2500}", Money.class))
                .isEqualTo(new Money("A", 2500));
    }

    @Test
    void rejectsFractionalMoney() {
        assertThatThrownBy(() ->
                objectMapper.readValue("{\"name\":\"A\",\"pricePaise\":2500.5}", Money.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    void rejectsMoneyAsString() {
        assertThatThrownBy(() ->
                objectMapper.readValue("{\"name\":\"A\",\"pricePaise\":\"2500\"}", Money.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    void rejectsUnknownFields() {
        assertThatThrownBy(() -> objectMapper.readValue(
                "{\"name\":\"A\",\"pricePaise\":1,\"userId\":\"mallory\"}", Money.class))
                .isInstanceOf(UnrecognizedPropertyException.class);
    }
}
