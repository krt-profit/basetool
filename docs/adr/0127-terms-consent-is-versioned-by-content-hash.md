# ADR-0127 — Terms-of-Use consent is versioned by a content hash, enforced in the backend

- **Status:** Accepted
- **Date:** 2026-08-03
- **Related:** spec `REQ-SEC-028` ([`security-and-access.md`](../specs/security-and-access.md)) ·
  builds on `REQ-SEC-027` (the approved-client obligation the consent makes enforceable) ·
  ADR-0047 (acyclic backend packages) · ADR-0088 (30-day authenticated session) ·
  `REQ-INGEST-001` (the gateway relays the caller's own bearer)

## Context

The terms took effect merely on access, and section 12 treated continued use as acceptance. That
left no evidence of who agreed to which wording — precisely what is needed when a clause is
enforced against an individual, which `REQ-SEC-027` now makes a live possibility.

Three questions had to be answered before any of it could be built, and each had an obvious answer
that turns out to be wrong.

## Decision

### 1. The version is derived from the wording, not declared next to it

A version number someone bumps by hand is a version number someone forgets to bump — and the
failure is silent: the terms change, nobody is re-prompted, and the recorded consent now points at
text that no longer exists. So the version **is** a hash of the `terms.*` bundle entries, computed
at build time by the root `generateTermsVersion` task.

The cost is real and accepted: a typo fix re-prompts the whole squadron. `-PtermsVersion=<value>`
pins the value for one build when an edit was genuinely cosmetic. The default stays derived,
because the failure mode of the default matters more than the convenience of the exception.

**Committed, not generated during the build.** The first cut generated it into the build directory
and wired the task into `processResources`. That made the **backend** build read a **frontend**
source file — and the backend Docker image copies only `frontend/build.gradle.kts` for layer
caching, so the E2E image build died with `Input file does not exist` while every local build
stayed green. The artifact is therefore committed to
`backend/src/main/resources/terms-version.properties` and the task is run explicitly
(`./gradlew generateTermsVersion`), following the `openapi.json` precedent already in this repo.

What a committed artifact loses is the guarantee that it matches the text — the whole point of
deriving it. `TermsVersionParityTest` restores that guarantee: it re-derives the digest from the
bundle and fails the build when the committed file disagrees, so editing the terms and forgetting
to regenerate breaks CI instead of silently leaving everyone consented to wording that no longer
exists.

**Backend only.** The obvious symmetry — give both modules the version — creates two sources of
truth that can disagree. The backend is the authority; the frontend and the gateway learn the
answer from it.

### 2. Enforcement lives in the backend, not the frontend

The frontend redirect is the visible part, so it looks like the natural home. It is not: the ingest
gateway relays the caller's own bearer, so the desktop extractor reaches the backend without
passing any frontend code. Enforcing in the backend covers the web UI and the extractor with one
filter, and the gateway needs no copy of the rule — it already relays a 4xx with the backend's own
`detail`, so the extractor shows a localized, actionable message for free.

Consequence: `config.TermsAcceptanceAccessFilter` needs an answer that lives in `service`, which
closes a package cycle ArchUnit rejects (ADR-0047). Resolved as that rule's own message prescribes —
the question is declared as `support.TermsConsentCheck` in the dependency-free leaf and implemented
by the service, so both packages point at the leaf instead of at each other.

### 3. The gate is armed by default and stood down only under the `test` profile

Arming it broke 120 of 4640 backend tests: MockMvc callers are synthetic subjects with no acceptance
row and no way to create one. The tempting fix is a property that switches enforcement on — and it
is the wrong one, because it ships a gate that looks armed and is not. That is the same failure
`TermsVersionProvider` refuses to start for.

So the carve-out is profile-scoped, mirroring the CSRF carve-out already in the same file, and the
filters are driven directly by their own tests. The E2E profile is `dev`, **not** `test`, so the
gate is live there; `E2eSupport#acceptTermsIfPrompted` clicks through it on every login rather than
pre-seeding an acceptance row, which keeps the whole suite exercising the real path instead of
bypassing it with fixture data.

## Consequences

- Any wording change — including a typo — re-prompts every member and, because the gate covers
  ingest, stops the desktop extractor until they accept once in a browser. The pin flag exists for
  exactly this.
- Consent is append-only (`terms_acceptance`, V229): re-consent adds history rather than
  overwriting it, so "accepted version A on X, version B on Y" stays readable as evidence.
- No cache may outlive a wording change. The frontend re-reads every 60 s (a session lives 30 days,
  ADR-0088); the backend caches only *positive* answers, which are monotonic within a process
  because the version is a build artifact. A cached negative would keep blocking a user who
  accepted on another instance.
- `TermsConsentRolloutStalled` fires when the gate refuses callers while nobody gets through — the
  shape a broken consent path has from the outside. A flat acceptance gauge alone proves nothing.

## Rejected alternatives

- **A hand-maintained version number** — silently goes stale, and the staleness is invisible until
  someone asks which wording a stored consent refers to.
- **A property that enables enforcement** — a gate that looks armed and is not.
- **Enforcing only in the frontend** — leaves the extractor path unguarded, the one caller that does
  not pass through frontend code.
- **Pre-seeding acceptances for E2E users** — makes the suite green by bypassing the feature it is
  supposed to cover.
- **Blocking non-UUID subjects** (service accounts, malformed tokens) — they are not people who can
  accept anything, and the audience and scope checks already govern them.

