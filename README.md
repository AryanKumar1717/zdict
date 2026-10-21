# zdict

Self-learning zstd dictionary compression for JSON APIs (Spring Boot starter & client libraries).

## Quickstart

### 1. Add Dependency

Add the starter to your Spring Boot project (Java 17+, Spring Boot 3.x):

```xml
<dependency>
    <groupId>dev.zdict</groupId>
    <artifactId>zdict-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

### 2. Configure (5 Lines)

Enable dictionary compression on your chosen API paths in `application.yml`:

```yaml
zdict:
  enabled: true
  paths:
    - /api/products
  data-dir: zdict-data
```

*(Or in `application.properties`:)*
```properties
zdict.enabled=true
zdict.paths=/api/products
zdict.data-dir=zdict-data
```

### 3. Client & Server Header Flow (curl Example)

**First request** (client holds no dictionary):
```bash
curl -i http://localhost:8080/api/products
```
The server returns uncompressed JSON and advertises the active dictionary ID via `X-Dict-Available`:
```http
HTTP/1.1 200 OK
Content-Type: application/json
X-Dict-Available: 9e522a5e
Vary: X-Dict-Ids, Accept-Encoding
```

**Client fetches dictionary once**:
```bash
curl -o /tmp/9e522a5e.dict http://localhost:8080/zdict/dictionary/9e522a5e
```

**Subsequent requests** (client advertises held dictionary):
```bash
curl -i -H "X-Dict-Ids: 9e522a5e" http://localhost:8080/api/products
```
The server compresses the response using that dictionary:
```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Encoding: zstd-dict
X-Dict-Id: 9e522a5e
Vary: X-Dict-Ids, Accept-Encoding
```

*(Or use our client libraries: `dev.zdict:zdict-client` for Java 17 `HttpClient` or `dev.zdict:zdict-client-okhttp` for OkHttp, which handle this negotiation automatically).*

---

## How It Works

zdict automates the entire dictionary lifecycle without code changes to your endpoint handlers:

```
[Incoming Request]
        │
        ▼
[Security & Gating Filter] ──(Fails allowlist / Auth / BREACH)──► Pass through plain
        │
        ▼ (Eligible)
[Client Has Active Dict?] ────(No)──► Send plain + "X-Dict-Available: <id>" + Sample
        │ (Yes)
        ▼
[zstd-dict Compression] ────────────► Send "Content-Encoding: zstd-dict" + Sample
        │
        ▼
[Reservoir Sampling (PII Scrubbed)]
        │
        ▼
[Periodic Background Auto-Learning]
   ├─► Train candidate (80/20 train/holdout)
   ├─► "Worth-It" Gate: Gain >= 10% over BOTH gzip & plain zstd?
   │      ├─► No  ──► Status: NOT_WORTH_IT (Compression skipped for group)
   │      └─► Yes ──► Canary Rollout (5% traffic)
   ├─► Canary Evaluation (300 responses): Ratio >= 1.03x active?
   │      ├─► Yes ──► Promote to ACTIVE
   │      └─► No  ──► Rollback & Retire
   └─► Drift Detection (Rolling ratio drop > 15%):
          └─► Reset reservoir & retrain fresh
