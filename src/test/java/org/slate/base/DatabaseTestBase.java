package org.slate.base;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slate.metrics.MetricsRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public abstract class DatabaseTestBase {

    protected Path testDir;
    protected MetricsRegistry metrics;

    @BeforeEach
    void setupBase() throws IOException {
        testDir = Files.createTempDirectory("slatedb-test-");
        metrics  = new MetricsRegistry();
    }

    @AfterEach
    void teardownBase() throws IOException {
        // recursively delete temp dir after each test
        if (testDir != null && Files.exists(testDir)) {
            Files.walk(testDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); }
                        catch (IOException ignored) {}
                    });
        }
    }

    protected Path testFile(String name) {
        return testDir.resolve(name);
    }
}
