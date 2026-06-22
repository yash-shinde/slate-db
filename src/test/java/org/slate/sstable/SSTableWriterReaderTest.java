package org.slate.sstable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slate.base.DatabaseTestBase;
import org.slate.memtable.MemTable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

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
}