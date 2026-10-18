# zdict

Self-learning zstd dictionary compression for JSON APIs (JVM library).

**Work in progress.**

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
- `no_dict_id` / `wrong_dict_id`: Client did not provide matching `X-Dict-Id`
- `non_json`: Content-Type is not `application/json`
- `size_out_of_bounds`: Body outside 200 B – 1 MB bounds
- `compression_error`: Native zstd compression failed (fail-open)

## Actuator Endpoints

- `GET /actuator/zdict`: Returns current operational state: `{"enabled": true, "circuitOpen": false}`.
- `POST /actuator/zdict`: Runtime kill switch toggle (`{"enabled": false}` to disable, `{"enabled": true}` to re-enable).
- `GET /zdict/dictionary/{id}`: Serves dictionary raw bytes for client negotiation.
