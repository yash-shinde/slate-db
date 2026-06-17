package org.slate.metrics;

import java.util.concurrent.atomic.AtomicLong;

public class Counter {
    private final String name;
    private final AtomicLong count = new AtomicLong(0);

    public Counter(String name) {
        this.name = name;
    }

    public void increment() {
        count.incrementAndGet();
    }

    public void increment(long amount) {
        count.addAndGet(amount);
    }

    public long getCount() {
        return count.get();
    }

    public void reset() {
        count.set(0);
    }

    public String getName() {
        return name;
    }
}
