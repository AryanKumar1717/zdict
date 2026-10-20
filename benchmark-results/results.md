# zdict Replay Benchmark Results

- **Date**: `2026-10-20T06:22:02.0933411Z`
- **Java Version**: `21.0.10`
- **OS**: `Windows 11 amd64`
- **Note**: *Timing is a simple loop after JVM warmup, not JMH.*

## Overall Summary

- Total Payloads: **1000** (Train: 666, Test: 334)
- Uncompressed Test Bytes: **105,831 B**
- Dictionary Size: **32,768 B**
- Break-even vs gzip: **153 payloads** (avg savings: **215.2 B/payload**)

| Codec | Compressed Size | Compression Ratio | Space Savings | p50 Latency (µs) | p99 Latency (µs) |
|---|---|---|---|---|---|
| gzip (level 6) | 85,178 B | **1.24x** | 19.5% | 16.5 µs | 37.5 µs |
| zstd (level 3) | 86,105 B | **1.23x** | 18.6% | 7.3 µs | 11.8 µs |
| zstd+dict (level 3) | 13,311 B | **7.95x** | 87.4% | 3.2 µs | 7.4 µs |

## Per-Group Results

### Group: `products_v1`

- Total Payloads: **600** (Train: 400, Test: 200)
- Uncompressed Test Bytes: **57,935 B**
- Dictionary Size: **16,384 B**
- Break-even vs gzip: **82 payloads** (avg savings: **199.9 B/payload**)

| Codec | Compressed Size | Compression Ratio | Space Savings | p50 Latency (µs) | p99 Latency (µs) |
|---|---|---|---|---|---|
| gzip (level 6) | 47,745 B | **1.21x** | 17.6% | 15.4 µs | 47.7 µs |
| zstd (level 3) | 47,573 B | **1.22x** | 17.9% | 6.9 µs | 11.4 µs |
| zstd+dict (level 3) | 7,762 B | **7.46x** | 86.6% | 3.2 µs | 6.3 µs |

#### Ratio by Payload Size Bucket

| Size Bucket | Payload Count | gzip Ratio | zstd Ratio | zstd+dict Ratio |
|---|---|---|---|---|
| <1KB | 200 | 1.21x | 1.22x | **7.46x** |
| 1-4KB | 0 | 0.00x | 0.00x | **0.00x** |
| >4KB | 0 | 0.00x | 0.00x | **0.00x** |

### Group: `products_v2`

- Total Payloads: **400** (Train: 266, Test: 134)
- Uncompressed Test Bytes: **47,896 B**
- Dictionary Size: **16,384 B**
- Break-even vs gzip: **69 payloads** (avg savings: **237.9 B/payload**)

| Codec | Compressed Size | Compression Ratio | Space Savings | p50 Latency (µs) | p99 Latency (µs) |
|---|---|---|---|---|---|
| gzip (level 6) | 37,433 B | **1.28x** | 21.8% | 17.5 µs | 27.2 µs |
| zstd (level 3) | 38,532 B | **1.24x** | 19.6% | 7.6 µs | 12.2 µs |
| zstd+dict (level 3) | 5,549 B | **8.63x** | 88.4% | 3.2 µs | 8.4 µs |

#### Ratio by Payload Size Bucket

| Size Bucket | Payload Count | gzip Ratio | zstd Ratio | zstd+dict Ratio |
|---|---|---|---|---|
| <1KB | 134 | 1.28x | 1.24x | **8.63x** |
| 1-4KB | 0 | 0.00x | 0.00x | **0.00x** |
| >4KB | 0 | 0.00x | 0.00x | **0.00x** |

