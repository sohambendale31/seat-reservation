package com.seatres.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/** Releases N tasks simultaneously through a start gate, with no sleep-based assertions. */
public final class Races {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private Races() {
    }

    public static <T> List<T> runTogether(int tasks, IntFunction<T> task) {
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(tasks);
        List<Future<T>> futures = new ArrayList<>(tasks);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < tasks; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!startGate.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start gate never opened");
                    }
                    return task.apply(index);
                }));
            }
            if (!ready.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("tasks never became ready");
            }
            startGate.countDown();

            List<T> results = new ArrayList<>(tasks);
            for (Future<T> future : futures) {
                results.add(future.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while racing", e);
        } catch (Exception e) {
            throw new IllegalStateException("a racing task failed", e);
        }
    }
}
