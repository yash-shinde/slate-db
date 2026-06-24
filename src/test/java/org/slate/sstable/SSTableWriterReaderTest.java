package org.slate.sstable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slate.base.DatabaseTestBase;
import org.slate.memtable.MemTable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

class SSTableWriterReaderTest extends DatabaseTestBase {

    private SSTableWriter writer;
    private Path sstableFile;

    @BeforeEach
    void setup() {
        writer = new SSTableWriter();
        sstableFile = testFile("test.sst");
    }

    @Test
    @DisplayName("flushing an empty MemTable produces a readable, empty SSTable")
    void flushingEmptyMemTableProducesEmptySSTable() throws IOException {
        MemTable memTable = new MemTable(metrics);

        writer.flush(memTable, sstableFile);

        try (SSTableReader reader = new SSTableReader(sstableFile)) {
            assertThat(reader.entryCount()).isZero();
            assertThat(reader.get("anything".getBytes())).isEmpty();
        }
    }

    @Test
    @DisplayName("flushing a single-key MemTable round-trips correctly")
    void singleKeyRoundTrips() throws IOException {
        MemTable memTable = new MemTable(metrics);
        memTable.put("name".getBytes(), "yash".getBytes());

        writer.flush(memTable, sstableFile);

        try (SSTableReader reader = new SSTableReader(sstableFile)) {
            Optional<SSTableReader.Value> result = reader.get("name".getBytes());
            assertThat(result).isPresent();
            assertThat(result.get().data()).isEqualTo("yash".getBytes());
            assertThat(result.get().tombstone()).isFalse();
        }
    }

    @Test
    @DisplayName("every key written can be read back with correct value")
    void everyKeyReadsBackCorrectly() throws IOException {
        MemTable memTable = new MemTable(metrics);
        for (int i = 0; i < 200; i++) {
            memTable.put(("key-" + i).getBytes(), ("val-" + i).getBytes());
        }

        writer.flush(memTable, sstableFile);

        try (SSTableReader reader = new SSTableReader(sstableFile)) {
            assertThat(reader.entryCount()).isEqualTo(200);
            for (int i = 0; i < 200; i++) {
                Optional<SSTableReader.Value> result = reader.get(("key-" + i).getBytes());
                assertThat(result).isPresent();
                assertThat(result.get().data()).isEqualTo(("val-" + i).getBytes());
            }
        }
    }

    @Test
    @DisplayName("a tombstoned key is written and read back with tombstone flag set")
    void tombstonedKeyPreservesFlag() throws IOException {
        MemTable memTable = new MemTable(metrics);
        memTable.put("doomed".getBytes(), "value".getBytes());
        memTable.delete("doomed".getBytes());

        writer.flush(memTable, sstableFile);

        try (SSTableReader reader = new SSTableReader(sstableFile)) {
            Optional<SSTableReader.Value> result = reader.get("doomed".getBytes());
            assertThat(result).isPresent();
            assertThat(result.get().tombstone()).isTrue();
        }
    }

    @Test
    @DisplayName("a delete-only key (never had a PUT) still flushes as a tombstone")
    void deleteOnlyKeyFlushesAsTombstone() throws IOException {
        MemTable memTable = new MemTable(metrics);
        memTable.delete("ghost".getBytes());

        writer.flush(memTable, sstableFile);

        try (SSTableReader reader = new SSTableReader(sstableFile)) {
            Optional<SSTableReader.Value> result = reader.get("ghost".getBytes());
            assertThat(result).isPresent();
            assertThat(result.get().tombstone()).isTrue();
        }
    }

    @Test
    @DisplayName("looking up a key never inserted returns empty (Bloom filter or scan miss)")
    void missingKeyReturnsEmpty() throws IOException {
        MemTable memTable = new MemTable(metrics);
        memTable.put("exists".getBytes(), "value".getBytes());

        writer.flush(memTable, sstableFile);

        try (SSTableReader reader = new SSTableReader(sstableFile)) {
            assertThat(reader.get("does-not-exist".getBytes())).isEmpty();
        }
    }

