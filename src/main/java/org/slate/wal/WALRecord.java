package org.slate.wal;

import java.util.Arrays;

public record WALRecord(OpType opType, byte[] key, byte[] value) {
    public static WALRecord put(byte[] key, byte[] value) {
        return new WALRecord(OpType.PUT, key, value);
    }

    public static WALRecord delete(byte[] key) {
        return new WALRecord(OpType.DELETE, key, new byte[0]);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WALRecord other)) return false;
        return opType == other.opType
                && Arrays.equals(key, other.key)
                && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        int result = opType.hashCode();
        result = 31 * result + Arrays.hashCode(key);
        result = 31 * result + Arrays.hashCode(value);
        return result;
    }
}
