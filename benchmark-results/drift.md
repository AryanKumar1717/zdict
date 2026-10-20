# [SYNTHETIC] zdict Drift Scenario Benchmark

> [!NOTE]
> **Dataset**: **SYNTHETIC** (all numbers derived from deterministic synthetic payload generators labeled `_dataset: "SYNTHETIC"`).

> [!IMPORTANT]
> **Ratio Definition**: All compression ratios in this report are **total-bytes based** ($\sum \text{uncompressed bytes} / \sum \text{compressed bytes}$), not an arithmetic average of per-payload ratios.

## Configuration & Parameters

- **Date**: `2026-10-20T07:54:04.8866907Z`
- **Java Version**: `21.0.10`
- **Traffic Stream**: **1000 payloads** total (600 Shape A, 400 Shape B)
- **Real Shape Switch Point**: Payload **#600**
- **Drift Detected At**: Payload **#640** (**40 payloads elapsed** after shape switch)
- **Dictionary Switch Points**: Adaptive-200: [840] | Adaptive-100: [740]
- **Evaluation Window**: **200 payloads**
- **Drift Detection Threshold**: `rolling mean < baseline * 0.85` (15% drop)
- **Reporting Window**: **100 payloads**

## Canonical 60/40 Stream Strategy Comparison

| Strategy | Total Compressed Size | Mean Ratio | Retrain Count | Post-Switch 100 Ratio | Training Time (ms) | Recovery Horizon |
|---|---|---|---|---|---|---|
| **gzip (level 6)** | 261,710 B | **1.26x** | 0 | 1.29x | 0 ms | - |
| **Static zstd+dict** | 146,904 B | **2.25x** | 0 | 1.33x | 174 ms | - |
| **Fixed-schedule (200/200)** | 104,306 B | **3.17x** | 4 | 1.30x | 308 ms | 200 payloads |
| **Fixed-schedule (100/100)** | 94,526 B | **3.50x** | 9 | 1.30x | 327 ms | 100 payloads |
| **Adaptive zdict (buffer=200)** | 112,463 B | **2.94x** | 1 | 1.33x | 205 ms | 240 payloads |
| **Adaptive zdict (buffer=100)** | 95,133 B | **3.48x** | 1 | 1.33x | 217 ms | 140 payloads |

## Sensitivity Matrix: One Row Per (Strategy, Switch Point) Across 5 Seeds

*Evaluated across 4 switch points (`550, 650, 730, 800`) and 5 random seeds (`42, 101, 2024, 777, 9999`). Means and min/max computed across seeds for each switch point.*

