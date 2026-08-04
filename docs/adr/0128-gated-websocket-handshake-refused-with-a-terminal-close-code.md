# ADR-0128 — A consent-gated WebSocket handshake completes and is refused with a terminal close code

- **Status:** Accepted
- **Date:** 2026-08-04
- **Related:** spec `REQ-SEC-028` ([`security-and-access.md`](../specs/security-and-access.md)) ·
  spec `REQ-FE-015` ([`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md)) ·
  ADR-0127 (the consent gate this refuses on behalf of) · ADR-0094 (the multiplexed `/ws/sync`
  relay) · `REQ-SEC-029` (why the detection is not a path test) · ADR-0047 (the acyclic-package
  shape `support.TermsGateHandoff` copies)

## Context

`TermsAcceptanceGateFilter` answers a user without recorded consent in the caller's own idiom: a
browser navigation gets `302`, an XHR gets `403` plus `X-Terms-Acceptance-Required`, an
`EventSource` gets a one-shot `terms-gate` event on the channel. The `/ws/sync` handshake got the
`302`, and that is the one answer a WebSocket cannot use.

**A refused upgrade carries no information.** Whatever status a handshake is answered with, the
browser's `WebSocket` object surfaces the failure as `close` with code `1006` and an empty reason —
the same event a dropped TCP connection produces. `krt-live-sync.js` therefore does the only correct
thing for a dropped connection and reconnects, on full-jitter backoff capped at 30 s, for as long as
any topic is registered. Consent cannot be given from a background socket, so the retry can only
repeat the refusal: the loop has no exit and no attempt cap, and one open tab sustains it.

This is the same class of defect as the SSE stream's (`REQ-SEC-028`), but the fix there does not
transfer: that one answers *on the channel*, and at handshake time no channel exists yet.

The measured 2026-08-03 incident traffic does **not** include this loop — 491 stream attempts plus
483 consent-page renders against 973 observed backend `/api/v1/terms/status` reads closes to within
one request, which positively excludes a second contributor. This is latent hardening.

## Decision

### 1. The handshake completes; the socket is refused

The gate lets a marked upgrade through and `LiveSyncWebSocketHandler.afterConnectionEstablished`
closes it immediately with **`4003`**, the consent-page URL riding the close reason. That is the
first moment at which a close code exists at all, and a close code is the only thing on this
transport a client can distinguish from a network fault.

The alternative — keeping the handshake gated and inventing a terminal contract around the refusal
— was rejected because there is nothing to hang it on. The client would have to treat some `1006`
as terminal, and `1006` is exactly what a real outage produces; a client that stops reconnecting on
it loses live sync permanently on the first flaky network. The information simply is not on the
wire, and no convention can put it there.

`4003` mirrors HTTP `403` the way the existing socket-cap code `4029` mirrors `429` — and
specifically the `403` the gate already answers an XHR with, since this is the same refusal in a
different idiom. The two codes mean opposites (`4029` transient and probing, `4003` terminal and
navigating), so `LiveSyncCloseCodeWireParityTest` pins both against `krt-live-sync.js`: an
unrecognised code falls through to the generic reconnect path, which is the defect, silently.

### 2. Marked, not exempted

`isExempt()` would have been one line, and it was rejected. Exempting means the gate never runs for
the handshake, so the relay would have to reach its own verdict — a second consent read per
handshake, plus a second copy of the `test`-profile and authentication carve-outs to keep in step
with the first. Marking keeps exactly one owner of the verdict, reuses its 60 s-bounded cache, and
leaves the relay relaying a decision rather than making one.

### 3. Keyed on `Upgrade`, not on the path

The mark is applied when the request carries `Upgrade: websocket`, which is what actually identifies
the idiom the answer has to differ for. A path test would have to be a raw-URI string test —
`REQ-SEC-029` keeps this filter's matching raw deliberately, because there an encoded spelling fails
*closed* — and here the same rawness fails *open* into the redirect loop, since a `/%77s/sync` that
misses the mark is a socket nobody refuses. A browser cannot set custom headers on a handshake, so
this can never collide with the `X-Requested-With` branch.

### 4. The handoff lives in `support`

`config` already depends on `websocket` (the endpoint registration), so letting `websocket` read the
attribute name off the filter would close a package cycle. `support.TermsGateHandoff` is a leaf both
may depend on — the frontend's counterpart to the shape ADR-0047 forced on the backend's
`support.TermsConsentCheck` — and it keeps one owner for the attribute name. Two literals would
drift into a socket that is never refused, and nothing would log.

## Consequences

- A user without consent loses live sync on every open tab and is sent to the consent page from
  whichever tab notices first. That is the intended outcome: the gate's whole purpose is that the
  application stops working until consent is recorded.
- The refusal is counted as `basetool_livesync_socket_rejected_total{reason="terms_gate"}`,
  operationally distinct from the abuse-shaped `user_cap` on the same meter. The operations
  dashboard already breaks that meter down `by (reason)`, so the new series appears with no panel
  change.
- The refusal is checked before the per-user socket cap is acquired, so a tab reconnecting against a
  closed gate cannot exhaust its own budget and turn a consent prompt into a `4029` once the user
  accepts.
- **Tabs holding the pre-fix client are not repaired by deploying this.** They have no `4003`
  branch, so they keep reconnecting until they are reloaded. What ends immediately is the
  server-side half — the handshake stops being answered with a redirect, so the consent-page render
  and the backend status read each attempt used to drive disappear.
- A close reason is capped at 123 UTF-8 bytes. A context path long enough to overflow it drops the
  URL rather than the close: the tab stops looping but the user navigates themselves.

## Alternatives rejected

- **Exempt `/ws/sync` in `isExempt()` and let the socket live.** No loop, but also no prompt: a
  background tab would keep an open socket and never learn it must consent, and the relay would be
  fanning peer changes to a user whose every fragment fetch the backend refuses.
- **Keep gating the handshake, add a terminal contract on the failure.** Unimplementable — see
  decision 1. A rejected handshake yields `1006` with no code and no reason.
- **Cap the reconnect attempts instead.** Treats the symptom and breaks the cure: the same cap would
  stop a tab recovering from a genuine frontend redeploy or network outage, which is the case the
  unbounded backoff exists for.
- **Send a control frame before closing.** Would work, but adds a wire message and a
  send-then-close ordering dependency to say what the close code and reason already say.

