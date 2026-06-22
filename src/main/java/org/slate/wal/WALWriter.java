package org.slate.wal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

public class WALWriter implements AutoCloseable{

    private static final Logger log = LoggerFactory.getLogger(WALWriter.class);

    private final FileChannel channel;

    public WALWriter(Path walFile) throws IOException {
        this.channel = FileChannel.open(
                walFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
        );
    }

    /**
     * Appends a record and fsyncs before returning.
     * Record layout: [checksum(4)][opType(1)][keyLen(4)][key][valueLen(4)][value]
     * (each of the sizes is in bytes)
     * Checksum covers everything AFTER the checksum field itself.
     */
    public synchronized void append(WALRecord record) throws IOException {
        byte[] key = record.key();
        byte[] value = record.value();

        int bodySize = 1 + 4 + key.length + 4 + value.length;
        ByteBuffer buffer = ByteBuffer.allocate(4 + bodySize);

        // Build the body first so we can checksum it before writing the checksum field
        ByteBuffer body = ByteBuffer.allocate(bodySize);
        body.put(record.opType().code());
        body.putInt(key.length);
        body.put(key);
        body.putInt(value.length);
        body.put(value);
        body.flip();

        CRC32 crc = new CRC32();
        crc.update(body.array(), 0, body.limit());
        long checksum = crc.getValue();

        buffer.putInt((int) checksum); // CRC32 fits in 32 bits unsigned; cast is safe for storage
        buffer.put(body.array(), 0, body.limit());
        buffer.flip();

        channel.write(buffer);
        channel.force(true); // fsync — the actual durability guarantee
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