| Strategy | Switch Point | Total Compressed Bytes (mean [min - max]) | Retrain Count (mean [min - max]) | Post-Switch 100 Ratio (mean [min - max]) | Training Time ms (mean [min - max]) |
|---|---|---|---|---|---|
| **gzip (level 6)** | #550 | 263,708 B [263,546 - 263,931] | 0.0 [0 - 0] | 1.29x [1.29x - 1.29x] | 0.0 ms [0 - 0] |
| **Static zstd+dict** | #550 | 159,869 B [158,528 - 162,221] | 0.0 [0 - 0] | 1.31x [1.30x - 1.33x] | 30.4 ms [25 - 38] |
| **Fixed-schedule (200/200)** | #550 | 115,646 B [114,945 - 117,147] | 4.0 [4 - 4] | 1.31x [1.30x - 1.31x] | 162.6 ms [125 - 192] |
| **Fixed-schedule (100/100)** | #550 | 85,545 B [85,092 - 85,946] | 9.0 [9 - 9] | 2.02x [1.99x - 2.05x] | 326.4 ms [252 - 407] |
| **Adaptive zdict (buffer=200)** | #550 | 114,027 B [112,585 - 117,756] | 1.0 [1 - 1] | 1.31x [1.30x - 1.33x] | 65.0 ms [49 - 76] |
| **Adaptive zdict (buffer=100)** | #550 | 97,333 B [96,253 - 101,049] | 2.0 [2 - 2] | 1.31x [1.30x - 1.33x] | 100.6 ms [87 - 108] |
| **gzip (level 6)** | #650 | 259,482 B [259,372 - 259,635] | 0.0 [0 - 0] | 1.29x [1.29x - 1.29x] | 0.0 ms [0 - 0] |
| **Static zstd+dict** | #650 | 137,957 B [136,413 - 141,460] | 0.0 [0 - 0] | 1.31x [1.30x - 1.33x] | 32.2 ms [23 - 37] |
| **Fixed-schedule (200/200)** | #650 | 93,831 B [93,327 - 95,490] | 4.0 [4 - 4] | 1.31x [1.29x - 1.32x] | 155.0 ms [122 - 181] |
| **Fixed-schedule (100/100)** | #650 | 84,438 B [84,026 - 84,687] | 9.0 [9 - 9] | 2.03x [1.99x - 2.05x] | 291.0 ms [225 - 352] |
| **Adaptive zdict (buffer=200)** | #650 | 113,469 B [112,032 - 118,460] | 1.0 [1 - 1] | 1.31x [1.30x - 1.33x] | 60.2 ms [46 - 72] |
| **Adaptive zdict (buffer=100)** | #650 | 94,992 B [93,322 - 100,382] | 1.0 [1 - 1] | 1.31x [1.30x - 1.33x] | 61.4 ms [47 - 84] |
| **gzip (level 6)** | #730 | 256,092 B [255,891 - 256,228] | 0.0 [0 - 0] | 1.29x [1.29x - 1.29x] | 0.0 ms [0 - 0] |
| **Static zstd+dict** | #730 | 120,446 B [118,734 - 124,932] | 0.0 [0 - 0] | 1.31x [1.29x - 1.34x] | 27.0 ms [17 - 39] |
| **Fixed-schedule (200/200)** | #730 | 77,028 B [76,295 - 78,605] | 4.0 [4 - 4] | 1.68x [1.67x - 1.69x] | 142.2 ms [87 - 210] |
| **Fixed-schedule (100/100)** | #730 | 87,612 B [87,352 - 87,796] | 9.0 [9 - 9] | 1.68x [1.66x - 1.70x] | 264.8 ms [171 - 374] |
| **Adaptive zdict (buffer=200)** | #730 | 113,337 B [111,553 - 118,711] | 1.0 [1 - 1] | 1.31x [1.29x - 1.34x] | 55.6 ms [34 - 82] |
| **Adaptive zdict (buffer=100)** | #730 | 93,705 B [91,586 - 99,382] | 1.0 [1 - 1] | 1.31x [1.29x - 1.34x] | 59.0 ms [33 - 81] |
| **gzip (level 6)** | #800 | 253,154 B [252,925 - 253,303] | 0.0 [0 - 0] | 1.29x [1.29x - 1.29x] | 0.0 ms [0 - 0] |
| **Static zstd+dict** | #800 | 105,116 B [103,232 - 110,484] | 0.0 [0 - 0] | 1.31x [1.30x - 1.33x] | 18.2 ms [17 - 20] |
| **Fixed-schedule (200/200)** | #800 | 103,809 B [103,135 - 105,582] | 4.0 [4 - 4] | 1.30x [1.30x - 1.31x] | 95.2 ms [88 - 99] |
| **Fixed-schedule (100/100)** | #800 | 92,896 B [92,350 - 93,167] | 9.0 [9 - 9] | 1.31x [1.30x - 1.33x] | 177.4 ms [171 - 186] |
| **Adaptive zdict (buffer=200)** | #800 | 105,116 B [103,232 - 110,484] | 0.0 [0 - 0] *(5/5 seeds 0 retrains)* | 1.31x [1.30x - 1.33x] | 18.2 ms [17 - 20] |
| **Adaptive zdict (buffer=100)** | #800 | 92,520 B [90,451 - 98,790] | 1.0 [1 - 1] | 1.31x [1.30x - 1.33x] | 36.2 ms [35 - 39] |

## Missed-Drift & Horizon Truncation Analysis (Adaptive Runs with 0 Retrains)

| Strategy | Switch Point | Seed | Drift Detected At | Payloads Left After Detection | Buffer Needed | Horizon Diagnosis |
|---|---|---|---|---|---|---|
| **Adaptive zdict (buffer=200)** | #800 | 42 | #839 (39 elapsed) | **161 payloads** | 200 payloads | Detected at #839 (39 elapsed after switch #800), leaving 161 payloads in stream (< 200 needed for buffer). Retrain could not complete before stream end. |
| **Adaptive zdict (buffer=200)** | #800 | 101 | #841 (41 elapsed) | **159 payloads** | 200 payloads | Detected at #841 (41 elapsed after switch #800), leaving 159 payloads in stream (< 200 needed for buffer). Retrain could not complete before stream end. |
| **Adaptive zdict (buffer=200)** | #800 | 2024 | #840 (40 elapsed) | **160 payloads** | 200 payloads | Detected at #840 (40 elapsed after switch #800), leaving 160 payloads in stream (< 200 needed for buffer). Retrain could not complete before stream end. |
| **Adaptive zdict (buffer=200)** | #800 | 777 | #833 (33 elapsed) | **167 payloads** | 200 payloads | Detected at #833 (33 elapsed after switch #800), leaving 167 payloads in stream (< 200 needed for buffer). Retrain could not complete before stream end. |
| **Adaptive zdict (buffer=200)** | #800 | 9999 | #836 (36 elapsed) | **164 payloads** | 200 payloads | Detected at #836 (36 elapsed after switch #800), leaving 164 payloads in stream (< 200 needed for buffer). Retrain could not complete before stream end. |

## Gradual-Drift Stream Verification (400-Payload Transition: #300 -> #700)

