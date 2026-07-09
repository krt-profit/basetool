# ADR-0012 — Standardize frontend mutations on krtFetch + JSON with session/meta CSRF + retry-on-403

- **Status:** Accepted
- **Date:** 2026-06-13
- **Deciders:** @greluc
- **Related:** spec REQ-FE-001..005 ([`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md)) · epic #571 · phase #572 · security spec REQ-SEC-* ([`security-and-access.md`](../specs/security-and-access.md)) · UI spec ([`ui-design-system.md`](../specs/ui-design-system.md))

## Context

The `frontend` module is a Thymeleaf server-rendered UI that mutates data two ways today: ~95 plain
`POST → redirect → GET` forms and ~90 "AJAX then `window.location.reload()`" handlers. Both reload
the whole document on every edit — re-downloading the HTML, re-running every `<head>` script,
re-rendering the full page, and losing scroll/focus. That is a UX and performance cost paid on a
single dropdown toggle.

Three forces make a cross-cutting decision necessary rather than an incremental clean-up:

- **CSRF duplication.** The CSRF-header read is hand-rolled ~30 times across 5 JS files and 14
  templates in 6 syntactic variants, including a latent bug in `members.html` that hard-codes the
  header name `X-CSRF-TOKEN` (it only works because that happens to equal Spring's default header
  name; a config change would silently break it).
- **Stale-token 403s.** The meta-token approach 403s on a stale browser tab and after re-login,
  because `sessionFixation(changeSessionId)` rotates the session id and `maximumSessions(10)` can
  evict a session. A `CsrfFilter` 403 bypasses `GlobalExceptionHandler`, so it arrives as a bare
  403 with no `problem+json` body — every call site would need its own recovery.
- **CSP constraint.** The app ships a strict policy: `script-src 'nonce-…' 'strict-dynamic'`, no
  `'unsafe-inline'`, no `'unsafe-eval'`. Any solution that needs an inline-expression evaluator is
  off the table.

A reference implementation already exists and is battle-tested: `mission-subresource.js`
(`window.MissionSubresource`) does fine-grained optimistic-locking writes with RFC 7807 handling,
`syncVersion`, and KRT toasts/confirm. It is just mission-namespaced.

## Decision

We will standardize every frontend mutation on **one shared client module, `krtFetch` + `krtCsrf`
(`krt-fetch.js`), returning JSON**, generalized from `mission-subresource.js` and loaded globally
from `fragments/head.html`.

- **Writes** go through `krtFetch.write({method,url,payload,…})`: it builds CSRF headers from the
  freshest `_csrf` meta tags via `krtCsrf`, parses JSON / `application/problem+json`, branches RFC
  7807 409s (`OPTIMISTIC_LOCK`/`PESSIMISTIC_LOCK` → reload-confirm; domain codes → toast only),
  runs `syncVersion` to propagate the new `@Version` to every related `[data-version]`, and shows
  KRT toasts. Controllers expose JSON proxy endpoints that reuse the existing backend APIs/DTOs.
- **CSRF stays session-based.** We keep `HttpSessionCsrfTokenRepository` +
  `XorCsrfTokenRequestAttributeHandler` (Spring Security defaults). `krtCsrf` is the single reader;
  a new authenticated `GET /csrf` endpoint returns `{headerName, token}`, and on a bare 403
  `krtFetch` refetches it once, updates the meta tags, and retries the request exactly once. This
  is **not** a change to the CSRF repository or handler, so it is not a binding security-surface
  change requiring a security amendment — only the additive `/csrf` endpoint + retry path is new.
- **Lists / filters / pagination** use server-rendered **HTML fragment swaps** via `krtFetch.swap`,
  which also intercepts in-container pagination/sort anchors so paging stays in place (fixing the
  regression where pagination inside an AJAX results container triggered a full reload).
- **No new runtime dependency, no CSP change.** Plain `fetch` + DOM patching; all scripts stay
  nonce-gated.

Per-area conversion is tracked in the child issues of epic #571; Phase 0 (#572) lands the
foundation + exemplars and this ADR + the `REQ-FE-*` spec.

## Consequences

- One implementation of CSRF handling, optimistic-lock UX, and toasts replaces ~30 bespoke blocks;
  the `members.html` latent header bug is fixed by construction.
- Stale-tab / post-re-login 403s self-heal transparently, app-wide, via one retry path.
- Edits ship a small JSON payload and touch only changed nodes — scroll/focus preserved.
- **Cost:** `syncVersion` correctness becomes load-bearing — an incomplete `data-version`
  propagation 409s the next click (enforced by the spec's DoD + per-area "double-action" e2e).
  Patching DOM from server JSON adds JS-DOM-XSS surface (`js/xss-through-dom`), mitigated by
  `textContent` / `escape-html.js` and kept green in CodeQL.
- Progressive enhancement is retained during migration: the server-side `POST → redirect` handler
  stays as the no-JS fallback until the AJAX path is covered by e2e, so a regressed flow can fall
  back to the form path.

## Alternatives considered

- **htmx** — clean attribute-driven swaps, but a new runtime dependency, its own ADR, and a broad
  rewrite of controllers to return fragments; heavier than reusing the proven in-house helper.
  Rejected.
- **Cookie-based CSRF (`CookieCsrfTokenRepository`)** — would remove the meta-tag read, but it is a
  binding change to the security model and is weaker against cookie-injection than the
  session-stored token. Rejected (Decision 1 of epic #571).
- **App-wide Alpine.js** — its standard build evaluates expressions via `new Function()`, which
  requires `'unsafe-eval'` in `script-src` and would break the strict CSP. (Alpine was already an
  orphaned, inert dependency and was removed; see the cleanup preceding this epic.) Rejected.
- **Full SPA / client framework** — a disproportionate rewrite of a server-rendered app for a
  no-reload goal that DOM patching already achieves. Rejected.

