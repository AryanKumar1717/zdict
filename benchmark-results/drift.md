# [SYNTHETIC] zdict Drift Scenario Benchmark

> [!NOTE]
> **Dataset**: **SYNTHETIC** (all numbers derived from deterministic synthetic payload generators labeled `_dataset: "SYNTHETIC"`).

> [!IMPORTANT]
> **Ratio Definition**: All compression ratios in this report are **total-bytes based** ($\sum \text{uncompressed bytes} / \sum \text{compressed bytes}$), not an arithmetic average of per-payload ratios.

## Configuration & Parameters

- **Date**: `2026-10-20T07:04:45.8290763Z`
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
| **Static zstd+dict** | 135,008 B | **2.34x** | 0 | 1.30x | 99 ms | - |
| **Fixed-schedule (200/200)** | 87,705 B | **3.61x** | 4 | 1.27x | 228 ms | 0 payloads |
| **Fixed-schedule (100/100)** | 77,556 B | **4.08x** | 9 | 1.31x | 307 ms | 0 payloads |
| **Adaptive zdict (buffer=200)** | 88,888 B | **3.56x** | 1 | 1.30x | 144 ms | 208 payloads |
| **Adaptive zdict (buffer=100)** | 70,681 B | **4.48x** | 1 | 1.30x | 131 ms | 108 payloads |

## Sensitivity Matrix: One Row Per (Strategy, Switch Point) Across 5 Seeds

*Evaluated across 4 switch points (`550, 650, 730, 800`) and 5 random seeds (`42, 101, 2024, 777, 9999`). Means and min/max computed across seeds for each switch point.*

| Strategy | Switch Point | Total Compressed Bytes (mean [min - max]) | Retrain Count (mean [min - max]) | Post-Switch 100 Ratio (mean [min - max]) | Training Time ms (mean [min - max]) |
|---|---|---|---|---|---|
| **gzip (level 6)** | #550 | 256,953 B [256,953 - 256,953] | 0.0 [0 - 0] | 1.28x [1.28x - 1.28x] | 0.0 ms [0 - 0] |
| **Static zstd+dict** | #550 | 148,122 B [148,122 - 148,122] | 0.0 [0 - 0] | 1.27x [1.27x - 1.27x] | 25.6 ms [24 - 27] |
| **Fixed-schedule (200/200)** | #550 | 99,788 B [99,788 - 99,788] | 4.0 [4 - 4] | 1.26x [1.26x - 1.26x] | 141.6 ms [120 - 167] |
| **Fixed-schedule (100/100)** | #550 | 68,953 B [68,953 - 68,953] | 9.0 [9 - 9] | 2.03x [2.03x - 2.03x] | 271.8 ms [226 - 334] |
| **Adaptive zdict (buffer=200)** | #550 | 90,337 B [90,337 - 90,337] | 1.0 [1 - 1] | 1.27x [1.27x - 1.27x] | 54.8 ms [48 - 67] |
| **Adaptive zdict (buffer=100)** | #550 | 73,331 B [73,331 - 73,331] | 1.0 [1 - 1] | 1.27x [1.27x - 1.27x] | 51.2 ms [47 - 61] |
| **gzip (level 6)** | #650 | 252,899 B [252,899 - 252,899] | 0.0 [0 - 0] | 1.28x [1.28x - 1.28x] | 0.0 ms [0 - 0] |
| **Static zstd+dict** | #650 | 123,967 B [123,967 - 123,967] | 0.0 [0 - 0] | 1.27x [1.27x - 1.27x] | 33.2 ms [22 - 41] |
| **Fixed-schedule (200/200)** | #650 | 76,044 B [76,044 - 76,044] | 4.0 [4 - 4] | 1.26x [1.26x - 1.26x] | 163.8 ms [126 - 194] |
| **Fixed-schedule (100/100)** | #650 | 68,142 B [68,142 - 68,142] | 9.0 [9 - 9] | 2.05x [2.05x - 2.05x] | 308.2 ms [245 - 369] |
| **Adaptive zdict (buffer=200)** | #650 | 90,492 B [90,492 - 90,492] | 1.0 [1 - 1] | 1.27x [1.27x - 1.27x] | 67.8 ms [56 - 78] |
| **Adaptive zdict (buffer=100)** | #650 | 70,821 B [70,821 - 70,821] | 1.0 [1 - 1] | 1.27x [1.27x - 1.27x] | 62.4 ms [47 - 77] |
| **gzip (level 6)** | #730 | 249,641 B [249,641 - 249,641] | 0.0 [0 - 0] | 1.28x [1.28x - 1.28x] | 0.0 ms [0 - 0] |
| **Static zstd+dict** | #730 | 104,564 B [104,564 - 104,564] | 0.0 [0 - 0] | 1.26x [1.26x - 1.26x] | 39.8 ms [38 - 41] |
| **Fixed-schedule (200/200)** | #730 | 58,217 B [58,217 - 58,217] | 4.0 [4 - 4] | 1.66x [1.66x - 1.66x] | 165.4 ms [131 - 190] |
| **Fixed-schedule (100/100)** | #730 | 71,710 B [71,710 - 71,710] | 9.0 [9 - 9] | 1.64x [1.64x - 1.64x] | 301.6 ms [231 - 353] |
| **Adaptive zdict (buffer=200)** | #730 | 91,417 B [91,417 - 91,417] | 1.0 [1 - 1] | 1.26x [1.26x - 1.26x] | 75.8 ms [58 - 82] |
| **Adaptive zdict (buffer=100)** | #730 | 70,302 B [70,302 - 70,302] | 1.0 [1 - 1] | 1.26x [1.26x - 1.26x] | 71.0 ms [55 - 77] |
| **gzip (level 6)** | #800 | 246,800 B [246,800 - 246,800] | 0.0 [0 - 0] | 1.28x [1.28x - 1.28x] | 0.0 ms [0 - 0] |
| **Static zstd+dict** | #800 | 87,546 B [87,546 - 87,546] | 0.0 [0 - 0] | 1.27x [1.27x - 1.27x] | 17.2 ms [16 - 19] |
| **Fixed-schedule (200/200)** | #800 | 87,230 B [87,230 - 87,230] | 4.0 [4 - 4] | 1.26x [1.26x - 1.26x] | 85.8 ms [83 - 89] |
| **Fixed-schedule (100/100)** | #800 | 76,822 B [76,822 - 76,822] | 9.0 [9 - 9] | 1.28x [1.28x - 1.28x] | 173.0 ms [157 - 223] |
| **Adaptive zdict (buffer=200)** | #800 | 87,546 B [87,546 - 87,546] | 0.0 [0 - 0] *(5/5 seeds 0 retrains)* | 1.27x [1.27x - 1.27x] | 17.2 ms [16 - 19] |
| **Adaptive zdict (buffer=100)** | #800 | 68,746 B [68,746 - 68,746] | 1.0 [1 - 1] | 1.27x [1.27x - 1.27x] | 33.8 ms [32 - 35] |