| Strategy | Detected Drift? | Detected At | Payloads Into Transition | Retrain Count | Total Bytes | Mean Ratio | Training Time (ms) |
|---|---|---|---|---|---|---|---|
| **gzip (level 6)** | No | - | - | 0 | 265,557 B | **1.27x** | 0 ms |
| **Static zstd+dict** | No | - | - | 0 | 155,617 B | **2.17x** | 32 ms |
| **Fixed-schedule (200/200)** | No (blind schedule) | - | - | 4 | 98,784 B | **3.42x** | 103 ms |
| **Fixed-schedule (100/100)** | No (blind schedule) | - | - | 9 | 91,141 B | **3.70x** | 175 ms |
| **Adaptive zdict (buffer=200)** | **Yes** | #475 | 175 payloads | 1 | 102,635 B | **3.29x** | 48 ms |
| **Adaptive zdict (buffer=100)** | **Yes** | #475 | 175 payloads | 2 | 112,777 B | **2.99x** | 69 ms |

## Stationary Traffic Stream Verification

### Clean No-Drift Stream (Shape A Only, 1,000 Payloads)

| Strategy | Total Compressed Size | Mean Ratio | Retrain Count | Training Time (ms) |
|---|---|---|---|---|
| **gzip (level 6)** | 244,680 B | **1.24x** | **0** | 0 ms |
| **Static zstd+dict** | 59,177 B | **5.13x** | **0** | 21 ms |
| **Fixed-schedule (200/200)** | 58,559 B | **5.19x** | **4** | 95 ms |
| **Fixed-schedule (100/100)** | 71,362 B | **4.26x** | **9** | 182 ms |
| **Adaptive zdict (buffer=200)** | 59,177 B | **5.13x** | **0** | 21 ms |
| **Adaptive zdict (buffer=100)** | 59,177 B | **5.13x** | **0** | 21 ms |

### Noisy-Stationary Stream (Shape A Varying Sizes + Outliers, 1,000 Payloads)

| Strategy | Total Compressed Size | Mean Ratio | Retrain Count | False Alarm Count | Training Time (ms) |
|---|---|---|---|---|---|
| **gzip (level 6)** | 251,519 B | **1.62x** | **0** | **0** | 0 ms |
| **Static zstd+dict** | 64,330 B | **6.33x** | **0** | **0** | 18 ms |
| **Fixed-schedule (200/200)** | 51,752 B | **7.86x** | **4** | **0** | 84 ms |
| **Fixed-schedule (100/100)** | 63,736 B | **6.38x** | **9** | **0** | 153 ms |
| **Adaptive zdict (buffer=200)** | 64,330 B | **6.33x** | **0** | **0** | 18 ms |
| **Adaptive zdict (buffer=100)** | 64,330 B | **6.33x** | **0** | **0** | 18 ms |

## Adaptive Variant Tradeoff: Buffer=100 vs Buffer=200

| Metric | Adaptive (buffer=200) | Adaptive (buffer=100) | Tradeoff Analysis |
|---|---|---|---|
| **Canonical Total Bytes** | 112,463 B | 95,133 B | **17,330 B saved** by buffer=100 due to earlier recovery |
| **Canonical Time-to-Recovery** | 240 payloads | 140 payloads | **100 payloads faster** recovery for buffer=100 |
| **Canonical Training Time** | 205 ms | 217 ms | Faster training on 100 samples vs 200 samples |
| **Switch Point 800 Horizon** | Fails to retrain before end (needs 200) | **Successfully recovers at #915** | Buffer=100 handles late-stage drift without truncation |

## Window-by-Window Breakdown

| Window | Payloads | Dominant Schema | gzip Ratio | Static Ratio | Fixed-200 Ratio | Fixed-100 Ratio | Adaptive-200 Ratio | Adaptive-100 Ratio |
|---|---|---|---|---|---|---|---|---|
| #1 | 0 - 100 | Shape A | 1.24x | 7.02x | 7.02x | 5.41x | **7.02x** | **7.02x** |
| #2 | 100 - 200 | Shape A | 1.24x | 5.16x | 5.16x | 4.32x | **5.16x** | **5.16x** |
| #3 | 200 - 300 | Shape A | 1.24x | 4.92x | 4.98x | 4.02x | **4.92x** | **4.92x** |
| #4 | 300 - 400 | Shape A | 1.24x | 4.94x | 4.94x | 4.17x | **4.94x** | **4.94x** |
| #5 | 400 - 500 | Shape A | 1.25x | 5.00x | 5.04x | 4.12x | **5.00x** | **5.00x** |
| #6 | 500 - 600 | Shape A | 1.24x | 4.95x | 5.05x | 4.26x | **4.95x** | **4.95x** |
| #7 | 600 - 700 | Shape B | 1.29x | 1.33x | 1.30x | 1.30x | **1.33x** | **1.33x** |
| #8 | 700 - 800 | Shape B | 1.29x | 1.32x | 1.30x | 4.56x | **1.32x** | **2.30x** |
| #9 | 800 - 900 | Shape B | 1.29x | 1.32x | 5.67x | 4.57x | **2.44x** | **4.51x** |
| #10 | 900 - 1000 | Shape B | 1.29x | 1.32x | 5.68x | 4.64x | **5.57x** | **4.52x** |

