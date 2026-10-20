# zdict Replay Benchmark Results

- **Date**: `2026-10-20T10:20:38.0143087Z`
- **Java Version**: `21.0.10`
- **OS**: `Windows 11 amd64`
- **Note**: *Timing is a simple loop after JVM warmup, not JMH.*

## Per-Group Results

### Group: `forecast`

- Total Payloads: **300** (Train: 200, Test: 100)
- Average Payload Size: **1,001 B**
- Uncompressed Test Bytes: **100,185 B**
- Dictionary Size: **16,384 B**
- Break-even vs gzip: **72 payloads** (avg savings: **227.7 B/payload**)

| Codec | Compressed Size | Compression Ratio | Space Savings | p50 Latency (µs) | p99 Latency (µs) |
|---|---|---|---|---|---|
| gzip (level 6) | 35,118 B | **2.85x** | 64.9% | 15.5 µs | 59.7 µs |
| zstd (level 3) | 35,543 B | **2.82x** | 64.5% | 11.0 µs | 44.9 µs |
| zstd+dict (level 3) | 12,346 B | **8.11x** | 87.7% | 6.9 µs | 25.9 µs |

#### Ratio by Payload Size Bucket

| Size Bucket | Payload Count | gzip Ratio | zstd Ratio | zstd+dict Ratio |
|---|---|---|---|---|
| <1KB | 98 | 2.85x | 2.82x | **8.12x** |
| 1-4KB | 2 | 2.91x | 2.84x | **7.87x** |
| >4KB | 0 | 0.00x | 0.00x | **0.00x** |

### Group: `geocoding`

- Total Payloads: **200** (Train: 133, Test: 67)
- Average Payload Size: **1,566 B**
- Uncompressed Test Bytes: **136,091 B**
- Dictionary Size: **16,384 B**
- Break-even vs gzip: **94 payloads** (avg savings: **176.1 B/payload**)

| Codec | Compressed Size | Compression Ratio | Space Savings | p50 Latency (µs) | p99 Latency (µs) |
|---|---|---|---|---|---|
| gzip (level 6) | 45,732 B | **2.98x** | 66.4% | 43.6 µs | 130.4 µs |
| zstd (level 3) | 46,151 B | **2.95x** | 66.1% | 20.1 µs | 138.9 µs |
| zstd+dict (level 3) | 33,931 B | **4.01x** | 75.1% | 14.6 µs | 55.8 µs |

#### Ratio by Payload Size Bucket

| Size Bucket | Payload Count | gzip Ratio | zstd Ratio | zstd+dict Ratio |
|---|---|---|---|---|
| <1KB | 3 | 2.32x | 2.29x | **3.61x** |
| 1-4KB | 62 | 2.89x | 2.86x | **3.96x** |
| >4KB | 2 | 4.57x | 4.64x | **4.78x** |

