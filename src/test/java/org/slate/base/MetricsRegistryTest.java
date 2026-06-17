package org.slate.base;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slate.metrics.Counter;
import org.slate.metrics.Timer;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsRegistryTest extends DatabaseTestBase {

    // ─── Counter tests ───────────────────────────────────────────

    @Test
    @DisplayName("Counter increments by one")
    void counterIncrementsByOne() {
        Counter c = metrics.counter("writes");
        c.increment();
        c.increment();
        assertThat(c.getCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Counter increments by arbitrary amount")
    void counterIncrementsByAmount() {
        Counter c = metrics.counter("writes");
        c.increment(10);
        c.increment(5);
        assertThat(c.getCount()).isEqualTo(15);
    }

    @Test
    @DisplayName("Counter resets to zero")
    void counterResetsToZero() {
        Counter c = metrics.counter("writes");
        c.increment(100);
        c.reset();
        assertThat(c.getCount()).isZero();
    }

    @Test
    @DisplayName("Same counter name returns same instance")
    void sameNameReturnsSameInstance() {
        Counter c1 = metrics.counter("writes");
        Counter c2 = metrics.counter("writes");
        assertThat(c1).isSameAs(c2);
    }

    // ─── Timer tests ─────────────────────────────────────────────

    @Test
    @DisplayName("Timer records call count and total nanos correctly")
    void timerRecordsCorrectly() {
        Timer t = metrics.timer("read-latency");
        t.record(1_000);
        t.record(2_000);
        assertThat(t.getCallCount()).isEqualTo(2);
        assertThat(t.getTotalNanos()).isEqualTo(3_000);
    }

    @Test
    @DisplayName("Timer computes mean latency correctly")
    void timerComputesMeanMicros() {
        Timer t = metrics.timer("read-latency");
        t.record(1_000_000); // 1ms = 1000µs
        t.record(3_000_000); // 3ms = 3000µs
        assertThat(t.getMeanMicros()).isEqualTo(2000.0);
    }

    @Test
    @DisplayName("Timer mean is zero when no calls recorded")
    void timerMeanIsZeroWithNoCalls() {
        Timer t = metrics.timer("read-latency");
        assertThat(t.getMeanMicros()).isZero();
    }

    @Test
    @DisplayName("Timer resets correctly")
    void timerResetsCorrectly() {
        Timer t = metrics.timer("read-latency");
        t.record(5_000_000);
        t.reset();
        assertThat(t.getCallCount()).isZero();
        assertThat(t.getTotalNanos()).isZero();
        assertThat(t.getMeanMicros()).isZero();
    }

    // ─── Registry-level tests ─────────────────────────────────────

    @Test
    @DisplayName("resetAll zeroes all counters and timers")
    void resetAllZeroesEverything() {
        metrics.counter("writes").increment(50);
        metrics.counter("reads").increment(100);
        metrics.timer("latency").record(9_000_000);

        metrics.resetAll();

        assertThat(metrics.counter("writes").getCount()).isZero();
        assertThat(metrics.counter("reads").getCount()).isZero();
        assertThat(metrics.timer("latency").getCallCount()).isZero();
    }
}
