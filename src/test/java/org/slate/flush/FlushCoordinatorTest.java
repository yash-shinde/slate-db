package org.slate.flush;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slate.base.DatabaseTestBase;
import org.slate.memtable.MemTable;
import org.slate.sstable.SSTableReader;
import org.slate.wal.WALRecord;
import org.slate.wal.WALWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class FlushCoordinatorTest extends DatabaseTestBase {

    private FlushCoordinator coordinator;

    @BeforeEach
    void setup() {
        coordinator = new FlushCoordinator(testDir);
    }

    @Test
    @DisplayName("flushAndRotate produces a valid, readable SSTable")
    void flushAndRotateProducesReadableSSTable() throws IOException {
        Path walFile = testFile("wal-001.log");
        try (WALWriter w = new WALWriter(walFile)) {
            w.append(WALRecord.put("name".getBytes(), "yash".getBytes()));
        }

        MemTable memTable = new MemTable(metrics);
        memTable.put("name".getBytes(), "yash".getBytes());

        Path sstableFile = coordinator.flushAndRotate(memTable, walFile);

        try (SSTableReader reader = new SSTableReader(sstableFile)) {
            assertThat(reader.get("name".getBytes())).isPresent();
            assertThat(reader.get("name".getBytes()).get().data()).isEqualTo("yash".getBytes());
        }
    }

    @Test
    @DisplayName("flushAndRotate deletes the retiring WAL file after successful flush")
    void flushAndRotateDeletesOldWal() throws IOException {
        Path walFile = testFile("wal-001.log");
        try (WALWriter w = new WALWriter(walFile)) {
            w.append(WALRecord.put("a".getBytes(), "1".getBytes()));
        }
        assertThat(walFile).exists();

        MemTable memTable = new MemTable(metrics);
        memTable.put("a".getBytes(), "1".getBytes());

        coordinator.flushAndRotate(memTable, walFile);

        assertThat(walFile).doesNotExist();
    }

    @Test
    @DisplayName("successive flushes produce distinctly named SSTable files")
    void successiveFlushesProduceDistinctFiles() throws IOException {
        Path wal1 = testFile("wal-001.log");
        Path wal2 = testFile("wal-002.log");
        Files.createFile(wal1);
        Files.createFile(wal2);

        MemTable mt1 = new MemTable(metrics);
        mt1.put("a".getBytes(), "1".getBytes());
        Path sstable1 = coordinator.flushAndRotate(mt1, wal1);

        MemTable mt2 = new MemTable(metrics);
        mt2.put("b".getBytes(), "2".getBytes());
        Path sstable2 = coordinator.flushAndRotate(mt2, wal2);

        assertThat(sstable1).isNotEqualTo(sstable2);
        assertThat(sstable1).exists();
        assertThat(sstable2).exists();
    }

    @Test
    @DisplayName("flushAndRotate with a non-existent WAL file does not throw (idempotent delete)")
    void flushWithNonExistentWalDoesNotThrow() throws IOException {
        Path walFile = testFile("never-existed.log"); // never created

        MemTable memTable = new MemTable(metrics);
        memTable.put("a".getBytes(), "1".getBytes());

        assertThatCode(() -> coordinator.flushAndRotate(memTable, walFile))
                .doesNotThrowAnyException();
    }
}