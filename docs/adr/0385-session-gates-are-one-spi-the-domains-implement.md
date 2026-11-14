# ADR-0385 — Session gates are one SPI the domains implement

- **Status:** Accepted
- **Date:** 2026-11-14
- **Deciders:** @greluc
- **Related:** [security-and-access spec](../specs/security-and-access.md) (REQ-SEC-028,
  REQ-SEC-029) · [patch-change spec](../specs/patch-change.md) (REQ-PATCH-020) ·
  [ADR-0128](0128-gated-websocket-handshake-refused-with-a-terminal-close-code.md) ·
  [ADR-0380](0380-the-patchchange-module-records-patch-changes-and-runs-their-effects.md) ·
  owner decision D4 on the review of #3423

## Context

Two steps hold a member before the Basetool serves them: accepting the Terms of Use
(REQ-SEC-028) and, after a patch change, choosing a new home location (REQ-PATCH-020). Each had
its own backend filter and its own frontend filter, nearly identical, and the frontend kernel
named both domains: the filters, the status DTO, the exempt pages, the SSE cascade of the
notification stream and the AJAX header list of `krt-fetch.js` each listed the two gates by hand.
A third gate would have meant editing every one of these places, and the kernel and the platform
are closed to domain meaning (REQ-MOD-*, `KernelDomainReachTest`).

The frontend's home-location gate also cached "may proceed" for 60 s, so a member a new patch
change asks could keep using the tool for up to a minute while every backend call answered `403`.

## Decision

1. **The backend platform owns `platform.api.SessionGate`**: a gate names its `ProblemCode`, its
   own exempt endpoints, whether it holds an authenticated caller, an English fallback detail and
   an optional hook for a refusal. One `SessionGateAccessFilter` runs every gate in its `@Order`
   and refuses with the first that holds the caller, with the gate's code, `type` and bundle keys,
   which `ProblemCode` derives. The identity module implements the consent gate
   (`TermsAcceptanceGate`, first), the patch-change module the home-location gate
   (`HomeLocationGate`, second).
2. **The frontend kernel owns `kernel.security.SessionGate`**: a gate names its page, its AJAX
   header, its SSE event and its backend problem code, decides whether it holds the session and
   keeps its own cached verdict. `SessionGates` is the one ordered list; `SessionGateFilter`, the
   notification stream's hand-off, the error handler, the role sync and the active-org-unit restore
   read it. The identity domain implements `TermsGate`, the patch-change domain `HomeLocationGate`.
3. **A gate leaves open what an earlier gate needs.** On both sides a gate never holds a member on
   its own page or endpoints nor on those of a gate ordered before it, so each step stays reachable
   while an earlier one holds the member. The approval waiting page is open to every gate. A gate's
   page renders the layout, whose reads the backend gate refuses, so on a gate's own page a gate
   refusal is never handed off again: `SessionGateFilter` marks the request (`GatePageRequest`), a
   page section falls back, and the error handler shows a direct refusal instead of redirecting to
   the page the browser is on.
4. **Every gate refusal is marked, and the frontend tells it by the mark.**
   `SessionGateAccessFilter` adds the problem property `sessionGate: true` to every gate's `403`;
   the body only grows. The frontend kernel parses a marked refusal into a
   `SessionGateRefusalException` and never compares a gate's code: `BackendErrorMapper` logs it at
   `DEBUG` and does not count it as a failed call, and `SectionFallback` lets it through like a lost
   session, also out of a joined parallel read, while every other section failure still falls back.
5. **A gate's backend refusal evicts the frontend's cached verdict.** When the backend answers a
   request with a gate's `403`, directly or inside a page section, the frontend discards that gate's
   verdict and hands the browser to the gate's page: a redirect for a navigation, a `403` with the
   gate's header for an AJAX caller. The gate is the one in `SessionGates` whose code the refusal
   carries. A gate's own page is the exception of decision 3.
6. **The browser half stays a list of its own.** `krt-fetch.js` and `notifications.js` keep the
   gates' headers and events as constants, and `SessionGateClientParityTest` pins them against the
   gate beans of the running context.

Paths, AJAX headers, SSE events, the WebSocket hand-off, the session attributes and the metric
labels stay as they were for both gates.

## Consequences

- A further gate is one bean per side plus its header and event in the two scripts, which the
  parity test enforces.
- The kernel and the platform name no domain gate; `KernelDomainReachTest` lost one frozen reach,
  and `KernelGateCodeReachTest` fails the build when a kernel class file carries a gate's code.
- A member a patch change asks is sent to the home-location page by the first refused request,
  a page section's read included, instead of after the cache expires.

## Alternatives considered

- **Keep two filters and share a helper.** It removes the duplicated code but not the gate list
  spread over the kernel, nor the domain types in it.
- **One header and one SSE event for every gate.** Simpler for the browser, but it changes the
  wire the deployed pages and the extractor already rely on.
- **Render the gate list into every page.** It would drive the scripts from the server, but pages
  outside the layout would lose the gates; a parity test pins the same contract without that risk.
- **Read the gates' codes from `SessionGates` in the error mapper.** The gates read their status
  through the backend client that owns the mapper, so the beans would depend on each other.
- **A shared problem-type prefix for the gates.** It changes the `type` existing clients already
  see; a new property leaves every field they read as it is.
- **Evict the cached verdicts from the trigger.** The frontend's sessions live in Redis, keyed by
  session, not by member; reaching them from the backend's commit would need a new channel.
