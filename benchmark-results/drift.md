# [SYNTHETIC] zdict Drift Scenario Benchmark

> [!NOTE]
> **Dataset**: **SYNTHETIC** (payloads generated with schema variation, marked `_dataset: "SYNTHETIC"`).

## Configuration & Parameters

- **Date**: `2026-10-20T06:37:57.5881876Z`
- **Java Version**: `21.0.10`
- **Traffic Stream**: **1000 payloads** total (600 Shape A, 400 Shape B)
- **Real Shape Switch Point**: Payload **#600**
- **Drift Detected At**: Payload **#608** (**8 payloads elapsed** after shape switch)
- **Dictionary Switch Points**: [808] (trained on ONLY 200 fresh post-drift payloads)
- **Evaluation Window**: **200 payloads**
- **Drift Detection Threshold**: `rolling mean < baseline * 0.85` (15% drop)
- **Reporting Window**: **100 payloads**

## Strategy Comparison

| Strategy | Total Compressed Size | Mean Ratio | Retrain Count | Post-Switch 100 Ratio | Description |
|---|---|---|---|---|---|
| **gzip (level 6)** | 254,783 B | **1.24x** | 0 | 1.28x | Standard gzip compression |
| **Static zstd+dict** | 135,008 B | **2.34x** | 0 | 1.30x | Trained once on Shape A, never updated |
| **Fixed-schedule zstd+dict** | 87,705 B | **3.61x** | 4 | 1.27x | Retrained every 200 payloads on last 200 without drift detection |
| **Adaptive zdict** | 88,888 B | **3.56x** | 1 | **1.30x** | Self-learning: detects drift, resets buffer, retrains on 200 fresh post-drift samples |

> **Adaptive Efficiency**: Adaptive zdict saved **46,120 bytes (34.2% reduction)** compared to static dictionary compression.

## Window-by-Window Breakdown

| Window | Payloads | Dominant Schema | gzip Ratio | Static Ratio | Fixed Ratio | Adaptive Ratio | Note |
|---|---|---|---|---|---|---|---|
| #1 | 0 - 100 | Shape A | 1.21x | 9.07x | 9.07x | **9.07x** | - |
| #2 | 100 - 200 | Shape A | 1.21x | 7.37x | 7.37x | **7.37x** | - |
| #3 | 200 - 300 | Shape A | 1.21x | 7.09x | 7.09x | **7.09x** | - |
| #4 | 300 - 400 | Shape A | 1.22x | 7.45x | 7.45x | **7.45x** | - |
| #5 | 400 - 500 | Shape A | 1.21x | 7.10x | 7.27x | **7.10x** | - |
| #6 | 500 - 600 | Shape A | 1.21x | 7.09x | 7.59x | **7.09x** | - |
| #7 | 600 - 700 | Shape B | 1.28x | 1.30x | 1.27x | **1.30x** | Drift detected; accumulating fresh post-drift samples |
| #8 | 700 - 800 | Shape B | 1.28x | 1.27x | 1.26x | **1.27x** | Drift detected; accumulating fresh post-drift samples |
| #9 | 800 - 900 | Shape B | 1.28x | 1.27x | 8.45x | **5.85x** | **Ratio recovered after retrain** |
| #10 | 900 - 1000 | Shape B | 1.28x | 1.27x | 8.70x | **8.72x** | **Ratio recovered after retrain** |

