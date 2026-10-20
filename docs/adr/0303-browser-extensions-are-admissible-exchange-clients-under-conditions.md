# ADR-0303 — Browser extensions are admissible exchange clients under conditions

- **Status:** Accepted
- **Date:** 2026-10-20
- **Deciders:** @greluc
- **Related:** REQ-XCH-024 · REQ-XCH-027 · REQ-INGEST-001 · spec
  [`external-exchange.md`](../specs/external-exchange.md) ·
  [ADR-0217](0217-third-party-clients-are-public-device-grant-clients-in-a-db-registry.md) ·
  [ADR-0166](0166-identity-moves-onto-the-app-origin.md) · [ADR-0268](0268-the-backend-is-internal-only-and-ingest-is-the-single-public-api-entry-point.md) · developer page
  [`client-security.md`](../exchange/client-security.md)

## Context

The exchange API was written for desktop clients. Its client security requirements name the
Windows Credential Manager, DPAPI and the Linux Secret Service as the only places a token may live,
and the ingest gateway answers every cross-origin request with an empty CORS allowlist. A browser
extension therefore cannot be approved and could not talk to the gateway if it were.

A third-party developer builds a GPL-3.0 Manifest V3 extension that reads the member's RSI hangar
and syncs the ships through the exchange. An extension is the natural shape for that: the hangar is
a web page the member is already signed in to.

An extension differs from a desktop client in four ways that matter here:

1. **Its secret store is the browser's.** There is no OS keychain API. What an extension has is
   WebCrypto, whose keys can be created non-extractable and kept in the extension's IndexedDB, and
   `storage.local`, which lives in the browser profile and is deleted with the extension.
2. **It can be granted access to web origins.** A host permission lets it read pages and send
   requests with the member's cookies. On `profit-base.online` that would let it ride the member's
   web session, because the web UI and Keycloak share that origin (ADR-0166). The exchange gateway
   is a different origin, `ingest.profit-base.online` (ADR-0268): its CORS answer and a host
   permission for it give no access to the web UI's host-only `__Host-SESSION` cookie or to
   Keycloak's cookies, and the gateway itself is stateless and reads no cookie.
3. **Its origin can be fixed in Chromium and is random per installation in Firefox.**
   Chrome and Edge use `chrome-extension://<32-letter id>`. The id is fixed by a `key` in the
   manifest, whether the build is unpacked or self-distributed, or by a store listing, and the
   Chrome Web Store and Edge Add-ons each assign their own. Firefox uses `moz-extension://<uuid>`, a
   new UUID for each installation; its release builds install only Mozilla-signed add-ons
   permanently, which AMO's unlisted self-distribution provides without a public listing.
   Firefox sends that origin on its fetches (Mozilla bugs 1405971 and 1507814, both open), and a
   host permission does not reliably drop it. Keycloak matches a client's Web Origins exactly or
   through `*`, nothing in between (`DefaultCors.isOriginAllowed`), and echoes the request origin
   on a match; since 26.6.3 (CVE-2026-37977, our pin 26.8.1) it refuses a request whose `Origin` is
   not allowed with `403 Invalid origin` before any endpoint logic. `moz-extension://*` is therefore
   impossible in Keycloak.
4. **Chromium does not let an extension set `User-Agent`.** The minimum-version gate
   (REQ-XCH-024) reads the version from that header, so a Chromium extension could not pass it once
   a minimum is set.

## Decision

**Browser extensions for Chrome, Edge and Firefox on Manifest V3 are admissible exchange clients,**
approved and registered like any other public device-grant client (ADR-0217), under these
conditions (REQ-XCH-027):

1. **Credential storage.** The DPoP key is an ES256 WebCrypto key generated non-extractable and
   kept in the extension's IndexedDB; the refresh token is kept in `storage.local`, never in
   `storage.sync`. For an extension this is the platform secret store. Tokens and the key are used
   only in the service worker or an extension page, never in a content script and never passed to
   one.
2. **Minimal host permissions.** The manifest's host permissions, optional host permissions and
   content-script matches name only the RSI pages the extension reads and the exchange host
   `https://ingest.profit-base.online/*` — nothing on `profit-base.online` or a wildcard covering
   it, no `<all_urls>`, no `cookies`, `webRequest` or `debugger` permission, and no Basetool origin
   in `externally_connectable`. Keycloak's device, token and revocation endpoints are reached
   through CORS, not through a host permission. The manifest is checked at application time and
   for every release that changes its permissions.
3. **Disconnect and uninstall.** "Disconnect" revokes the refresh token (RFC 7009) and deletes the
   token and the key. No browser runs extension code on uninstall; the browser deletes
   `storage.local` and IndexedDB with the extension, and the member revokes the server-side grant
   under „Verbundene Anwendungen". Where a browser API does let the extension act on uninstall, it
   revokes the token there. An uninstall URL never carries a token or an id.
