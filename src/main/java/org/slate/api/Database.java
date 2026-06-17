package org.slate.api;

import java.io.Closeable;
import java.util.Optional;

public interface Database extends Closeable {
    void put(byte[] key, byte[] value);

    Optional<byte[]> get(byte[] key);

    void delete(byte[] key);
}
