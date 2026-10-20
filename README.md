# zdict

Self-learning zstd dictionary compression for JSON APIs (JVM library).

**Work in progress.**

## How Learning Works

zdict automatically learns endpoint-specific zstd dictionaries directly from live HTTP traffic without manual intervention:

1. **Endpoint Grouping**: Requests are grouped by Spring MVC's best-matching handler pattern (e.g. `/api/products/{id}` via `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`), avoiding high-cardinality path explosions.
2. **PII Scrubbing**: Before payloads are sampled, `PayloadScrubber` parses JSON trees with Jackson and replaces denylisted keys (`password`, `token`, `secret`, `email`, `phone`, `card`, `ssn`, etc.) with `"x"`. It also applies regex scrubbing to mask emails, JWT-like three-segment tokens, and 9+ digit runs. Non-JSON payloads are discarded and never stored.
3. **Reservoir Sampling**: `SampleStore` performs bounded reservoir sampling (Algorithm R) per endpoint group both in memory and persisted under `zdict.data-dir/samples/<group-slug>/`. Only responses eligible for compression (status 200, JSON, no auth/cookies, not reflected) are sampled. Sampling runs safely after response delivery and never affects request processing.
4. **Holdout Evaluation & Canary Registration**:
   - `DictionaryTrainerService.train(group)` requires at least `zdict.train.min-samples` (default 200).
   - Samples are split 80/20 into training and holdout sets.
   - A candidate dictionary (16 KB) is trained on the 80% split using `ZstdDictTrainer`.
   - Compression ratios are measured across the 20% holdout set against plain zstd and against the currently ACTIVE dictionary (if one exists).
   - If no ACTIVE dictionary exists, the candidate goes directly to `ACTIVE` (`PROMOTED_FIRST_ACTIVE`).
   - If an ACTIVE dictionary already exists and candidate beats it by >= 5% on holdout, the candidate is registered as `CANARY` (`CANARY_STARTED`). If improvement < 5%, it is discarded.
5. **Drift Detection & Auto-Retraining**:
   - A rolling circular window (last 500 responses per group/dict) tracks `original size / compressed size` and failure counts in `RatioTracker`.
   - Baseline ratio is recorded at training time from holdout evaluation.
   - A group is `DRIFTING` when its `ACTIVE` dictionary has >= `zdict.drift.min-responses` (default 200) in the window AND rolling mean ratio < `baseline * (1 - zdict.drift.threshold)` (default 0.15).
   - The scheduled job runs every `zdict.auto-learning.interval` (default 60s):
     - No active dictionary + enough samples: trains initial dictionary and promotes to `ACTIVE`.
     - `DRIFTING`: clears that group's sample reservoir once (so old-shape payloads do not pollute retraining), continues serving the old active dictionary, and retrains as soon as `min-samples` fresh samples accumulate.
     - Enforces cooldown: at most one training per group per `zdict.train.cooldown` (default 15m).
6. **Canary Slicing & Autonomous Evaluation**:
   - Slicing: If the client advertises the canary ID, it is served canary compression for `zdict.canary.percent` of requests (default 5%, randomly selected per request). If the client lacks the canary ID, roughly the same percent of requests send `X-Dict-Available: <canaryId>` so clients fetch it. Clients never receive a canary encoding unless they advertised its ID.
   - Promotion: Once the canary has >= `zdict.canary.min-responses` (default 300) responses, it is compared against active:
     - `PROMOTE`: if canary mean ratio >= active mean ratio * 1.03 and canary failure rate <= active failure rate. Canary becomes `ACTIVE`; old active becomes `RETIRED`.
     - `ROLLBACK`: otherwise, canary is retired with reason string. Active dictionary remains unchanged.
     - `TIMEOUT`: if `zdict.canary.max-duration` (default 30m) elapses without enough traffic, canary rolls back with reason `"insufficient traffic"`.
   - Up to 3 versions per group are retained on disk and in memory (`ACTIVE`, `CANARY`, `RETIRED`).
