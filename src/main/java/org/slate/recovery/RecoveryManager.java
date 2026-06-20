package org.slate.recovery;

import org.slate.memtable.MemTable;
import org.slate.wal.OpType;
import org.slate.wal.WALReader;
import org.slate.wal.WALRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class RecoveryManager {
    private static final Logger log = LoggerFactory.getLogger(RecoveryManager.class);

    /**
     * Replays all valid records from the WAL at walFile into the given MemTable,
     * in the exact order they were written. If walFile doesn't exist, this is a
     * no-op — a fresh database with no prior WAL has nothing to recover.
     *
     * Returns the number of records replayed, for observability/logging.
     */
    public int recover(Path walFile, MemTable memTable) throws IOException {
        if (!Files.exists(walFile)) {
            log.info("No WAL file found at {} — starting with empty MemTable.", walFile);
            return 0;
        }

        List<WALRecord> records;
        try (WALReader reader = new WALReader(walFile)) {
            records = reader.readAll();
        }

        for (WALRecord record : records) {
            if (record.opType() == OpType.PUT) {
                memTable.put(record.key(), record.value());
            } else if (record.opType() == OpType.DELETE) {
                memTable.delete(record.key());
            }
        }

        log.info("Recovery complete — replayed {} records from {}.", records.size(), walFile);
        return records.size();
    }
}
