# zdict

Self-learning zstd dictionary compression for JSON APIs (JVM library).

**Work in progress.**

## How Learning Works

zdict automatically learns endpoint-specific zstd dictionaries directly from live HTTP traffic without manual intervention:

1. **Endpoint Grouping**: Requests are grouped by Spring MVC's best-matching handler pattern (e.g. `/api/products/{id}` via `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`), avoiding high-cardinality path explosions.
2. **PII Scrubbing**: Before payloads are sampled, `PayloadScrubber` parses JSON trees with Jackson and replaces denylisted keys (`password`, `token`, `secret`, `email`, `phone`, `card`, `ssn`, etc.) with `"x"`. It also applies regex scrubbing to mask emails, JWT-like three-segment tokens, and 9+ digit runs. Non-JSON payloads are discarded and never stored.
3. **Reservoir Sampling**: `SampleStore` performs bounded reservoir sampling (Algorithm R) per endpoint group both in memory and persisted under `zdict.data-dir/samples/<group-slug>/`. Only responses eligible for compression (status 200, JSON, no auth/cookies, not reflected) are sampled. Sampling runs safely after response delivery and never affects request processing.
4. **Holdout Evaluation & Promotion**:
   - `DictionaryTrainerService.train(group)` requires at least `zdict.train.min-samples` (default 200).
   - Samples are split 80/20 into training and holdout sets.
   - A candidate dictionary (16 KB) is trained on the 80% split using `ZstdDictTrainer`.
   - Compression ratios are measured across the 20% holdout set against plain zstd and against the currently ACTIVE dictionary (if one exists).
   - The candidate is promoted to `ACTIVE` only if no active dictionary exists or if it beats the active dictionary by at least 5% on the holdout set; otherwise, it is discarded.
5. **Versioned Registry & Stale Client Support**:
   - `DictionaryRegistry` stores versions on disk under `zdict.data-dir/dicts/<group-slug>/<id>.dict` and `<id>.json` metadata (`createdAt`, `sampleCount`, `dictSize`, `holdoutRatio`, `state`).
   - Exactly one version is `ACTIVE` per group.
   - Up to 3 versions per group are retained on disk and in memory (`ACTIVE` and `RETIRED`) so slightly stale clients can still decode while older ones are automatically pruned.
   - All state is reloaded from disk on startup.
6. **Client Negotiation Protocol**:
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
| `zdict.allow-authenticated` | `false` | When false, skips compression and sampling for requests with auth/cookies |
| `zdict.skip-reflected-input` | `true` | When true, skips compression if query params (length >= 4) reflect in body |
| `zdict.denylisted-fields` | standard PII keys | Set of field names whose values are replaced with `"x"` |

## Actuator & API Endpoints

- `GET /actuator/zdict`: Returns current operational state: `{"enabled": true, "circuitOpen": false}`.
- `POST /actuator/zdict`: Runtime kill switch toggle (`{"enabled": false}` to disable, `{"enabled": true}` to re-enable).
- `POST /actuator/zdict/train?group=...`: Triggers dictionary training for an endpoint group, returns holdout ratios and promotion decision.
- `GET /zdict/dictionary/{id}`: Serves raw dictionary bytes for any stored version (`ACTIVE` or `RETIRED`).

## Metrics (Micrometer / Actuator)

zdict exposes operational metrics via Micrometer and Spring Boot Actuator (`/actuator/metrics`):

| Metric Name | Type | Tags | Description |
|---|---|---|---|
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
