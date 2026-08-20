# ADR-0136 — The endpoints a shipped client consumes are a frozen contract

- **Status:** Proposed
- **Date:** 2026-08-18
- **Related:** [ADR-0135](0135-public-api-vhost-not-a-gateway.md) ·
  [ADR-0003](0003-inventory-append-only-group-on-read.md) (the carve-out this narrows) ·
  specs `REQ-API-001`, `REQ-API-007`, `REQ-API-009`, `REQ-SEC-027` ·
  [`ANDROID_API_EXPOSURE_PLAN.md`](../ANDROID_API_EXPOSURE_PLAN.md) item B3 ·
  `ExternalContractTest`

## Context

REQ-API-001 carries a carve-out that has served the project well:

> a `/api/v1` endpoint consumed solely by the in-repo frontend may change its response *shape* in
> place (no `/api/v2` bump) when frontend and backend deploy atomically

The justification is the atomic deploy. Both modules ship from one repository, promote as one
digest set, and `DtoOpenApiContractTest` catches a frontend mirror that drifts from the server. In
that world a shape change is a refactor, not a break, and demanding `/api/v2` for it would be
ceremony.

A released Android build removes the premise entirely. It sits on a member's phone for weeks or
months; distribution is GitHub Releases plus Obtainium (decision Q1), so there is not even a store
pushing silent updates. A field the server stops sending is a crash or a blank screen in a version
nobody can redeploy, and the operator's only lever would be the client kill switch — taking every
version out at once to fix a break that affects one.

So the question is not *whether* some endpoints must be frozen. It is **which**, and what frozen
means precisely enough to be checkable.

## Decision

**An explicit external contract set, enumerated in the repository, frozen against in-place shape
change, and grown one app phase at a time.**

REQ-API-009 defines it; `ExternalContractTest` enumerates it and fails the build when it is
violated. As of the app's phase 1 the set is the five operations the vhost allow-lists today —
terms status and acceptance, the two `/me` reads, and the registration-status read.

**Frozen means, for an operation in the set:**

- it keeps its path and its verb;
- its response keeps every field it had — additive change is explicitly fine, removal and rename are
  not;
- its request accepts everything it accepted before — a new required field is a break;
- retiring it goes through `/api/v2` plus `@ApiDeprecation` with a sunset, not a deletion.

Everything outside the set keeps REQ-API-001's carve-out unchanged. The web app's endpoints stay as
evolvable as they are today; this narrows the carve-out rather than replacing it.

**The spec plus the test are the source of truth — not the vhost allow-list.** The allow-list
decides what is *reachable* and lives in the NPM admin database, which is not version-controlled and
cannot be reviewed in a PR. It must be a subset of the contract set, and the two move in the same
change: opening a family to the app and freezing it are the same decision seen from two sides.

## Consequences

**Each app phase costs one deliberate edit.** Phase 2 (missions, notifications, hangar, inventory,
orders, bank reads) extends the contract set, the allow-list and the test together. That friction is
the point: it is the moment to ask whether an endpoint's shape is one we want to live with, while
changing it is still free.

**The guard is honest about its reach.** `ExternalContractTest` reads the committed `openapi.json`
— the artifact REQ-API-007 already keeps in sync — and catches a vanished operation, a changed verb
and a dropped response field. It follows the response schema's `$ref` **or, for a list endpoint,
the `$ref` of its `items`**; that second case was added when phase 2 put the first array-returning
operation in the set, and without it every list endpoint resolved to no fields at all — an entry
recording none would then have passed while proving nothing. It does **not** compare types, nullability or enum values. A field
that turns from string to object, or an enum that loses a constant, passes it and still breaks an
old build. The real answer is a schema diff of the contract subset against the previous release
tag, and that is the next step this ADR names rather than one it pretends to have taken.

**Retirement needs A5.** A sunset only ends when the old builds are gone, and nothing today can tell
an old build to stop. The minimum-app-version gate (`User-Agent: basetool-android/<semver>`, plan
item A5) is what converts "deprecated" into "actually retired", which makes it a prerequisite for
the first `/api/v2` rather than a nice-to-have.

**Anonymous and guest paths are not in the set yet.** They join it when guest mode ships, together
with their allow-list entries — the same lock-step.

**A break that must happen anyway is not forbidden, only made visible.** The test fails, the PR
explains why, and the decision is taken with the sunset and the min-version gate in view instead of
discovered by a member whose app stopped working.

## Alternatives considered

*Freeze all of `/api/v1`.* Simple to state and far too broad: it would end in-place evolution for
the web app, which is the majority of the surface and has an atomic deploy that makes the freedom
safe. The carve-out exists for a reason; the app removes that reason only where the app reaches.

*Let the vhost allow-list define the set implicitly.* Attractive — one list instead of two — and
wrong in the same way as any config that lives only in a production database. It cannot be diffed in
review, and a change to it would silently redefine what the project has promised.

*Per-field deprecation metadata in the DTOs, no set at all.* More granular and much heavier: every
field would need a lifecycle, and the thing that actually breaks a client — an operation
disappearing — is not a field-level concern.

*Nothing formal; fix forward when a break appears.* This is the status quo, and its cost lands
entirely on people who cannot redeploy. It also has no mechanism: without a recorded contract there
is nothing for a test or a reviewer to notice.
