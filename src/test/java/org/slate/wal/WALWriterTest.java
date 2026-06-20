package org.slate.wal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slate.base.DatabaseTestBase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class WALWriterTest extends DatabaseTestBase {
    private Path walFile;

    @BeforeEach
    void setupWalFile() {
        walFile = testFile("test.wal");
    }

    @Test
    @DisplayName("appending a PUT record writes bytes to disk")
    void appendingPutWritesBytesToDisk() throws IOException {
        try (WALWriter writer = new WALWriter(walFile)) {
            writer.append(WALRecord.put("key".getBytes(), "value".getBytes()));
        }
        assertThat(walFile).exists();
        assertThat(Files.size(walFile)).isGreaterThan(0);
    }

    @Test
    @DisplayName("appending multiple records grows the file")
    void appendingMultipleRecordsGrowsFile() throws IOException {
        try (WALWriter writer = new WALWriter(walFile)) {
            writer.append(WALRecord.put("a".getBytes(), "1".getBytes()));
            long sizeAfterOne = Files.size(walFile);

            writer.append(WALRecord.put("b".getBytes(), "2".getBytes()));
            long sizeAfterTwo = Files.size(walFile);

            assertThat(sizeAfterTwo).isGreaterThan(sizeAfterOne);
        }
    }

    @Test
    @DisplayName("written records can be read back correctly")
    void writtenRecordsCanBeReadBack() throws IOException {
        try (WALWriter writer = new WALWriter(walFile)) {
            writer.append(WALRecord.put("name".getBytes(), "yash".getBytes()));
        }

        List<WALRecord> records;
        try (WALReader reader = new WALReader(walFile)) {
            records = reader.readAll();
        }

        assertThat(records).hasSize(1);
        assertThat(records.get(0)).isEqualTo(
                WALRecord.put("name".getBytes(), "yash".getBytes())
        );
    }

    @Test
    @DisplayName("DELETE record round-trips with empty value")
    void deleteRecordRoundTrips() throws IOException {
        try (WALWriter writer = new WALWriter(walFile)) {
            writer.append(WALRecord.delete("ghost".getBytes()));
        }

        List<WALRecord> records;
        try (WALReader reader = new WALReader(walFile)) {
            records = reader.readAll();
        }

        assertThat(records).hasSize(1);
        assertThat(records.get(0).opType()).isEqualTo(OpType.DELETE);
        assertThat(records.get(0).value()).isEmpty();
    }

    @Test
    @DisplayName("reopening WALWriter appends rather than overwrites")
    void reopeningWriterAppends() throws IOException {
        try (WALWriter writer = new WALWriter(walFile)) {
            writer.append(WALRecord.put("a".getBytes(), "1".getBytes()));
        }
        try (WALWriter writer = new WALWriter(walFile)) {
            writer.append(WALRecord.put("b".getBytes(), "2".getBytes()));
        }

        List<WALRecord> records;
        try (WALReader reader = new WALReader(walFile)) {
            records = reader.readAll();
        }

        assertThat(records).hasSize(2);
    }
}
