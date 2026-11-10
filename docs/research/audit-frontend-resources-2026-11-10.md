# Frontend audit resource characterization

Measured on 2026-11-10 for #3284, #3285, #3286, #3292 and #3293, under epic #3267.
These are controlled local characterization results, not production capacity guarantees.

## Reproduction

Run through the repository wrapper from the audit worktree:

```powershell
$env:GRADLE_USER_HOME = 'D:\Coding\Agents\Temp\codex\basetool-audit-3267\gradle-home'
.\gradlew.bat :frontend:resourceProfileTest -PresourceProfileOutput=D:/Coding/Agents/Temp/codex/basetool-audit-3267/frontend/profile
```

The opt-in `FrontendResourceProfileTest` runs in a separate Java 25 JVM with a 1 GiB maximum heap.
It writes `frontend-resources.json` under the explicit output directory and deletes its synthetic
payload. Regular `test` excludes the `resource-profile` tag.

## Parallel near-cap transfers

Four workers each transfer 63,000,000 bytes, 98.4% of the 64,000,000-byte upload limit. Each case
processes 252,000,000 payload bytes. Uploads start from a disk-backed resource; the buffered baseline
materializes the same file before sending it. Downloads compare whole-body decoding with the
streaming transfer into a counting output. Baseline download arrays rendezvous before release,
representing concurrent responses retained for browser output.

A loopback JDK HTTP server consumes uploads without retaining bodies and generates downloads from
a reusable 16 KiB chunk. Heap usage is sampled every 5 ms; forced collections separate cases and
measure retention. The numbers include the client and the small discard/generator server in one
JVM. They exclude TLS, real OAuth grants, servlet multipart spooling, backend parsing/rendering,
direct/native buffers and production network conditions.

| Case | Heap before | Sampled peak heap | Retained heap after GC | Elapsed |
| --- | ---: | ---: | ---: | ---: |
| Buffered uploads | 41,983,448 B | 351,807,664 B (335.51 MiB) | 43,600,160 B | 465 ms |
| Streamed uploads | 43,599,920 B | 84,494,384 B (80.58 MiB) | 43,717,784 B | 241 ms |
| Buffered downloads | 43,716,992 B | 307,918,256 B (293.65 MiB) | 44,348,592 B | 184 ms |
| Streamed downloads | 44,349,648 B | 63,224,016 B (60.30 MiB) | 44,391,896 B | 256 ms |

The streaming cases lower sampled heap for this fixture. Download elapsed time increases in this
run; the implementation makes no general throughput claim. Sampling can miss shorter peaks, and
one local run does not establish a latency distribution.

## Inactive OAuth client retention

The fixture creates 5,000 real synthetic authorized-client objects with 1,024-character access
tokens. Their freshness window ends naturally; no subsequent request or explicit session-end
eviction accesses the expiring cache. Weak references distinguish retained clients from unrelated
framework heap. A reachability fence keeps each owning cache alive during the measurement.

| Policy | Retained heap delta before expiry | Clients reachable after natural expiry |
| --- | ---: | ---: |
| Unexpiring map baseline | 6,502,904 B | 5,000 |
| Scheduled token-aware cache | 6,892,896 B | 0 |

The expiring case retains 272,088 bytes above its starting heap after natural expiry and GC,
including weak-reference bookkeeping. Its populated-cache overhead is higher than the map's in
this fixture; the improvement is automatic release of inactive token/client objects. Separate
regression tests pin per-session single-flight refresh and session-specific invalidation.

## Browser asset characterization

The opt-in `assetProfileTest` uses the standard Playwright stack harness, fresh Chromium contexts
and disabled browser caches. It compares `/privacy` with eager optional module tags and the pinned
original stylesheet against the selective asset variant, with five alternating trials per variant.
It records actual decoded JS/CSS response-body bytes, request counts, Chromium `ScriptDuration`
and full-page screenshots in the profile directory. The original CSS is minified with the same
comment/whitespace transformation as `minifyStaticCss`; no bundler or dynamic import is involved.

```powershell
git show 5f0a2d0f1:frontend/src/main/resources/static/css/kernel/styles.css |
    Set-Content -LiteralPath 'D:\Coding\Agents\Temp\codex\basetool-audit-3267\frontend\profile\styles-baseline.css' -Encoding utf8
.\gradlew.bat :frontend:assetProfileTest -Pe2e.browser=chromium -PresourceProfileOutput=D:/Coding/Agents/Temp/codex/basetool-audit-3267/frontend/profile -PassetProfileBaselineCss=D:/Coding/Agents/Temp/codex/basetool-audit-3267/frontend/profile/styles-baseline.css
```

Browser results are recorded after the isolated stack validation completes. A decoded-body byte
comparison does not measure wire compression or HTTP header overhead; browser timing has its own
run-to-run variance.
