# zdict Replay Benchmark Results

- **Date**: `2026-10-20T10:04:02.4586506Z`
- **Java Version**: `21.0.10`
- **OS**: `Windows 11 amd64`
- **Note**: *Timing is a simple loop after JVM warmup, not JMH.*

## Overall Summary

- Total Payloads: **600** (Train: 400, Test: 200)
- Uncompressed Test Bytes: **26,360,037 B**
- Dictionary Size: **32,768 B**
- Break-even vs gzip: **143 payloads** (avg savings: **230.2 B/payload**)

| Codec | Compressed Size | Compression Ratio | Space Savings | p50 Latency (µs) | p99 Latency (µs) |
|---|---|---|---|---|---|
| gzip (level 6) | 1,226,371 B | **21.49x** | 95.3% | 664.9 µs | 1238.3 µs |
| zstd (level 3) | 1,294,818 B | **20.36x** | 95.1% | 170.2 µs | 383.6 µs |
| zstd+dict (level 3) | 1,180,338 B | **22.33x** | 95.5% | 187.9 µs | 579.1 µs |

## Per-Group Results

### Group: `pokemon`

- Total Payloads: **300** (Train: 200, Test: 100)
- Uncompressed Test Bytes: **23,662,633 B**
- Dictionary Size: **16,384 B**

| Codec | Compressed Size | Compression Ratio | Space Savings | p50 Latency (µs) | p99 Latency (µs) |
|---|---|---|---|---|---|
| gzip (level 6) | 684,951 B | **34.55x** | 97.1% | 1069.0 µs | 1996.3 µs |
| zstd (level 3) | 722,837 B | **32.74x** | 96.9% | 258.1 µs | 503.0 µs |
| zstd+dict (level 3) | 693,610 B | **34.12x** | 97.1% | 282.1 µs | 724.7 µs |

#### Ratio by Payload Size Bucket

| Size Bucket | Payload Count | gzip Ratio | zstd Ratio | zstd+dict Ratio |
|---|---|---|---|---|
| <1KB | 0 | 0.00x | 0.00x | **0.00x** |
| 1-4KB | 0 | 0.00x | 0.00x | **0.00x** |
| >4KB | 100 | 34.55x | 32.74x | **34.12x** |

### Group: `species`

- Total Payloads: **300** (Train: 200, Test: 100)
- Uncompressed Test Bytes: **2,697,404 B**
- Dictionary Size: **16,384 B**
- Break-even vs gzip: **30 payloads** (avg savings: **546.9 B/payload**)

| Codec | Compressed Size | Compression Ratio | Space Savings | p50 Latency (µs) | p99 Latency (µs) |
|---|---|---|---|---|---|
| gzip (level 6) | 541,420 B | **4.98x** | 79.9% | 260.7 µs | 480.3 µs |
| zstd (level 3) | 571,981 B | **4.72x** | 78.8% | 82.3 µs | 264.2 µs |
| zstd+dict (level 3) | 486,728 B | **5.54x** | 82.0% | 93.6 µs | 433.4 µs |

#### Ratio by Payload Size Bucket

| Size Bucket | Payload Count | gzip Ratio | zstd Ratio | zstd+dict Ratio |
|---|---|---|---|---|
| <1KB | 0 | 0.00x | 0.00x | **0.00x** |
| 1-4KB | 0 | 0.00x | 0.00x | **0.00x** |
| >4KB | 100 | 4.98x | 4.72x | **5.54x** |

