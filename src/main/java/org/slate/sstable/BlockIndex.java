package org.slate.sstable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class BlockIndex {

    public static final int DEFAULT_INTERVAL = 16;

    // chunkLengthBytes = exact number of bytes from this entry's offset
    // up to (but not including) the next index entry's offset — or, for
    // the LAST index entry, up to the end of the data block.
    public record IndexEntry(byte[] key, long offset, long chunkLengthBytes) {}

    private final List<IndexEntry> entries;

    private BlockIndex(List<IndexEntry> entries) {
        this.entries = entries;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<IndexEntry> findStartingPoint(byte[] targetKey) {
        if (entries.isEmpty()) {
            return Optional.empty();
        }

        int lo = 0, hi = entries.size() - 1;
        int resultIdx = -1;

        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            int cmp = Arrays.compare(entries.get(mid).key(), targetKey);
            if (cmp <= 0) {
                resultIdx = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }

        if (resultIdx == -1) {
            return Optional.empty();
        }
        return Optional.of(entries.get(resultIdx));
    }

    public int size() {
        return entries.size();
    }

    // ─── Serialization ──────────────────────────────────────────

    public byte[] serialize() {
        int totalSize = 4; // entry count
        for (IndexEntry e : entries) {
            totalSize += 4 + e.key().length + 8 + 8; // keyLen + key + offset + chunkLengthBytes
        }

        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.putInt(entries.size());
        for (IndexEntry e : entries) {
            buffer.putInt(e.key().length);
            buffer.put(e.key());
            buffer.putLong(e.offset());
            buffer.putLong(e.chunkLengthBytes());
        }
        return buffer.array();
    }

    public static BlockIndex deserialize(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        int count = buffer.getInt();
        List<IndexEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int keyLen = buffer.getInt();
            byte[] key = new byte[keyLen];
            buffer.get(key);
            long offset = buffer.getLong();
            long chunkLengthBytes = buffer.getLong();
            entries.add(new IndexEntry(key, offset, chunkLengthBytes));
        }
        return new BlockIndex(entries);
    }

    // ─── Builder ─────────────────────────────────────────────────
    // Key change: we can't know a chunk's length until we know where the
    // NEXT chunk starts. So we record offsets as entries arrive, and only
    // backfill chunkLengthBytes once the next boundary (or end of data
    // block) is known.

    public static class Builder {
        private final List<byte[]> pendingKeys = new ArrayList<>();
        private final List<Long> pendingOffsets = new ArrayList<>();
        private int sinceLastIndex = 0;
        private final int interval;

        private Builder() {
            this(DEFAULT_INTERVAL);
        }

        private Builder(int interval) {
            this.interval = interval;
        }

        public Builder interval(int interval) {
            return new Builder(interval);
        }

        public void maybeAddEntry(byte[] key, long offsetInDataBlock) {
            if (pendingKeys.isEmpty() || sinceLastIndex >= interval) {
                pendingKeys.add(key);
                pendingOffsets.add(offsetInDataBlock);
                sinceLastIndex = 0;
            }
            sinceLastIndex++;
        }

        /**
         * Finalizes the index. dataBlockTotalLength is required to compute
         * the chunk length of the FINAL index entry, which has no "next
         * entry" to bound it — it runs to the end of the data block instead.
         */
        public BlockIndex build(long dataBlockTotalLength) {
            List<IndexEntry> finalized = new ArrayList<>(pendingKeys.size());
            for (int i = 0; i < pendingKeys.size(); i++) {
                long thisOffset = pendingOffsets.get(i);
                long nextBoundary = (i + 1 < pendingOffsets.size())
                        ? pendingOffsets.get(i + 1)
                        : dataBlockTotalLength;
                long chunkLength = nextBoundary - thisOffset;
                finalized.add(new IndexEntry(pendingKeys.get(i), thisOffset, chunkLength));
            }
            return new BlockIndex(finalized);
        }
    }
}