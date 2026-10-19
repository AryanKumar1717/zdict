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
| `zdict.allow-authenticated` | `false` | When false, skips compression and sampling for requests with auth/cookies |
| `zdict.skip-reflected-input` | `true` | When true, skips compression if query params (length >= 4) reflect in body |
| `zdict.denylisted-fields` | standard PII keys | Set of field names whose values are replaced with `"x"` |

## Actuator & API Endpoints

- `GET /actuator/zdict`: Returns current operational state: `{"enabled": true, "circuitOpen": false}`.
- `GET /actuator/zdict/groups`: Returns status per endpoint group (`HEALTHY | DRIFTING | CANARY | NO_DICTIONARY`), active/canary IDs, baseline ratio, rolling ratio, sample count, last trained time, and last event.
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
