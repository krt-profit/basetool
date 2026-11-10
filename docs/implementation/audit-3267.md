# Whole-codebase audit implementation

> **Doc type:** Living implementation record for #3267.

The audit is delivered on `codex/implement-audit-3267` in a separate pull request from the aUEC
work. The implementation baseline is `5f0a2d0f150205eb8db145d34d5e8e5f695f0e83` from main.
All 57 native child issues were enumerated; none has a nested child. A row is not complete until
its acceptance and relevant regression evidence are recorded. No production host was accessed.

## Acceptance matrix

| Child | Finding | Delivery state |
| --- | --- | --- |
| [#3268](https://github.com/krt-profit/basetool/issues/3268) | Preserve Lombok qualifier configuration in application image builds | Implementation present; application-image artifact check pending |
| [#3269](https://github.com/krt-profit/basetool/issues/3269) | Mask structured log values without corrupting JSON or retaining escaped secret suffixes | Implementation present; final regression verification pending |
| [#3270](https://github.com/krt-profit/basetool/issues/3270) | Preserve staged blueprints when a backend batch write fails | Implementation present; final regression verification pending |
| [#3271](https://github.com/krt-profit/basetool/issues/3271) | Check the administrator's expected version when editing roles | Implementation present; final regression verification pending |
| [#3272](https://github.com/krt-profit/basetool/issues/3272) | Make container cleanup permissions and success reporting match actual prune commands | Implementation present; final regression verification pending |
| [#3273](https://github.com/krt-profit/basetool/issues/3273) | Strengthen restore-drill verification beyond schema table counts | Implementation present; final regression verification pending |
| [#3274](https://github.com/krt-profit/basetool/issues/3274) | Keep the Alloy validator exit status when checking monitoring configuration | Implementation present; final regression verification pending |
| [#3275](https://github.com/krt-profit/basetool/issues/3275) | Remove every undeclared change-set field independently of the warning cap | Implementation present; final regression verification pending |
| [#3276](https://github.com/krt-profit/basetool/issues/3276) | Enforce the global DPoP proof capacity atomically and bound saturated-store work | Implementation present; final regression verification pending |
| [#3277](https://github.com/krt-profit/basetool/issues/3277) | Expose post-write callback failures without resending completed mutations | Implementation present; final regression verification pending |
| [#3278](https://github.com/krt-profit/basetool/issues/3278) | Let expired-session failures reach the central frontend reauthentication handler | Implementation present; final regression verification pending |
| [#3279](https://github.com/krt-profit/basetool/issues/3279) | Model announcements as a database-enforced singleton without GET-side writes | Implementation present; final regression verification pending |
| [#3280](https://github.com/krt-profit/basetool/issues/3280) | Batch roster and command-group reads for the leadership overview | Implementation present; final regression verification pending |
| [#3281](https://github.com/krt-profit/basetool/issues/3281) | Batch existing-row lookups inside UEX synchronization chunks | Implementation present; final regression verification pending |
| [#3282](https://github.com/krt-profit/basetool/issues/3282) | Read purchasing page state once per request | Implementation present; final regression verification pending |
| [#3283](https://github.com/krt-profit/basetool/issues/3283) | Fetch refinery status counters through one scoped aggregate read | Implementation present; final regression verification pending |
| [#3284](https://github.com/krt-profit/basetool/issues/3284) | Expire cached OAuth clients without invalidating every session entry | Implementation present; final regression verification pending |
| [#3285](https://github.com/krt-profit/basetool/issues/3285) | Stream P4K upload relays with bounded frontend memory | Implementation present; final regression verification pending |
| [#3286](https://github.com/krt-profit/basetool/issues/3286) | Stream binary export responses through the frontend | Streaming relay implemented; lifetime/disconnect verification pending |
| [#3287](https://github.com/krt-profit/basetool/issues/3287) | Bound backend export working sets and separate query snapshots from rendering | Snapshot/export implementation in progress |
| [#3288](https://github.com/krt-profit/basetool/issues/3288) | Remove the redundant backend request-body array clone | Implementation present; final regression verification pending |
| [#3289](https://github.com/krt-profit/basetool/issues/3289) | Reuse buffered request data and avoid unnecessary idempotency response conversions | Implementation present; final regression verification pending |
| [#3290](https://github.com/krt-profit/basetool/issues/3290) | Set backend cache budgets according to retained value size | Weighted policy implemented; calibration and regression results pending |
| [#3291](https://github.com/krt-profit/basetool/issues/3291) | Prepare personal-data export scrubbing from a narrow handle projection | Implementation present; final regression verification pending |
| [#3292](https://github.com/krt-profit/basetool/issues/3292) | Load optional shared scripts only on pages that use them | Implementation present; final regression verification pending |
| [#3293](https://github.com/krt-profit/basetool/issues/3293) | Split the global stylesheet into cohesive selectively loaded style groups | Implementation present; final regression verification pending |
| [#3294](https://github.com/krt-profit/basetool/issues/3294) | Apply backend resilience limits through response-body completion | Decoded-body policy implemented; timeout/bulkhead verification pending |
| [#3295](https://github.com/krt-profit/basetool/issues/3295) | Replace manual frontend wire DTOs incrementally with generated records | Implementation present; final regression verification pending |
| [#3296](https://github.com/krt-profit/basetool/issues/3296) | Reconcile the duplicated transfer-fee audit finding with completed AUEC policy work | Reconciled with existing implementation in PR #3214; no duplicate implementation |
| [#3297](https://github.com/krt-profit/basetool/issues/3297) | Separate inventory checkout coordination from merge and bulk responsibilities | Implementation present; final regression verification pending |
| [#3298](https://github.com/krt-profit/basetool/issues/3298) | Split backend mission HTTP handlers by operation family | Implementation present; final regression verification pending |
| [#3299](https://github.com/krt-profit/basetool/issues/3299) | Separate org-stock validation and planning from change-set execution | Implementation present; final regression verification pending |
| [#3300](https://github.com/krt-profit/basetool/issues/3300) | Split frontend mission write handlers by independently editable responsibility | Implementation present; final regression verification pending |
| [#3301](https://github.com/krt-profit/basetool/issues/3301) | Extract cohesive deployment modules from the main orchestration script | Implementation present; final regression verification pending |
| [#3302](https://github.com/krt-profit/basetool/issues/3302) | Declare Node script tests through a typed Gradle registration helper | Implementation present; final regression verification pending |
| [#3303](https://github.com/krt-profit/basetool/issues/3303) | Generate the default German message bundle from one canonical source | Implementation present; final regression verification pending |
| [#3304](https://github.com/krt-profit/basetool/issues/3304) | Give identical cross-application technical logic one narrow owner | Implementation present; final regression verification pending |
| [#3305](https://github.com/krt-profit/basetool/issues/3305) | Use one explicit environment-file grammar in the configuration renderers | Implementation present; final regression verification pending |
| [#3306](https://github.com/krt-profit/basetool/issues/3306) | Share personal-blueprint construction between import and manual acquisition | Implementation present; final regression verification pending |
| [#3307](https://github.com/krt-profit/basetool/issues/3307) | Share registered-counterparty snapshot resolution inside Bank | Implementation present; final regression verification pending |
| [#3308](https://github.com/krt-profit/basetool/issues/3308) | Share counterparty-cell formatting across Bank PDF reports | Implementation present; final regression verification pending |
| [#3309](https://github.com/krt-profit/basetool/issues/3309) | Replace remaining role and squadron entity caches with immutable read models | Implementation present; final regression verification pending |
| [#3310](https://github.com/krt-profit/basetool/issues/3310) | Construct test collaborators instead of reflectively replacing final fields | Implementation present; final regression verification pending |
| [#3311](https://github.com/krt-profit/basetool/issues/3311) | Reuse identical production bytecode imports across backend architecture tests | Implementation present; final regression verification pending |
| [#3312](https://github.com/krt-profit/basetool/issues/3312) | Give frontend hook registries concrete function contracts | Implementation present; final regression verification pending |
| [#3313](https://github.com/krt-profit/basetool/issues/3313) | Use typed request records for Bank visibility mutations | Implementation present; final regression verification pending |
| [#3314](https://github.com/krt-profit/basetool/issues/3314) | Type the org-stock operation discriminator at the adapter boundary | Implementation present; final regression verification pending |
| [#3315](https://github.com/krt-profit/basetool/issues/3315) | Prevent older blueprint search responses from replacing newer results | Implementation present; final regression verification pending |
| [#3316](https://github.com/krt-profit/basetool/issues/3316) | Isolate transaction-bound resources across suspension and REQUIRES_NEW | Implementation present; final regression verification pending |
| [#3317](https://github.com/krt-profit/basetool/issues/3317) | Reuse strict same-origin validation for login and consent navigation | Implementation present; final regression verification pending |
| [#3318](https://github.com/krt-profit/basetool/issues/3318) | Bound Keycloak SPI HTTP response bodies | Implementation present; final regression verification pending |
| [#3319](https://github.com/krt-profit/basetool/issues/3319) | Encode purchasing CSV cells without data loss and define spreadsheet interpretation | Implementation present; final regression verification pending |
| [#3320](https://github.com/krt-profit/basetool/issues/3320) | Give external tool and HTTP calls explicit time budgets | Implementation present; final regression verification pending |
| [#3321](https://github.com/krt-profit/basetool/issues/3321) | Cover installed operational units and companion files in host drift verification | Implementation present; final regression verification pending |
| [#3322](https://github.com/krt-profit/basetool/issues/3322) | Restrict environment control in password-free read-only Podman access | Implementation present; final regression verification pending |
| [#3323](https://github.com/krt-profit/basetool/issues/3323) | Localize the Keycloak OTP QR-code alternative text | Implementation present; final regression verification pending |
| [#3324](https://github.com/krt-profit/basetool/issues/3324) | Remove the retired contact-address literal from the archived changelog | Implementation present; final regression verification pending |

## Shared fee policy disposition

AUD-29 (#3296) overlaps completed AUEC 06 #3223. Its closure records the exact implementation:
[commit 55f161bc40e2658b8b7c2aacd030d1e7ae1f1525](https://github.com/krt-profit/basetool/commit/55f161bc40e2658b8b7c2aacd030d1e7ae1f1525)
in [PR #3214](https://github.com/krt-profit/basetool/pull/3214). Inspection of that commit confirms
`admin.api.TransferFeePolicy` and `admin.internal.ConfiguredTransferFeePolicy` own the setting,
default, parsing and bounds. Bank consumes immutable rate snapshots and exact whole-aUEC fees;
Operations shares configuration parsing while retaining its two-decimal fee and final payout
rounding. There is no duplicated policy framework or JobOrder-to-Bank dependency.

That commit is not an ancestor of this main-based audit baseline. The shared policy remains in
its existing aUEC delivery; the audit does not import unrelated aUEC changes or claim it has already
merged into main. Its successful integration remains owned by PR #3214. The audit child is
reconciled through this explicit existing-work disposition, rather than commissioned again.

## Validation record

The first logging run reproduced seven failing encoder tests and the adapter was corrected.
The next logging and Keycloak SPI test suites passed. The new technical library's tests,
Checkstyle and SpotBugs passed. Initial application and browser-helper runs exposed moved-method
references and test harness mistakes; fixes are being validated. This record will be completed
with the final reproducible commands and results before delivery.

## Resource evidence

Allocation, retained-heap, query-count and response-lifetime evidence belongs to the individual
regression or characterization tasks. Synthetic results do not establish production latency or
heap improvements. Long-period exports, concurrent work and cancellation are tested without a
silent data cutoff; no production payload or credential is used.
