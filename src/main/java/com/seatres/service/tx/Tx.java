package com.seatres.service.tx;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Guards against a write or a lock running outside a transaction, where it would not be atomic. */
public final class Tx {

    private Tx() {
    }

    public static void requireActive() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("must run inside an active transaction");
        }
    }
}
