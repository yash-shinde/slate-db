package org.slate.metrics;

import java.util.concurrent.atomic.AtomicLong;

public class Timer {
    private final String name;
    private final AtomicLong totalNanos = new AtomicLong(0);
    private final AtomicLong callCount  = new AtomicLong(0);

    public Timer(String name) {
        this.name = name;
    }

    public void record(long nanos) {
        totalNanos.addAndGet(nanos);
        callCount.incrementAndGet();
    }

    public long getTotalNanos()  { return totalNanos.get(); }
    public long getCallCount()   { return callCount.get(); }

    public double getMeanMicros() {
        long calls = callCount.get();
        if (calls == 0) return 0.0;
        return (totalNanos.get() / 1_000.0) / calls;
    }

    public void reset() {
        totalNanos.set(0);
        callCount.set(0);
    }

    public String getName() { return name; }
}
