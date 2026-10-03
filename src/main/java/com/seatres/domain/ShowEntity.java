package com.seatres.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The only JPA-mapped table; every concurrency-critical table uses JdbcTemplate. Column definitions
 * must stay in step with V1__baseline_schema.sql, since Hibernate runs with ddl-auto=validate.
 */
@Entity
@Table(name = "shows")
public class ShowEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "per_user_limit", nullable = false)
    private int perUserLimit;

    @Column(name = "total_seats", nullable = false)
    private int totalSeats;

    /** Set by the application, not the DB default, so it is known without a re-select. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ShowEntity() {
        // for JPA
    }

    public ShowEntity(UUID id, String name, Instant startsAt, int perUserLimit, int totalSeats,
            Instant createdAt) {
        this.id = id;
        this.name = name;
        this.startsAt = startsAt;
        this.perUserLimit = perUserLimit;
        this.totalSeats = totalSeats;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public int getPerUserLimit() {
        return perUserLimit;
    }

    public int getTotalSeats() {
        return totalSeats;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