```

1. **Learning**:
   - Requests are mapped to Spring MVC's best-matching handler pattern (e.g., `/api/products/{id}`).
   - Eligible responses (status 200, JSON, 200 B – 1 MB) are sampled into a bounded reservoir (Algorithm R) per group.
   - When `min-samples` (default 200) accumulate, a 16 KB candidate dictionary is trained on 80% of samples and evaluated against the 20% holdout set.
2. **The "Worth-It" Gate**:
   - Before promoting any dictionary or starting a canary rollout, the candidate must demonstrate at least `zdict.train.min-gain` (default 10%, `0.10`) compression ratio improvement over **both** gzip and plain zstd on the holdout set.
   - If a payload type already compresses well with generic algorithms or lacks shared structure, dictionary training is rejected, no canary is started, and the endpoint group status is marked `NOT_WORTH_IT`.
3. **Canary Rollout & Promotion**:
   - If an active dictionary already exists and a new candidate clears the worth-it gate with >= 5% improvement over active, it enters a `CANARY` phase.
   - 5% of requests advertising the canary ID receive canary compression.
   - After accumulating 300 responses, the canary is promoted to `ACTIVE` if its rolling ratio is >= 1.03x the active ratio and its failure rate is <= active. Otherwise, it is rolled back.
4. **Drift Detection & Remediation**:
   - A circular buffer tracks the rolling compression ratio of the active dictionary.
   - If rolling ratio drops by > 15% from its holdout baseline, the group is flagged as `DRIFTING`.
   - The sample store resets that group's reservoir so stale payloads do not pollute retraining, continues serving the existing dictionary, and retrains as soon as fresh post-drift samples are collected.

---

## Security

> [!WARNING]
> **Actuator & Dashboard Security Notice**: Both `/actuator/zdict/*` and `/zdict/dashboard` expose internal payload statistics, operational metrics, and management actions (kill switch and manual training triggers). They must **never be publicly exposed** to untrusted clients. Restrict access using firewall rules, internal network boundaries, or Spring Security.

- **Explicit Path Allowlist**: Only endpoints explicitly listed in `zdict.paths` are eligible for dictionary compression and sampling. Unlisted endpoints pass through unmodified.
- **No Authenticated Data by Default**: Requests carrying `Authorization` or `Cookie` headers are skipped by default (`zdict.allow-authenticated: false`).
- **BREACH Attack Guard**: If query parameters of 4 or more characters are reflected in the response body, compression is skipped (`zdict.skip-reflected-input: true`) to mitigate compression side-channel attacks.
- **Automatic PII Scrubbing**: Before response bodies are stored in sample reservoirs, `PayloadScrubber` parses the JSON tree, replaces denylisted fields (`password`, `token`, `secret`, `email`, `phone`, `card`, `ssn`, etc.) with `"x"`, and scrubs regex patterns for emails, JWT tokens, and 9+ digit runs. Non-JSON payloads are never stored.

---

## Configuration Reference

| Property | Default | Description |
|---|---|---|
| `zdict.enabled` | `false` | Master toggle to enable zdict compression |
| `zdict.paths` | `[]` | Path prefix allowlist eligible for dictionary compression |
| `zdict.data-dir` | `zdict-data` | Directory where samples and versioned dictionaries are persisted |
| `zdict.sample.rate` | `0.05` | Sampling probability for eligible responses (5% default) |
| `zdict.sample.per-group-max` | `2000` | Maximum number of samples kept per endpoint group reservoir |
| `zdict.train.min-samples` | `200` | Minimum sample count required to train a dictionary |
| `zdict.train.dict-size` | `16384` | Target dictionary size in bytes (16 KB) |
| `zdict.train.min-gain` | `0.10` | Worth-it gate: minimum fractional gain (10%) over gzip and plain zstd |
| `zdict.train.cooldown` | `15m` | Minimum duration between training attempts for the same group |
| `zdict.drift.threshold` | `0.15` | Drop from baseline ratio triggering drift detection (15%) |
| `zdict.drift.min-responses` | `200` | Minimum responses before drift evaluation begins |
| `zdict.canary.percent` | `5` | Percentage of eligible traffic routed to CANARY dictionary |
| `zdict.canary.min-responses` | `300` | Minimum canary responses required before promotion evaluation |
| `zdict.canary.max-duration` | `30m` | Canary timeout before rollback on insufficient traffic |
| `zdict.auto-learning.enabled` | `false` | Enables autonomous background trainer and canary scheduler |
| `zdict.auto-learning.interval` | `60s` | Interval for background training and canary checks |
| `zdict.dashboard.enabled` | `false` | Enables web dashboard at `/zdict/dashboard` (returns 404 when false) |
| `zdict.allow-authenticated` | `false` | When false, skips requests with `Authorization` or `Cookie` headers |
| `zdict.skip-reflected-input` | `true` | When true, skips responses reflecting query params >= 4 characters |
| `zdict.denylisted-fields` | standard PII keys | Fields scrubbed prior to reservoir sampling |

---

## Benchmarks

All benchmark measurements below were gathered on real public API payloads using `ReplayBench` in `zdict-bench`.
- **Measurement Methodology**: Timing is measured using a simple loop after JVM warmup passes (not JMH microbenchmarks).
- **Metric**: Measured across body bytes only.
- **Training Setup**: First 2/3 of payloads used for dictionary training (16 KB dictionary), remaining 1/3 evaluated in strict original order.

### Real API Benchmark Results

| Endpoint / Group | Data Source | Sample Count | Avg Size | gzip (lvl 6) | zstd+dict (16 KB) | Gate / Note |
|---|---|---|---|---|---|---|
| **forecast** | Open-Meteo | 100 test payloads | 1,001 B | **2.85x** | **8.11x** | **8.11x vs 2.85x** (break-even: **72 payloads**) |
| **geocoding** | Open-Meteo | 67 test payloads | 1,566 B | **2.98x** | **4.01x** | **4.01x vs 2.98x** (break-even: **94 payloads**) |
| **species** | PokeAPI | 100 test payloads | ~27 KB | **4.98x** | **5.54x** | Dictionary yields modest gain |
| **pokemon** | PokeAPI | 100 test payloads | ~236 KB | **34.55x** | **34.12x** | **Dictionary does not help; the gate disables it** |

### Client Break-Even Analysis

A 16 KB dictionary represents a one-time download cost over the wire. The client amortizes this dictionary transfer cost through per-request bandwidth savings:
- **`forecast` (Open-Meteo, avg 1,001 B)**: Saves ~228 bytes per request over gzip; reaches break-even after **72 payloads** per client.
- **`geocoding` (Open-Meteo, avg 1,566 B)**: Saves ~176 bytes per request over gzip; reaches break-even after **94 payloads** per client.
- **`pokemon` (PokeAPI, ~236 KB)**: Large payloads already have massive repetitive internal redundancy where sliding-window zstd/gzip excels. The dictionary provides no advantage (34.12x vs 34.55x), and the **worth-it gate prevents deployment**.

---

## SYNTHETIC Drift Benchmark

> [!NOTE]
> **Synthetic Benchmark Notice**: All results in this section were generated using **SYNTHETIC** generated datasets (`DriftBench`) to evaluate schema drift dynamics under controlled conditions.

Simulates continuous traffic across 1,000 payloads: first 60% with Schema A (legacy schema), followed by 40% Schema B (renamed fields and nested structures).

### Strategy Comparison (Canonical Run: Switch Point #600)

| Strategy | Total Compressed Size | Mean Ratio | Retrain Count | Post-Switch 100 Ratio | Training Time (ms) | Recovery Horizon |
|---|---|---|---|---|---|---|
| **gzip (level 6)** | 261,710 B | 1.26x | 0 | 1.29x | 0 ms | - |
| **Static zstd+dict** | 146,904 B | 2.25x | 0 | 1.33x | 174 ms | - |
| **Fixed-schedule (200/200)** | 104,306 B | 3.17x | 4 | 1.30x | 308 ms | 200 payloads |
| **Fixed-schedule (100/100)** | 94,526 B | 3.50x | 9 | 1.30x | 327 ms | 100 payloads |
| **Adaptive zdict (buffer=200)** | **112,463 B** | **2.94x** | **1** | **1.33x** | 205 ms | 240 payloads |
| **Adaptive zdict (buffer=100)** | **95,133 B** | **3.48x** | **1** | **1.33x** | 217 ms | 140 payloads |

### Honest Synthetic Findings

1. **Adaptive makes far fewer retrains than a fixed schedule**:
   Adaptive triggers **1 retrain** upon verified drift versus **4 retrains** (200/200) or **9 retrains** (100/100) on fixed schedules. In stationary traffic with no drift, adaptive performs **0 false retrains**, whereas fixed schedules waste CPU cycles repeatedly retraining identical dictionaries.
2. **Adaptive does not always win on total bytes**:
   A high-frequency fixed schedule (like 100/100) retrains every 100 requests blindly. If a schema switch happens to occur just before a scheduled interval, fixed-schedule retrains immediately and can achieve slightly lower total wire bytes than an adaptive strategy that waits for statistical verification.
3. **Adaptive detects gradual drift late**:
   When fields migrate slowly (e.g. gradual drift across 400 payloads from #300 to #700), the compression ratio degrades gradually rather than sharply. Adaptive detected the shift at payload #475 (175 payloads into the transition), because rolling ratios take time to drop past the 15% threshold.
4. **Horizon Truncation**:
   When drift occurs late in a finite traffic stream (e.g. switch point #800 out of 1,000), an adaptive strategy requiring 200 post-drift samples detects drift at ~#839, leaving only 161 payloads—too few to complete retraining before the stream terminates.

---

## Limitations

- **JSON Only**: Designed specifically for `application/json` responses. Non-JSON formats (HTML, binary, protobuf) are not processed.
- **Payload Size Bounds**: Automatically skips responses below 200 bytes (where dictionary overhead outweighs benefits) or above 1 MB.
- **Client Support Required**: Clients must speak the `X-Dict-Ids` / `X-Dict-Available` header protocol and decode `Content-Encoding: zstd-dict`. Use `zdict-client` or `zdict-client-okhttp`.
- **Native JNI Dependency**: `zstd-jni` packages native dynamic libraries for common desktop and server operating systems (Linux, macOS, Windows). Android packaging has not yet been verified.
- **Memory Footprint**: Keeps active and canary dictionary instances in off-heap memory and bounded sample reservoirs on disk/memory.

---

## License

[Apache License 2.0](LICENSE)
