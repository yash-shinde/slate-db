package org.slate.metrics;

import java.util.concurrent.ConcurrentHashMap;

public class MetricsRegistry {
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer>   timers   = new ConcurrentHashMap<>();

    public Counter counter(String name) {
        return counters.computeIfAbsent(name, Counter::new);
    }

    public Timer timer(String name) {
        return timers.computeIfAbsent(name, Timer::new);
    }

    public void printReport() {
        System.out.println("\n=== Metrics Report ===");
        counters.forEach((name, c) ->
                System.out.printf("  [counter] %s = %d%n", name, c.getCount()));
        timers.forEach((name, t) ->
                System.out.printf("  [timer]   %s — calls=%d mean=%.2f µs%n",
                        name, t.getCallCount(), t.getMeanMicros()));
        System.out.println("======================\n");
    }

    public void resetAll() {
        counters.values().forEach(Counter::reset);
        timers.values().forEach(Timer::reset);
    }
}
