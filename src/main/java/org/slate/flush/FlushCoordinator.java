package org.slate.flush;

import org.slate.memtable.MemTable;
import org.slate.sstable.SSTableWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FlushCoordinator {
    private static final Logger log = LoggerFactory.getLogger(FlushCoordinator.class);

    private final Path dataDir;
    private final SSTableWriter sstableWriter = new SSTableWriter();
    private int sstableCounter = 0;

    public FlushCoordinator(Path dataDir) {
        this.dataDir = dataDir;
    }

    /**
     * Flushes the given (frozen) MemTable to a new SSTable file, then deletes
     * the WAL file that backed it — since its data is now durably persisted
     * in the SSTable. The NEW WAL for the next MemTable must
     * already exist before this is called; this method only retires the OLD one.
     */
    public Path flushAndRotate(MemTable frozenMemTable, Path retiringWalFile) throws IOException {
        Path sstableFile = dataDir.resolve("sstable-" + (sstableCounter++) + ".sst");

        sstableWriter.flush(frozenMemTable, sstableFile);

        Files.deleteIfExists(retiringWalFile);
        log.info("Flush complete. Retired WAL {} after writing {}", retiringWalFile, sstableFile);

        return sstableFile;
    }
}
