package com.demo.upimesh.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** Runs tasks on separate threads released at the same instant, so duplicate deliveries really race. */
final class Parallel {
    private Parallel() {}

    static <T> List<T> all(List<Callable<T>> tasks) {
        if (tasks.isEmpty()) return List.of();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(16, tasks.size()));
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> t : tasks) {
                futures.add(pool.submit(() -> { gate.await(); return t.call(); }));
            }
            gate.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) out.add(f.get(60, TimeUnit.SECONDS));
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for parallel tasks", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Parallel task failed", e);
        } finally {
            pool.shutdownNow();
        }
    }
}
