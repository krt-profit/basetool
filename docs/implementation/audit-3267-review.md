# Independent audit implementation review

## Scope

Two fresh reviewers independently examined the candidate
`8e34c673e6b77d3acdab7be5c67b37bff6927c3b` against main baseline
`5f0a2d0f150205eb8db145d34d5e8e5f695f0e83`. The originating specification is
[#3267](https://github.com/krt-profit/basetool/issues/3267) and its 57 native children. The Spec
reviewer fetched every child body. Neither reviewer changed files, ran Gradle or accessed production.

## Standards

No actionable substantive finding in the pinned candidate. The reviewer concentrated on role
section locks, announcement concurrency, inventory and exchange ownership, transaction suspension,
Bank snapshot/resource lifetimes, frontend resilience and reauthentication, DPoP capacity, shared
technical ownership and build integration. This was a targeted static examination of a large diff.
Formatting, compiler and architecture-gate findings remain the responsibility of the executable
checks; this result does not replace them.

## Spec

Two P2 findings were confirmed and corrected:

1. **Superseded error-body reads (#3315).** A delayed non-2xx JSON body could lose its cancellation
   and clear newer blueprint search results. The transport checks cancellation before response
   classification, during error-body failure handling and after decoding. Three reversed-response
   regressions retain the latest visible data when the old body resolves, aborts or fails parsing.
2. **Multiple eligible terminal names (#3281).** A single name-to-row map discarded other unclaimed
   local terminals with the same name. An ordered candidate queue consumes each eligible row once;
   existing external IDs and renames cannot displace another candidate. Unit and PostgreSQL
   regressions preserve both local UUIDs, overrides and shop references.

The Spec reviewer re-examined both fixes in the working tree and found no further concrete
regression in those changes. Their executable results belong to the
[implementation validation record](audit-3267.md#validation-record). No scope-creep finding was
reported. The existing-work disposition of #3296 was accepted as consistent with keeping the aUEC
implementation in its separate PR.

Standards: 0 actionable static findings. Spec: 2 P2 findings corrected and statically rechecked;
final regression verification is recorded separately.

## Focused JVM generator and image follow-up

A read-only static review examined the uncommitted JVM generator port and its Docker, Compose
watch, SBOM and image-reuse changes against
`30e836fbb786b09cb6ca92616797d6bcef51f176`. The scope covered the cacheable typed
`GenerateWireRecords` task, unsupported wire-schema guards, application and sandbox image inputs,
and the corresponding rebuild triggers. No actionable defect was found.

This follow-up ran no Gradle task, build, gate or production command. The successful frontend
Docker AOT build, overall check and generator runs are separate executable evidence in the
[implementation validation record](audit-3267.md#validation-record); they were not repeated by
this reviewer.
