# zdict Drift Scenario Benchmark

- **Date**: `2026-10-20T06:21:27.1139327Z`
- **Traffic Stream**: **1000 payloads** total (600 Shape A, 400 Shape B)
- **Drift Retrains Triggered**: **2**

## Overall Comparison

| Strategy | Total Compressed Size | Overall Ratio | Savings vs gzip | Description |
|---|---|---|---|---|
| **gzip (level 6)** | 254,783 B | **1.24x** | baseline | Standard gzip compression |
| **Static zstd+dict** | 135,008 B | **2.34x** | 47.0% | Trained on Shape A, never updated (suffers on Shape B) |
| **Adaptive zdict** | 89,920 B | **3.52x** | **64.7%** | Self-learning: detects drift, retrains on fresh samples, recovers ratio |

> Adaptive zdict saved **45,088 bytes (33.4%)** compared to static dictionary compression after schema drift.

## Window-by-Window Breakdown

| Window | Payloads | Dominant Schema | gzip Ratio | Static Ratio | Adaptive Ratio | Note |
|---|---|---|---|---|---|---|
| #1 | 0 - 100 | Shape A | 1.21x | 9.07x | **9.07x** | - |
| #2 | 100 - 200 | Shape A | 1.21x | 7.37x | **7.37x** | - |
| #3 | 200 - 300 | Shape A | 1.21x | 7.09x | **7.09x** | - |
| #4 | 300 - 400 | Shape A | 1.22x | 7.45x | **7.45x** | - |
| #5 | 400 - 500 | Shape A | 1.21x | 7.10x | **7.10x** | - |
| #6 | 500 - 600 | Shape A | 1.21x | 7.09x | **7.09x** | - |
| #7 | 600 - 700 | Shape B | 1.28x | 1.30x | **1.27x** | Drift detected; accumulating fresh samples |
| #8 | 700 - 800 | Shape B | 1.28x | 1.27x | **1.26x** | Drift detected; accumulating fresh samples |
| #9 | 800 - 900 | Shape B | 1.28x | 1.27x | **5.83x** | **Ratio recovered after retrain** |
| #10 | 900 - 1000 | Shape B | 1.28x | 1.27x | **8.72x** | **Ratio recovered after retrain** |

