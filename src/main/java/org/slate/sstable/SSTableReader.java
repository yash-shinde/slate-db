package org.slate.sstable;

import org.slate.bloom.BloomFilter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Optional;

public class SSTableReader implements AutoCloseable{
    public record Footer(long dataBlockOffset, long dataBlockLength,
                         long bloomBlockOffset, long bloomBlockLength,
                         int entryCount) {}

    public record Value(byte[] data, boolean tombstone) {}

    private final FileChannel channel;
    private final Footer footer;
    private final BloomFilter bloomFilter;

    public SSTableReader(Path file) throws IOException {
        this.channel = FileChannel.open(file, StandardOpenOption.READ);
        this.footer = readFooter();
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
        long bloomBlockOffset = buffer.getLong();
        long bloomBlockLength = buffer.getLong();
        int entryCount = buffer.getInt();

        return new Footer(dataBlockOffset, dataBlockLength, bloomBlockOffset, bloomBlockLength, entryCount);
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
     * Point lookup. Checks the Bloom filter first — a "definitely not present"
     * result avoids the data block scan entirely. Otherwise scans the data
     * block linearly (no sparse index yet — that's M4).
     */
    public Optional<Value> get(byte[] key) throws IOException {
        if (!bloomFilter.mightContain(key)) {
            return Optional.empty(); // definitely not here — zero disk reads into data block
        }

        ByteBuffer dataBlock = ByteBuffer.allocate((int) footer.dataBlockLength());
        channel.read(dataBlock, footer.dataBlockOffset());
        dataBlock.flip();

        while (dataBlock.hasRemaining()) {
            int keyLen = dataBlock.getInt();
            byte[] candidateKey = new byte[keyLen];
            dataBlock.get(candidateKey);

            int valueLen = dataBlock.getInt();
            byte[] candidateValue = new byte[valueLen];
            dataBlock.get(candidateValue);

            boolean tombstone = dataBlock.get() == 1;

            if (Arrays.equals(candidateKey, key)) {
                return Optional.of(new Value(candidateValue, tombstone));
            }
        }

        return Optional.empty(); // bloom filter false positive — key not actually here
    }

    public int entryCount() {
        return footer.entryCount();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
