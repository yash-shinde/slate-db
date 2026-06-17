package org.slate.base;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slate.db.InMemoryDatabase;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class InMemoryDatabaseTest extends DatabaseTestBase{
    private InMemoryDatabase db;

    @BeforeEach
    void setup() {
        db = new InMemoryDatabase(metrics);
    }

    @Test
    @DisplayName("GET on empty store returns empty")
    void getOnEmptyStoreReturnsEmpty() {
        assertThat(db.get("key".getBytes())).isEmpty();
    }

    @Test
    @DisplayName("PUT then GET returns same value")
    void putThenGetReturnsSameValue() {
        db.put("key".getBytes(), "value".getBytes());
        Optional<byte[]> result = db.get("key".getBytes());
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("value".getBytes());
    }

    @Test
    @DisplayName("PUT overwrites existing key")
    void putOverwritesExistingKey() {
        db.put("key".getBytes(), "first".getBytes());
        db.put("key".getBytes(), "second".getBytes());
        assertThat(db.get("key".getBytes()).get())
                .isEqualTo("second".getBytes());
    }

    @Test
    @DisplayName("DELETE removes key")
    void deleteRemovesKey() {
        db.put("key".getBytes(), "value".getBytes());
        db.delete("key".getBytes());
        assertThat(db.get("key".getBytes())).isEmpty();
    }

    @Test
    @DisplayName("DELETE on non-existent key does not throw")
    void deleteOnNonExistentKeyDoesNotThrow() {
        assertThatCode(() -> db.delete("ghost".getBytes()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Different keys are independent")
    void differentKeysAreIndependent() {
        db.put("k1".getBytes(), "v1".getBytes());
        db.put("k2".getBytes(), "v2".getBytes());
        assertThat(db.get("k1".getBytes()).get()).isEqualTo("v1".getBytes());
        assertThat(db.get("k2".getBytes()).get()).isEqualTo("v2".getBytes());
    }

    @Test
    @DisplayName("Same key content is treated as same key")
    void sameKeyContentIsSameKey() {
        byte[] key1 = "key".getBytes();
        byte[] key2 = "key".getBytes(); // different object, same content
        db.put(key1, "value".getBytes());
        assertThat(db.get(key2)).isPresent();
    }

    @Test
    @DisplayName("Metrics are recorded correctly")
    void metricsAreRecordedCorrectly() {
        db.put("k".getBytes(), "v".getBytes());
        db.get("k".getBytes());   // hit
        db.get("x".getBytes());   // miss

        assertThat(metrics.counter("db.put.count").getCount()).isEqualTo(1);
        assertThat(metrics.counter("db.get.count").getCount()).isEqualTo(2);
        assertThat(metrics.counter("db.get.hit").getCount()).isEqualTo(1);
        assertThat(metrics.counter("db.get.miss").getCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("size reflects current entry count")
    void sizeReflectsEntryCount() {
        assertThat(db.size()).isZero();
        db.put("k1".getBytes(), "v1".getBytes());
        db.put("k2".getBytes(), "v2".getBytes());
        assertThat(db.size()).isEqualTo(2);
        db.delete("k1".getBytes());
        assertThat(db.size()).isEqualTo(1);
    }


}
