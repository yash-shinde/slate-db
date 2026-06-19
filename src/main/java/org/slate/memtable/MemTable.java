package org.slate.memtable;

import org.slate.metrics.Counter;
import org.slate.metrics.MetricsRegistry;
import org.slate.metrics.Timer;

import java.util.*;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class MemTable {
    //64 MB set for flush to ss table
    //Max entries before flush ~170K (384-byte avg entry)
    public static final long DEFAULT_MAX_SIZE_BYTES = 64L * 1024 * 1024;

    public record Entry(byte[] value, boolean tombstone) {
        static Entry of(byte[] value) {
            return new Entry(value, false);
        }
        static Entry tombstoneEntry(){
            return new Entry(null,true);
        }
    }

    public record BytesKey(byte[] data) implements Comparable<BytesKey> {

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

        @Override
        public int compareTo(BytesKey other) {
            return Arrays.compare(this.data, other.data);
        }
    }

    private final SkipList<BytesKey, Entry> skipList;
    private final long maxSizeBytes;
    private long approxSizeBytes = 0;

    private final Counter putCounter;
    private final Counter getCounter;
    private final Counter deleteCounter;
    private final Counter tombstoneHitCounter;
    private final Timer   putTimer;
    private final Timer getTimer;

    public MemTable(MetricsRegistry metrics) {
        this(metrics, DEFAULT_MAX_SIZE_BYTES);
    }

    public MemTable(MetricsRegistry metrics, long maxSizeBytes) {
        Comparator<BytesKey> keyComparator = Comparator.naturalOrder();
        this.skipList = new SkipList<>(keyComparator);
        this.maxSizeBytes = maxSizeBytes;

        this.putCounter         = metrics.counter("memtable.put.count");
        this.getCounter         = metrics.counter("memtable.get.count");
        this.deleteCounter      = metrics.counter("memtable.delete.count");
        this.tombstoneHitCounter = metrics.counter("memtable.get.tombstone");
        this.putTimer            = metrics.timer("memtable.put.latency");
        this.getTimer            = metrics.timer("memtable.get.latency");
    }

    private void adjustSize(BytesKey key, Entry existing, int newValueLen) {
        long delta;
        if (existing == null) {
            delta = key.data().length + newValueLen + 64; // node overhead estimate
        } else {
            //old is being replaced
            //header remains the same even when tombstoning //only the internal values change
            int oldLen = existing.tombstone() ? 0 : existing.value().length;
            delta = newValueLen - oldLen;
        }
        approxSizeBytes += delta;
    }

    public void put(byte[] key, byte[] value) {
        long start = System.nanoTime();
        BytesKey k = new BytesKey(key);
        //returns value if already existing
        Entry existing = skipList.put(k, Entry.of(value));
        adjustSize(k, existing, value.length);
        putTimer.record(System.nanoTime() - start);
        putCounter.increment();
    }

    public void delete(byte[] key) {
        BytesKey k = new BytesKey(key);
        Entry existing = skipList.put(k, Entry.tombstoneEntry());
        adjustSize(k, existing, 0);
        deleteCounter.increment();
    }

    public Optional<byte[]> get(byte[] key) {
        long start = System.nanoTime();
        Entry entry = skipList.get(new BytesKey(key));
        getTimer.record(System.nanoTime() - start);
        getCounter.increment();

        if (entry == null) {
            return Optional.empty();
        }
        if (entry.tombstone()) {
            tombstoneHitCounter.increment();
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    /** Exposes the raw entry including tombstone state — needed by compaction. **/
    public Optional<Entry> getEntry(byte[] key) {
        return Optional.ofNullable(skipList.get(new BytesKey(key)));
    }

    public boolean shouldFlush() {
        return approxSizeBytes >= maxSizeBytes;
    }

    public long approxSizeBytes() {
        return approxSizeBytes;
    }

    public int entryCount() {
        return skipList.size();
    }

    /** Sorted forward iteration — used by SSTable flush in M3. */
    public Stream<SkipList.Node<BytesKey, Entry>> entries() {
        Iterator<SkipList.Node<BytesKey, Entry>> it = skipList.iterator();
        Spliterator<SkipList.Node<BytesKey, Entry>> spliterator =
                Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED | Spliterator.SORTED);
        return StreamSupport.stream(spliterator, false);
    }
}
