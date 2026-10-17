# ADR-0271 — CSRF and CORS are off on the backend's main chain, and a guard pins bearer-only

- **Status:** Accepted — decided ahead of the change; implemented in #2673. Supersedes
  [ADR-0144](0144-csrf-is-exempted-for-the-whole-bearer-only-api.md).
- **Date:** 2026-10-17
- **Deciders:** @greluc (issue #2638)
- **Related:** [ADR-0268](0268-the-backend-is-internal-only-and-ingest-is-the-single-public-api-entry-point.md) ·
  [ADR-0012](0012-frontend-krtfetch-json-mutations-csrf-retry.md) (the frontend's CSRF model, which
  this does not touch) · [ADR-0265](0265-the-management-ports-require-the-scrape-credential.md) ·
  [ADR-0214](0214-code-carries-no-comments-besides-javadoc.md) (where the reasoning goes instead of
  a suppression comment) · specs `REQ-SEC-022`, `REQ-SEC-037`, `REQ-SEC-044`, `REQ-SEC-052` ·
  `SecurityConfig`, `SecurityConfigCsrfExemptionTest`, `MonitoringScrapeSecurityConfig`

## Context

ADR-0144 exempted `/api/v1/**`, `/internal/**` and `/actuator/loggers/**` from cookie-based CSRF and
deliberately stopped there rather than disabling it, for two reasons it stated: scoping, so that
*"adding something browser-facing later does not arrive pre-exempted"*, and the CodeQL rule
`java/spring-disabled-csrf-protection`, which it did not want a reviewer to have to argue with.

Both reasons have worn through.

**The exemption now covers the whole surface.** What is left outside the three patterns is `/error`,
`/actuator/health(/**)`, `/v3/api-docs` (ADMIN) and the scrape path, which lives on its own chain.
None of them takes a write. A scoped exemption whose complement contains nothing writable is not
scoping; it is a disable a reader has to compute. `SecurityConfigCsrfExemptionTest` pins the
*list* — it cannot tell anyone whether the list still means anything.

**The precondition was prose.** ADR-0144's model is one sentence: *stateless, bearer-only*. It said
plainly that if either ever stopped being true the exemption would have to be revisited, and nothing
checks either. Its own blind-spot paragraph records the cost of that shape — the `test` profile
disables CSRF outright, so no integration test ever observed the production branch, and the only
end-to-end check of it was a nightly probe whose `403` was read as noise for two days.

**CORS was configured for a caller that is leaving.** `app.cors.allowed-origin-patterns` is empty by
default, so a cross-origin browser call is rejected today. But an empty allow-list is a *value*: one
environment variable sets an origin, and the mechanism exists because a non-browser client might one
day have needed it. After ADR-0268 there is no browser that can reach this backend at all — the
frontend calls it server-side over `net-backend-frontend`.

## Decision

**CSRF and CORS are disabled on the main chain, and the invariant that makes that safe becomes a
test.**

1. **CSRF off, unconditionally**, in every profile. The `test`-profile branch goes with it, which
   closes ADR-0144's blind spot by removing the thing it was blind to: there is one branch, so the
   tests exercise what production runs.
2. **CORS explicitly off** — `cors(AbstractHttpConfigurer::disable)`, with the
   `CorsConfigurationSource` bean and `app.cors.allowed-origin-patterns` removed. No preflight to
   answer and no property that could answer one.
3. **A guard test asserts the precondition instead of the exemption list.** The main chain accepts
   **nothing but a bearer JWT** and **creates no session**: no form login, no HTTP basic, no
   remember-me, no OAuth2 login, no session-based authentication, and
   `SessionCreationPolicy.STATELESS`. ADR-0144's sentence becomes the assertion, and it fails on the
   *cause* — an ambient credential — rather than on a path somebody forgot to add.
4. **The Prometheus scrape chain is untouched.** `@Order(1)`, `securityMatcher("/actuator/prometheus")`,
   HTTP basic against the scrape identity (ADR-0265), stateless, and CSRF at Spring's default. Basic
   auth is precisely the credential a browser can be made to attach, so nothing is relaxed there; it
   is read-only in effect and CSRF has no write to protect, which is why the default needs no change.
5. **The CodeQL alert is dismissed per alert, with a reason pointing here — the query is not
   excluded.** `codeql.yml`'s `query-filters` excludes repo-wide, and
   `java/spring-disabled-csrf-protection` must keep watching the **frontend**, whose CSRF model
   (ADR-0012) is real and load-bearing. Suppressing the query for the repository to quiet one
   intended call site would blind the one place that needs it. No suppression comment is written, per
   ADR-0214; the reasoning lives in this ADR, the commit and the PR.

`/internal/**` keeps its shared-secret gate (REQ-SEC-022), which is what actually protects it and
always was.

## Consequences

**The security model is now checked rather than described.** A browser-facing endpoint, a session, a
cookie credential or a form login on this backend fails a test that names why. ArchUnit already
forbids HTML here; this covers the half ArchUnit cannot see.

**We pay the cost ADR-0144 declined.** A dismissed code-scanning alert is a standing item that a
future maintainer meets before they meet this ADR. The dismissal comment carries the ADR number,
which is the cheapest link from the alert to the reasoning; it is weaker than a suppression comment
would be if comments were allowed, and stronger than the pattern list it replaces, because the
dismissal is one decision rather than a list that silently drifts.

**"Does not arrive pre-exempted" is lost as a property and regained as a gate.** Under ADR-0144 a new
browser-facing path would have been outside the exemption patterns and would have got CSRF by
default. Under this decision it gets no CSRF — and the guard test refuses the chain shape that would
make that matter. The protection moves from "the path is not on a list" to "the chain cannot hold an
ambient credential", which is the condition CSRF actually depends on.

**One filter and one bean fewer in front of every request**, and the CORS property leaves
`.env.example` and the deployment docs.

**If this backend ever has to serve a browser**, the guard test is the thing that will refuse — and
that is the moment to re-decide with a new ADR, which is what ADR-0144 wanted and could only ask for
in prose.

## Alternatives considered

*Keep the exemption list and add nothing.* The status quo, and its defect is that it reads as a
narrow exemption while being a total one. Someone reconstructing the posture has to enumerate the
chain's matchers to learn that the complement is `/error` and two reads.

*Keep CORS with its empty default.* It costs nothing to run and says nothing. An empty list is a
configured value that a later environment variable overrides; `cors.disable()` is a decision in the
code, where it is reviewed.

*Disable CSRF only under `prod` and keep it armed in tests.* An exact restatement of ADR-0144's blind
spot: the branch the tests never see is the branch production runs. The inverse — arming CSRF in
tests to prove the production branch — would mean every MockMvc write fetching a token to satisfy a
control the chain has no credential for.

*Keep `SecurityConfigCsrfExemptionTest` beside the new guard.* It would pin a constant that no longer
exists. The guard supersedes it: the list was a proxy for the invariant, and the invariant is now
asserted directly.
