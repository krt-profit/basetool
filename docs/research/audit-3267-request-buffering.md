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

## Saturated DPoP replay storage

The opt-in method `DpopProofReplayStoreTest.profileSaturatedRefusalsAgainstTheTwoScanBaselineModel`
compares the actual indexed store with a model of the pinned finding's two full-map scans. Every
proof remains live under a fixed clock; 1,000 attempts per capacity must be refused without growth
and must return the same 31-second retry interval.

```powershell
$env:JAVA_TOOL_OPTIONS = '-Dbasetool.audit.resourceProfile=true'
.\gradlew.bat :ingest:test --tests '*DpopProofReplayStoreTest' --console=plain
Remove-Item Env:JAVA_TOOL_OPTIONS
```

| Stored proofs | Model entries visited per refusal | Scan model ns/refusal | Indexed store ns/refusal |
| --- | ---: | ---: | ---: |
| 1,000 | 2,000 | 43,708 | 58,149 |
| 10,000 | 20,000 | 60,145 | 549 |
| 100,000 | 200,000 | 1,058,085 | 431 |

The first case includes cold execution and assertion-library initialization. These are single local
runs of an algorithm model and a tested implementation, not a baseline application binary or a
production latency benchmark. Assertions also contribute to indexed timings. The evidence supports
removing refusal-time full-map scans; it does not establish a universal speedup. Separate concurrent
admission tests verify that 200 racing members cannot exceed the configured global capacity.
