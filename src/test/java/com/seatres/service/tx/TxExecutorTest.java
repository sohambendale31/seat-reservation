package com.seatres.service.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.seatres.error.ApiException;
import com.seatres.error.ErrorCode;
import com.seatres.error.OutcomeUnknownException;
import com.seatres.error.ServiceUnavailableException;
import com.seatres.error.ShowNotFoundException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class TxExecutorTest {

    private PlatformTransactionManager transactionManager;
    private TxExecutor executor;

    @BeforeEach
    void setUp() {
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        executor = new TxExecutor(transactionManager, new DbErrorClassifier());
    }

    @Test
    void commitsAndReturnsTheResult() {
        String result = executor.execute(context -> "done");

        assertThat(result).isEqualTo("done");
        verify(transactionManager).commit(any());
        verify(transactionManager, never()).rollback(any());
    }

    @Test
    void transactionsUseTheConfiguredTimeout() {
        executor.execute(context -> null);

        verify(transactionManager).getTransaction(
                org.mockito.ArgumentMatchers.argThat(definition ->
                        definition.getTimeout() == 45
                                && definition.getPropagationBehavior()
                                        == TransactionDefinition.PROPAGATION_REQUIRED));
    }

    @Test
    void afterCommitHooksRunOnlyOnceTheCommitSucceeds() {
        AtomicInteger hookRuns = new AtomicInteger();

        executor.execute(context -> {
            context.afterCommit(hookRuns::incrementAndGet);
            context.afterCommit(hookRuns::incrementAndGet);
            assertThat(hookRuns).hasValue(0);
            return null;
        });

        assertThat(hookRuns).hasValue(2);
    }

    @Test
    void afterCommitHooksDoNotRunWhenTheCallbackFails() {
        AtomicInteger hookRuns = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(context -> {
            context.afterCommit(hookRuns::incrementAndGet);
            throw deadlock();
        })).isInstanceOf(ServiceUnavailableException.class);

        assertThat(hookRuns).hasValue(0);
    }

    @Test
    void aFailingHookNeverReachesTheCaller() {
        AtomicInteger hookRuns = new AtomicInteger();

        String result = executor.execute(context -> {
            context.afterCommit(() -> {
                throw new IllegalStateException("metrics backend down");
            });
            context.afterCommit(hookRuns::incrementAndGet);
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(hookRuns).hasValue(1);
    }

    @Test
    void deadlocksAreRetriedUpToThreeAttemptsThenBecomeUnavailable() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(context -> {
            attempts.incrementAndGet();
            throw deadlock();
        })).isInstanceOf(ServiceUnavailableException.class);

        assertThat(attempts).hasValue(3);
        verify(transactionManager, times(3)).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void aRetriedAttemptThatSucceedsReturnsNormally() {
        AtomicInteger attempts = new AtomicInteger();

        String result = executor.execute(context -> {
            if (attempts.incrementAndGet() < 3) {
                throw serializationFailure();
            }
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(attempts).hasValue(3);
        verify(transactionManager, times(2)).rollback(any());
        verify(transactionManager).commit(any());
    }

    @Test
    void constraintViolationsAreNotRetriedAndBecomeInternalErrors() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(context -> {
            attempts.incrementAndGet();
            throw new UncategorizedSQLException("insert", "INSERT",
                    new SQLException("duplicate key", "23505"));
        })).isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).code())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void rowCountAssertionsBecomeInternalErrors() {
        assertThatThrownBy(() -> executor.execute(context -> {
            throw new IllegalStateException("expected 2 rows, updated 1");
        })).isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).code())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void businessDeclinesPassThroughUntouched() {
        UUID showId = UUID.randomUUID();

        assertThatThrownBy(() -> executor.execute(context -> {
            throw new ShowNotFoundException(showId);
        })).isInstanceOf(ShowNotFoundException.class);

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void lockTimeoutsAreNotRetriedAndBecomeUnavailable() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(context -> {
            attempts.incrementAndGet();
            throw new UncategorizedSQLException("lock", "SELECT",
                    new SQLException("lock not available", "55P03"));
        })).isInstanceOf(ServiceUnavailableException.class);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void aConnectionFailureDuringCommitLeavesTheOutcomeUnknown() {
        doThrow(new UncategorizedSQLException("commit", "COMMIT",
                new SQLException("connection closed", "08006")))
                .when(transactionManager).commit(any());
        AtomicInteger hookRuns = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(context -> {
            context.afterCommit(hookRuns::incrementAndGet);
            return "done";
        })).isInstanceOf(OutcomeUnknownException.class);

        assertThat(hookRuns).hasValue(0);
    }

    @Test
    void aDefiniteFailureDuringCommitIsNotAnUnknownOutcome() {
        doThrow(new UncategorizedSQLException("commit", "COMMIT",
                new SQLException("constraint", "23505")))
                .when(transactionManager).commit(any());

        assertThatThrownBy(() -> executor.execute(context -> "done"))
                .isInstanceOf(ApiException.class)
                .isNotInstanceOf(OutcomeUnknownException.class);
    }

    @Test
    void aCommitIsNeverRetried() {
        doThrow(new UncategorizedSQLException("commit", "COMMIT",
                new SQLException("deadlock", "40P01")))
                .when(transactionManager).commit(any());

        assertThatThrownBy(() -> executor.execute(context -> "done"))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(transactionManager, times(1)).commit(any());
    }

    @Test
    void aPoolTimeoutWhileStartingTheTransactionBecomesUnavailable() {
        when(transactionManager.getTransaction(any())).thenThrow(
                new DataAccessResourceFailureException("no connection",
                        new SQLTransientConnectionException("request timed out")));
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(context -> {
            attempts.incrementAndGet();
            return null;
        })).isInstanceOf(ServiceUnavailableException.class);

        assertThat(attempts).hasValue(0);
    }

    @Test
    void anAlreadyCompletedTransactionIsNotRolledBackAgain() {
        TransactionStatus completed = mock(TransactionStatus.class);
        when(completed.isCompleted()).thenReturn(true);
        when(transactionManager.getTransaction(any())).thenReturn(completed);

        assertThatThrownBy(() -> executor.execute(context -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(ApiException.class);

        verify(transactionManager, never()).rollback(any());
    }

    @Test
    void hooksFromAFailedAttemptAreNotCarriedIntoTheRetry() {
        AtomicInteger hookRuns = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();

        executor.execute(context -> {
            context.afterCommit(hookRuns::incrementAndGet);
            if (attempts.incrementAndGet() == 1) {
                throw deadlock();
            }
            return null;
        });

        assertThat(attempts).hasValue(2);
        assertThat(hookRuns).hasValue(1);
    }

    @Test
    void listResultsAreReturnedUnchanged() {
        assertThat(executor.<List<String>>execute(context -> List.of("A1", "A2")))
                .containsExactly("A1", "A2");
    }

    private static RuntimeException deadlock() {
        return new UncategorizedSQLException("update", "UPDATE",
                new SQLException("deadlock detected", "40P01"));
    }

    private static RuntimeException serializationFailure() {
        return new UncategorizedSQLException("update", "UPDATE",
                new SQLException("could not serialize", "40001"));
    }
}
