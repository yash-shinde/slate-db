package org.slate.benchmark;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.slate.db.InMemoryDatabase;
import org.slate.metrics.MetricsRegistry;

import java.io.IOException;
import java.util.Optional;
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


    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(BenchmarkRunner.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
