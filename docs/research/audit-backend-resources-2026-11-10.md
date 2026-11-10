# Backend audit resource characterization

Measured on 2026-11-10 for the Bank export work in #3287 under epic #3267. These are controlled
local characterization results, not production capacity guarantees or a before/after benchmark.

## Reproduction

Run through the repository wrapper from the implementation checkout, with the test JVM's temporary
directory configured under the task's temporary folder:

```powershell
$env:GRADLE_USER_HOME = 'D:\Coding\Agents\Temp\codex\basetool-audit-3267\gradle-home'
$env:JAVA_TOOL_OPTIONS = '-Dbasetool.audit.resourceProfile=true'
.\gradlew.bat :backend:test --tests '*BankReportResourceProfileTest' --console=plain
Remove-Item Env:JAVA_TOOL_OPTIONS
```

`BankReportResourceProfileTest` is opt-in. It uses synthetic account, holder and posting data and
removes its private export directories. The two methods exercise a real PostgreSQL snapshot and
four concurrent detached renderings respectively. Their stdout is part of the Gradle test result.

## Database snapshot and detached rendering

The database fixture books 1,001 deposits and captures three posting chunks of at most 500 rows.
It verifies all 1,001 rows and an exact closing balance of 1,001, then asserts that the snapshot's
transaction has ended before invoking the renderer.

| Measurement | Result |
| --- | ---: |
| Snapshot transaction | 99 ms |
| Prepared statements during snapshot | 8 |
| Detached rendering | 426 ms |
| Snapshot file | 190,235 B |
| PDF file | 1,469,983 B |

This fixture measures one account and one holder. It does not establish a database latency
distribution or compare the transaction duration with the former rendering transaction. A separate
MVC regression exercises the org-unit export endpoint and verifies that the authorization
transaction, connection and entity manager are released before rendering starts.

## Concurrent complete long-period statements

Four workers each render 20,000 synthetic postings within a 1970–2050 period. All rows are consumed;
there is no report-row truncation. Each worker streams its completed PDF to a discard sink and
closes its spool. The fixture first warms the renderer with 100 rows and samples JVM heap every
10 ms during the concurrent run.

| Measurement | Result |
| --- | ---: |
| Statements / rows per statement | 4 / 20,000 |
| Total snapshot files | 16,917,960 B |
| Total PDF files | 136,391,104 B |
| Heap before concurrent run | 668,311,872 B |
| Sampled peak heap | 780,807,992 B |
| Longest synthetic snapshot write | 88 ms |
| Longest rendering | 3,353 ms |
| Total concurrent elapsed time | 3,459 ms |

This invocation shared a test JVM with another Spring application context from the OpenAPI test.
The heap values include that context, other framework state and garbage awaiting collection; they
are not isolated renderer allocation or retained-heap measurements. Sampling can miss short peaks.
The snapshot phase here writes generated detached rows rather than querying PostgreSQL. The
results exclude HTTP/TLS transfer, browser handling, native/direct buffers and production disk or
database contention. OpenPDF retains document/page metadata, so total heap is not claimed to be
constant with page count. The bounded parts are row chunks, leg lookups, table flushes and chart
vertices; the snapshot and complete PDF remain disk-backed.

## Analogous exports

The target-branch source review also checked `AuditReportService`, `BankAuditReportService`,
`JobOrderHandoverReportService` and `JobOrderItemHandoverReportService`. Both audit services load
their selected events and formatted rows and return PDF byte arrays within the export transaction.
Their existing REQ-AUDIT-004 limit rejects periods above 100,000 rows before retrieval; it does not
silently truncate them. Both handover services load one handover's items, sort detached display
rows and return a PDF byte array while their read-only transaction remains active. These services
do not use the Bank statement/management spool. No heap or transaction-duration measurement was
made for those different workloads, and the Bank measurements do not establish their capacity.

## Lifecycle and operational limits

Regression tests cover normal conversion, repeated resource opens, HEAD/zero-body responses,
conversion errors, cancellation and failed rendering. Each request owns its export until response
conversion completes. Bank endpoints belong to the existing no-store API family and bypass the
shallow ETag response buffer for GET requests.

The canonical setting is `app.bank.exports.directory`, defaulting to the existing writable JVM
temporary directory. Private child directories use owner-only POSIX permissions or filesystem
ACLs; unsupported filesystems fail closed. Available disk must hold every concurrent snapshot and
PDF until its request completes. A process termination can interrupt in-process cleanup; normal
runtime temporary-directory housekeeping remains necessary. No additional production host setting
or host write is required by this change.
