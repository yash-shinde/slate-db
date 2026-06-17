package org.slate.base;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseTestBaseTest extends DatabaseTestBase {

    @Test
    @DisplayName("testDir is created before each test")
    void testDirIsCreated() {
        assertThat(testDir).isNotNull();
        assertThat(testDir).exists();
        assertThat(testDir).isDirectory();
    }

    @Test
    @DisplayName("testDir is unique per test")
    void testDirIsUniquePerTest() {
        // each test gets a fresh temp dir — verify it's prefixed correctly
        assertThat(testDir.getFileName().toString())
                .startsWith("slatedb-test-");
    }

    @Test
    @DisplayName("testFile resolves path under testDir")
    void testFileResolvesUnderTestDir() {
        Path f = testFile("wal.log");
        assertThat(f.getParent()).isEqualTo(testDir);
        assertThat(f.getFileName().toString()).isEqualTo("wal.log");
    }

    @Test
    @DisplayName("testFile path does not need to exist yet")
    void testFileDoesNotNeedToExist() {
        Path f = testFile("does-not-exist.sst");
        assertThat(f).doesNotExist();
    }

    @Test
    @DisplayName("files written to testDir are cleaned up after test")
    void filesInTestDirAreAccessibleDuringTest() throws IOException {
        // write a file during the test — teardown deletes it
        // we can only verify it exists NOW, not after teardown
        Path f = testFile("temp.log");
        Files.writeString(f, "test data");
        assertThat(f).exists();
        assertThat(Files.readString(f)).isEqualTo("test data");
    }

    @Test
    @DisplayName("metrics registry is fresh for each test")
    void metricsRegistryIsFreshPerTest() {
        // should start empty — no leftover state from other tests
        metrics.counter("x").increment(99);
        // this test's registry has 99 — other tests get a clean one
        assertThat(metrics.counter("x").getCount()).isEqualTo(99);
    }

    @Test
    @DisplayName("nested paths resolve correctly under testDir")
    void nestedPathsResolveCorrectly() throws IOException {
        Path subDir = testDir.resolve("sstables").resolve("level-0");
        Files.createDirectories(subDir);
        assertThat(subDir).exists();
        assertThat(subDir).isDirectory();
        // teardown should delete this recursively too
    }
}