7. **Client Negotiation Protocol**:
   - Clients send `X-Dict-Ids`: a comma-separated list of dictionary IDs they hold.
   - If the server has an `ACTIVE` dictionary for that group and its ID is in `X-Dict-Ids`, it compresses with it and sets `Content-Encoding: zstd-dict`, `X-Dict-Id: <id>`, `Vary: X-Dict-Ids, Accept-Encoding`.
   - If the client lacks the active dictionary, the server sends uncompressed data along with hint header `X-Dict-Available: <id>`.
   - `ZdictClient` caches dictionaries, sends `X-Dict-Ids`, fetches advertised dictionaries via `GET /zdict/dictionary/{id}`, and falls back to plain JSON upon any error.

## Configuration Keys

| Property | Default | Description |
|---|---|---|
| `zdict.enabled` | `false` | Enable or disable zdict compression |
| `zdict.paths` | `[]` | Allowlist of path prefixes eligible for dictionary compression and sampling |
| `zdict.data-dir` | `zdict-data` | Directory where samples and versioned dictionaries are persisted |
| `zdict.seed-dictionary-path` | `""` | Optional seed dictionary file path to initialize active dictionary at startup |
| `zdict.sample.rate` | `0.05` | Sampling probability for compression-eligible responses (5% default) |
| `zdict.sample.per-group-max` | `2000` | Maximum number of samples kept per endpoint group reservoir |
| `zdict.train.min-samples` | `200` | Minimum sample count required to train a dictionary for an endpoint group |
| `zdict.train.dict-size` | `16384` | Target dictionary size in bytes (16 KB default) |
| `zdict.train.cooldown` | `15m` | Minimum duration between training attempts for the same endpoint group |
| `zdict.drift.threshold` | `0.15` | Drop percentage from baseline ratio triggering drift detection (15% default) |
| `zdict.drift.min-responses` | `200` | Minimum rolling responses required before drift evaluation begins |
| `zdict.canary.percent` | `5` | Percentage of eligible traffic routed to CANARY dictionary |
| `zdict.canary.min-responses` | `300` | Minimum canary responses required before promotion evaluation |
| `zdict.canary.max-duration` | `30m` | Maximum lifetime of a canary dictionary before rollback on insufficient traffic |
| `zdict.auto-learning.enabled` | `false` | Enable scheduled autonomous training, drift remediation, and canary rollouts |
| `zdict.auto-learning.interval` | `60s` | Frequency of scheduled background auto-learning and canary checks |
| `zdict.dashboard.enabled` | `false` | Enable web dashboard at `/zdict/dashboard` (returns 404 when disabled) |
| `zdict.allow-authenticated` | `false` | When false, skips compression and sampling for requests with auth/cookies |
| `zdict.skip-reflected-input` | `true` | When true, skips compression if query params (length >= 4) reflect in body |
| `zdict.denylisted-fields` | standard PII keys | Set of field names whose values are replaced with `"x"` |

## Actuator, Dashboard & Security

> [!WARNING]
> **Security Notice**: Both the `/actuator/zdict/*` endpoints and `/zdict/dashboard` expose internal operational metrics, payload statistics, and manual trigger controls. They must **never be exposed publicly** to untrusted clients. Ensure reverse proxies or Spring Security restrict access to internal networks or authorized administrative roles.

- `GET /zdict/dashboard`: Live monitoring web dashboard (plain HTML + inline CSS + vanilla JS; supports system dark/light modes). Returns HTTP 404 when `zdict.dashboard.enabled: false`.
- `GET /actuator/zdict`: Returns current operational state: `{"enabled": true, "circuitOpen": false}`.
- `GET /actuator/zdict/groups`: Returns status per endpoint group (`HEALTHY | DRIFTING | CANARY | NO_DICTIONARY`), active/canary IDs, baseline ratio, rolling ratio, sample count, byte metrics (`bytesOriginal`, `bytesCompressed`, `bytesSaved`, `compressedResponses`), last trained time, and last event.
- `GET /actuator/zdict/events`: Returns the recent lifecycle events list.
- `POST /actuator/zdict`: Runtime kill switch toggle (`{"enabled": false}` to disable, `{"enabled": true}` to re-enable).
- `POST /actuator/zdict/train?group=...`: Triggers dictionary training for an endpoint group, returns holdout ratios and promotion decision.
- `GET /zdict/dictionary/{id}`: Serves raw dictionary bytes for any stored version (`ACTIVE`, `CANARY`, or `RETIRED`).

