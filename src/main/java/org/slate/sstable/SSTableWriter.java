package org.slate.sstable;

import org.slate.bloom.BloomFilter;
import org.slate.memtable.MemTable;
import org.slate.memtable.SkipList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public class SSTableWriter {
    private static final Logger log = LoggerFactory.getLogger(SSTableWriter.class);
    private static final double BLOOM_FALSE_POSITIVE_RATE = 0.01;

    /**
     * Flushes a frozen MemTable to an immutable SSTable file.
     * Iterates entries in sorted order (guaranteed by SkipList), writes the
     * data block, builds + writes a Bloom filter, then writes the footer.
     */
    public void flush(MemTable memTable, Path outputFile) throws IOException {
        List<SkipList.Node<MemTable.BytesKey, MemTable.Entry>> entries = new ArrayList<>();
        memTable.entries().forEach(entries::add);

        BloomFilter bloomFilter = new BloomFilter(
                Math.max(1, entries.size()), BLOOM_FALSE_POSITIVE_RATE
        );
        for (var entry : entries) {
            bloomFilter.add(entry.getKey().data());
        }

        try (FileChannel channel = FileChannel.open(
                outputFile,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {

            long dataBlockOffset = 0;
            long dataBlockLength = writeDataBlock(channel, entries);

            long bloomBlockOffset = channel.position();
            byte[] bloomBytes = bloomFilter.serialize();
            channel.write(ByteBuffer.wrap(bloomBytes));
            long bloomBlockLength = bloomBytes.length;

            writeFooter(channel, dataBlockOffset, dataBlockLength,
                    bloomBlockOffset, bloomBlockLength, entries.size());

            channel.force(true);
        }

        log.info("Flushed {} entries to SSTable {}", entries.size(), outputFile);
    }

    private long writeDataBlock(FileChannel channel,
                                List<SkipList.Node<MemTable.BytesKey, MemTable.Entry>> entries) throws IOException {
        long startPosition = channel.position();

        for (var node : entries) {
            byte[] key = node.getKey().data();
            MemTable.Entry entry = node.getValue();
            boolean tombstone = entry.tombstone();
            byte[] value = tombstone ? new byte[0] : entry.value();

            int recordSize = 4 + key.length + 4 + value.length + 1;
            ByteBuffer buffer = ByteBuffer.allocate(recordSize);
            buffer.putInt(key.length);
            buffer.put(key);
            buffer.putInt(value.length);
            buffer.put(value);
            buffer.put((byte) (tombstone ? 1 : 0));
            buffer.flip();

            channel.write(buffer);
        }

        return channel.position() - startPosition;
    }

    private void writeFooter(FileChannel channel,
                             long dataBlockOffset, long dataBlockLength,
                             long bloomBlockOffset, long bloomBlockLength,
                             int entryCount) throws IOException {
        ByteBuffer footer = ByteBuffer.allocate(8 + 8 + 8 + 8 + 4);
        footer.putLong(dataBlockOffset);
        footer.putLong(dataBlockLength);
        footer.putLong(bloomBlockOffset);
        footer.putLong(bloomBlockLength);
        footer.putInt(entryCount);
        footer.flip();
        channel.write(footer);
    }

    public static int footerSize() {
        return 8 + 8 + 8 + 8 + 4;
    }
}
