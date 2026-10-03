package com.seatres.service;

import com.seatres.config.AppProperties;
import com.seatres.domain.ReserveCommand;
import com.seatres.error.ServiceUnavailableException;
import com.seatres.repository.IdempotencyJdbcRepository;
import com.seatres.repository.IdempotencyJdbcRepository.StoredOutcome;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Runs inside the caller's transaction so the claim, the stored outcome and the business effect
 * become visible together. Never opens a transaction of its own.
 */
@Service
public class IdempotencyService {

    private static final int CLAIM_ATTEMPTS = 2;

    private final IdempotencyJdbcRepository records;
    private final AppProperties properties;

    public IdempotencyService(IdempotencyJdbcRepository records, AppProperties properties) {
        this.records = records;
        this.properties = properties;
    }

    /**
     * Claims the key for this transaction, or returns the outcome another transaction already
     * committed for it. An empty result means this transaction owns the key and must do the work.
     */
    public Optional<StoredOutcome> claimOrLoad(UUID recordId, ReserveCommand command) {
        for (int attempt = 1; attempt <= CLAIM_ATTEMPTS; attempt++) {
            boolean claimed = records.claim(recordId, command.userId(), command.idemKey(),
                    command.fingerprint(), properties.idempotency().retention());
            if (claimed) {
                return Optional.empty();
            }
            Optional<StoredOutcome> stored = records.find(command.userId(), command.idemKey());
            if (stored.isPresent()) {
                if (stored.get().status() == null) {
                    throw new IllegalStateException("committed idempotency record has no response");
                }
                return stored;
            }
            // Retention cleanup deleted the row between the claim and the lookup; claim again.
        }
        throw new ServiceUnavailableException(
                new IllegalStateException("idempotency record kept vanishing during the claim"));
    }

    public void complete(UUID recordId, int status, String body) {
        records.complete(recordId, status, body);
    }
}
