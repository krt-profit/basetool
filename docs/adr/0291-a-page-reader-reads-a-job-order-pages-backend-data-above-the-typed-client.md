# ADR-0291 — A page reader reads a job-order page's backend data above the typed client

- **Status:** Accepted (pilot for the job-order pages)
- **Date:** 2026-10-18
- **Deciders:** @greluc
- **Related:** `REQ-FE-036`, `REQ-FE-029` (plan F3, `TypedBackendClientTest`) · issue #2740

## Context

The frontend page controllers called the typed `<Domain>BackendClient` method by method. A part of a
page that may fail without failing the page went through `SectionFallback.load(…, null)`, and the
controller then replaced the `null` with its own default. `JobOrderPageController` alone had 17 such
sites, and each page repeated the same choices: which calls are needed, which run in parallel, which
part may be missing, what it falls back to. Controller tests could only stub `BackendApiClient` by
path underneath the typed clients, so two calls of the same type were indistinguishable.

The typed clients are not the problem. They own the domain's backend paths, enforce URI-template
variables (`REQ-SEC-051`) and are pinned by `*BackendClientTest`; deleting or fattening them would
break plan F3.

## Decision

1. **A page reader sits above the typed client.** `JobOrderPageReader` is a Spring bean in
   `joborder.web`. It exposes one read per page (`readOrderList`, `readOrderDetail`,
   `readOrderCreate`, `readItemEdit`) and the one endpoint read that has a fallback and a default
   (`readItemBlueprints`). Each returns an immutable record holding every backend value the page
   renders. It calls only `JobOrderBackendClient` and the kernel helpers (`SectionFallback`,
   `ParallelPageLoader`); it reads only, writes keep going through the typed client.
2. **The reader owns the page's resilience.** Which calls run, which run in parallel, which part may
   fail, and the default each gets live in the reader; a part that fails is logged once through
   `SectionFallback` under its existing section name. Availability is explicit in the record (a
   flag, an `Optional`, or an access enum) and never a `null` the controller interprets. A part whose
   failure fails the page today still does, and a lost session propagates as
   `ReauthenticationRequiredException`, also out of a parallel part.
3. **The controller only maps the record into the model.** It has no `SectionFallback.load(…, null)`
   and no re-defaulting of a backend result. A form post that fails re-renders by redirecting back to
   the page GET, so it gets the same reader read.
4. **When a page gets a reader read.** When it has a section fallback, a default, or a parallel load.
   A page whose backend data is one call with no fallback and no default keeps calling the typed
   client, and the reader grows no method that only forwards to it.
5. **The records add no hand-maintained DTO layer.** They hold the existing DTO mirrors, so the DTO
   generation of ADR-0161 is unaffected.
6. **Tests.** A reader test stubs `JobOrderBackendClient` by method and covers success, each optional
   part failing, a required part failing and a lost session; a controller test stubs the reader and
   asserts the model.
7. **Outcome criterion of the pilot.** The other domains follow, one issue each, only after
   @greluc has accepted the pilot. No follow-up issue is opened before.

## Consequences

- A controller test stubs one reader method instead of a dozen backend paths.
- A fallback log line now carries the reader's logger name instead of the controller's; the message,
  section name and level are unchanged.
- `JobOrderPageController` shrinks and its size-ratchet entry (ADR-0267) is lowered.
- `QualityTierCatalog` stays a controller call: it is one cached call without fallback or default.

## Alternatives considered

- **Defaults inside the typed clients** — breaks the thin-client rule of plan F3 and mixes wire and
  page concerns.
- **Delete the typed clients** — moves paths into controllers and breaks `REQ-SEC-051` and the
  `TypedBackendClientTest` confinement.
- **Leave it as is** — keeps the repeated null-then-default pattern and path-matching controller
  tests.
