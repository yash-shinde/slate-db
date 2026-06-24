package org.slate.sstable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class BlockIndexTest {

    @Test
    @DisplayName("empty index returns empty for any lookup")
    void emptyIndexReturnsEmpty() {
        BlockIndex index = BlockIndex.builder().build(0);
        assertThat(index.findStartingPoint("anything".getBytes())).isEmpty();
        assertThat(index.size()).isZero();
    }

    @Test
    @DisplayName("indexes the first entry unconditionally, regardless of interval")
    void indexesFirstEntryUnconditionally() {
        BlockIndex.Builder builder = BlockIndex.builder().interval(16);
        builder.maybeAddEntry("first".getBytes(), 0);
        BlockIndex index = builder.build(1000L); // generous — only size matters here, not chunk length

        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("indexes every Nth entry per the configured interval")
    void indexesEveryNthEntry() {
        BlockIndex.Builder builder = BlockIndex.builder().interval(4);
        long lastOffset = 0;
        for (int i = 0; i < 20; i++) {
            lastOffset = i * 100L;
            builder.maybeAddEntry(("key-" + String.format("%02d", i)).getBytes(), lastOffset);
        }
        BlockIndex index = builder.build(lastOffset + 1000L); // comfortably past last offset

        // entries 0, 4, 8, 12, 16 → 5 indexed entries for 20 total at interval=4
        assertThat(index.size()).isEqualTo(5);
    }

    @Test
    @DisplayName("findStartingPoint returns the largest indexed key <= target")
    void findsLargestKeyLessOrEqualToTarget() {
        BlockIndex.Builder builder = BlockIndex.builder().interval(1); // index every entry for this test
        builder.maybeAddEntry("apple".getBytes(), 0);
        builder.maybeAddEntry("grape".getBytes(), 100);
        builder.maybeAddEntry("mango".getBytes(), 200);
        builder.maybeAddEntry("peach".getBytes(), 300);
        BlockIndex index = builder.build(1000L); // comfortably past offset 300

        // "lemon" is between grape and mango — should land on grape
        Optional<BlockIndex.IndexEntry> result = index.findStartingPoint("lemon".getBytes());

        assertThat(result).isPresent();
        assertThat(new String(result.get().key())).isEqualTo("grape");
        assertThat(result.get().offset()).isEqualTo(100);
    }

    @Test
    @DisplayName("findStartingPoint on an exact indexed key returns that exact entry")
    void findsExactMatchOnIndexedKey() {
        BlockIndex.Builder builder = BlockIndex.builder().interval(1);
        builder.maybeAddEntry("apple".getBytes(), 0);
        builder.maybeAddEntry("grape".getBytes(), 100);
        builder.maybeAddEntry("mango".getBytes(), 200);
        BlockIndex index = builder.build(1000L);

        Optional<BlockIndex.IndexEntry> result = index.findStartingPoint("grape".getBytes());

        assertThat(result).isPresent();
        assertThat(new String(result.get().key())).isEqualTo("grape");
        assertThat(result.get().offset()).isEqualTo(100);
    }

    @Test
    @DisplayName("findStartingPoint for a key smaller than the smallest indexed key returns empty")
    void keySmallerThanAllIndexedKeysReturnsEmpty() {
        BlockIndex.Builder builder = BlockIndex.builder().interval(1);
        builder.maybeAddEntry("mango".getBytes(), 0);
        builder.maybeAddEntry("peach".getBytes(), 100);
        BlockIndex index = builder.build(1000L);

        // "apple" sorts before "mango" — nothing in the index can bound it from below
        assertThat(index.findStartingPoint("apple".getBytes())).isEmpty();
    }

    @Test
    @DisplayName("findStartingPoint for a key larger than the largest indexed key returns the last entry")
    void keyLargerThanAllIndexedKeysReturnsLastEntry() {
        BlockIndex.Builder builder = BlockIndex.builder().interval(1);
        builder.maybeAddEntry("apple".getBytes(), 0);
        builder.maybeAddEntry("mango".getBytes(), 100);
        BlockIndex index = builder.build(1000L);

        // "zebra" sorts after everything — the scan should start from the last
        // index point, since that's the only place it COULD be (it won't be found,
        // but findStartingPoint's job is just to give a valid starting bound)
        Optional<BlockIndex.IndexEntry> result = index.findStartingPoint("zebra".getBytes());

        assertThat(result).isPresent();
        assertThat(new String(result.get().key())).isEqualTo("mango");
    }

    @Test
    @DisplayName("serialize then deserialize preserves all entries and lookup behavior")
    void serializeRoundTripPreservesLookupBehavior() {
        BlockIndex.Builder builder = BlockIndex.builder().interval(2);
        long lastOffset = 0;
        for (int i = 0; i < 10; i++) {
            lastOffset = i * 50L;
            builder.maybeAddEntry(("key-" + i).getBytes(), lastOffset);
        }
        BlockIndex original = builder.build(lastOffset + 500L);

        byte[] bytes = original.serialize();
        BlockIndex restored = BlockIndex.deserialize(bytes);

        assertThat(restored.size()).isEqualTo(original.size());

        Optional<BlockIndex.IndexEntry> originalResult = original.findStartingPoint("key-5".getBytes());
        Optional<BlockIndex.IndexEntry> restoredResult = restored.findStartingPoint("key-5".getBytes());

        assertThat(restoredResult.map(e -> new String(e.key())))
                .isEqualTo(originalResult.map(e -> new String(e.key())));
    }

    @Test
    @DisplayName("chunkLengthBytes is computed correctly relative to the next index boundary")
    void chunkLengthBytesComputedCorrectly() {
        BlockIndex.Builder builder = BlockIndex.builder().interval(1);
        builder.maybeAddEntry("a".getBytes(), 0);
        builder.maybeAddEntry("b".getBytes(), 150);
        builder.maybeAddEntry("c".getBytes(), 400);
        BlockIndex index = builder.build(600L); // total data block length

        Optional<BlockIndex.IndexEntry> first = index.findStartingPoint("a".getBytes());
        Optional<BlockIndex.IndexEntry> second = index.findStartingPoint("b".getBytes());
        Optional<BlockIndex.IndexEntry> third = index.findStartingPoint("c".getBytes());

        // chunk length = next entry's offset - this entry's offset
        assertThat(first.get().chunkLengthBytes()).isEqualTo(150);   // 150 - 0
        assertThat(second.get().chunkLengthBytes()).isEqualTo(250);  // 400 - 150

        // LAST entry has no "next" — its chunk runs to the end of the data block
        assertThat(third.get().chunkLengthBytes()).isEqualTo(200);   // 600 - 400
    }
}