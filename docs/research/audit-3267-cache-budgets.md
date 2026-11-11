# Cache budget characterization

## Reproduction

Run the sanctioned isolated task from the implementation checkout:

```powershell
.\gradlew.bat :backend:characterizeCacheBudgets --console=plain
```

The task depends on test compilation, starts a separate 512 MiB Java 25 JVM with compact object
headers, and starts no Spring application or database. Fixtures use real `TerminalDto` values with
synthetic names/UUIDs. Shapes are one terminal, a page of 50, a page of 1,000 and a complete index of
10,000 family/product keys. Explicit collection before/after a retained batch gives an approximate
heap delta per value; this is not a graph-layout oracle or a production heap measurement.

The request trace has seed 3267, 20,000 requests, twenty hot page keys and a 10% uniform component
over 2,000 keys. It compares the 1,000-entry baseline with a 16 MiB weighted cache. Caffeine admission
and concurrent maintenance can vary exact trace statistics; the input trace is reproducible. Report
hit rates and retained weights together instead of claiming a latency improvement.

## Admission model and selected budgets

The estimator charges 32 bytes for record/string headers, 8 per reference, 64 for collection/map
structure, 64 per map entry, 256 for page metadata and a lookup-key allowance, two bytes per string
character, and bounded scalar/array payload estimates. Public record accessors are cached by class.
Identity tracking counts aliases once per entry and prevents cycles. Unknown/inaccessible shapes and
entries exceeding their cache's allowance are not retained; reads still return their complete value.

The large terminal catalogue and complete family index each receive 16 MiB. Material/ship/POI
catalogues each receive 8 MiB; the intermediate catalogues and single-material cache each receive
4 MiB; small reference catalogues, role and squadron snapshots each receive 2 MiB. Total declared
admission weight is 90 MiB across seventeen caches. These explicit model budgets replace the
uniform 1,000-entry ceiling while leaving freshness and invalidation unchanged.

## Evidence limits

Approximate retained deltas depend on the VM and collector and exclude application workload.
Admission weights intentionally overestimate many small records; bookkeeping and cross-entry sharing
make them different from actual retained bytes. A hit-rate tradeoff is acceptable evidence only
alongside its retained working set. The change makes no unmeasured production performance claim.

## Local result

The isolated Java 25 compact-header run completed with a 512 MiB heap. The synthetic retained batch
produced these approximate per-value heap deltas; they exclude application load, native memory and
cache bookkeeping. Estimator weights include the lookup-key allowance.

| Value shape | Admission weight (bytes/value) | Approximate heap delta (bytes/value) |
| --- | ---: | ---: |
| Single terminal (10,000 retained samples) | 1,056 | 215 |
| Terminal page, 50 rows | 19,184 | 10,962 |
| Terminal page, 1,000 rows | 368,584 | 216,971 |
| Family/product index, 10,000 entries | 2,535,894 | 1,437,936 |

On the seeded 20,000-request page trace, the 1,000-entry policy reported a 0.93720 hit rate with
1,000 entries and 19,795,960 bytes of estimated admission weight. The 16 MiB policy reported a
0.93160 hit rate with 847 entries and 16,765,488 bytes of estimated weight. In this synthetic trace,
the bound reduced estimated admission weight by approximately 15.3% at a 0.56 percentage-point
hit-rate cost. These are cache admission estimates and isolated heap deltas, not exact retained
sizes, request latency or production throughput measurements.
