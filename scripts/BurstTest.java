/// Burst driver for the seat reservation service. No dependencies: run with
/// java scripts/BurstTest.java [--base-url URL] [--scenario all] [--concurrency 200] ...
/// Exits non-zero if any correctness assertion fails.
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public final class BurstTest {

    private static final String DEV_ADMIN_KEY = "local-dev-only-admin-key-change-me-0123456789";
    private static final List<Long> RESOLUTION_BACKOFF_MS = List.of(500L, 1000L, 2000L);
    private static final List<String> SCENARIOS =
            List.of("hot-seat", "pool-burst", "same-key", "user-limit", "all");

    private final Config config;
    private final HttpClient http;
    private final String runId;
    private String adminToken;

    private BurstTest(Config config) {
        this.config = config;
        // HTTP/1.1 on purpose: over TLS the client would negotiate HTTP/2 and multiplex every
        // request onto one connection, so the concurrency below would serialize behind that
        // connection's stream limit. HTTP/1.1 opens a connection per in-flight request instead.
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(20))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        this.runId = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
                .replaceAll("[^0-9T]", "") + "-" + Long.toHexString(new Random().nextInt(0x10000));
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            usage();
            return;
        }
        Config config;
        try {
            config = Config.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            usage();
            System.exit(2);
            return;
        }
        BurstTest burst = new BurstTest(config);
        if (!SCENARIOS.contains(config.scenario())) {
            System.err.println("error: unknown scenario: " + config.scenario());
            usage();
            System.exit(2);
            return;
        }
        System.out.printf("target=%s runId=%s concurrency=%d%n",
                config.baseUrl(), burst.runId, config.concurrency());
        System.exit(burst.run() ? 0 : 1);
    }

    private static void usage() {
        System.err.println("""
                usage: java scripts/BurstTest.java [options]
                  --base-url    URL of the target            (BASE_URL, default http://localhost:8080)
                  --scenario    hot-seat | pool-burst | same-key | user-limit | all  (SCENARIO, default all)
                  --concurrency max requests in flight       (CONCURRENCY, default 200)
                  --requests    requests for pool-burst      (REQUESTS, default 20000)
                  --users       distinct users for pool-burst (USERS, default 2000)
                  --seats       seats in the pool-burst show (SEATS, default 1000)
                  --hot-users   users for hot-seat           (HOT_USERS, default 500)
                  --timeout-ms  per-request timeout          (TIMEOUT_MS, default 120000)
                  --seed        RNG seed for seat choices    (SEED, default 42)
                ADMIN_KEY in the environment is the target's admin key; it is only used to get the
                ADMIN token that creates the burst shows. Exits 1 if any assertion fails.""");
    }

    private boolean run() throws Exception {
        adminToken = mintToken("burst-" + runId + "-admin", "ADMIN");
        List<String> scenarios = "all".equals(config.scenario())
                ? SCENARIOS.subList(0, SCENARIOS.size() - 1)
                : List.of(config.scenario());

        boolean allPassed = true;
        for (String scenario : scenarios) {
            boolean passed = switch (scenario) {
                case "hot-seat" -> hotSeat();
                case "pool-burst" -> poolBurst();
                case "same-key" -> sameKey();
                case "user-limit" -> userLimit();
                default -> throw new IllegalArgumentException("unknown scenario: " + scenario
                        + " (expected hot-seat, pool-burst, same-key, user-limit or all)");
            };
            allPassed &= passed;
        }
        return allPassed;
    }

    // ---------------------------------------------------------------- scenarios

    private boolean hotSeat() throws Exception {
        String show = createShow("hot", 1, 10, 4);
        List<String> tokens = mintUserTokens(config.hotUsers());
        List<Call> calls = IntStream.range(0, config.hotUsers())
                .mapToObj(i -> new Call(i, tokens.get(i), newKey(), List.of("A1")))
                .toList();

        Run run = execute(show, calls);
        Checks checks = new Checks();
        checks.require("exactly one logical confirmation",
                run.logical().reservations().size() == 1,
                "got " + run.logical().reservations().size());
        checks.require("A1 is confirmed on the server",
                run.confirmedOnServer().equals(Set.of("A1")),
                "server confirmed " + run.confirmedOnServer());
        return report("hot-seat", show, config.hotUsers(), run, checks);
    }

    private boolean poolBurst() throws Exception {
        String show = createShow("pool", config.seats() / 50, 50, 4);
        List<String> labels = showLabels(config.seats() / 50, 50);
        List<String> tokens = mintUserTokens(config.users());
        Random random = new Random(config.seed());

        List<Call> calls = new ArrayList<>(config.requests());
        for (int i = 0; i < config.requests(); i++) {
            int user = random.nextInt(config.users());
            int wanted = 1 + random.nextInt(4);
            Set<String> picked = new LinkedHashSet<>();
            while (picked.size() < wanted) {
                picked.add(labels.get(random.nextInt(labels.size())));
            }
            calls.add(new Call(user, tokens.get(user), newKey(), List.copyOf(picked)));
        }

        Run run = execute(show, calls);
        Logical logical = run.logical();
        int seatsAcrossReservations = logical.reservations().values().stream()
                .mapToInt(Set::size).sum();
        Checks checks = new Checks();
        checks.require("seats in different reservations are pairwise disjoint",
                seatsAcrossReservations == logical.confirmedSeats().size(),
                seatsAcrossReservations + " seats across reservations but "
                        + logical.confirmedSeats().size() + " distinct");
        logical.perUser().forEach((user, seats) -> checks.require(
                "user " + user + " holds at most 4 seats", seats.size() <= 4,
                "holds " + seats.size()));
        return report("pool-burst", show, config.users(), run, checks);
    }

    private boolean sameKey() throws Exception {
        String show = createShow("samekey", 1, 10, 4);
        String user = "burst-" + runId + "-sk";
        String token = mintToken(user, "USER");

        String identicalKey = newKey();
        List<Call> identical = IntStream.range(0, 50)
                .mapToObj(i -> new Call(0, token, identicalKey, List.of("A1", "A2")))
                .toList();
        Run first = execute(show, identical);

        String alternatingKey = newKey();
        List<Call> alternating = IntStream.range(0, 20)
                .mapToObj(i -> new Call(0, token, alternatingKey,
                        i % 2 == 0 ? List.of("A5") : List.of("A6")))
                .toList();
        Run second = execute(show, alternating);

        Checks checks = new Checks();
        checks.require("50 identical requests all answered 201",
                first.outcomes().getOrDefault("201", 0) == 50,
                "201 count was " + first.outcomes().getOrDefault("201", 0));
        checks.require("50 identical requests made one reservation",
                first.logical().reservations().size() == 1,
                "got " + first.logical().reservations().size());
        checks.require("49 of them were replays", first.replayed() == 49,
                "replay header seen " + first.replayed() + " times");
        checks.require("alternating bodies made one reservation",
                second.logical().reservations().size() == 1,
                "got " + second.logical().reservations().size());
        checks.require("the losing body was refused as a reused key",
                second.outcomes().getOrDefault("409 IDEMPOTENCY_KEY_REUSED", 0) > 0,
                "no IDEMPOTENCY_KEY_REUSED seen");
        checks.require("no first-attempt 5xx in either wave",
                second.serverErrors().isEmpty(), second.serverErrors().toString());

        Run combined = first.mergedWith(second, confirmedOnServer(show), seatCounts(show));
        return report("same-key", show, 1, combined, checks);
    }

    private boolean userLimit() throws Exception {
        String show = createShow("limit", 1, 10, 4);
        String token = mintToken("burst-" + runId + "-ul", "USER");
        List<String> labels = showLabels(1, 10);
        List<Call> calls = IntStream.range(0, 10)
                .mapToObj(i -> new Call(0, token, newKey(), List.of(labels.get(i))))
                .toList();

        Run run = execute(show, calls);
        Checks checks = new Checks();
        checks.require("exactly 4 confirmations", run.resolvedOutcomes().getOrDefault("201", 0) == 4,
                "got " + run.resolvedOutcomes().getOrDefault("201", 0));
        checks.require("exactly 6 limit declines",
                run.resolvedOutcomes().getOrDefault("409 USER_LIMIT_EXCEEDED", 0) == 6,
                "got " + run.resolvedOutcomes().getOrDefault("409 USER_LIMIT_EXCEEDED", 0));
        checks.require("the server holds 4 confirmed seats",
                run.confirmedOnServer().size() == 4,
                "server confirmed " + run.confirmedOnServer().size());
        return report("user-limit", show, 1, run, checks);
    }

    // ---------------------------------------------------------------- execution

    private Run execute(String show, List<Call> calls) throws Exception {
        Semaphore gate = new Semaphore(config.concurrency());
        List<Response> responses = new ArrayList<>(calls.size());
        long startedAt = System.nanoTime();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Response>> futures = new ArrayList<>(calls.size());
            for (Call call : calls) {
                futures.add(executor.submit(() -> {
                    gate.acquire();
                    try {
                        return reserve(show, call);
                    } finally {
                        gate.release();
                    }
                }));
            }
            for (Future<Response> future : futures) {
                responses.add(future.get());
            }
        }
        Duration wall = Duration.ofNanos(System.nanoTime() - startedAt);

        List<Response> resolved = resolve(show, calls, responses);
        return new Run(calls, responses, resolved, wall, confirmedOnServer(show), seatCounts(show));
    }

    /** Re-sends ambiguous outcomes with the same key and body, which is the documented client rule. */
    private List<Response> resolve(String show, List<Call> calls, List<Response> firstAttempts)
            throws Exception {
        List<Response> resolved = new ArrayList<>(firstAttempts);
        for (int i = 0; i < firstAttempts.size(); i++) {
            if (!firstAttempts.get(i).ambiguous()) {
                continue;
            }
            Response latest = firstAttempts.get(i);
            for (long backoff : RESOLUTION_BACKOFF_MS) {
                Thread.sleep(backoff);
                latest = reserve(show, calls.get(i));
                if (!latest.ambiguous()) {
                    break;
                }
            }
            resolved.set(i, latest);
        }
        return resolved;
    }

    private Response reserve(String show, Call call) {
        String body = call.labels().stream()
                .map(label -> "\"" + label + "\"")
                .collect(Collectors.joining(",", "{\"seats\":[", "]}"));
        HttpRequest request = HttpRequest.newBuilder(uri("/shows/" + show + "/reserve"))
                .timeout(Duration.ofMillis(config.timeoutMs()))
                .header("Authorization", "Bearer " + call.token())
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", call.key())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        long startedAt = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = (System.nanoTime() - startedAt) / 1_000_000;
            boolean replayed = response.headers().firstValue("Idempotent-Replayed")
                    .map("true"::equals).orElse(false);
            return new Response(response.statusCode(), response.body(), replayed, latency,
                    Failure.NONE);
        } catch (HttpTimeoutException e) {
            return new Response(0, "", false, (System.nanoTime() - startedAt) / 1_000_000,
                    Failure.TIMEOUT);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Response(0, "", false, (System.nanoTime() - startedAt) / 1_000_000,
                    Failure.TRANSPORT);
        }
    }

    // ---------------------------------------------------------------- server reads

    private Set<String> confirmedOnServer(String show) {
        String body = get("/shows/" + show, adminToken);
        Set<String> confirmed = new LinkedHashSet<>();
        Matcher matcher = Pattern
                .compile("\\{\"label\":\"([^\"]+)\",\"status\":\"([^\"]+)\"")
                .matcher(body);
        while (matcher.find()) {
            if ("CONFIRMED".equals(matcher.group(2))) {
                confirmed.add(matcher.group(1));
            }
        }
        return confirmed;
    }

    private SeatCounts seatCounts(String show) {
        String body = get("/shows/" + show, adminToken);
        String counts = Json.object(body, "seatCounts");
        int seatsInList = Pattern.compile("\"label\":\"").matcher(body).results().toList().size();
        return new SeatCounts(Json.number(counts, "total"), Json.number(counts, "available"),
                Json.number(counts, "held"), Json.number(counts, "confirmed"), seatsInList);
    }

    private SeatCounts gaugeCounts(String show) {
        String scrape = get("/actuator/prometheus", null);
        int available = gauge(scrape, show, "available");
        int held = gauge(scrape, show, "held");
        int confirmed = gauge(scrape, show, "confirmed");
        return new SeatCounts(available + held + confirmed, available, held, confirmed,
                available + held + confirmed);
    }

    private static int gauge(String scrape, String show, String status) {
        Matcher matcher = Pattern.compile("seatres_seats\\{show_id=\"" + Pattern.quote(show)
                + "\",status=\"" + status + "\"\\}\\s+([0-9.eE+-]+)").matcher(scrape);
        return matcher.find() ? (int) Double.parseDouble(matcher.group(1)) : -1;
    }

    // ---------------------------------------------------------------- setup

    private String createShow(String suffix, int rows, int seatsPerRow, int perUserLimit) {
        String rowSpecs = IntStream.range(0, rows)
                .mapToObj(i -> "{\"row\":\"%s\",\"seatCount\":%d,\"pricePaise\":25000}"
                        .formatted(rowName(i), seatsPerRow))
                .collect(Collectors.joining(","));
        String body = """
                {"name":"burst-%s-%s","startsAt":"2026-12-01T19:30:00+05:30",\
                "perUserLimit":%d,"rows":[%s]}"""
                .formatted(runId, suffix, perUserLimit, rowSpecs);
        String response = post("/shows", adminToken, body, null);
        String id = Json.string(response, "id");
        if (id == null) {
            throw new IllegalStateException("could not create the show: " + response);
        }
        return id;
    }

    private List<String> mintUserTokens(int count) throws Exception {
        Semaphore gate = new Semaphore(Math.min(config.concurrency(), 100));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = IntStream.range(0, count)
                    .mapToObj(i -> executor.submit(() -> {
                        gate.acquire();
                        try {
                            return mintToken("burst-" + runId + "-u" + i, "USER");
                        } finally {
                            gate.release();
                        }
                    }))
                    .toList();
            List<String> tokens = new ArrayList<>(count);
            for (Future<String> future : futures) {
                tokens.add(future.get());
            }
            return tokens;
        }
    }

    private String mintToken(String subject, String role) {
        String body = "{\"sub\":\"%s\",\"roles\":[\"%s\"]}".formatted(subject, role);
        String adminKey = "ADMIN".equals(role) ? config.adminKey() : null;
        String response = post("/auth/token", null, body, adminKey);
        String token = Json.string(response, "accessToken");
        if (token == null) {
            throw new IllegalStateException("could not mint a token for " + subject + ": " + response);
        }
        return token;
    }

    // ---------------------------------------------------------------- plain HTTP

    private String get(String path, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofMillis(config.timeoutMs()))
                .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return send(request.build());
    }

    private String post(String path, String token, String body, String adminKey) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofMillis(config.timeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (adminKey != null) {
            request.header("X-Admin-Key", adminKey);
        }
        return send(request.build());
    }

    private String send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException e) {
            throw new IllegalStateException(request.method() + " " + request.uri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private URI uri(String path) {
        return URI.create(config.baseUrl() + path);
    }

    private static String newKey() {
        return java.util.UUID.randomUUID().toString();
    }

    private static String rowName(int index) {
        return index < 26
                ? String.valueOf((char) ('A' + index))
                : String.valueOf((char) ('A' + index / 26 - 1)) + (char) ('A' + index % 26);
    }

    private static List<String> showLabels(int rows, int seatsPerRow) {
        List<String> labels = new ArrayList<>(rows * seatsPerRow);
        for (int row = 0; row < rows; row++) {
            for (int seat = 1; seat <= seatsPerRow; seat++) {
                labels.add(rowName(row) + seat);
            }
        }
        return labels;
    }

    // ---------------------------------------------------------------- reporting

    private boolean report(String scenario, String show, int users, Run run, Checks checks) {
        SeatCounts counts = run.seatCounts();
        SeatCounts gauge = gaugeCounts(show);
        Logical logical = run.logical();

        boolean invariantHolds = counts.available() + counts.held() + counts.confirmed()
                == counts.total() && counts.total() == counts.seatsInList();
        checks.require("available + held + confirmed == total == seats.length", invariantHolds,
                counts.toString());
        checks.require("held is zero", counts.held() == 0, "held=" + counts.held());
        checks.require("no first-attempt 5xx", run.serverErrors().isEmpty(),
                run.serverErrors().toString());
        checks.require("the seats gauge matches the show read", gauge.sameTotals(counts),
                "gauge=" + gauge + " get=" + counts);
        if (run.unresolved() == 0) {
            checks.require("the server's confirmed seats are exactly the client's",
                    run.confirmedOnServer().equals(logical.confirmedSeats()),
                    "server=" + run.confirmedOnServer().size()
                            + " client=" + logical.confirmedSeats().size());
        } else {
            checks.require("the server's confirmed seats cover the client's",
                    run.confirmedOnServer().containsAll(logical.confirmedSeats()),
                    "server=" + run.confirmedOnServer().size()
                            + " client=" + logical.confirmedSeats().size());
        }

        System.out.printf("%n=== scenario: %s  show=%s  users=%d  concurrency=%d ===%n",
                scenario, show, users, config.concurrency());
        System.out.println("HTTP outcomes (first attempt)");
        line("201 Created", run.outcomes().getOrDefault("201", 0));
        line("409 SEAT_UNAVAILABLE", run.outcomes().getOrDefault("409 SEAT_UNAVAILABLE", 0));
        line("409 USER_LIMIT_EXCEEDED", run.outcomes().getOrDefault("409 USER_LIMIT_EXCEEDED", 0));
        line("409 IDEMPOTENCY_KEY_REUSED",
                run.outcomes().getOrDefault("409 IDEMPOTENCY_KEY_REUSED", 0));
        line("other 4xx", run.otherClientErrors().values().stream().mapToInt(Integer::intValue).sum(),
                run.otherClientErrors().toString());
        line("5xx", run.serverErrors().values().stream().mapToInt(Integer::intValue).sum(),
                run.serverErrors().toString());
        line("timeouts", run.failures(Failure.TIMEOUT));
        line("transport errors", run.failures(Failure.TRANSPORT));
        line("replayed (Idempotent-Replayed: true)", run.replayed());
        System.out.println("After ambiguity resolution");
        System.out.printf("  resolved ............................... %d   unresolved ... %d%n",
                run.resolvedCount(), run.unresolved());
        System.out.println("Logical outcome");
        line("distinct reservations (client)", logical.reservations().size());
        line("seats confirmed (client)", logical.confirmedSeats().size());
        line("seats confirmed (server GET)", run.confirmedOnServer().size());
        Latency latency = run.latency();
        System.out.printf("Latency (ms, first attempt, all responses)  p50=%d  p95=%d  p99=%d  max=%d%n",
                latency.p50(), latency.p95(), latency.p99(), latency.max());
        System.out.printf("Wall time .............................. %.1f s   achieved rate %.0f req/s%n",
                run.wall().toMillis() / 1000.0, run.rate());
        System.out.printf("Reconcile: total=%d available=%d held=%d confirmed=%d  invariant=%s%n",
                counts.total(), counts.available(), counts.held(), counts.confirmed(),
                invariantHolds ? "OK" : "BROKEN");
        checks.printFailures();
        System.out.println("ASSERTIONS: " + (checks.passed() ? "PASS" : "FAIL"));
        System.out.println(summaryJson(scenario, show, users, run, latency, counts, checks));
        return checks.passed();
    }

    private String summaryJson(String scenario, String show, int users, Run run, Latency latency,
            SeatCounts counts, Checks checks) {
        return ("SUMMARY {\"scenario\":\"%s\",\"runId\":\"%s\",\"baseUrl\":\"%s\",\"show\":\"%s\","
                + "\"users\":%d,\"concurrency\":%d,\"requests\":%d,\"created\":%d,"
                + "\"seatUnavailable\":%d,\"userLimitExceeded\":%d,\"keyReused\":%d,"
                + "\"other4xx\":%d,\"serverErrors\":%d,\"timeouts\":%d,\"transportErrors\":%d,"
                + "\"replayed\":%d,\"resolved\":%d,\"unresolved\":%d,"
                + "\"logicalReservations\":%d,\"clientSeats\":%d,\"serverSeats\":%d,"
                + "\"p50Ms\":%d,\"p95Ms\":%d,\"p99Ms\":%d,\"maxMs\":%d,"
                + "\"wallSeconds\":%.1f,\"ratePerSecond\":%.0f,"
                + "\"total\":%d,\"available\":%d,\"held\":%d,\"confirmed\":%d,\"pass\":%b}")
                .formatted(scenario, runId, config.baseUrl(), show, users, config.concurrency(),
                        run.responses().size(), run.outcomes().getOrDefault("201", 0),
                        run.outcomes().getOrDefault("409 SEAT_UNAVAILABLE", 0),
                        run.outcomes().getOrDefault("409 USER_LIMIT_EXCEEDED", 0),
                        run.outcomes().getOrDefault("409 IDEMPOTENCY_KEY_REUSED", 0),
                        run.otherClientErrors().values().stream().mapToInt(Integer::intValue).sum(),
                        run.serverErrors().values().stream().mapToInt(Integer::intValue).sum(),
                        run.failures(Failure.TIMEOUT), run.failures(Failure.TRANSPORT),
                        run.replayed(), run.resolvedCount(), run.unresolved(),
                        run.logical().reservations().size(), run.logical().confirmedSeats().size(),
                        run.confirmedOnServer().size(), latency.p50(), latency.p95(), latency.p99(),
                        latency.max(), run.wall().toMillis() / 1000.0, run.rate(), counts.total(),
                        counts.available(), counts.held(), counts.confirmed(), checks.passed());
    }

    private static void line(String label, int value) {
        line(label, value, null);
    }

    private static void line(String label, int value, String extra) {
        String dots = ".".repeat(Math.max(1, 38 - label.length()));
        System.out.printf("  %s %s %d%s%n", label, dots, value, extra == null ? "" : "   " + extra);
    }

    // ---------------------------------------------------------------- model

    private record Config(String baseUrl, String scenario, int concurrency, int requests, int users,
            int seats, int hotUsers, long timeoutMs, long seed, String adminKey) {

        static Config parse(String[] args) {
            Map<String, String> options = new HashMap<>();
            for (int i = 0; i < args.length; i += 2) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for " + args[i]);
                }
                options.put(args[i], args[i + 1]);
            }
            return new Config(
                    text(options, "--base-url", "BASE_URL", "http://localhost:8080"),
                    text(options, "--scenario", "SCENARIO", "all"),
                    number(options, "--concurrency", "CONCURRENCY", 200),
                    number(options, "--requests", "REQUESTS", 20_000),
                    number(options, "--users", "USERS", 2_000),
                    number(options, "--seats", "SEATS", 1_000),
                    number(options, "--hot-users", "HOT_USERS", 500),
                    number(options, "--timeout-ms", "TIMEOUT_MS", 120_000),
                    number(options, "--seed", "SEED", 42),
                    System.getenv().getOrDefault("ADMIN_KEY", DEV_ADMIN_KEY));
        }

        private static String text(Map<String, String> options, String flag, String env,
                String fallback) {
            if (options.containsKey(flag)) {
                return options.get(flag);
            }
            return System.getenv().getOrDefault(env, fallback);
        }

        private static int number(Map<String, String> options, String flag, String env,
                int fallback) {
            return Integer.parseInt(text(options, flag, env, String.valueOf(fallback)));
        }
    }

    private enum Failure { NONE, TIMEOUT, TRANSPORT }

    private record Call(int user, String token, String key, List<String> labels) {}

    private record Response(int status, String body, boolean replayed, long latencyMs,
            Failure failure) {

        /** A timeout, a transport error or a 503 leaves the outcome unknown to the client. */
        boolean ambiguous() {
            return failure != Failure.NONE || status == 503;
        }

        String outcome() {
            if (failure != Failure.NONE) {
                return failure.name().toLowerCase();
            }
            if (status == 201) {
                return "201";
            }
            String code = Json.string(body, "code");
            return status + (code == null ? "" : " " + code);
        }
    }

    private record SeatCounts(int total, int available, int held, int confirmed, int seatsInList) {

        boolean sameTotals(SeatCounts other) {
            return total == other.total && available == other.available && held == other.held
                    && confirmed == other.confirmed;
        }

        @Override
        public String toString() {
            return "total=%d available=%d held=%d confirmed=%d seats=%d"
                    .formatted(total, available, held, confirmed, seatsInList);
        }
    }

    private record Latency(long p50, long p95, long p99, long max) {

        static Latency of(List<Long> samples) {
            if (samples.isEmpty()) {
                return new Latency(0, 0, 0, 0);
            }
            List<Long> sorted = samples.stream().sorted().toList();
            return new Latency(at(sorted, 50), at(sorted, 95), at(sorted, 99),
                    sorted.get(sorted.size() - 1));
        }

        private static long at(List<Long> sorted, int percentile) {
            int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
            return sorted.get(Math.min(Math.max(index, 0), sorted.size() - 1));
        }
    }

    /** Distinct reservationIds, which is what makes a replay count once rather than twice. */
    private record Logical(Map<String, Set<String>> reservations, Set<String> confirmedSeats,
            Map<Integer, Set<String>> perUser) {}

    private record Run(List<Call> calls, List<Response> responses, List<Response> resolved,
            Duration wall, Set<String> confirmedOnServer, SeatCounts seatCounts) {

        Map<String, Integer> outcomes() {
            return tally(responses);
        }

        Map<String, Integer> resolvedOutcomes() {
            return tally(resolved);
        }

        private static Map<String, Integer> tally(List<Response> responses) {
            Map<String, Integer> tally = new LinkedHashMap<>();
            responses.forEach(response ->
                    tally.merge(response.outcome(), 1, Integer::sum));
            return tally;
        }

        Map<String, Integer> otherClientErrors() {
            return outcomes().entrySet().stream()
                    .filter(entry -> entry.getKey().matches("^4\\d\\d.*"))
                    .filter(entry -> !entry.getKey().startsWith("409 SEAT_UNAVAILABLE"))
                    .filter(entry -> !entry.getKey().startsWith("409 USER_LIMIT_EXCEEDED"))
                    .filter(entry -> !entry.getKey().startsWith("409 IDEMPOTENCY_KEY_REUSED"))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                            Integer::sum, LinkedHashMap::new));
        }

        Map<String, Integer> serverErrors() {
            return outcomes().entrySet().stream()
                    .filter(entry -> entry.getKey().matches("^5\\d\\d.*"))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                            Integer::sum, LinkedHashMap::new));
        }

        int failures(Failure failure) {
            return (int) responses.stream().filter(r -> r.failure() == failure).count();
        }

        int replayed() {
            return (int) responses.stream().filter(Response::replayed).count();
        }

        int resolvedCount() {
            return (int) IntStream.range(0, responses.size())
                    .filter(i -> responses.get(i).ambiguous() && !resolved.get(i).ambiguous())
                    .count();
        }

        int unresolved() {
            return (int) resolved.stream().filter(Response::ambiguous).count();
        }

        Latency latency() {
            return Latency.of(responses.stream()
                    .filter(response -> response.failure() == Failure.NONE)
                    .map(Response::latencyMs)
                    .toList());
        }

        double rate() {
            double seconds = wall.toMillis() / 1000.0;
            return seconds == 0 ? 0 : responses.size() / seconds;
        }

        Logical logical() {
            Map<String, Set<String>> reservations = new LinkedHashMap<>();
            Map<Integer, Set<String>> perUser = new LinkedHashMap<>();
            for (int i = 0; i < resolved.size(); i++) {
                Response response = resolved.get(i);
                if (response.status() != 201) {
                    continue;
                }
                String reservationId = Json.string(response.body(), "reservationId");
                if (reservationId == null || reservations.containsKey(reservationId)) {
                    continue;
                }
                Set<String> labels = Json.labels(response.body());
                reservations.put(reservationId, labels);
                perUser.computeIfAbsent(calls.get(i).user(), key -> new LinkedHashSet<>())
                        .addAll(labels);
            }
            Set<String> confirmed = new LinkedHashSet<>();
            reservations.values().forEach(confirmed::addAll);
            return new Logical(reservations, confirmed, perUser);
        }

        /** Two waves of one scenario reported as one, with the server state read once at the end. */
        Run mergedWith(Run other, Set<String> serverConfirmed, SeatCounts serverCounts) {
            List<Call> allCalls = new ArrayList<>(calls);
            allCalls.addAll(other.calls);
            List<Response> allResponses = new ArrayList<>(responses);
            allResponses.addAll(other.responses);
            List<Response> allResolved = new ArrayList<>(resolved);
            allResolved.addAll(other.resolved);
            return new Run(allCalls, allResponses, allResolved, wall.plus(other.wall),
                    serverConfirmed, serverCounts);
        }
    }

    private static final class Checks {

        private final List<String> failures = new ArrayList<>();

        void require(String what, boolean holds, String detail) {
            if (!holds) {
                failures.add(what + " (" + detail + ")");
            }
        }

        boolean passed() {
            return failures.isEmpty();
        }

        void printFailures() {
            failures.forEach(failure -> System.out.println("  FAILED: " + failure));
        }
    }

    /** Field reader for the few shapes this script needs, so it stays dependency-free. */
    private static final class Json {

        private Json() {
        }

        static String string(String body, String field) {
            Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"")
                    .matcher(body);
            return matcher.find() ? matcher.group(1) : null;
        }

        static int number(String body, String field) {
            Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(body);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
        }

        static String object(String body, String field) {
            Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\\{([^}]*)\\}")
                    .matcher(body);
            return matcher.find() ? matcher.group(1) : "";
        }

        static Set<String> labels(String body) {
            Set<String> labels = new LinkedHashSet<>();
            Matcher matcher = Pattern.compile("\"label\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
            while (matcher.find()) {
                labels.add(matcher.group(1));
            }
            return labels;
        }
    }
}
