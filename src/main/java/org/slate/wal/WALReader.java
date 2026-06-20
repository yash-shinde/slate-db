package org.slate.wal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;

public class WALReader implements AutoCloseable{

    private static final Logger log = LoggerFactory.getLogger(WALReader.class);

    private final FileChannel channel;

    public WALReader(Path path) throws IOException {
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
    }

    /**
     * Reads all valid records from the current position to EOF or first corruption.
     * Stops permanently at the first sign of truncation or checksum mismatch —
     * never attempts to resync past a bad record.
     * Currently , we read record by record . We don't buffer it .This is intentional
     * since buffering introduces complexity .This code is going to be mostly useful
     * when we have had a crash and once used won't be used until the next crash
     * Essentially this doesn't fall on the hot path so we can forego bufferred reading
     * for now.
     **/
    public List<WALRecord> readAll() throws IOException {
        List<WALRecord> records = new ArrayList<>();
        while (true) {
            Optional<WALRecord> record = tryReadOne();
            if (record.isEmpty()) {
                break;
            }
            records.add(record.get());
        }
        return records;
    }

    private Optional<WALRecord> tryReadOne() throws IOException {
        long recordStart = channel.position();

        // ─── Read checksum field ───
        ByteBuffer checksumBuf = ByteBuffer.allocate(4);
        //read here return the no of bytes read
        //0 or -1 may indicate EOF
        int read = channel.read(checksumBuf);
        if (read < 4) {
            if (read <= 0) {
                return Optional.empty(); // clean EOF — normal end of file
            }
            log.warn("Truncated WAL record at position {} — incomplete checksum field. Stopping replay.", recordStart);
            channel.position(recordStart); // restore, though we're done reading anyway
            return Optional.empty();
        }
        checksumBuf.flip();
        long storedChecksum = checksumBuf.getInt() & 0xFFFFFFFFL; // treat as unsigned

        // ─── Read opType ───
        ByteBuffer opTypeBuf = ByteBuffer.allocate(1);
        if (channel.read(opTypeBuf) < 1) {
            log.warn("Truncated WAL record at position {} — missing opType. Stopping replay.", recordStart);
            return Optional.empty();
        }
        //flip switches the buffer from read to write mode
        //look at what buffer tracks internally and to understand flip
        //if we dont flip buffer remains in write mode
        //once flipped it writes to destination the no of bytes stored in it.
        opTypeBuf.flip();
        byte opTypeCode = opTypeBuf.get();

        // ─── Read keyLen + key ───
        Optional<byte[]> key = readLengthPrefixed(recordStart);
        if (key.isEmpty()) return Optional.empty();

        // ─── Read valueLen + value ───
        Optional<byte[]> value = readLengthPrefixed(recordStart);
        if (value.isEmpty()) return Optional.empty();

        // ─── Verify checksum over [opType][keyLen][key][valueLen][value] ───
        int bodySize = 1 + 4 + key.get().length + 4 + value.get().length;
        ByteBuffer body = ByteBuffer.allocate(bodySize);
        body.put(opTypeCode);
        body.putInt(key.get().length);
        body.put(key.get());
        body.putInt(value.get().length);
        body.put(value.get());

        CRC32 crc = new CRC32();
        crc.update(body.array(), 0, body.limit());
        long computedChecksum = crc.getValue();

        if (computedChecksum != storedChecksum) {
            log.warn("Checksum mismatch at WAL record position {} — expected {}, got {}. Stopping replay.",
                    recordStart, storedChecksum, computedChecksum);
            return Optional.empty();
        }

        OpType opType = OpType.fromCode(opTypeCode);
        return Optional.of(new WALRecord(opType, key.get(), value.get()));
    }

    /** Reads a 4-byte length prefix followed by that many bytes. Empty if truncated. */
    private Optional<byte[]> readLengthPrefixed(long recordStart) throws IOException {
        ByteBuffer lenBuf = ByteBuffer.allocate(4);
        int read = channel.read(lenBuf);
        if (read < 4) {
            log.warn("Truncated WAL record at position {} — incomplete length field. Stopping replay.", recordStart);
            return Optional.empty();
        }
        lenBuf.flip();
        int length = lenBuf.getInt();

        if (length < 0) {
            log.warn("Corrupt WAL record at position {} — negative length field ({}). Stopping replay.", recordStart, length);
            return Optional.empty();
        }

        ByteBuffer dataBuf = ByteBuffer.allocate(length);
        int totalRead = 0;
        while (totalRead < length) {
            int n = channel.read(dataBuf);
            if (n < 0) {
                log.warn("Truncated WAL record at position {} — expected {} bytes, got {}. Stopping replay.",
                        recordStart, length, totalRead);
                return Optional.empty();
            }
            totalRead += n;
        }
        dataBuf.flip();
        byte[] data = new byte[length];
        dataBuf.get(data);
        return Optional.of(data);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