## Metrics (Micrometer / Actuator)

zdict exposes operational metrics via Micrometer and Spring Boot Actuator (`/actuator/metrics`):

| Metric Name | Type | Tags | Description |
|---|---|---|---|
| `zdict.ratio.rolling` | Gauge | `group` | Current rolling compression ratio (`original / compressed`) for active dictionary |
| `zdict.events` | Counter | `type`, `group` | Lifecycle events (`TRAINED`, `DRIFT_DETECTED`, `CANARY_STARTED`, `PROMOTED`, `ROLLED_BACK`) |
| `zdict.bytes.original` | Counter | `path` | Uncompressed response body size in bytes before compression |
| `zdict.bytes.compressed` | Counter | `path` | Compressed response body size in bytes sent over the wire |
| `zdict.responses` | Counter | `result`, `reason` | Total responses processed (`result = compressed \| skipped \| failed`, with skip/failure reason) |
| `zdict.compress.time` | Timer | - | Duration of zstd dictionary compression operations |
| `zdict.circuit.open` | Gauge | - | Circuit breaker state: `1.0` when open (compression suspended), `0.0` when closed |

### Skip & Failure Reasons

The `zdict.responses` counter includes the reason tag for visibility into gating decisions:
- `disabled`: Compression disabled by property or kill switch
- `circuit_open`: Circuit breaker open due to high failure rate or latency
- `unsupported_method`: HTTP method is not GET
- `authenticated_request`: Request contained `Authorization` or `Cookie` header (with `zdict.allow-authenticated=false`)
- `set_cookie_response`: Response set a cookie
- `private_cache_response`: Response marked `Cache-Control: private` or `no-store`
- `reflected_input`: BREACH protection triggered (query param >= 4 chars reflected in body)
- `no_dict_id` / `wrong_dict_id`: Client did not provide matching dictionary ID
- `non_json`: Content-Type is not `application/json`
- `size_out_of_bounds`: Body outside 200 B – 1 MB bounds
- `compression_error`: Native zstd compression failed (fail-open)

## Benchmarks

> [!IMPORTANT]
> **Data Authenticity Notice**: Benchmark results are only as meaningful as the input data. All numbers presented below are generated directly from reproducible test runs and are **never invented or rounded up**.
>
> - **Synthetic vs Real Data**: The results below were measured on **SYNTHETIC** datasets generated by `DriftBench --generate` (with payload schema changes labeled `_dataset: "SYNTHETIC"`). When benchmarking in production, use real browser HAR captures imported via `HarImporter`.
> - **Timing Methodology**: Per-payload latency measurements (p50 and p99 in microseconds) are collected via a simple measurement loop after JVM warmup passes, **not JMH microbenchmarks**. They reflect representative client-side/server-side CPU overhead for real payloads.

### 1. Compression Ratio Comparison

![Compression Ratio](benchmark-results/ratio.svg)

### 2. Compression Latency (p50 / p99)

![Compression Latency](benchmark-results/latency.svg)

### 3. Replay Benchmark Results (Synthetic Dataset)

- **Date**: `2026-10-20T06:22:02.0933411Z`
- **Environment**: Java `21.0.10`, Windows 11 `amd64`
- **Total Test Payloads**: 334 uncompressed JSON payloads (first 2/3 used for training, last 1/3 evaluated; strict time order, never shuffled, never tested on training data).
- **One-time Dictionary Size**: 16,384 bytes (16 KB) per group.

