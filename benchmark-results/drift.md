# [SYNTHETIC] zdict Drift Scenario Benchmark

> [!NOTE]
> **Dataset**: **SYNTHETIC** (all numbers derived from deterministic synthetic payload generators labeled `_dataset: "SYNTHETIC"`).

> [!IMPORTANT]
> **Ratio Definition**: All compression ratios in this report are **total-bytes based** ($\sum \text{uncompressed bytes} / \sum \text{compressed bytes}$), not an arithmetic average of per-payload ratios.

## Configuration & Parameters

- **Date**: `2026-10-20T06:46:25.8191977Z`
- **Java Version**: `21.0.10`
- **Traffic Stream**: **1000 payloads** total (600 Shape A, 400 Shape B)
- **Real Shape Switch Point**: Payload **#600**
- **Drift Detected At**: Payload **#608** (**8 payloads elapsed** after shape switch)
- **Dictionary Switch Points**: Adaptive-200: [808] | Adaptive-100: [708]
- **Evaluation Window**: **200 payloads**
- **Drift Detection Threshold**: `rolling mean < baseline * 0.85` (15% drop)
- **Reporting Window**: **100 payloads**

## Canonical 60/40 Stream Strategy Comparison

| Strategy | Total Compressed Size | Mean Ratio | Retrain Count | Post-Switch 100 Ratio | Training Time (ms) | Recovery Horizon |
|---|---|---|---|---|---|---|
| **gzip (level 6)** | 254,783 B | **1.24x** | 0 | 1.28x | 0 ms | - |
| **Static zstd+dict** | 135,008 B | **2.34x** | 0 | 1.30x | 78 ms | - |
| **Fixed-schedule zstd+dict** | 87,705 B | **3.61x** | 4 | 1.27x | 159 ms | 0 payloads |
| **Adaptive zdict (buffer=200)** | 88,888 B | **3.56x** | 1 | 1.30x | 98 ms | 208 payloads |
| **Adaptive zdict (buffer=100)** | 70,681 B | **4.48x** | 1 | 1.30x | 97 ms | 108 payloads |

## Sensitivity Matrix Across Switch Points & Seeds

*Evaluated across 4 switch points (`550, 650, 730, 800`) and 5 random seeds (`42, 101, 2024, 777, 9999`), total 20 simulation runs per strategy.*

| Strategy | Total Compressed Bytes (mean [min - max]) | Retrain Count (mean [min - max]) | Post-Switch 100 Ratio (mean [min - max]) | Training Time ms (mean [min - max]) |
|---|---|---|---|---|
| **gzip (level 6)** | 251,573 B [246,800 - 256,953] | 0.0 [0 - 0] | 1.28x [1.28x - 1.28x] | 0.0 ms [0 - 0] |
| **Static zstd+dict** | 116,050 B [87,546 - 148,122] | 0.0 [0 - 0] | 1.27x [1.26x - 1.27x] | 18.6 ms [16 - 25] |
| **Fixed-schedule zstd+dict** | 80,320 B [58,217 - 99,788] | 4.0 [4 - 4] | 1.36x [1.26x - 1.66x] | 88.2 ms [82 - 97] |
| **Adaptive zdict (buffer=200)** | 89,948 B [87,546 - 91,417] | 0.8 [0 - 1] | 1.27x [1.26x - 1.27x] | 31.8 ms [17 - 42] |
| **Adaptive zdict (buffer=100)** | 70,800 B [68,746 - 73,331] | 1.0 [1 - 1] | 1.27x [1.26x - 1.27x] | 35.3 ms [31 - 46] |

## No-Drift Stream Verification (Shape A Only, 1,000 Payloads)

| Strategy | Total Compressed Size | Mean Ratio | Retrain Count | Training Time (ms) | Efficiency Verdict |
|---|---|---|---|---|---|
| **gzip (level 6)** | 238,687 B | **1.21x** | **0** | 0 ms | - |
| **Static zstd+dict** | 39,105 B | **7.40x** | **0** | 17 ms | Optimal for static traffic |
| **Fixed-schedule zstd+dict** | 38,443 B | **7.53x** | **4** | 84 ms | **Wasteful** (retrains every 200 payloads with 0 drift) |
| **Adaptive zdict (buffer=200)** | 39,105 B | **7.40x** | **0** | 17 ms | **Optimal** (0 false alarms, 0 unnecessary retrains) |
| **Adaptive zdict (buffer=100)** | 39,105 B | **7.40x** | **0** | 17 ms | **Optimal** (0 false alarms, 0 unnecessary retrains) |

## Adaptive Variant Tradeoff: Buffer=100 vs Buffer=200

| Metric | Adaptive (buffer=200) | Adaptive (buffer=100) | Tradeoff Analysis |
|---|---|---|---|
| **Canonical Total Bytes** | 88,888 B | 70,681 B | **18,207 B saved** by buffer=100 due to earlier recovery |
| **Canonical Time-to-Recovery** | 208 payloads | 108 payloads | **100 payloads faster** recovery for buffer=100 |
| **Canonical Training Time** | 98 ms | 97 ms | Faster training on 100 samples vs 200 samples |
| **Switch Point 800 Horizon** | Fails to retrain before end (needs 200) | **Successfully recovers at #908** | Buffer=100 handles late-stage drift without truncation |

## Window-by-Window Breakdown

| Window | Payloads | Dominant Schema | gzip Ratio | Static Ratio | Fixed Ratio | Adaptive-200 Ratio | Adaptive-100 Ratio |
|---|---|---|---|---|---|---|---|
| #1 | 0 - 100 | Shape A | 1.21x | 9.07x | 9.07x | **9.07x** | **9.07x** |
| #2 | 100 - 200 | Shape A | 1.21x | 7.37x | 7.37x | **7.37x** | **7.37x** |
| #3 | 200 - 300 | Shape A | 1.21x | 7.09x | 7.09x | **7.09x** | **7.09x** |
| #4 | 300 - 400 | Shape A | 1.22x | 7.45x | 7.45x | **7.45x** | **7.45x** |
| #5 | 400 - 500 | Shape A | 1.21x | 7.10x | 7.27x | **7.10x** | **7.10x** |
| #6 | 500 - 600 | Shape A | 1.21x | 7.09x | 7.59x | **7.09x** | **7.09x** |
| #7 | 600 - 700 | Shape B | 1.28x | 1.30x | 1.27x | **1.30x** | **1.30x** |
| #8 | 700 - 800 | Shape B | 1.28x | 1.27x | 1.26x | **1.27x** | **4.59x** |
| #9 | 800 - 900 | Shape B | 1.28x | 1.27x | 8.45x | **5.85x** | **5.78x** |
| #10 | 900 - 1000 | Shape B | 1.28x | 1.27x | 8.70x | **8.72x** | **5.79x** |