4. **Version header.** A client that cannot set `User-Agent` sends the same
   `<Product>/<semver> (+url)` value in `Exchange-Client-Agent`; the gateway's version gate reads
   that header when it holds text and `User-Agent` otherwise. It is sent to the exchange host only.
5. **Gateway CORS.** On `/exchange/**` only, the gateway admits the configured fixed
   `chrome-extension://<id>` origin of each approved Chromium build
   (`app.exchange.cors.extension-origins`) and `moz-extension://*`
   (`app.exchange.cors.firefox-extensions`, on by default), for `GET`, `POST` and `OPTIONS`, with
   the exchange's request headers, `allowCredentials=false`, and `DPoP-Nonce`, `WWW-Authenticate`,
   `RateLimit`, `RateLimit-Policy`, `Retry-After`, `Idempotency-Replayed` and `X-Correlation-Id`
   exposed. Every other path keeps the empty allowlist.
6. **Keycloak Web Origins.** An extension's Keycloak client lists the exact `chrome-extension://`
   origin of each approved Chromium build. A client with a Firefox build gets `*` — set **per
   client**, only on clients with a Firefox build, never realm-wide — because Keycloak allows no
   pattern and refuses an unlisted origin. `*` admits **every origin, every web page included**,
   not only Firefox extensions: for that client, any page can call the device authorization, token
   and revocation endpoints from a browser. The impact is bounded: the client is public and device
   grant only, with standard flow, direct grants and redirect URIs off; its tokens are DPoP-bound;
   none of those endpoints acts on cookies; and the device-flow verification page keeps its
   phishing warning and the code to compare. The remaining risk is that a phishing page can drive
   that client's device flow entirely in the victim's browser. It is accepted (arc42 §11.7a) and
   revisited when more clients with a Firefox build arrive or Keycloak changes its CORS behaviour
   again.
7. **The rest of REQ-XCH-027 applies unchanged**, including the 7-day fix rule for a token-handling
   flaw ("publish a fixed release within 7 days"). Store-account two-factor authentication and
   reproducible builds are **not** required.
8. **No store publication and no signing requirement.** The owner requires only a stable extension
   id. Where it comes from — a store listing, an unlisted listing, or a `key` in the manifest of an
   unpacked or self-distributed build — does not matter, and nothing has to be published. Code
   signing stays recommended, not required. Temporary add-ons are fine for development and the
   sandbox.

## Consequences

- An extension can be approved, and the gateway answers it cross-origin. A new Chromium build's
  fixed extension id is one more entry in `IRI_EXCHANGE_CORS_EXTENSION_ORIGINS` and in the client's Web
  Origins; a Firefox build needs neither.
- `moz-extension://*` admits every Firefox extension's preflight. That opens nothing on its own:
  the gateway never accepts credentials, every exchange request still needs a DPoP-bound token of
  a registered client, and the registry gate refuses the rest.
- A Firefox-capable client's `*` Web Origins means any web page can start a device login for that
  client id and poll it from the visitor's browser. A server could always start one; what is new
  is that a phishing page can run the whole flow in the victim's browser. The member still has to
  type the code on the warning page and consent.
- The minimum-version gate works for extensions, at the price of one cooperative header beside
  `User-Agent`. It stays cooperative: a client that lies passes either way.
- A refresh token outlives an uninstalled extension until the member disconnects it or the offline
  session expires. The connected-apps page is where the member ends it.

## Alternatives considered

- **Keep extensions out.** Simplest, but the hangar is a browser page, and a desktop client reading
  it would need the member's RSI session copied out of the browser — worse than an extension.
- **Host permission for Keycloak instead of CORS.** It would avoid `*` Web Origins for Firefox, but
  Keycloak shares `profit-base.online` with the web UI, so the same permission would let the
  extension ride the member's web and SSO sessions. Refused.
- **Relaying the sign-in endpoints through the ingest gateway.** Device authorization, token and
  revocation would go to the gateway, which checks `moz-extension://*` and forwards to Keycloak,
  keeping `*` out of Keycloak. Rejected: the DPoP proof's `htu` would have to name Keycloak's URL
  while the request goes to the gateway, a deviation from RFC 9449; the pinned issuer and its
  discovery documents would break or need a special case; refresh and access tokens would pass
  through the internet-facing gateway; Keycloak would see only the gateway's address, weakening
  its brute-force protection and events; sign-in would depend on the gateway being up; and it is a
  large effort for a bounded gain. Revisited under the same triggers as the `*` itself.
- **One wildcard such as `chrome-extension://*` on the gateway.** Simpler to operate, but it would
  admit any Chromium extension's preflight while a fixed id is available; per-build ids are kept.
- **Store-account 2FA and reproducible builds as criteria.** Considered and dropped by the owner:
  neither is checkable by the Basetool, and the 7-day fix rule and suspension already cover a
  compromised release.
- **A store listing as the source of the extension id.** Dropped by the owner: a `key` in the
  manifest fixes the id just as well, and publication is not a condition.