## Missed-Drift & Horizon Truncation Analysis (Adaptive Runs with 0 Retrains)

| Strategy | Switch Point | Seed | Drift Detected At | Payloads Left After Detection | Buffer Needed | Horizon Diagnosis |
|---|---|---|---|---|---|---|
| **Adaptive zdict (buffer=200)** | #800 | 42 | #815 (15 elapsed) | **185 payloads** | 200 payloads | Detected at #815 (15 elapsed after switch #800), leaving 185 payloads in stream (< 200 needed for buffer). Retrain could not complete before stream end. |
| **Adaptive zdict (buffer=200)** | #800 | 101 | #815 (15 elapsed) | **185 payloads** | 200 payloads | Detected at #815 (15 elapsed after switch #800), leaving 185 payloads in stream (< 200 needed for buffer). Retrain could not complete before stream end. |
| **Adaptive zdict (buffer=200)** | #800 | 2024 | #815 (15 elapsed) | **185 payloads** | 200 payloads | Detected at #815 (15 elapsed after switch #800), leaving 185 payloads in stream (< 200 needed for buffer). Retrain could not complete before stream end. |
| **Adaptive zdict (buffer=200)** | #800 | 777 | #815 (15 elapsed) | **185 payloads** | 200 payloads | Detected at #815 (15 elapsed after switch #800), leaving 185 payloads in stream (< 200 needed for buffer). Retrain could not complete before stream end. |
| **Adaptive zdict (buffer=200)** | #800 | 9999 | #815 (15 elapsed) | **185 payloads** | 200 payloads | Detected at #815 (15 elapsed after switch #800), leaving 185 payloads in stream (< 200 needed for buffer). Retrain could not complete before stream end. |

## Stationary Traffic Stream Verification

### Clean No-Drift Stream (Shape A Only, 1,000 Payloads)

