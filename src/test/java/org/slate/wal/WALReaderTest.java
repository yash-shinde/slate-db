package org.slate.wal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slate.base.DatabaseTestBase;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

public class WALReaderTest extends DatabaseTestBase {
    private Path walFile;

    @BeforeEach
    void setupWalFile() {
        walFile = testFile("test.wal");
    }

    @Test
    @DisplayName("reading an empty WAL file returns no records")
    void readingEmptyFileReturnsNoRecords() throws IOException {
        Files.createFile(walFile);

        List<WALRecord> records;
        try (WALReader reader = new WALReader(walFile)) {
            records = reader.readAll();
        }

        assertThat(records).isEmpty();
    }

    @Test
    @DisplayName("records replay in the exact order they were written")
    void recordsReplayInWriteOrder() throws IOException {
        try (WALWriter writer = new WALWriter(walFile)) {
            writer.append(WALRecord.put("name".getBytes(), "yash".getBytes()));
            writer.append(WALRecord.delete("name".getBytes()));
            writer.append(WALRecord.put("name".getBytes(), "shinde".getBytes()));
        }

        List<WALRecord> records;
        try (WALReader reader = new WALReader(walFile)) {
            records = reader.readAll();
        }

        assertThat(records).hasSize(3);
        assertThat(records.get(0).opType()).isEqualTo(OpType.PUT);
        assertThat(records.get(0).value()).isEqualTo("yash".getBytes());
        assertThat(records.get(1).opType()).isEqualTo(OpType.DELETE);
        assertThat(records.get(2).opType()).isEqualTo(OpType.PUT);
        assertThat(records.get(2).value()).isEqualTo("shinde".getBytes());
    }

    @Test
    @DisplayName("1000 records all replay correctly and in order")
    void thousandRecordsReplayCorrectly() throws IOException {
        try (WALWriter writer = new WALWriter(walFile)) {
            for (int i = 0; i < 1000; i++) {
                writer.append(WALRecord.put(("key-" + i).getBytes(), ("val-" + i).getBytes()));
            }
        }

        List<WALRecord> records;
        try (WALReader reader = new WALReader(walFile)) {
            records = reader.readAll();
        }

        assertThat(records).hasSize(1000);
        for (int i = 0; i < 1000; i++) {
            assertThat(records.get(i).key()).isEqualTo(("key-" + i).getBytes());
        }
    }

    // ─── Corruption / crash simulation tests ───────────────────────

    @Test
    @DisplayName("truncated file mid-checksum stops replay, keeps prior valid records")
    void truncatedMidChecksumStopsReplay() throws IOException {
        try (WALWriter writer = new WALWriter(walFile)) {
            writer.append(WALRecord.put("good".getBytes(), "1".getBytes()));
        }
        long fullSize = Files.size(walFile);

        // simulate a crash mid-write: append 2 garbage bytes (less than a 4-byte checksum)
        // to a NEW second record that never finished
        try (var raf = new RandomAccessFile(walFile.toFile(), "rw")) {
            raf.seek(fullSize);
            raf.write(new byte[]{0x01, 0x02}); // incomplete checksum field for record 2
        }

        List<WALRecord> records;
        try (WALReader reader = new WALReader(walFile)) {
            records = reader.readAll();
        }

        // record 1 survives, the truncated partial record 2 is discarded
        assertThat(records).hasSize(1);
        assertThat(records.get(0).key()).isEqualTo("good".getBytes());
    }

    @Test
    @DisplayName("truncated file mid-key bytes stops replay at that record")
    void truncatedMidKeyBytesStopsReplay() throws IOException {
        try (WALWriter writer = new WALWriter(walFile)) {
            writer.append(WALRecord.put("good".getBytes(), "1".getBytes()));
            writer.append(WALRecord.put("alsoGood".getBytes(), "2".getBytes()));
        }

        // truncate the file to cut off partway through the second record's key bytes
        long fullSize = Files.size(walFile);
        long firstRecordSize = fullSize / 2; // rough cut into the middle of record 2
        try (var raf = new RandomAccessFile(walFile.toFile(), "rw")) {
            raf.setLength(firstRecordSize + 5); // leave a few dangling bytes of record 2
        }

        List<WALRecord> records;
        try (WALReader reader = new WALReader(walFile)) {
            records = reader.readAll();
        }

        // at minimum the first record must survive; second is incomplete and discarded
        assertThat(records).hasSizeGreaterThanOrEqualTo(1);
        assertThat(records.get(0).key()).isEqualTo("good".getBytes());
    }

