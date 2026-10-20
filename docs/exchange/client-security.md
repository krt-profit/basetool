# Client security requirements

Every approved client meets these requirements (REQ-XCH-027). They are checked when a client applies
(see [onboarding](onboarding.md)) and again for every release that changes how it signs in or stores
credentials.

## Sign-in

How each step works is on the [authentication](authentication.md) page.

- Use the OAuth 2.0 device authorization grant (RFC 8628) against the pinned production issuer
  `https://profit-base.online/auth/realms/iri`, with your registered public client id and no client
  secret.
- Show the `user_code` and the bare `verification_uri`, and let the member type the code in their
  browser. Never open, show or send `verification_uri_complete`, not even as a QR code: it skips the
  page where the Basetool warns about device-code phishing.
- Bind every token to a DPoP key (RFC 9449, ES256): prove possession at the token endpoint and on
  every API call, and retry once with the server's `DPoP-Nonce`.
- Pin the production issuer. Another issuer — the local sandbox — may be selected **only** through a
  developer environment variable, never in the user interface: a user-facing issuer switch would be
  a phishing lever.
- Request `exchange.connect` and only the scopes of the features the member enabled.
- Label each installation (`POST /exchange/v1/me/installation`) so the member can tell two devices
  apart and disconnect one of them. The label is at most 40 characters of letters, digits, spaces,
  the ASCII hyphen-minus `-`, `_` and `.`, not starting with a space — no en dash, no other
  punctuation — and it is never an automatic host or computer name.

## Credential storage

- Keep the refresh token and the DPoP private key only in the platform's secret store: Windows
  Credential Manager or DPAPI, with the key non-exportable in CNG where available; on Linux the
  Secret Service, or — only where it is unavailable — a file readable by the user alone (`0600`),
  in a directory only the user can open (`0700`), with a visible hint that it is used.
- Never write a token or a key into a log, a backup, a diagnostics bundle, a problem report or any
  channel that leaves the device. Redact `Authorization` and `DPoP` headers and anything shaped like
  a JWT from everything you collect.
- "Disconnect" revokes the refresh token (RFC 7009) and deletes the stored credential.

## Data

- Send only the signed-in member's own data, and never purchase data, account ids or other members'
  data. The RSI handle goes only to the account check.
- An `externalId` never carries a purchase identifier such as an RSI pledge id; derive it, for
  example as a hash ([ships](resources/ships.md#links)).
- Store the org demand for at most 7 days, outside cloud-synced folders, never in backups or
  diagnostics, and never forward it.
- Send a descriptive `User-Agent`: product, version and a URL, for example
  `ExampleClient/1.2.0 (+https://example.org/client)`. A client that cannot set its `User-Agent`,
  such as a Chromium extension, sends the same value in `Exchange-Client-Agent`
  ([versioning](versioning.md#minimum-client-version)).

## Sync behaviour

The [sync guide](sync-guide.md) explains each rule; these are the ones an application is checked
against:

- Every resource is an explicit opt-in of the member and off by default.
- Pull before push; the first sync of a resource is add-only; a `remove` is sent only from a diff
  against the last synced state.
- Never re-add what the member removed elsewhere: a `REMOVED_ELSEWHERE` is shown to the member, and
  `override` is sent only after the member agreed.
- Ships are linked to the member's existing ships before any new one is created.
- After `410 CURSOR_EXPIRED`, reconcile a fresh snapshot against the last synced state; it is not an
  add-only first sync.
- Stock is sent as lots with the `expectedQuantity` last seen; a `VERSION_CONFLICT` is resolved by
  pulling, never by resending blindly.
- Before the first sync of a newly detected game account, run the account check and warn the member
  on `mismatch`.
- The baseline, the ship links and the feed cursors are kept per installation, keyed by its
  `installationId`, and never shared with another installation — not through a shared data folder
  either ([several installations](sync-guide.md#several-installations)).
- Every `Idempotency-Key` is random, such as a UUID, and used for one logical write only.
- When a change result reports `detachedFromMissions`, `offersReduced` or `offersRemoved` above 0,
  the member is shown what happened ([ships](resources/ships.md), [stock](resources/stock.md)).
- Every ship `upsert` sends the ship's current `name` and `location` — the server's, or the member's
  change to them — because an `upsert` clears an omitted one ([ships](resources/ships.md)).
- Retries back off from 5 seconds, doubling up to at most 5 minutes, with random jitter, and never
  wait less than the answer's `Retry-After`
  ([back-off](sync-guide.md#back-off-and-sync-cadence)).
- Syncs run on start and after a local change, and a timed sync at most every 5 minutes
  ([sync cadence](sync-guide.md#back-off-and-sync-cadence)).

## Browser extensions

Extensions for Chrome, Edge and Firefox on Manifest V3 can be approved (ADR-0303). Everything above
applies; these points replace or add to it.

- **Credential storage.** Generate the DPoP key with WebCrypto as a non-extractable ES256 key
  (`extractable: false`) and keep the `CryptoKey` in the extension's IndexedDB. Keep the refresh
  token in `storage.local`, **never** in `storage.sync`, which copies it to every browser the member
  signs in to. For an extension, this is the platform's secret store.
- Use tokens and the key only in the service worker or an extension page. A content script on the
  RSI pages reads the hangar and hands the data over; it never receives a token or the key.
- **Minimal host permissions.** `host_permissions`, `optional_host_permissions` and the
  content-script `matches` name only the RSI pages you read and `https://ingest.profit-base.online/*`.
  Nothing on `profit-base.online` and no wildcard that covers it: the Basetool web UI and its
  sign-in share that origin, and a permission there would let the extension act in the member's
  web session. No `<all_urls>`, no `cookies`, `webRequest` or `debugger` permission, and no Basetool
  origin in `externally_connectable`. The exchange host is a separate origin and reads no cookie.
- Reach the sign-in endpoints — device authorization, token and revocation — through CORS, without
  a host permission. Their answers allow your extension's origin once the client is registered.
- The manifest is checked when you apply, and again for every release that changes its permissions.
- **Disconnect** revokes the refresh token (RFC 7009) and deletes the token and the key. No browser
  runs extension code on uninstall; it deletes `storage.local` and IndexedDB with the extension,
  and the member ends the grant under *Connected applications* in the Basetool. Where a browser lets
  you act on uninstall, revoke there too. An uninstall URL never carries a token or an id.
- Chromium ignores a `User-Agent` set by an extension: send `Exchange-Client-Agent` with the same
  value instead. Send it to the exchange host only; the sign-in endpoints do not allow the header.
- **A fixed extension id.** For Chrome and Edge, the gateway and the sign-in admit the
  `chrome-extension://<id>` origin of each build you name in the application. Any fixed id works:
  one from a `key` in `manifest.json`, for an unpacked or self-distributed build, or one a store
  assigns — the Chrome Web Store and Edge Add-ons each assign their own, so list both if you use
  both. A store listing is not required, and neither is publishing the extension at all.
- Firefox extensions are admitted whatever their per-installation origin. Firefox's release
  builds install only Mozilla-signed add-ons permanently; AMO's unlisted self-distribution signs one
  without a public listing. Temporary add-ons are fine for development and the sandbox.
- The gateway never accepts credentials: send `fetch` with the default `credentials` or `"omit"`,
  never `"include"`.

## Response and supply chain

- Publish a privacy statement and a security contact.
- Publish a fixed release within **7 days** of a reported token-handling flaw; otherwise the client
  is suspended or restricted by a minimum version.
- Code signing of releases is recommended, not required.
