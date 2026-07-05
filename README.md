# slate-db
# SlateDB

A distributed LSM-tree key-value database built from scratch in Java 21.

Inspired by Apache Cassandra, Amazon DynamoDB, and RocksDB — built as a deep learning exercise in storage engine and distributed systems internals.

---

## What This Is

SlateDB is not a framework exercise. There is no Spring Boot, no ORM, no managed infrastructure. Every component — from the SkipList to the WAL to the Bloom filter — is built from first principles, with a benchmark at every milestone to prove the numbers match the theory.

---

## Architecture

```
Write Path
──────────
PUT/DELETE → WAL (fsync) → MemTable (SkipList)
                                 │
                          threshold hit
                                 │
                                 ▼
                           SSTable flush
                                 │
                         L0 threshold hit
                                 │
                                 ▼
                        Leveled Compaction
                       (k-way merge → L1)

Read Path
─────────
GET → MemTable → Bloom Filter → Sparse Index → SSTable data block
```

---

## Storage Engine Components

| Component | What it does |
|---|---|
| **SkipList** | Probabilistic sorted data structure. O(log n) reads/writes. Core of the MemTable. |
| **MemTable** | In-memory write buffer backed by the SkipList. Tombstone-aware deletes. Flush threshold. |
| **WAL** | Append-only write-ahead log. CRC32 checksums per record. Crash recovery via replay. |
| **SSTable** | Immutable on-disk sorted file. Data block + Sparse Index + Bloom Filter + variable footer. |
| **Bloom Filter** | Probabilistic membership test per SSTable. Eliminates unnecessary disk reads on misses. |
| **Sparse Index** | Block index with exact chunk boundaries. O(log n/k) binary search + O(k) scan. |
| **Leveled Compaction** | K-way merge of L0 files into L1. Tombstone resolution. Write stall on L0 threshold. |

---

## Milestones

```
M-1   Engineering Foundation     Logging, MetricsRegistry, BenchmarkRunner, DatabaseTestBase
M0    In-Memory KV Store         HashMap-backed PUT/GET/DELETE, stable Database interface
M1    SkipList + MemTable         Sorted in-memory store, tombstones, property-based tests
M2    WAL                        Durability, CRC32 checksums, crash recovery
M3    SSTable + Bloom Filter     MemTable flush to disk, Bloom filter at flush time
M4    Sparse Index               Exact chunk boundaries, 88x GET improvement over M3
M5    Leveled Compaction         K-way merge iterator, tombstone resolution, write stalls
M6    Concurrent Engine          [ upcoming ]
M7    Netty TCP Server           [ upcoming ]
M8    Consistent Hashing         [ upcoming ]
M9    Replication                [ upcoming ]
M10   Quorum Reads/Writes        [ upcoming ]
M11   Read Repair + Gossip       [ upcoming ]
M12   Metrics + Observability    [ upcoming ]
M13   Load Testing               [ upcoming ]
M14   Failure Testing            [ upcoming ]
```

---

## Benchmark Results

Every milestone is benchmarked before moving on. Numbers are from JMH on a local machine — absolute values vary by hardware, ratios are what matter.

```
Operation           Throughput        Notes
──────────────────────────────────────────────────────────────────
HashMap GET         ~16M ops/sec      O(1), unsorted — M0 baseline
MemTable GET        ~1.6M ops/sec     O(log n), sorted — 10x cost of sort
MemTable PUT        ~1.5M ops/sec     Fixed from 36x regression (double-traversal bug)
WAL append          ~2K ops/sec       fsync-bound — real cost of durability
SSTable GET (M3)    ~4–8K ops/sec     O(n) full scan, no index
SSTable GET (M4)    ~386K ops/sec     88x improvement with sparse index
```

The WAL number (~2K ops/sec) is not a bug. That is the physical cost of `fsync` — the guarantee that an acknowledged write survives a crash.

---

## Key Engineering Decisions

**Why SkipList over Red-Black Tree**
Lock-striping for concurrent access (M6) is simpler on a SkipList. Forward iteration for SSTable flush is natural. It's what RocksDB, Cassandra, and LevelDB use.

**Why tombstones instead of in-place deletes**
Older versions of a deleted key may still exist in SSTable files on disk. A tombstone shadows them during reads and is resolved during compaction — once it reaches the oldest level, it's safe to drop.

**Why exact chunk boundaries in the sparse index**
An average-based estimate for the scan window would silently produce false negatives when entry sizes vary within a chunk. The exact-boundary approach stores `chunkLengthBytes` per index entry at write time, computed from the next entry's offset (or end of data block for the last entry). No estimation, no correctness risk.

**Why footer-of-the-footer**
The SSTable footer is variable-length (contains minKey/maxKey). A fixed-size trailer (4 bytes, always last in the file) stores the footer's total length, so the reader always knows exactly how far to seek without scanning.

**Why `force(true)` on every WAL write**
Without fsync, the OS page cache can buffer writes and lose them on a crash even after `write()` returns. The ~750x throughput cost vs MemTable is the price of the durability guarantee. Group commit is a logged future optimization.

---

## What's Deliberately Out of Scope

- SQL layer
- ACID transactions / 2PC
- Raft / Paxos (this is a Dynamo-style AP system, not CP)
- Secondary indexes
- Authentication
- Multi-datacenter replication
- Compression

---

## Tech Stack

```
Language      Java 21
Build         Maven (no Spring Boot, no frameworks)
Testing       JUnit 5 + AssertJ + jqwik (property-based)
Benchmarking  JMH (Java Microbenchmark Harness)
Networking    Netty (M7+)
Metrics       Micrometer + Prometheus (M12+)
Hashing       Guava MurmurHash (Bloom filter only)
Logging       SLF4J + Logback
```

---

## Running Tests

```bash
mvn test
```

## Running Benchmarks

```bash
mvn clean package -DskipTests
java -jar target/benchmarks.jar
```

---

## Future Optimizations (Logged, Not Yet Built)

- VarHandle word-at-a-time byte comparator (replaces `Arrays.compare` in SkipList)
- Buffered chunked WAL reader (replaces per-field syscall reads)
- Streaming bounded-memory SSTable iterator (replaces full data block load in MergeIterator)
- Group commit / batched fsync for WAL throughput
- WAL segment rotation + truncation post-flush

---

## Project Status

Active development. Storage engine (M0–M5) complete. Concurrency and networking layer next.
