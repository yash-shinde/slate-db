package org.slate.bloom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

public class BloomFilterTest {
    @Test
    @DisplayName("inserted key always returns mightContain true (no false negatives)")
    void insertedKeyNeverFalseNegative() {
        BloomFilter filter = new BloomFilter(100, 0.01);
        filter.add("hello".getBytes());
        assertThat(filter.mightContain("hello".getBytes())).isTrue();
    }

    @Test
    @DisplayName("empty filter says everything is definitely not present")
    void emptyFilterSaysNotPresent() {
        BloomFilter filter = new BloomFilter(100, 0.01);
        assertThat(filter.mightContain("anything".getBytes())).isFalse();
    }

    @Test
    @DisplayName("single element filter correctly identifies that element")
    void singleElementFilterIdentifiesElement() {
        BloomFilter filter = new BloomFilter(1, 0.01);
        filter.add("only-key".getBytes());
        assertThat(filter.mightContain("only-key".getBytes())).isTrue();
    }

    @Test
    @DisplayName("serialize then deserialize preserves mightContain behavior")
    void serializeRoundTripPreservesBehavior() {
        BloomFilter original = new BloomFilter(100, 0.01);
        original.add("a".getBytes());
        original.add("b".getBytes());
        original.add("c".getBytes());

        byte[] bytes = original.serialize();
        BloomFilter restored = BloomFilter.deserialize(bytes);

        assertThat(restored.mightContain("a".getBytes())).isTrue();
        assertThat(restored.mightContain("b".getBytes())).isTrue();
        assertThat(restored.mightContain("c".getBytes())).isTrue();
    }

    @Test
    @DisplayName("serialize then deserialize preserves bit set size and hash function count")
    void serializeRoundTripPreservesConfig() {
        BloomFilter original = new BloomFilter(500, 0.01);
        byte[] bytes = original.serialize();
        BloomFilter restored = BloomFilter.deserialize(bytes);

        assertThat(restored.getBitSetSize()).isEqualTo(original.getBitSetSize());
        assertThat(restored.getNumHashFunctions()).isEqualTo(original.getNumHashFunctions());
    }

    // ─── Property-based: no false negatives, ever, regardless of input ─────

    @Property
    @DisplayName("every inserted key is always found, regardless of how many keys are inserted")
    void allInsertedKeysAreFound(@ForAll @IntRange(min = 1, max = 1000) int count) {
        BloomFilter filter = new BloomFilter(count, 0.01);
        Random random = new Random(42);
        Set<String> inserted = new HashSet<>();

        for (int i = 0; i < count; i++) {
            String key = "key-" + random.nextInt(1_000_000) + "-" + i; // ensure uniqueness
            filter.add(key.getBytes());
            inserted.add(key);
        }

        for (String key : inserted) {
            assertThat(filter.mightContain(key.getBytes()))
                    .as("Key '%s' was inserted but mightContain returned false — false negative!", key)
                    .isTrue();
        }
    }

    // ─── Statistical: false positive rate stays within reasonable bounds ───

    @Test
    @DisplayName("false positive rate stays close to configured target (1%) at scale")
    void falsePositiveRateStaysNearTarget() {
        int n = 10_000;
        double targetFpr = 0.01;
        BloomFilter filter = new BloomFilter(n, targetFpr);

        Random random = new Random(7);
        Set<String> inserted = new HashSet<>();
        for (int i = 0; i < n; i++) {
            String key = "inserted-" + i;
            filter.add(key.getBytes());
            inserted.add(key);
        }

        // test against a large set of keys NEVER inserted
        int falsePositives = 0;
        int trials = 50_000;
        for (int i = 0; i < trials; i++) {
            String key = "never-inserted-" + i;
            if (filter.mightContain(key.getBytes())) {
                falsePositives++;
            }
        }

        double observedFpr = (double) falsePositives / trials;

        // allow generous margin — statistical tests need tolerance, not exact equality
        assertThat(observedFpr)
                .as("Observed FPR %.4f should be reasonably close to target %.4f", observedFpr, targetFpr)
                .isLessThan(targetFpr * 3); // within 3x target is a healthy margin for this kind of test
    }
}