| Strategy | Total Compressed Size | Mean Ratio | Retrain Count | Training Time (ms) | Efficiency Verdict |
|---|---|---|---|---|---|
| **gzip (level 6)** | 238,687 B | **1.21x** | **0** | 0 ms | - |
| **Static zstd+dict** | 39,105 B | **7.40x** | **0** | 17 ms | Optimal for static traffic |
| **Fixed-schedule (200/200)** | 38,443 B | **7.53x** | **4** | 82 ms | **Wasteful** (4 blind retrains with 0 drift) |
| **Fixed-schedule (100/100)** | 53,589 B | **5.40x** | **9** | 159 ms | **Wasteful** (9 blind retrains with 0 drift) |
| **Adaptive zdict (buffer=200)** | 39,105 B | **7.40x** | **0** | 17 ms | **Optimal** (0 false alarms, 0 unnecessary retrains) |
| **Adaptive zdict (buffer=100)** | 39,105 B | **7.40x** | **0** | 17 ms | **Optimal** (0 false alarms, 0 unnecessary retrains) |

### Noisy-Stationary Stream (Shape A Varying Sizes + Outliers, 1,000 Payloads)

| Strategy | Total Compressed Size | Mean Ratio | Retrain Count | False Alarm Count | Training Time (ms) | Resilience Verdict |
|---|---|---|---|---|---|---|
| **gzip (level 6)** | 252,470 B | **1.62x** | **0** | **0** | 0 ms | - |
| **Static zstd+dict** | 46,123 B | **8.87x** | **0** | **0** | 19 ms | Stationary reference |
| **Fixed-schedule (200/200)** | 46,009 B | **8.89x** | **4** | **0** | 86 ms | **Blind Schedule**: 4 retrains regardless of noise |
| **Fixed-schedule (100/100)** | 60,792 B | **6.73x** | **9** | **0** | 167 ms | **Blind Schedule**: 9 retrains regardless of noise |
| **Adaptive zdict (buffer=200)** | 46,123 B | **8.87x** | **0** | **0** | 19 ms | **Robust**: 0 false alarms despite noise & outliers |
| **Adaptive zdict (buffer=100)** | 46,123 B | **8.87x** | **0** | **0** | 19 ms | **Robust**: 0 false alarms despite noise & outliers |

## Adaptive Variant Tradeoff: Buffer=100 vs Buffer=200

| Metric | Adaptive (buffer=200) | Adaptive (buffer=100) | Tradeoff Analysis |
|---|---|---|---|
| **Canonical Total Bytes** | 88,888 B | 70,681 B | **18,207 B saved** by buffer=100 due to earlier recovery |
| **Canonical Time-to-Recovery** | 208 payloads | 108 payloads | **100 payloads faster** recovery for buffer=100 |
| **Canonical Training Time** | 144 ms | 131 ms | Faster training on 100 samples vs 200 samples |
| **Switch Point 800 Horizon** | Fails to retrain before end (needs 200) | **Successfully recovers at #908** | Buffer=100 handles late-stage drift without truncation |

## Window-by-Window Breakdown

| Window | Payloads | Dominant Schema | gzip Ratio | Static Ratio | Fixed-200 Ratio | Fixed-100 Ratio | Adaptive-200 Ratio | Adaptive-100 Ratio |
|---|---|---|---|---|---|---|---|---|
| #1 | 0 - 100 | Shape A | 1.21x | 9.07x | 9.07x | 5.93x | **9.07x** | **9.07x** |
| #2 | 100 - 200 | Shape A | 1.21x | 7.37x | 7.37x | 5.55x | **7.37x** | **7.37x** |
| #3 | 200 - 300 | Shape A | 1.21x | 7.09x | 7.09x | 5.30x | **7.09x** | **7.09x** |
| #4 | 300 - 400 | Shape A | 1.22x | 7.45x | 7.45x | 5.30x | **7.45x** | **7.45x** |
| #5 | 400 - 500 | Shape A | 1.21x | 7.10x | 7.27x | 5.27x | **7.10x** | **7.10x** |
| #6 | 500 - 600 | Shape A | 1.21x | 7.09x | 7.59x | 5.34x | **7.09x** | **7.09x** |
| #7 | 600 - 700 | Shape B | 1.28x | 1.30x | 1.27x | 1.31x | **1.30x** | **1.30x** |
| #8 | 700 - 800 | Shape B | 1.28x | 1.27x | 1.26x | 5.90x | **1.27x** | **4.59x** |
| #9 | 800 - 900 | Shape B | 1.28x | 1.27x | 8.45x | 5.90x | **5.85x** | **5.78x** |
| #10 | 900 - 1000 | Shape B | 1.28x | 1.27x | 8.70x | 5.66x | **8.72x** | **5.79x** |

