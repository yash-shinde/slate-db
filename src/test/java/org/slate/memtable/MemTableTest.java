package org.slate.memtable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slate.base.DatabaseTestBase;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class MemTableTest extends DatabaseTestBase {
    private MemTable memTable;

    @BeforeEach
    void setup() {
        memTable = new MemTable(metrics);
    }

    @Test
    @DisplayName("GET on empty memtable returns empty")
    void getOnEmptyMemTableReturnsEmpty() {
        assertThat(memTable.get("key".getBytes())).isEmpty();
    }

    @Test
    @DisplayName("PUT then GET returns same value")
    void putThenGetReturnsSameValue() {
        memTable.put("key".getBytes(), "value".getBytes());
        assertThat(memTable.get("key".getBytes())).contains("value".getBytes());
    }

    @Test
    @DisplayName("DELETE then GET returns empty (tombstone hides value)")
    void deleteThenGetReturnsEmpty() {
        memTable.put("key".getBytes(), "value".getBytes());
        memTable.delete("key".getBytes());
        assertThat(memTable.get("key".getBytes())).isEmpty();
    }

    @Test
    @DisplayName("DELETE on non-existent key still creates tombstone")
    void deleteOnNonExistentKeyCreatesTombstone() {
        memTable.delete("ghost".getBytes());
        // entry exists internally as tombstone, even though never PUT
        assertThat(memTable.getEntry("ghost".getBytes())).isPresent();
        assertThat(memTable.getEntry("ghost".getBytes()).get().tombstone()).isTrue();
    }

    @Test
    @DisplayName("getEntry distinguishes tombstone from never-existed")
    void getEntryDistinguishesTombstoneFromMissing() {
        memTable.put("a".getBytes(), "1".getBytes());
        memTable.delete("a".getBytes());

        // "a" was deleted — entry present, tombstoned
        assertThat(memTable.getEntry("a".getBytes())).isPresent();
        assertThat(memTable.getEntry("a".getBytes()).get().tombstone()).isTrue();

        // "b" never existed — no entry at all
        assertThat(memTable.getEntry("b".getBytes())).isEmpty();
    }

    @Test
    @DisplayName("PUT after DELETE resurrects the key")
    void putAfterDeleteResurrectsKey() {
        memTable.put("key".getBytes(), "v1".getBytes());
        memTable.delete("key".getBytes());
        memTable.put("key".getBytes(), "v2".getBytes());

        assertThat(memTable.get("key".getBytes())).contains("v2".getBytes());
    }

    @Test
    @DisplayName("entries() returns keys in sorted byte order")
    void entriesReturnsSortedByteOrder() {
        memTable.put("banana".getBytes(), "1".getBytes());
        memTable.put("apple".getBytes(), "2".getBytes());
        memTable.put("cherry".getBytes(), "3".getBytes());

        List<String> keys = memTable.entries()
                .map(node -> new String(node.getKey().data()))
                .collect(Collectors.toList());

        assertThat(keys).containsExactly("apple", "banana", "cherry");
    }

    @Test
    @DisplayName("shouldFlush is false below threshold")
    void shouldFlushIsFalseBelowThreshold() {
        memTable.put("key".getBytes(), "value".getBytes());
        assertThat(memTable.shouldFlush()).isFalse();
    }

    @Test
    @DisplayName("shouldFlush is true above threshold")
    void shouldFlushIsTrueAboveThreshold() {
        MemTable smallMemTable = new MemTable(metrics, 100); // 100 byte threshold
        smallMemTable.put("key".getBytes(), new byte[200]); // exceeds threshold
        assertThat(smallMemTable.shouldFlush()).isTrue();
    }

    @Test
    @DisplayName("entryCount reflects distinct keys including tombstones")
    void entryCountReflectsDistinctKeys() {
        memTable.put("a".getBytes(), "1".getBytes());
        memTable.put("b".getBytes(), "2".getBytes());
        memTable.delete("a".getBytes()); // overwrites, not a new entry

        assertThat(memTable.entryCount()).isEqualTo(2);
    }
}
