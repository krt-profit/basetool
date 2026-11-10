# Request buffering allocation characterization

Measured locally on Java 25 on 2026-11-10 for AUD-22 (#3289). This is a synthetic allocation
comparison of the affected operations, not a production throughput or whole-request heap benchmark.

## Reproduction

```powershell
.\gradlew.bat :ingest:test --tests '*ExchangeBufferAllocationTest' --console=plain
```

`ExchangeBufferAllocationTest` uses the supported thread-allocation counter after warmup. Its request
is 2,097,151 bytes, one byte below the default 2 MiB ingest limit. Its response is 32,769 bytes, one
byte beyond the idempotency cache's response ceiling. The fixture compares the former copy/hash and
copy/decode shape with the actual read-only fingerprint and response-size admission guard. It does
not measure servlet/network allocation or the retained original request buffer.

| Operation shape | Allocated bytes per operation |
| --- | ---: |
| Request copy plus response copy/decode model | 4,285,240 |
| Buffered request fingerprint plus early response-size guard | 4,768 |

The measured local reduction concerns these avoidable allocations only. Independent regression
checks verify equal fingerprints, preserved buffer cursors, reuse through nested servlet wrappers,
and complete oversized responses without idempotency-cache retention. No request limit or response
payload is truncated to obtain the result.
