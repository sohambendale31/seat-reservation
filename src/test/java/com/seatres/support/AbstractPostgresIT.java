package com.seatres.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL container per test JVM, started here and never stopped so every test class can
 * share a single cached Spring context. Not managed by @Testcontainers/@Container: that extension
 * stops a static container when its test class finishes, leaving the cached context on a dead
 * database. Ryuk removes the container at JVM exit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {

    /** Digest-only form: Testcontainers rejects tag@digest when Boot derives the connection name. */
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea")
            .asCompatibleSubstituteFor("postgres");

    @ServiceConnection("postgresql")
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("seatres")
            .withUsername("seatres")
            .withPassword("seatres-test-only");

    static {
        POSTGRES.start();
    }
}
