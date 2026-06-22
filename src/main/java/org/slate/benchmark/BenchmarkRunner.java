package org.slate.benchmark;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.slate.db.InMemoryDatabase;
import org.slate.memtable.MemTable;
import org.slate.metrics.MetricsRegistry;
import org.slate.sstable.SSTableReader;
import org.slate.sstable.SSTableWriter;
import org.slate.wal.WALRecord;
import org.slate.wal.WALWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class BenchmarkRunner {

    // Placeholder — each milestone adds its own @Benchmark methods here
    // M0 adds: hashMapPut(), hashMapGet()
    // M1 adds: skipListPut(), skipListGet()
    // etc.
    @Benchmark
    public void baseline() {
        // intentionally empty — measures JMH overhead itself
        // every real benchmark compares against this
    }

    @State(Scope.Benchmark)
    public static class DbState {
        InMemoryDatabase db;
        byte[] key   = "benchmark-key".getBytes();
        byte[] value = "benchmark-value".getBytes();

        @Setup(Level.Trial)
        public void setup() {
            db = new InMemoryDatabase(new MetricsRegistry());
            db.put(key, value);
        }

        @TearDown(Level.Trial)
        public void teardown() throws IOException {
            db.close();
        }
    }

    @Benchmark
    public void hashMapPut(DbState state) {
        state.db.put(state.key, state.value);
    }

    @Benchmark
    public Optional<byte[]> hashMapGet(DbState state) {
        return state.db.get(state.key);
    }

    //M1 - memtable benchmarks
    @State(Scope.Benchmark)
    public static class MemTableState {
        MemTable memTable;
        byte[][] keys;
        byte[] value = "benchmark-value".getBytes();
        int counter = 0;

        @Setup(Level.Trial)
        public void setup() {
            memTable = new MemTable(new MetricsRegistry(), Long.MAX_VALUE); // disable flush threshold for this test
            keys = new byte[100_000][];
            Random random = new Random(42);
            for (int i = 0; i < keys.length; i++) {
                keys[i] = ("key-" + random.nextInt(1_000_000)).getBytes();
                memTable.put(keys[i], value);
            }
        }
    }

    @Benchmark
    public void memTablePut(MemTableState state) {
        byte[] key = state.keys[state.counter % state.keys.length];
        state.counter++;
        state.memTable.put(key, state.value);
    }

    @Benchmark
    public Optional<byte[]> memTableGet(MemTableState state) {
        byte[] key = state.keys[state.counter % state.keys.length];
        state.counter++;
        return state.memTable.get(key);
    }

    //M2 WAL table
    @State(Scope.Benchmark)
    public static class WALState {
        WALWriter writer;
        byte[] key = "benchmark-key".getBytes();
        byte[] value = "benchmark-value".getBytes();
        Path walFile;

        @Setup(Level.Trial)
        public void setup() throws IOException {
            walFile = Files.createTempFile("bench-wal-", ".wal");
            writer = new WALWriter(walFile);
        }

        @TearDown(Level.Trial)
        public void teardown() throws IOException {
            writer.close();
            Files.deleteIfExists(walFile);
        }
    }

    @Benchmark
    public void walAppend(WALState state) throws IOException {
        state.writer.append(WALRecord.put(state.key, state.value));
    }

    //M3 SSTable with Bloom Filters
    @State(Scope.Benchmark)
    public static class SSTableState {
        SSTableReader reader;
        byte[][] keys;
        Path sstableFile;
        int counter = 0;

        @Setup(Level.Trial)
        public void setup() throws IOException {
            sstableFile = Files.createTempFile("bench-sstable-", ".sst");
            Files.deleteIfExists(sstableFile); // SSTableWriter requires CREATE_NEW

            MemTable memTable = new MemTable(new MetricsRegistry());
            Random random = new Random(42);
            keys = new byte[10_000][];

            for (int i = 0; i < keys.length; i++) {
                keys[i] = ("key-" + random.nextInt(1_000_000) + "-" + i).getBytes();
                memTable.put(keys[i], "benchmark-value".getBytes());
            }

            new SSTableWriter().flush(memTable, sstableFile);
            reader = new SSTableReader(sstableFile);
        }

        @TearDown(Level.Trial)
        public void teardown() throws IOException {
            reader.close();
            Files.deleteIfExists(sstableFile);
        }
    }

    @Benchmark
    public Optional<SSTableReader.Value> sstableGet(SSTableState state) throws IOException {
        byte[] key = state.keys[state.counter % state.keys.length];
        state.counter++;
        return state.reader.get(key);
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(BenchmarkRunner.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
