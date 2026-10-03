package com.seatres.service.tx;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatres.service.tx.DbErrorClassifier.Category;
import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.UncategorizedSQLException;

class DbErrorClassifierTest {

    private final DbErrorClassifier classifier = new DbErrorClassifier();

    @ParameterizedTest
    @CsvSource({
            "40P01, RETRYABLE",
            "40001, RETRYABLE",
            "55P03, BUSY",
            "57014, BUSY",
            "53300, UNAVAILABLE",
            "57P01, UNAVAILABLE",
            "57P02, UNAVAILABLE",
            "57P03, UNAVAILABLE",
            "08000, UNAVAILABLE",
            "08006, UNAVAILABLE",
            "23505, BUG",
            "23514, BUG",
            "23503, BUG",
            "42601, BUG"})
    void eachSqlStateMapsToItsCategory(String sqlState, Category expected) {
        assertThat(classifier.classify(new SQLException("failed", sqlState))).isEqualTo(expected);
    }

    @Test
    void sqlStatesAreFoundThroughNestedCauses() {
        Throwable nested = new UncategorizedSQLException("wrapped", "SELECT 1",
                new SQLException("deadlock detected", "40P01"));

        assertThat(classifier.classify(nested)).isEqualTo(Category.RETRYABLE);
        assertThat(classifier.sqlState(nested)).isEqualTo("40P01");
    }

    @Test
    void aPoolTimeoutIsUnavailable() {
        Throwable poolTimeout = new DataAccessResourceFailureException("no connection",
                new SQLTransientConnectionException("request timed out"));

        assertThat(classifier.classify(poolTimeout)).isEqualTo(Category.UNAVAILABLE);
    }

    @Test
    void anExceptionWithoutASqlStateIsABug() {
        assertThat(classifier.classify(new IllegalStateException("row count was 0")))
                .isEqualTo(Category.BUG);
        assertThat(classifier.sqlState(new IllegalStateException("boom"))).isNull();
    }

    @Test
    void connectionClassFailuresAreRecognisedForTheCommitPhase() {
        assertThat(classifier.isConnectionFailure(new SQLException("gone", "08006"))).isTrue();
        assertThat(classifier.isConnectionFailure(
                new SQLException("io", "58030", new IOException("socket closed")))).isTrue();
    }

    @Test
    void definiteFailuresAreNotTreatedAsUnknownOutcomes() {
        assertThat(classifier.isConnectionFailure(new SQLException("constraint", "23505"))).isFalse();
        assertThat(classifier.isConnectionFailure(new IllegalStateException("assertion"))).isFalse();
    }

    @Test
    void aDeeplyNestedSqlStateIsStillFound() {
        Throwable chain = new SQLException("deadlock detected", "40P01");
        for (int depth = 0; depth < 10; depth++) {
            chain = new IllegalStateException("layer " + depth, chain);
        }

        assertThat(classifier.classify(chain)).isEqualTo(Category.RETRYABLE);
    }

    @Test
    void theFirstSqlStateInTheChainWins() {
        Throwable chain = new SQLException("outer", "55P03", new SQLException("inner", "23505"));

        assertThat(classifier.classify(chain)).isEqualTo(Category.BUSY);
    }
}
