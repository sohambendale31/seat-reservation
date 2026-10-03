package com.seatres.service.tx;

import com.seatres.error.ApiException;
import com.seatres.error.ErrorCode;
import com.seatres.error.OutcomeUnknownException;
import com.seatres.error.ServiceUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/**
 * Programmatic transaction boundary. Managed here rather than with @Transactional so a failure in
 * the callback can be told apart from a failure in commit(), the whole attempt can be retried, and
 * after-commit hooks run only once the commit is confirmed.
 */
@Component
public class TxExecutor {

    private static final Logger log = LoggerFactory.getLogger(TxExecutor.class);

    private static final int MAX_ATTEMPTS = 3;
    private static final int TIMEOUT_SECONDS = 45;
    private static final int RETRY_BASE_MIN_MS = 5;
    private static final int RETRY_BASE_MAX_MS = 25;

    private final PlatformTransactionManager transactionManager;
    private final DbErrorClassifier classifier;

    public TxExecutor(PlatformTransactionManager transactionManager, DbErrorClassifier classifier) {
        this.transactionManager = transactionManager;
        this.classifier = classifier;
    }

    public <T> T execute(Function<TxContext, T> work) {
        for (int attempt = 1; ; attempt++) {
            TxContext context = new TxContext();
            TransactionStatus status = begin();

            T result;
            try {
                result = work.apply(context);
            } catch (Throwable failure) {
                rollbackQuietly(status);
                if (failure instanceof ApiException apiFailure) {
                    throw apiFailure;
                }
                DbErrorClassifier.Category category = classifier.classify(failure);
                if (category == DbErrorClassifier.Category.RETRYABLE && attempt < MAX_ATTEMPTS) {
                    log.warn("db.transient_failure sqlstate={} kind=deadlock_retry attempt={}",
                            classifier.sqlState(failure), attempt);
                    backOff(attempt);
                    continue;
                }
                throw mapped(category, failure);
            }

            if (context.rollbackOnly) {
                rollbackQuietly(status);
                return result;
            }

            try {
                transactionManager.commit(status);
            } catch (Throwable failure) {
                if (classifier.isConnectionFailure(failure)) {
                    log.error("db.outcome_unknown sqlstate={}", classifier.sqlState(failure));
                    throw new OutcomeUnknownException(failure);
                }
                throw mapped(classifier.classify(failure), failure);
            }

            context.runHooks();
            return result;
        }
    }

    /** A pool timeout surfaces here, so it has to become a 503 rather than an unexpected 500. */
    private TransactionStatus begin() {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        definition.setTimeout(TIMEOUT_SECONDS);
        try {
            return transactionManager.getTransaction(definition);
        } catch (Throwable failure) {
            throw mapped(classifier.classify(failure), failure);
        }
    }

    private RuntimeException mapped(DbErrorClassifier.Category category, Throwable failure) {
        String state = classifier.sqlState(failure);
        return switch (category) {
            case RETRYABLE, BUSY, UNAVAILABLE -> {
                log.warn("db.transient_failure sqlstate={} kind={}", state,
                        category.name().toLowerCase());
                yield new ServiceUnavailableException(failure);
            }
            case BUG -> {
                log.error("invariant.violation check={} sqlstate={}", checkOf(state), state, failure);
                yield new ApiException(ErrorCode.INTERNAL_ERROR);
            }
        };
    }

    private static String checkOf(String sqlState) {
        return sqlState != null && sqlState.startsWith("23") ? "db_constraint" : "rowcount_assertion";
    }

    private void rollbackQuietly(TransactionStatus status) {
        try {
            if (!status.isCompleted()) {
                transactionManager.rollback(status);
            }
        } catch (Throwable rollbackFailure) {
            log.warn("db.rollback_failed", rollbackFailure);
        }
    }

    private static void backOff(int attempt) {
        long millis = (long) ThreadLocalRandom.current()
                .nextInt(RETRY_BASE_MIN_MS, RETRY_BASE_MAX_MS + 1) * attempt;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException(e);
        }
    }

    /** Collects work that must happen only after a confirmed commit, such as metrics. */
    public static final class TxContext {

        private final List<Runnable> afterCommit = new ArrayList<>(2);
        private boolean rollbackOnly;

        public void afterCommit(Runnable hook) {
            afterCommit.add(hook);
        }

        /** For work that decided it has nothing to write. Skips the commit and the hooks. */
        public void rollbackOnly() {
            this.rollbackOnly = true;
        }

        private void runHooks() {
            for (Runnable hook : afterCommit) {
                try {
                    hook.run();
                } catch (RuntimeException e) {
                    log.warn("after_commit_hook_failed", e);
                }
            }
        }
    }
}
