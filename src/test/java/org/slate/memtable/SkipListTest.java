package org.slate.memtable;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

public class SkipListTest {
    private SkipList<Integer, String> list;

    @BeforeEach
    void setup() {
        Comparator<Integer> cmp = Comparator.naturalOrder();
        list = new SkipList<>(cmp);
    }

    @Test
    @DisplayName("GET on empty list returns null")
    void getOnEmptyListReturnsNull() {
        assertThat(list.get(1)).isNull();
    }

    @Test
    @DisplayName("PUT then GET returns same value")
    void putThenGetReturnsSameValue() {
        list.put(5, "five");
        assertThat(list.get(5)).isEqualTo("five");
    }

    @Test
    @DisplayName("PUT overwrites existing key without creating duplicate")
    void putOverwritesExistingKey() {
        list.put(5, "first");
        list.put(5, "second");
        assertThat(list.get(5)).isEqualTo("second");
        assertThat(list.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("size reflects number of distinct keys")
    void sizeReflectsDistinctKeys() {
        list.put(1, "a");
        list.put(2, "b");
        list.put(3, "c");
        assertThat(list.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("remove deletes key physically")
    void removeDeletesKey() {
        list.put(5, "five");
        boolean removed = list.remove(5);
        assertThat(removed).isTrue();
        assertThat(list.get(5)).isNull();
        assertThat(list.size()).isZero();
    }

    @Test
    @DisplayName("remove on non-existent key returns false")
    void removeOnNonExistentKeyReturnsFalse() {
        assertThat(list.remove(99)).isFalse();
    }

    @Test
    @DisplayName("iterator returns keys in sorted order")
    void iteratorReturnsSortedOrder() {
        list.put(5, "five");
        list.put(1, "one");
        list.put(3, "three");
        list.put(2, "two");
        list.put(4, "four");

        List<Integer> keys = new ArrayList<>();
        list.iterator().forEachRemaining(node -> keys.add(node.getKey()));

        assertThat(keys).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    @DisplayName("iterator on empty list has no elements")
    void iteratorOnEmptyListHasNoElements() {
        assertThat(list.iterator().hasNext()).isFalse();
    }

    @Test
    @DisplayName("containsKey reflects presence correctly")
    void containsKeyReflectsPresence() {
        list.put(1, "a");
        assertThat(list.containsKey(1)).isTrue();
        assertThat(list.containsKey(2)).isFalse();
    }

    // ─── Property-based test — the real correctness guarantee ─────

    @Property
    @DisplayName("inserting N random keys always yields sorted iteration order")
    void insertedKeysAlwaysIterateSorted(@ForAll @IntRange(min = 1, max = 500) int count) {
        Comparator<Integer> cmp = Comparator.naturalOrder();
        SkipList<Integer, Integer> sl = new SkipList<>(cmp);
        Set<Integer> inserted = new HashSet<>();
        Random random = new Random(42);

        for (int i = 0; i < count; i++) {
            int key = random.nextInt(10_000);
            sl.put(key, key);
            inserted.add(key);
        }

        List<Integer> iterated = new ArrayList<>();
        sl.iterator().forEachRemaining(node -> iterated.add(node.getKey()));

        List<Integer> expectedSorted = new ArrayList<>(inserted);
        Collections.sort(expectedSorted);

        assertThat(iterated).isEqualTo(expectedSorted);
    }

    @Property
    @DisplayName("every inserted key is retrievable")
    void everyInsertedKeyIsRetrievable(@ForAll @IntRange(min = 1, max = 300) int count) {
        Comparator<Integer> cmp = Comparator.naturalOrder();
        SkipList<Integer, String> sl = new SkipList<>(cmp);
        Random random = new Random(7);
        Map<Integer, String> expected = new HashMap<>();

        for (int i = 0; i < count; i++) {
            int key = random.nextInt(5_000);
            String value = "v" + key;
            sl.put(key, value);
            expected.put(key, value);
        }

        for (Map.Entry<Integer, String> e : expected.entrySet()) {
            assertThat(sl.get(e.getKey())).isEqualTo(e.getValue());
        }
    }
}
