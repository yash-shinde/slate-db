package org.slate.sstable;

import org.slate.bloom.BloomFilter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Optional;

public class SSTableReader implements AutoCloseable {

    public record Footer(long dataBlockOffset, long dataBlockLength,
                         long indexBlockOffset, long indexBlockLength,
                         long bloomBlockOffset, long bloomBlockLength,
                         int entryCount) {}

    public record Value(byte[] data, boolean tombstone) {}

    // How far past the index's starting offset we're willing to scan before
    // giving up — must be at least one full indexing interval's worth of
    // entries. We scan until we pass the target key (sorted data) or hit
    // the end of the data block.
    private static final int SCAN_SAFETY_MARGIN_ENTRIES = BlockIndex.DEFAULT_INTERVAL + 1;

    private final FileChannel channel;
    private final Footer footer;
    private final BloomFilter bloomFilter;
    private final BlockIndex blockIndex;

    public SSTableReader(Path file) throws IOException {
        this.channel = FileChannel.open(file, StandardOpenOption.READ);
        this.footer = readFooter();
        this.blockIndex = readBlockIndex();
        this.bloomFilter = readBloomFilter();
    }

    private Footer readFooter() throws IOException {
        long fileSize = channel.size();
        int footerSize = SSTableWriter.footerSize();
        ByteBuffer buffer = ByteBuffer.allocate(footerSize);
        channel.read(buffer, fileSize - footerSize);
        buffer.flip();

        long dataBlockOffset = buffer.getLong();
        long dataBlockLength = buffer.getLong();
        long indexBlockOffset = buffer.getLong();
        long indexBlockLength = buffer.getLong();
        long bloomBlockOffset = buffer.getLong();
        long bloomBlockLength = buffer.getLong();
        int entryCount = buffer.getInt();

        return new Footer(dataBlockOffset, dataBlockLength,
                indexBlockOffset, indexBlockLength,
                bloomBlockOffset, bloomBlockLength, entryCount);
    }

    private BlockIndex readBlockIndex() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate((int) footer.indexBlockLength());
        channel.read(buffer, footer.indexBlockOffset());
        buffer.flip();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return BlockIndex.deserialize(bytes);
    }

    private BloomFilter readBloomFilter() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate((int) footer.bloomBlockLength());
        channel.read(buffer, footer.bloomBlockOffset());
        buffer.flip();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return BloomFilter.deserialize(bytes);
    }

    /**
     * Point lookup using Bloom filter + sparse index, per M4 design:
     *   1. Bloom filter — skip entirely if definitely absent
     *   2. Binary search sparse index — find starting offset for the scan
     *   3. Read a SMALL slice of the data block (not the whole thing)
     *   4. Linear scan within that slice, stopping early once we pass
     *      the target key (sorted order guarantees nothing further matches)
     */
    public Optional<Value> get(byte[] key) throws IOException {
        if (!bloomFilter.mightContain(key)) {
            return Optional.empty();
        }

        Optional<BlockIndex.IndexEntry> startPoint = blockIndex.findStartingPoint(key);
        if (startPoint.isEmpty()) {
            return Optional.empty();
        }

        BlockIndex.IndexEntry entry = startPoint.get();
        long scanStartOffset = footer.dataBlockOffset() + entry.offset();
        long scanLength = entry.chunkLengthBytes(); // EXACT — no estimation, no over-read

        ByteBuffer slice = ByteBuffer.allocate((int) scanLength);
        channel.read(slice, scanStartOffset);
        slice.flip();

        while (slice.hasRemaining()) {
            int keyLen = slice.getInt();
            byte[] candidateKey = new byte[keyLen];
            slice.get(candidateKey);

            int valueLen = slice.getInt();
            byte[] candidateValue = new byte[valueLen];
            slice.get(candidateValue);

            boolean tombstone = slice.get() == 1;

            int cmp = Arrays.compare(candidateKey, key);
            if (cmp == 0) {
                return Optional.of(new Value(candidateValue, tombstone));
            }
            if (cmp > 0) {
                return Optional.empty(); // sorted order — passed where it would be
            }
        }

        return Optional.empty();
    }

    public int entryCount() {
        return footer.entryCount();
    }

    public int indexSize() {
        return blockIndex.size();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}