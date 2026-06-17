package org.slate.db;

import org.slate.api.Database;
import org.slate.metrics.Counter;
import org.slate.metrics.MetricsRegistry;
import org.slate.metrics.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryDatabase implements Database {

    private static final Logger log = LoggerFactory.getLogger(InMemoryDatabase.class);

    private final ConcurrentHashMap<BytesKey, byte[]> store = new ConcurrentHashMap<>();
    private final Counter putCounter;
    private final Counter getCounter;
    private final Counter deleteCounter;
    private final Counter hitCounter;
    private final Counter missCounter;
    private final Timer putTimer;
    private final Timer   getTimer;

    public InMemoryDatabase(MetricsRegistry metrics) {
        this.putCounter    = metrics.counter("db.put.count");
        this.getCounter    = metrics.counter("db.get.count");
        this.deleteCounter = metrics.counter("db.delete.count");
        this.hitCounter    = metrics.counter("db.get.hit");
        this.missCounter   = metrics.counter("db.get.miss");
        this.putTimer      = metrics.timer("db.put.latency");
        this.getTimer      = metrics.timer("db.get.latency");
        log.info("InMemoryDatabase initialized");
    }

    @Override
    public void put(byte[] key, byte[] value) {
        long start = System.nanoTime();
        store.put(new BytesKey(key),value);
        putTimer.record(System.nanoTime() - start);
        putCounter.increment();
    }

    @Override
    public Optional<byte[]> get(byte[] key) {
        long start = System.nanoTime();
        byte[] value = store.get(new BytesKey(key));
        getTimer.record(System.nanoTime() - start);
        getCounter.increment();
        if (value != null) {
            hitCounter.increment();
            return Optional.of(value);
        }
        missCounter.increment();
        return Optional.empty();
    }

    @Override
    public void delete(byte[] key) {
        store.remove(new BytesKey(key));
        deleteCounter.increment();
    }

    @Override
    public void close() throws IOException {
        log.info("InMemoryDatabase closed. Final size: {}", store.size());
        store.clear();
    }

    public int size() {
        return store.size();
    }

    // ─── Internal key wrapper ─────────────────────────────────────
    // byte[] does not implement equals/hashCode by value in Java.
    // Without this wrapper, two different byte[] with same content
    // would be treated as different keys in the HashMap.

    private record BytesKey(byte[] data) {

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BytesKey other)) return false;
            return Arrays.equals(data, other.data);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(data);
        }
    }
}