    @Test
    @DisplayName("corrupted checksum on first record stops replay immediately")
    void corruptedChecksumOnFirstRecordStopsImmediately() throws IOException {
        try (WALWriter writer = new WALWriter(walFile)) {
            writer.append(WALRecord.put("a".getBytes(), "1".getBytes()));
            writer.append(WALRecord.put("b".getBytes(), "2".getBytes()));
        }

        // flip a byte inside the FIRST record's checksum field (bytes 0-3)
        try (var raf = new RandomAccessFile(walFile.toFile(), "rw")) {
            raf.seek(0);
            raf.write(0xFF); // corrupt first checksum byte
        }

        List<WALRecord> records;
        try (WALReader reader = new WALReader(walFile)) {
            records = reader.readAll();
        }

        // entire file is lost — first record fails checksum, nothing after it is trusted
        assertThat(records).isEmpty();
    }

    @Test
    @DisplayName("corrupted checksum on second record keeps first, discards rest")
    void corruptedChecksumOnSecondRecordKeepsFirst() throws IOException {
        Path tempFile;
        try (WALWriter writer = new WALWriter(walFile)) {
            writer.append(WALRecord.put("good".getBytes(), "1".getBytes()));
        }
        long firstRecordEnd = Files.size(walFile);

        try (WALWriter writer = new WALWriter(walFile)) {
            writer.append(WALRecord.put("bad".getBytes(), "2".getBytes()));
        }

        // corrupt a byte inside record 2's checksum field (starts right after record 1 ends)
        try (var raf = new RandomAccessFile(walFile.toFile(), "rw")) {
            raf.seek(firstRecordEnd);
            raf.write(0xFF);
        }

        List<WALRecord> records;
        try (WALReader reader = new WALReader(walFile)) {
            records = reader.readAll();
        }

        assertThat(records).hasSize(1);
        assertThat(records.get(0).key()).isEqualTo("good".getBytes());
    }

    @Test
    @DisplayName("corrupted value bytes (not just length) are caught by checksum")
    void corruptedValueBytesAreCaughtByChecksum() throws IOException {
        try (WALWriter writer = new WALWriter(walFile)) {
            writer.append(WALRecord.put("key".getBytes(), "original".getBytes()));
        }

        // flip a byte somewhere in the value payload, near the end of the file
        long fileSize = Files.size(walFile);
        try (var raf = new RandomAccessFile(walFile.toFile(), "rw")) {
            raf.seek(fileSize - 1); // last byte, almost certainly inside "original"
            raf.write(0x00);
        }

        List<WALRecord> records;
        try (WALReader reader = new WALReader(walFile)) {
            records = reader.readAll();
        }

        // checksum was computed over the original bytes — any mutation must be caught
        assertThat(records).isEmpty();
    }

    @Test
    @DisplayName("negative length field is treated as corruption, not a crash")
    void negativeLengthFieldIsCorruption() throws IOException {
        try (WALWriter writer = new WALWriter(walFile)) {
            writer.append(WALRecord.put("key".getBytes(), "value".getBytes()));
        }

        // checksum(4) + opType(1) = byte offset 5 is where keyLen begins
        try (var raf = new RandomAccessFile(walFile.toFile(), "rw")) {
            raf.seek(5);
            raf.writeInt(-1); // force a negative keyLen
        }

        List<WALRecord> records;
        assertThatCode(() -> {
            try (WALReader reader = new WALReader(walFile)) {
                List<WALRecord> result = reader.readAll();
                assertThat(result).isEmpty();
            }
        }).doesNotThrowAnyException();
    }
}
