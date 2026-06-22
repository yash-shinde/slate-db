package org.slate.bloom;

import com.google.common.hash.Hashing;

import java.nio.ByteBuffer;
import java.util.BitSet;

public class BloomFilter {
    private final BitSet bits;
    private final int bitSetSize;
    private final int numHashFunctions;

    /**
     * Constructs a Bloom filter sized for expectedInsertions at the given
     * false positive rate. See M3 Step 2 for the sizing math.
     */
    public BloomFilter(int expectedInsertions, double falsePositiveRate) {
        this.bitSetSize = optimalBitSetSize(expectedInsertions, falsePositiveRate);
        this.numHashFunctions = optimalHashFunctions(expectedInsertions, bitSetSize);
        this.bits = new BitSet(bitSetSize);
    }

    /** Used when reconstructing a filter from disk — size/k already known. */
    private BloomFilter(BitSet bits, int bitSetSize, int numHashFunctions) {
        this.bits = bits;
        this.bitSetSize = bitSetSize;
        this.numHashFunctions = numHashFunctions;
    }

    private static int optimalBitSetSize(int n, double p) {
        double m = -(n * Math.log(p)) / (Math.log(2) * Math.log(2));
        return (int) Math.ceil(m);
    }

    private static int optimalHashFunctions(int n, int m) {
        double k = ((double) m / n) * Math.log(2);
        return Math.max(1, Math.round((float) k));
    }

    public void add(byte[] key) {
        long[] hashes = twoHashes(key);
        for (int i = 0; i < numHashFunctions; i++) {
            long combined = hashes[0] + (long) i * hashes[1];
            int index = (int) (Math.floorMod(combined, (long) bitSetSize));
            bits.set(index);
        }
    }

    /**
     * Returns true if the key MIGHT be present (could be a false positive).
     * Returns false if the key is DEFINITELY NOT present (never a false negative).
     */
    public boolean mightContain(byte[] key) {
        long[] hashes = twoHashes(key);
        for (int i = 0; i < numHashFunctions; i++) {
            long combined = hashes[0] + (long) i * hashes[1];
            int index = (int) (Math.floorMod(combined, (long) bitSetSize));
            if (!bits.get(index)) {
                return false; // definitely not present
            }
        }
        return true; // might be present
    }

    /** Double hashing — derive k hash values from 2 real hash computations. */
    private long[] twoHashes(byte[] key) {
        long hash128 = Hashing.murmur3_128().hashBytes(key).asLong();
        long h1 = hash128;
        long h2 = hash128 >>> 32;
        return new long[]{h1, h2};
    }

    // ─── Serialization — for writing into the SSTable bloom block ─────

    public byte[] serialize() {
        byte[] bitsBytes = bits.toByteArray();
        ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + 4 + bitsBytes.length);
        buffer.putInt(bitSetSize);
        buffer.putInt(numHashFunctions);
        buffer.putInt(bitsBytes.length);
        buffer.put(bitsBytes);
        return buffer.array();
    }

    public static BloomFilter deserialize(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        int bitSetSize = buffer.getInt();
        int numHashFunctions = buffer.getInt();
        int bitsLength = buffer.getInt();
        byte[] bitsBytes = new byte[bitsLength];
        buffer.get(bitsBytes);
        BitSet bits = BitSet.valueOf(bitsBytes);
        return new BloomFilter(bits, bitSetSize, numHashFunctions);
    }

    public int getBitSetSize() {
        return bitSetSize;
    }

    public int getNumHashFunctions() {
        return numHashFunctions;
    }
}