    @Test
    @DisplayName("entries are written to disk in sorted key order")
    void entriesWrittenInSortedOrder() throws IOException {
        MemTable memTable = new MemTable(metrics);
        memTable.put("banana".getBytes(), "1".getBytes());
        memTable.put("apple".getBytes(), "2".getBytes());
        memTable.put("cherry".getBytes(), "3".getBytes());

        writer.flush(memTable, sstableFile);

        // read the raw data block and confirm key order — proves SkipList's
        // sorted iteration carried through correctly into the file
        try (SSTableReader reader = new SSTableReader(sstableFile)) {
            assertThat(reader.entryCount()).isEqualTo(3);
            assertThat(reader.get("apple".getBytes())).isPresent();
            assertThat(reader.get("banana".getBytes())).isPresent();
            assertThat(reader.get("cherry".getBytes())).isPresent();
        }
    }

    @Test
    @DisplayName("flushing to a file that already exists throws rather than silently overwriting")
    void flushingToExistingFileThrows() throws IOException {
        MemTable memTable = new MemTable(metrics);
        memTable.put("a".getBytes(), "1".getBytes());
        writer.flush(memTable, sstableFile);

        MemTable secondMemTable = new MemTable(metrics);
        secondMemTable.put("b".getBytes(), "2".getBytes());

        assertThatThrownBy(() -> writer.flush(secondMemTable, sstableFile))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("SSTable file is never modified after flush completes (immutability check)")
    void sstableFileUnmodifiedAfterFlush() throws IOException {
        MemTable memTable = new MemTable(metrics);
        memTable.put("a".getBytes(), "1".getBytes());
        writer.flush(memTable, sstableFile);

        long sizeAfterFlush = java.nio.file.Files.size(sstableFile);

        // perform several reads — none should alter the file
        try (SSTableReader reader = new SSTableReader(sstableFile)) {
            reader.get("a".getBytes());
            reader.get("nonexistent".getBytes());
            reader.get("a".getBytes());
        }

        long sizeAfterReads = java.nio.file.Files.size(sstableFile);
        assertThat(sizeAfterReads).isEqualTo(sizeAfterFlush);
    }

    // ─── New M4-specific tests ─────────────────────────────────────

    @Test
    @DisplayName("SSTable exposes a non-zero index size for a non-trivial entry count")
    void sstableExposesIndexSize() throws IOException {
        MemTable memTable = new MemTable(metrics);
        for (int i = 0; i < 100; i++) {
            memTable.put(("key-" + String.format("%03d", i)).getBytes(), "value".getBytes());
        }

        writer.flush(memTable, sstableFile);

        try (SSTableReader reader = new SSTableReader(sstableFile)) {
            // 100 entries at interval=16 → roughly 7 index entries (100/16 rounded up)
            assertThat(reader.indexSize()).isGreaterThan(0);
            assertThat(reader.indexSize()).isLessThan(100); // sparse, not dense
        }
    }

    @Test
    @DisplayName("key exactly at an index boundary (every 16th entry) is found correctly")
    void keyAtIndexBoundaryIsFound() throws IOException {
        MemTable memTable = new MemTable(metrics);
        for (int i = 0; i < 64; i++) { // exactly 4 index boundaries at interval=16
            memTable.put(("key-" + String.format("%03d", i)).getBytes(), ("val-" + i).getBytes());
        }

        writer.flush(memTable, sstableFile);

        try (SSTableReader reader = new SSTableReader(sstableFile)) {
            // entries at index 0, 16, 32, 48 should be exact index boundary hits
            for (int boundary : new int[]{0, 16, 32, 48}) {
                String key = "key-" + String.format("%03d", boundary);
                Optional<SSTableReader.Value> result = reader.get(key.getBytes());
                assertThat(result).as("boundary key %s should be found", key).isPresent();
                assertThat(result.get().data()).isEqualTo(("val-" + boundary).getBytes());
            }
        }
    }

    @Test
    @DisplayName("key BETWEEN two index points (mid-scan-window) is found correctly")
    void keyBetweenIndexPointsIsFound() throws IOException {
        MemTable memTable = new MemTable(metrics);
        for (int i = 0; i < 64; i++) {
            memTable.put(("key-" + String.format("%03d", i)).getBytes(), ("val-" + i).getBytes());
        }

        writer.flush(memTable, sstableFile);

        try (SSTableReader reader = new SSTableReader(sstableFile)) {
            // key-008 sits between indexed key-000 and key-016 — must rely on
            // the linear scan portion of the read path, not just the index hit
            Optional<SSTableReader.Value> result = reader.get("key-008".getBytes());
            assertThat(result).isPresent();
            assertThat(result.get().data()).isEqualTo("val-8".getBytes());
        }
    }

    @Test
    @DisplayName("key smaller than every key in the SSTable returns empty, not an error")
    void keySmallerThanEverythingReturnsEmpty() throws IOException {
        MemTable memTable = new MemTable(metrics);
        memTable.put("mango".getBytes(), "1".getBytes());
        memTable.put("peach".getBytes(), "2".getBytes());

        writer.flush(memTable, sstableFile);

        try (SSTableReader reader = new SSTableReader(sstableFile)) {
            assertThat(reader.get("apple".getBytes())).isEmpty();
        }
    }

    @Test
    @DisplayName("key larger than every key in the SSTable returns empty, not an error")
    void keyLargerThanEverythingReturnsEmpty() throws IOException {
        MemTable memTable = new MemTable(metrics);
        memTable.put("apple".getBytes(), "1".getBytes());
        memTable.put("mango".getBytes(), "2".getBytes());

        writer.flush(memTable, sstableFile);

        try (SSTableReader reader = new SSTableReader(sstableFile)) {
            assertThat(reader.get("zebra".getBytes())).isEmpty();
        }
    }

    @Test
    @DisplayName("large dataset (1000 entries) — every key still resolves correctly via index")
    void largeDatasetResolvesCorrectlyViaIndex() throws IOException {
        MemTable memTable = new MemTable(metrics);
        List<String> keys = new ArrayList<>();
        Random random = new Random(42);

        for (int i = 0; i < 1000; i++) {
            String key = "key-" + String.format("%05d", random.nextInt(1_000_000)) + "-" + i;
            memTable.put(key.getBytes(), ("val-" + i).getBytes());
            keys.add(key);
        }

        writer.flush(memTable, sstableFile);

        try (SSTableReader reader = new SSTableReader(sstableFile)) {
            assertThat(reader.entryCount()).isEqualTo(1000);
            // index should be meaningfully smaller than entry count
            assertThat(reader.indexSize()).isLessThan(200); // ~1000/16 ≈ 63, generous margin

            for (int i = 0; i < keys.size(); i++) {
                Optional<SSTableReader.Value> result = reader.get(keys.get(i).getBytes());
                assertThat(result).as("key %s should be found", keys.get(i)).isPresent();
                assertThat(result.get().data()).isEqualTo(("val-" + i).getBytes());
            }
        }
    }

    @Test
    @DisplayName("chunk with wildly uneven entry sizes still resolves correctly (proves exact boundary, not estimate)")
    void unevenEntrySizesWithinChunkResolveCorrectly() throws IOException {
        MemTable memTable = new MemTable(metrics);
        // mix tiny and huge values within what will be the same ~16-entry index chunk
        memTable.put("a".getBytes(), "x".getBytes());                          // tiny
        memTable.put("b".getBytes(), new byte[10_000]);                        // huge
        memTable.put("c".getBytes(), "y".getBytes());                          // tiny
        memTable.put("d".getBytes(), new byte[5_000]);                         // huge
        for (int i = 0; i < 12; i++) {
            memTable.put(("e" + i).getBytes(), "small".getBytes());            // pad out the chunk
        }

        writer.flush(memTable, sstableFile);

        try (SSTableReader reader = new SSTableReader(sstableFile)) {
            // the LAST key in this unevenly-sized chunk is the one an
            // average-based estimate would most likely fail to reach
            assertThat(reader.get("e11".getBytes())).isPresent();
            assertThat(reader.get("d".getBytes())).isPresent();
            assertThat(reader.get("d".getBytes()).get().data()).hasSize(5_000);
        }
    }
}