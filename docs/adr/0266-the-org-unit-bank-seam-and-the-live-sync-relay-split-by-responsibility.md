# ADR-0266 — The org-unit bank seam and the live-sync relay split by responsibility

- **Status:** Accepted
- **Date:** 2026-10-17
- **Deciders:** @greluc
- **Related:** spec [`bank.md`](../specs/bank.md) (REQ-BANK-008, REQ-BANK-021, REQ-BANK-027) ·
  [`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md) (REQ-FE-015) ·
  ADR-0020 · ADR-0065 (amended, bank half) · ADR-0094 · ADR-0126 · issue #2662

## Context

Two classes on security- and consistency-relevant paths had grown past a size a reviewer can hold
in view: `OrgUnitBankAccessService` (1,820 lines) and `LiveSyncWebSocketHandler` (1,706 lines).
Every edit to the bank access rules or to the live-sync relay touched one file that several
concerns shared.

ADR-0065 kept the org-unit bank flows inside the seam because the containment pin
`orgUnitAwareBankSeamIsContainedToOneClass` allows exactly one class to depend on both
`OwnerScopeService` and the bank. That pin constrains where the *decisions* live, not where the
flows that use them live: a flow that asks the seam for every verdict and never reads the scope
itself is not a second bridge.

## Decision

### The org-unit bank seam keeps every decision; two flow services sit behind it

- **`OrgUnitBankAccessService`** stays the one bridge. It owns every org-unit bank decision —
  `canView`, `isResponsibleHolder`, `isApprovalExempt`, `canSetTarget`, `canConfigureVisibility`,
  `canConfigureApprovalLimits`, the `requireCan*` asserts, `resolveApplicableLimit`,
  `hasBankManagement`, `callerIsOlMember`, the area cascade — and the read side of the page: the
  account cards, the read-only drill-in, the bookings, the balance series and the redacted
  statement.
- **`OrgUnitBankSettingsService`** serves the responsibility settings: balance target, view grants,
  approval limits and the settings snapshot. It validates the role buckets and delegates the
  persistence and audit to `OrgUnitBankVisibilityService` and `OrgUnitBankApprovalLimitService`.
- **`OrgUnitBankRequestService`** serves the booking requests: create, purpose-bound and KRT direct
  requests, the approval routing, the requester's own list, edit and cancel, the approver's queue
  and the approval attestation. Persistence and audit stay in `BankBookingRequestService`.

Neither flow service depends on `OwnerScopeService`; both are registered on the org-unit side
(`ORG_UNIT_BANK_SIDE`), so the seam pins of ADR-0020 still select exactly one bridge, and the
settings-mutation pin (`orgUnitBankSettingsMutationsMustCallAnAuthorizationHelper`, floor 15) now
selects the mutations of `OrgUnitBankSettingsService`. Method bodies, transaction attributes, audit
events and the REST surface are unchanged.

### The live-sync relay splits along session lifecycle, subscription and relay

`LiveSyncWebSocketHandler` stays the Spring bean and the one entry point; it builds four
package-private collaborators and keeps the frame dispatch, the `changed` and presence relay paths,
the fan-out entry points and the reaper:

- **`LiveSyncSessionRegistry`** — admission at connect (Terms-of-Use gate, principal, per-user cap),
  the backpressure decorator, the keepalive sweep, the socket-lifetime timer and the session
  attributes they leave behind.
- **`LiveSyncSubscriptions`** — the `subscribe` flow: topic validation, throttle and topic cap, the
  asynchronous authorization probe and the `subscribed` / `denied` control frames.
- **`LiveSyncRooms`** — the room registry, the `changed` and `presence` frames written to it, the
  room gauges and the relay counters.
- **`LiveSyncRateLimiter`** and **`LiveSyncSections`** — the token buckets and the section
  whitelist filter.

The wire constants the handshake interceptor and the close-code parity tests read (`ATTR_*`,
`SOCKET_CAP_EXCEEDED`, `TOKEN_EXPIRED`, `TERMS_CONSENT_REQUIRED_CODE`, `MAX_SOCKETS_PER_USER`) stay
on the handler. The collaborators log under the handler's logger name, so every live-sync log line
keeps its logger and raising `LiveSyncWebSocketHandler` to DEBUG still shows all of them, as the
`LiveSyncSectionKeySkew` alert tells the operator to do. Metric names, tags and descriptions are
unchanged.

## Consequences

- `OrgUnitBankAccessService` 1,820 → 826 lines, with `OrgUnitBankSettingsService` (586) and
  `OrgUnitBankRequestService` (516) beside it; `LiveSyncWebSocketHandler` 1,706 → 662 lines, with
  five collaborators of 124–358 lines.
- No behaviour change: the existing tests pass unchanged in their assertions. The org-unit bank unit
  test builds the seam and both flow services on the same mocks; the controller test mocks all
  three.
- The seam's decisions are now public methods of a module-internal class, because the flow services
  call them through the Spring proxy; that is their only new reach.
- A new org-unit bank flow goes into the flow service of its kind and asks the seam for its
  verdicts; a new decision goes into the seam.

## Alternatives considered

- **Move the decisions into a separate policy class and keep the flows in the seam** — rejected:
  the policy would become the bridge and every seam pin, ADR-0020 and the bank spec would have to
  name a new class for no gain.
- **Keep the seam as a delegating facade over the new services** — rejected: the facade would keep
  every public signature and its Javadoc, so the class would barely shrink and every change would
  still touch it.
- **A size ratchet (Checkstyle `FileLength` / `MethodLength`) in the same change** — left for a
  follow-up: it touches the shared Checkstyle configuration of every module and needs its own
  ceilings.
