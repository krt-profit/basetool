# ADR-0304 — Extension clients are bound to their origin and reviewed on every manifest change

- **Status:** Accepted
- **Date:** 2026-10-20
- **Deciders:** @greluc
- **Related:** REQ-XCH-027 · amends
  [ADR-0303](0303-browser-extensions-are-admissible-exchange-clients-under-conditions.md) · spec
  [`external-exchange.md`](../specs/external-exchange.md) · developer pages
  [`client-security.md`](../exchange/client-security.md), [`onboarding.md`](../exchange/onboarding.md)

## Context

ADR-0303 admits browser extensions. Its gateway CORS allows a list of origins, but nothing ties an
origin to the client that may use it: the gateway answers an approved Chromium origin carrying any
registered client's token, and `moz-extension://*` with any client's token. A malicious extension
holding a stolen token of another client, or a Firefox extension acting for a desktop client, would
pass.

An extension lives in the browser, next to every page the member opens, so a token it holds is more
exposed than one in a desktop keychain. The exchange's offline sessions idle out after 30 days, the
same for every client.

An extension's risk lies largely in its manifest: the host permissions, content-script matches and
`permissions` decide what it can read and where it can send. Approval checks the manifest once, but a
later release can widen it.

## Decision

1. **The origin is bound to the client.** The gateway's configuration names each approved Chromium
   origin together with its client (`app.exchange.cors.extension-origins`, entries
   `<clientId>=chrome-extension://<id>`) and the clients with a Firefox build
   (`app.exchange.cors.firefox-clients`). On `/exchange/**` the registry gate refuses with
   `403 ORIGIN_NOT_ALLOWED` a request whose `Origin` is a `chrome-extension://` origin bound to
   another client than the token's `azp`, a `moz-extension://` origin for a client without a Firefox
   build, or any other browser origin. A request without `Origin` — a desktop client — is
   unchanged. `moz-extension://*` is in the CORS allowlist only while one client has a Firefox build.
   The refusal is counted like every gate refusal, as `origin_not_allowed` under the client's label.
2. **Extension clients idle out after 14 days.** The provisioner sets
   `client.offline.session.idle.timeout` and `client.session.idle.timeout` to 14 days for a client
   with a browser-extension build (`extensionOrigins` or `firefoxExtension` in
   `external-clients.json`); desktop clients keep 30 days, and the 90-day maximum stays for both. An
   extension unused for 14 days asks the member to sign in again.
3. **A manifest change is an admission-relevant change.** A release whose `manifest.json` changes
   the host permissions, optional host permissions, content-script matches or `permissions` is
   reviewed again before it may ship to members, and the applicant links the release's manifest each
   time.

## Consequences

- A stolen token is useless in another client's extension, and a Firefox extension cannot act for a
  desktop client. The binding is configuration: approving an extension build now also names its
  client in the gateway's `.env` (`docs/deployment.md`).
- The gate's refusal is visible to the extension, since the answer carries the CORS headers.
- An extension member who pauses for two weeks signs in again; a desktop client's member does not.
- Each permission-widening release waits for a review. A release that changes nothing else in the
  manifest ships as before.

## Alternatives considered

- **Keep the binding in the client registry.** The registry is the runtime home of a client, but it
  carries no origins, and adding them means a migration, the admin page and the mirror format, for a
  value that changes only at approval. The gateway configuration already holds the origins; binding
  them there keeps one place.
- **Bind only Chromium origins.** Leaves every client usable from any Firefox extension; rejected.
- **The same 30 days for every client.** Simpler, but ignores that an extension's token lives next
  to the member's open pages.
