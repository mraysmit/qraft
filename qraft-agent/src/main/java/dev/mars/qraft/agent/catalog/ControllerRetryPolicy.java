package dev.mars.qraft.agent.catalog;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;

/** Capped exponential retry delay with equal jitter and cancellable waits. */
public final class ControllerRetryPolicy {
    private final long minimumMillis;
    private final long maximumMillis;
    private final DoubleSupplier random;

    public ControllerRetryPolicy(long minimumMillis, long maximumMillis, DoubleSupplier random) {
        if (minimumMillis < 1 || maximumMillis < minimumMillis) {
            throw new IllegalArgumentException("Retry bounds must be positive and minimum must not exceed maximum");
        }
        this.minimumMillis = minimumMillis;
        this.maximumMillis = maximumMillis;
        this.random = Objects.requireNonNull(random, "random");
    }

    public long delayMillis(int retryNumber) {
        if (retryNumber < 0) throw new IllegalArgumentException("retryNumber must not be negative");
        long exponential = minimumMillis;
        for (int i = 0; i < retryNumber && exponential < maximumMillis; i++) {
            exponential = exponential > maximumMillis / 2 ? maximumMillis : exponential * 2;
        }
        long capped = Math.min(exponential, maximumMillis);
        long floor = Math.max(1, capped / 2);
        double sample = random.getAsDouble();
        if (sample < 0.0 || sample >= 1.0) throw new IllegalStateException("Random sample must be in [0, 1)");
        return floor + (long) Math.floor(sample * (capped - floor + 1));
    }

    public CompletableFuture<Void> delay(ScheduledExecutorService scheduler, int retryNumber) {
        Objects.requireNonNull(scheduler, "scheduler");
        CompletableFuture<Void> completion = new CompletableFuture<>();
        var scheduled = scheduler.schedule(() -> completion.complete(null),
                delayMillis(retryNumber), TimeUnit.MILLISECONDS);
        completion.whenComplete((ignored, failure) -> {
            if (completion.isCancelled()) scheduled.cancel(false);
        });
        return completion;
    }
}
