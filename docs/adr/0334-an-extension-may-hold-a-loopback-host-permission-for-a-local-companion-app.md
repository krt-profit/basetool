# ADR-0334 — An extension may hold a loopback host permission for a local companion app

- **Status:** Accepted
- **Date:** 2026-11-05
- **Deciders:** @greluc
- **Related:** REQ-XCH-027 · amends
  [ADR-0303](0303-browser-extensions-are-admissible-exchange-clients-under-conditions.md) · spec
  [`external-exchange.md`](../specs/external-exchange.md) · developer pages
  [`client-security.md`](../exchange/client-security.md),
  [`authentication.md`](../exchange/authentication.md) · application
  [#2910](https://github.com/krt-profit/basetool/issues/2910)

## Context

ADR-0303 condition 2 lets an extension's host permissions, optional host permissions and
content-script matches name only the RSI pages it reads and `https://ingest.profit-base.online/*`.
An extension can have more than one target: Hangar-Exporter sends the ship list it read from the
RSI pages, on the member's choice, to a file, to the Basetool, or to VerseKit running on the
member's own computer, which it reaches on `http://127.0.0.1/*`. The strict rule forces such an
extension to ship a separate package without that target.

The reason for the strict rule is the Basetool's own origin: a permission on `profit-base.online`
would let the extension ride the member's web and SSO sessions (ADR-0166). A loopback origin is
none of the Basetool's origins and carries no Basetool session.

Separately, the developer pages tell a client to measure each server's clock offset from the `Date`
header and correct the DPoP `iat`. `Date` is not a CORS-safelisted response header, and the
gateway did not expose it, so an extension could not read it.

## Decision

1. **A loopback host permission is admissible** in `host_permissions` or
   `optional_host_permissions`: `http://127.0.0.1/*`, `http://localhost/*` and `http://[::1]/*`,
   which match every port. It serves a companion application on the member's own computer only, and
   the extension uses it only on the member's explicit choice of that target.
2. **Nothing of the Basetool's crosses it.** No token, no DPoP key and no data read from the
   Basetool is sent to a loopback origin; only what the extension read itself, such as the RSI
   hangar.
3. **No content script on loopback.** Content-script `matches` keep naming only the RSI pages.
4. The permission is named in the application with the reason, and a release that adds or changes it
   is a manifest change reviewed again (ADR-0304).
5. **The gateway exposes `Date`** on `/exchange/**` to the approved extension origins, beside the
   headers ADR-0303 lists, so an extension can measure its clock offset. Keycloak runs on the same
   host clock, so the gateway's offset serves its token endpoint as well.

## Consequences

- One extension package can serve the Basetool and a local companion app; Hangar-Exporter is
  approved with its 0.5.0 manifest.
- A loopback permission lets the extension reach any service listening on the member's computer.
  That is a local risk the member's own extension choice already carries; it opens no Basetool
  session, and condition 2 keeps every Basetool credential and Basetool data away from it.
- The review checks the loopback use against the published source of the release.

## Alternatives considered

- **A separate package without the loopback permission.** Keeps the rule strict but makes the
  developer maintain two builds that differ in one permission, with no gain for the Basetool's
  sessions; rejected.
- **Allow any additional host the applicant names.** Opens the door to remote hosts that could
  receive what the extension reads; rejected in favour of loopback only.
