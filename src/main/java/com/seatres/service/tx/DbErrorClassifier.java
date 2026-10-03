package com.seatres.service.tx;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.springframework.stereotype.Component;

/** Maps a thrown exception to the category that decides the HTTP status and the retry policy. */
@Component
public class DbErrorClassifier {

    public enum Category {
        /** Deadlock or serialization failure: the attempt can be replayed safely. */
        RETRYABLE,
        /** A lock or statement timeout was reached. */
        BUSY,
        /** The database or a connection to it is unavailable. */
        UNAVAILABLE,
        /** A constraint fired or an assertion failed, which means the logic is wrong. */
        BUG
    }

    public Category classify(Throwable throwable) {
        if (hasCause(throwable, SQLTransientConnectionException.class)) {
            return Category.UNAVAILABLE;
        }
        String state = sqlState(throwable);
        if (state == null) {
            return Category.BUG;
        }
        return switch (state) {
            case "40P01", "40001" -> Category.RETRYABLE;
            case "55P03", "57014" -> Category.BUSY;
            case "53300" -> Category.UNAVAILABLE;
            default -> classifyByClass(state);
        };
    }

    /** True when a commit may or may not have reached the database. */
    public boolean isConnectionFailure(Throwable throwable) {
        String state = sqlState(throwable);
        return (state != null && state.startsWith("08")) || hasCause(throwable, IOException.class);
    }

    public String sqlState(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static Category classifyByClass(String state) {
        if (state.startsWith("57P") || state.startsWith("08")) {
            return Category.UNAVAILABLE;
        }
        return Category.BUG;
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}