| Endpoint Group | Codec | Compressed Size | Ratio | Space Savings | p50 Latency | p99 Latency | Break-Even vs gzip |
|---|---|---|---|---|---|---|---|
| **products_v1** (200 payloads) | gzip (lvl 6) | 47,745 B | **1.21x** | 17.6% | 15.4 µs | 47.7 µs | - |
| | zstd (lvl 3) | 47,573 B | **1.22x** | 17.9% | 6.9 µs | 11.4 µs | - |
| | **zstd+dict (16 KB)** | **7,762 B** | **7.46x** | **86.6%** | **3.2 µs** | **6.3 µs** | **82 payloads** (199.9 B saved/req) |
| **products_v2** (134 payloads) | gzip (lvl 6) | 37,433 B | **1.28x** | 21.8% | 17.5 µs | 27.2 µs | - |
| | zstd (lvl 3) | 38,532 B | **1.24x** | 19.6% | 7.6 µs | 12.2 µs | - |
| | **zstd+dict (16 KB)** | **5,549 B** | **8.63x** | **88.4%** | **3.2 µs** | **8.4 µs** | **69 payloads** (237.9 B saved/req) |
| **All Groups Combined** | gzip (lvl 6) | 85,178 B | **1.24x** | 19.5% | 16.5 µs | 37.5 µs | - |
| | zstd (lvl 3) | 86,105 B | **1.23x** | 18.6% | 7.3 µs | 11.8 µs | - |
| | **zstd+dict (16 KB)** | **13,311 B** | **7.95x** | **87.4%** | **3.2 µs** | **7.4 µs** | **153 payloads** (215.2 B saved/req) |

#### Ratio by Payload Size Bucket

| Group | Size Bucket | Payload Count | gzip Ratio | zstd Ratio | zstd+dict Ratio |
|---|---|---|---|---|---|
| `products_v1` | < 1 KB | 200 | 1.21x | 1.22x | **7.46x** |
| `products_v2` | < 1 KB | 134 | 1.28x | 1.24x | **8.63x** |

---

### 4. Schema Drift Benchmark: Static vs Adaptive

![Schema Drift](benchmark-results/drift.svg)

Simulates continuous traffic across 1,000 payloads: first 60% with Schema A (legacy schema), followed by 40% Schema B (renamed fields and nested structures).

| Strategy | Total Compressed Size | Overall Ratio | Savings vs gzip | Behavioral Note |
|---|---|---|---|---|
| **gzip (level 6)** | 254,783 B | **1.24x** | Baseline | Consistent, but low compression for repetitive JSON |
| **Static zstd+dict** | 135,008 B | **2.34x** | 47.0% | Excellent on Shape A (~7-9x), collapses to 1.27x on Shape B |
| **Adaptive zdict** | **89,920 B** | **3.52x** | **64.7%** | Detects drift at payload #607, retrains fresh dictionary, recovers ratio to 8.72x |

> **Adaptive Savings**: Adaptive zdict saved **45,088 bytes (33.4% reduction)** compared to static dictionary compression over the same 1,000-payload stream.

---

### 5. How to Reproduce

All benchmark tools reside in `zdict-bench` and require only Java 17+ and Maven.

#### Step A: Import Real Browser HAR Captures
Convert a browser `.har` network export into structured, filesystem-safe payload directories grouped by endpoint pattern:
```bash
cd zdict-bench
mvn compile exec:java -D"exec.mainClass=dev.zdict.HarImporter" -D"exec.args=path/to/capture.har ../samples-dir"
```
*Filters HTTP 200 and `application/json`, normalizes numeric and UUID segments to `{id}`, validates JSON structure, and skips payloads > 1 MB.*

#### Step B: Run the Replay Benchmark
Replay collected samples in strict time order (first 2/3 train, last 1/3 test), verify round trips, and write results:
```bash
cd zdict-bench
mvn compile exec:java -D"exec.mainClass=dev.zdict.ReplayBench" -D"exec.args=../samples-dir ../benchmark-results"
```
*Outputs `benchmark-results/results.json`, `benchmark-results/results.md`, `benchmark-results/ratio.svg`, and `benchmark-results/latency.svg`.*

#### Step C: Run the Schema Drift Benchmark
Generate synthetic datasets and evaluate gzip vs static dictionary vs adaptive zdict:
```bash
cd zdict-bench

# 1. Generate synthetic Shape A (600 samples) and Shape B (400 samples):
mvn compile exec:java -D"exec.mainClass=dev.zdict.DriftBench" -D"exec.args=--generate ../benchmark-data/synthetic-A ../benchmark-data/synthetic-B 600 400"

# 2. Run drift benchmark:
mvn compile exec:java -D"exec.mainClass=dev.zdict.DriftBench" -D"exec.args=../benchmark-data/synthetic-A ../benchmark-data/synthetic-B ../benchmark-results"
```
*Outputs `benchmark-results/drift.svg` and `benchmark-results/drift.md`.*
