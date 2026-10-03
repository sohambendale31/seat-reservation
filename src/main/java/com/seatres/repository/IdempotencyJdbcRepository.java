package com.seatres.repository;

import com.seatres.service.tx.Tx;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyJdbcRepository {

    private static final String CLAIM = """
            INSERT INTO idempotency_records
                (id, user_id, idem_key, request_fingerprint, created_at, expires_at)
            VALUES (:id, :userId, :idemKey, :fingerprint, now(), now() + CAST(:retention AS interval))
            ON CONFLICT (user_id, idem_key) DO NOTHING
            RETURNING id
            """;

    private static final String FIND = """
            SELECT request_fingerprint, response_status, response_body
            FROM idempotency_records
            WHERE user_id = :userId AND idem_key = :idemKey
            """;

    private static final String COMPLETE = """
            UPDATE idempotency_records
            SET response_status = :status, response_body = :body, completed_at = now()
            WHERE id = :id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public IdempotencyJdbcRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Takes the key for this transaction. Returns false when another committed transaction owns it;
     * a concurrent same-key insert blocks here on the unique index until that transaction ends.
     */
    public boolean claim(UUID id, String userId, String idemKey, String fingerprint,
            Duration retention) {
        Tx.requireActive();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId)
                .addValue("idemKey", idemKey)
                .addValue("fingerprint", fingerprint)
                .addValue("retention", retention.toString());
        ResultSetExtractor<Boolean> claimed = rs -> rs.next();
        return Boolean.TRUE.equals(jdbc.query(CLAIM, parameters, claimed));
    }

    public Optional<StoredOutcome> find(String userId, String idemKey) {
        Tx.requireActive();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("idemKey", idemKey);
        return jdbc.query(FIND, parameters, rs -> rs.next()
                ? Optional.of(new StoredOutcome(
                        rs.getString("request_fingerprint"),
                        rs.getObject("response_status", Integer.class),
                        rs.getString("response_body")))
                : Optional.empty());
    }

    public void complete(UUID id, int status, String body) {
        Tx.requireActive();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status)
                .addValue("body", body);
        int updated = jdbc.update(COMPLETE, parameters);
        if (updated != 1) {
            throw new IllegalStateException(
                    "expected to finalize 1 idempotency record but updated " + updated);
        }
    }

    public record StoredOutcome(String fingerprint, Integer status, String body) {}
}
