package org.slate.recovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slate.base.DatabaseTestBase;
import org.slate.memtable.MemTable;
import org.slate.wal.WALRecord;
import org.slate.wal.WALWriter;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

public class RecoveryManagerTest extends DatabaseTestBase {
    private Path walFile;
    private RecoveryManager recoveryManager;

    @BeforeEach
    void setup() {
        walFile = testFile("recovery.wal");
        recoveryManager = new RecoveryManager();
    }

    @Test
    @DisplayName("recovering from a non-existent WAL file is a no-op")
    void recoveringFromNonExistentWalIsNoOp() throws IOException {
        MemTable memTable = new MemTable(metrics);

        int replayed = recoveryManager.recover(walFile, memTable);

        assertThat(replayed).isZero();
        assertThat(memTable.entryCount()).isZero();
    }

    @Test
    @DisplayName("recovering replays a single PUT into the MemTable")
    void recoveringReplaysSinglePut() throws IOException {
        try (WALWriter writer = new WALWriter(walFile)) {
            writer.append(WALRecord.put("name".getBytes(), "yash".getBytes()));
        }

        MemTable memTable = new MemTable(metrics);
        int replayed = recoveryManager.recover(walFile, memTable);

        assertThat(replayed).isEqualTo(1);
        assertThat(memTable.get("name".getBytes())).contains("yash".getBytes());
    }

    @Test
    @DisplayName("recovering replays PUT, DELETE, PUT in order — final state reflects resurrection")
    void recoveringReplaysInOrderWithResurrection() throws IOException {
        try (WALWriter writer = new WALWriter(walFile)) {
            writer.append(WALRecord.put("name".getBytes(), "yash".getBytes()));
            writer.append(WALRecord.delete("name".getBytes()));
            writer.append(WALRecord.put("name".getBytes(), "shinde".getBytes()));
        }

        MemTable memTable = new MemTable(metrics);
        recoveryManager.recover(walFile, memTable);

        // final state must be "shinde", proving replay order was preserved
        assertThat(memTable.get("name".getBytes())).contains("shinde".getBytes());
    }

    @Test
    @DisplayName("recovering replays a DELETE-only record as a tombstone")
    void recoveringReplaysDeleteOnlyAsTombstone() throws IOException {
        try (WALWriter writer = new WALWriter(walFile)) {
            writer.append(WALRecord.delete("ghost".getBytes()));
        }

        MemTable memTable = new MemTable(metrics);
        recoveryManager.recover(walFile, memTable);

        assertThat(memTable.get("ghost".getBytes())).isEmpty();
        assertThat(memTable.getEntry("ghost".getBytes())).isPresent();
        assertThat(memTable.getEntry("ghost".getBytes()).get().tombstone()).isTrue();
    }

    @Test
    @DisplayName("recovering many records rebuilds MemTable state exactly")
    void recoveringManyRecordsRebuildsExactState() throws IOException {
        try (WALWriter writer = new WALWriter(walFile)) {
            for (int i = 0; i < 500; i++) {
                writer.append(WALRecord.put(("key-" + i).getBytes(), ("val-" + i).getBytes()));
            }
        }

        MemTable memTable = new MemTable(metrics);
        int replayed = recoveryManager.recover(walFile, memTable);

        assertThat(replayed).isEqualTo(500);
        assertThat(memTable.entryCount()).isEqualTo(500);
        for (int i = 0; i < 500; i++) {
            assertThat(memTable.get(("key-" + i).getBytes()))
                    .contains(("val-" + i).getBytes());
        }
    }

    @Test
    @DisplayName("THE crash recovery test — partial write before crash is excluded, all prior writes survive")
    void crashRecoveryExcludesPartialWriteButKeepsPriorWrites() throws IOException {
        // simulate normal operation: several successful, fsynced writes
        try (WALWriter writer = new WALWriter(walFile)) {
            writer.append(WALRecord.put("a".getBytes(), "1".getBytes()));
            writer.append(WALRecord.put("b".getBytes(), "2".getBytes()));
            writer.append(WALRecord.put("c".getBytes(), "3".getBytes()));
        }

        // simulate a crash mid-write of a 4th record — append a truncated, incomplete record
        long sizeBeforeCrash = Files.size(walFile);
        try (var raf = new RandomAccessFile(walFile.toFile(), "rw")) {
            raf.seek(sizeBeforeCrash);
            raf.write(new byte[]{0x01, 0x02, 0x03}); // 3 garbage bytes, not a full checksum even
        }

        // process "restarts" — fresh MemTable, recover from the WAL as-is
        MemTable memTable = new MemTable(metrics);
        int replayed = recoveryManager.recover(walFile, memTable);

        // the 3 fully-fsynced writes survive; the partial 4th write is correctly discarded
        assertThat(replayed).isEqualTo(3);
        assertThat(memTable.get("a".getBytes())).contains("1".getBytes());
        assertThat(memTable.get("b".getBytes())).contains("2".getBytes());
        assertThat(memTable.get("c".getBytes())).contains("3".getBytes());

        // and critically — the database is still WRITABLE after recovery,
        // proving recovery doesn't leave it in some broken state
        memTable.put("d".getBytes(), "4".getBytes());
        assertThat(memTable.get("d".getBytes())).contains("4".getBytes());
    }
}
