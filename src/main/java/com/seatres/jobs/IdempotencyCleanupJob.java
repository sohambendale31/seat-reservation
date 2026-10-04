package com.seatres.jobs;

import com.seatres.repository.IdempotencyJdbcRepository;
import com.seatres.service.tx.TxExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes expired idempotency records in small, independently committed batches. */
@Component
public class IdempotencyCleanupJob {

    private static final int MAX_BATCHES = 20;

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final TxExecutor tx;
    private final IdempotencyJdbcRepository records;

    public IdempotencyCleanupJob(TxExecutor tx, IdempotencyJdbcRepository records) {
        this.tx = tx;
        this.records = records;
    }

    @Scheduled(fixedDelay = 15, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    public int deleteExpired() {
        int deleted = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int inBatch = tx.execute(context -> records.deleteExpiredBatch());
            deleted += inBatch;
            if (inBatch < IdempotencyJdbcRepository.CLEANUP_BATCH_SIZE) {
                break;
            }
        }
        if (deleted > 0) {
            log.info("idempotency.cleanup deleted={}", deleted);
        }
        return deleted;
    }
}
