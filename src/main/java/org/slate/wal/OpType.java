package org.slate.wal;

public enum OpType {
    PUT((byte)0),
    DELETE((byte)1);

    private final byte code;

    OpType(byte code){this.code = code;}

    public byte code() {
        return code;
    }

    public static OpType fromCode(byte code) {
        for (OpType op : values()) {
            if (op.code == code) return op;
        }
        throw new IllegalArgumentException("Unknown WAL opType code: " + code);
    }
}
