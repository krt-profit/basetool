# Changelog

Changes to the Exchange API contract, newest first. Every change within `v1` is additive
([versioning](versioning.md)).

## 2026-10-03

- **Corrected: a refinery draft carries at least one order.** `refinery-draft.schema.json` now
  requires one to five `orders`, as the [drafts](resources/drafts.md) page always said; the
  fixture with an empty list moves to `refinery-draft/invalid/no-orders.json`. An empty list never
  succeeded: it was answered `400 SCHEMA_INVALID` before and still is, so this is a correction, not
  a breaking change ([versioning](versioning.md)). The gateway now refuses it itself, with
  `errors[]` naming `/orders` and the detail of its other schema refusals, instead of relaying it
  to the Basetool.

## 2026-09-28

- **`LEGACY_ENDPOINT_GONE` is retired.** It answered the SC Extractor's old `/v1/*` routes, which
  were never part of the exchange and are now removed; the code stays in the registry so it is never
  reused ([errors](errors.md)). Nothing under `/exchange/v1` changes.
- **`EXCHANGE_BUDGET_EXHAUSTED` tells when to retry and costs no write.** Its `Retry-After` is now
  the seconds until enough of the full budget expires, at most 3600, instead of a fixed 60 that a
  full budget never kept; and the refused write no longer counts against the daily write quota, so
  a client that honours `Retry-After` keeps its writes ([errors](errors.md),
  [sync guide](sync-guide.md#rate-limits-quota-and-back-off)).
- **New code `RELAY_BUSY`, and large change sets are relayed at most four at a time.** A change set
  of more than 100 ops that arrives while the gateway already relays four such sets, over all
  clients, is answered `503 RELAY_BUSY` with `Retry-After: 10` and not relayed; retry it under the
  same key; the refused set costs no write of the daily quota. Sets of at most 100 ops are
  unaffected. The gateway also waits up to 30 s for the Basetool instead of 15 s, so a slow large
  set is answered rather than failed with
  `502 BACKEND_RELAY_FAILED` after it was written ([errors](errors.md),
  [sync guide](sync-guide.md#batches)).
- **A connection without `offline_access` ends after 90 days too.** Its online session now ends
  after 30 days without use and after 90 days at the latest, as an offline session always did,
  instead of after the browser session's 180 days. A client that requests `offline_access`, as it
  must, is unaffected; one that omits it signs in again at the latest after 90 days
  ([authentication](authentication.md)).
- **Clarified: one code per situation, whichever side refuses.** The Basetool behind the gateway
  checks the switch, the registry, the revocations and the capabilities of every request again.
  Right after a change, while the gateway's registry cache still admits a request, that second
  check used to refuse it as `403 NOT_PERMITTED`, or `502 BACKEND_RELAY_FAILED` when it could not
  read the revocations. It now answers with the code, status, `Retry-After` and `detail` the gateway
  gives the same situation: `CLIENT_SUSPENDED`, `CLIENT_NOT_ALLOWED`, `EXCHANGE_DISABLED`,
  `INSTALLATION_REVOKED`, `CLIENT_REVOKED`, `SCOPE_MISSING`, `REGISTRY_UNAVAILABLE`; the service
  document answers such a refusal as well ([errors](errors.md)). No code is new, and the contract
  always documented these codes for these situations.
- **The org demand is withheld with `reason: NOT_PERMITTED`.** A member who may not see their
  organisation's job orders in the Basetool — no unit of theirs takes part in the profit sharing —
  now gets `200` with two empty lists and `"reason": "NOT_PERMITTED"` instead of their units' demand;
  a member whose unit leaves the profit sharing stops seeing it at once
  ([org demand](resources/org-demand.md#whose-demand)). `org-demand.schema.json` gains the optional
  `reason`, absent whenever the demand is shown. A client that does not read it shows an empty
  demand, as it already did for a member of no unit; the sandbox shows both answers.
- **Every refusal carries a registered code.** A body that is not a JSON document is
  `400 SCHEMA_INVALID` with one error at the pointer `""` instead of `400 BAD_REQUEST`, and it is no
  longer cached for its `Idempotency-Key`, so the corrected request under the same key runs. A body
  sent without `Content-Type: application/json` gets the new code `415 UNSUPPORTED_MEDIA_TYPE`
  instead of a `415` without `code`. A method other than `GET`, `POST`, `HEAD` or `OPTIONS` on an
  exchange path is `404 NOT_FOUND` like any unknown route instead of a bare `405`, and a query
  parameter without a name is `400 SCHEMA_INVALID` at `/` instead of a bare `400`. The registry
  now also lists `500 INTERNAL_ERROR`, the generic fallback ([errors](errors.md)). New codes are
  additive; a client handles them by their status.
- **Documented: the per-IP limit.** 120 requests a minute per source IP address, over all members
  and clients, are checked before the token is read; every client behind one address shares them.
  Its `429 RATE_LIMITED` carries no `RateLimit` headers and no `DPoP-Nonce`
  ([sync guide](sync-guide.md#rate-limits-quota-and-back-off)). The limit existed before.
- **Corrected: which answers carry `DPoP-Nonce`, and when the nonce challenge comes.** The answers
  given before the token is read — the per-IP `429`, `413 PAYLOAD_TOO_LARGE`, the identity
  provider's `503`, a refused method, path or query — carry no nonce; keep the last one. The
  challenge comes before the proof's claim checks, but a proof that cannot be parsed or verified is
  refused `invalid_dpop_proof` without a challenge
  ([authentication](authentication.md#the-server-nonce)). The behaviour is unchanged.
- **Corrected: smaller statements.** `NOT_FOUND` also means an unknown route or method, checked
  right after the token's audience; `IDEMPOTENCY_KEY_REUSED` means another route or another body
  under the key, and `IDEMPOTENCY_KEY_MISSING` also a malformed key; `RATE_LIMITED` also covers the
  hourly account check and the per-IP limit; `LOC_KEY_UNRESOLVED` is added whenever the key fields,
  the `locKey` included, resolve to no single entry; the schema check runs before the relay, after
  the gates; the installation label must not start with a space; only the gates' refusals are
  counted as metric reasons.
- **A full proof store answers `503 SERVICE_UNAVAILABLE`.** When all members together hold the
  gateway's 100 000 live DPoP proofs, a proof is no longer refused like a replayed one
  (`401 DPOP_INVALID`) but with `503 SERVICE_UNAVAILABLE` and `Retry-After`, the seconds until the
  earliest live proof no longer counts ([errors](errors.md),
  [authentication](authentication.md#live-proofs-per-member)). A client already handles the code
  by its status: wait `Retry-After`, then retry.
- **New approval criteria: back-off and sync cadence numbers.** "A few seconds" and "every few
  minutes" are replaced by binding numbers: retries back off from 5 seconds, doubling up to at most
  5 minutes, with random jitter and never less than `Retry-After`; a client syncs on start and after
  a local change, and a timed sync runs at most every 5 minutes
  ([sync guide](sync-guide.md#back-off-and-sync-cadence), [client security](client-security.md)).
- **A `formatVersion` major other than 1 is refused.** A blueprint draft whose `formatVersion` is not
  a `1.x` gets `400 SCHEMA_INVALID` with `errors[]` at `/formatVersion` ("unsupported major
  version"), and the web import refuses such a file; every `1.x` is still read
  ([formats](formats.md#offline-file--envelope), [drafts](resources/drafts.md#errors)). The schema
  is unchanged; its description states the rule. Until now the field was not read, and `2.0` was
  read as `1.0`.

## 2026-09-27

- **New code `429 DPOP_PROOF_LIMIT`.** A proof whose member already holds 600 live proofs is no
  longer refused like a replayed one (`401 DPOP_INVALID`) but with `429 DPOP_PROOF_LIMIT` and
  `Retry-After`, the seconds until the member's earliest live proof no longer counts
  ([errors](errors.md), [authentication](authentication.md#live-proofs-per-member)). A new code is
  additive in `v1`; a client that does not know it handles it by its status, `429`.
- **More approval criteria.** An application is now also checked for: a Linux fallback key
  file in a `0700` directory; baseline, ship links and cursors kept per installation and random
  idempotency keys; showing `detachedFromMissions`, `offersReduced` and `offersRemoved` to the
  member; and every ship `upsert` sending the current `name` and `location`
  ([client security](client-security.md)). A sandbox run before applying is recommended, not
  required.
- **Corrected: `Retry-After` per code.** The pages said every Redis failure answers
  `Retry-After: 60`. The gateway sends 30 with `EXCHANGE_DISABLED`, `REGISTRY_UNAVAILABLE` and a
  `SERVICE_UNAVAILABLE` whose daily write quota cannot be counted; 60 with
  `EXCHANGE_BUDGET_EXHAUSTED` and a `SERVICE_UNAVAILABLE` for a store it cannot reach; 5 when the
  identity provider cannot be reached ([errors](errors.md)). The behaviour is unchanged.
- **Clarified: the change result's `cursor` is reserved.** `change-result.schema.json` declares an
  optional `cursor`, which the server has never sent. It stays in the schema, since `v1` never
  removes a field, and is marked reserved; read the feed after a push for the new position.
- **Corrected: `UNAUTHENTICATED` is answered by a refresh.** The error registry said to start a
  device login again; as the authentication page says, refresh once, and start a device login only
  after the refresh answers `invalid_grant` and the member asks.
- **Corrected: the `docsUrl` example.** The service document's example and its fixture showed
  `https://krt-profit.github.io/basetool/exchange/`, which does not exist. The gateway sends the
  site root, `https://krt-profit.github.io/basetool/`.
- **Documented: the cap on live DPoP proofs.** A member holds at most 600 live proofs over all
  clients ([authentication](authentication.md#live-proofs-per-member)). The cap existed before;
  only the page is new.
- **Documented: the problem fields.** The [error registry](errors.md#the-problem-document) lists
  which fields a problem carries and when, and the `X-Correlation-Id` header. `retryAfterSeconds` is
  reserved and not sent. `problem.schema.json` allows a `correlationId` of up to 128 characters
  instead of 64, because the gateway echoes a client's own id of that length.
- **Clarified: `confirmationUrl` is a secret.** Like a draft's `frontendUrl`, it carries a one-time
  handoff id; never log or share it ([sync guide](sync-guide.md#the-mass-change-guard)). The
  fixture now has the real shape, `/connected-apps/confirm?handoff=…`.
- **Corrected: draft slots.** The drafts page said a member's ten live drafts are shared with the SC
  Extractor's uploads. Each client holds ten per member in slots of its own, and staging an eleventh
  drops only that client's oldest. A draft item's `provenance` is not read; the review records
  `import`.
- **Documented: offline files.** The web import takes a file of at most 8 MiB, checks only
  `format` and the items, and does not read `formatVersion` ([formats](formats.md#offline-file--envelope)).
- **Clarified: SCU decimals.** `quantity.schema.json` read like a limit of three decimals, which
  nothing checks. An SCU amount may carry any number; the server rounds it half-up to three before
  storing or comparing it ([formats](formats.md#quantity--quantity)).
- **Clarified: sandbox `htu`.** The sandbox gateway compares `htu` with the called URL, port
  included, so `localhost` and `127.0.0.1` both work when the proof names the address the request
  went to ([sandbox](sandbox.md#addresses)).
- **Clarified: which answers are replayed.** `409 IDEMPOTENCY_IN_PROGRESS`, `422
  IDEMPOTENCY_KEY_REUSED`, `400 IDEMPOTENCY_KEY_MISSING` and `413` are never cached, and an answer
  the server could not store is not replayed ([sync guide](sync-guide.md#idempotency-keys)).
- **Clarified: the label's hyphen and the tombstone channels.** The installation label allows only
  the ASCII hyphen-minus; `VerseKit – Windows` with an en dash is refused
  ([connect](resources/connect.md#label-the-installation--post-exchangev1meinstallation)). A
  tombstone's `removedBy.channel` is `web`, `app`, `client` or `system`
  ([sync guide](sync-guide.md#tombstones-and-never-re-adding)).
- **Clarified: after `CLIENT_SUSPENDED`.** A client cannot learn when a suspension ends, so it tries
  again at its next start or when the member asks, never on a timer.
- **One held mass change per client.** A newer held batch replaces only your client's older one for
  that member; another client's held batch no longer displaces yours.
- **Show the bare `verification_uri`.** A client shows the `user_code` and `verification_uri` and
  lets the member type the code; it no longer opens `verification_uri_complete`, which skips the
  page that warns about device-code phishing. The device response is unchanged.
- **`CLIENT_REVOKED` counts from the sign-in.** After the member disconnects a client, a token
  without `offline_access` is refused while its `auth_time` lies before the disconnect, also when it
  was refreshed afterwards; a client that requests `offline_access`, as it must, is unaffected.
- **Store outages.** A store the exchange cannot reach answers `503 SERVICE_UNAVAILABLE` with
  `Retry-After: 60` on every exchange route. A lost Redis connection while staging a draft
  answered with the extractor upload's `Retry-After: 5`, and a store failure outside the staging
  could answer `500`.
- **Oversize drafts.** A draft or a held-back change set whose staged form exceeds the cap answered
  `400 BAD_REQUEST`, a code outside the registry, and the answer was replayed for its key. It now
  answers `413 PAYLOAD_TOO_LARGE` (draft) or `413 BATCH_TOO_LARGE` (change set), which is not
  cached.
- **Overlong unknown field names.** A request whose undeclared field has a JSON Pointer longer than
  200 characters is refused with `400 SCHEMA_INVALID` (`errors[]` names its parent) instead of being
  written and answered `502 BACKEND_RELAY_FAILED`.
- **Fixed `detail` texts.** A refusal the Basetool raises behind the gateway arrives with its code
  and a fixed English `detail` per code, no longer with the Basetool's own, possibly localised text
  ([errors](errors.md)).
- **OpenAPI document matches the gateway.** The `Idempotency-Key` takes 8 to 128 characters of
  `[A-Za-z0-9._~-]`; `POST /exchange/v1/me/installation` needs none; `catalog/resolve` and
  `catalog/locations` accept any exchange scope, `exchange.connect` included.
- **`VERSION_CONFLICT` at request level.** A write that meets a concurrent change the row locks did
  not already turn into a per-op `VERSION_CONFLICT` now answers `409 VERSION_CONFLICT` instead of
  `502 BACKEND_RELAY_FAILED`.
- **Drafts.** `POST /exchange/v1/me/drafts/blueprints` and `…/drafts/refinery-orders` stage a
  `basetool.blueprints` envelope or a refinery extract for the member's review and answer the
  `draft-result`. A draft too large to hand off is `413 PAYLOAD_TOO_LARGE`; a draft the Basetool
  refuses as malformed is `400 SCHEMA_INVALID` without `errors[]`.
- **Offline blueprint files.** The web's blueprint import reads the `basetool.blueprints` envelope.
- **Provenance.** A blueprint `add` records its `provenance.source`, and the blueprint feed answers
  it for every blueprint whose source was recorded.
- **Stock.** A change set that lowers a lot and raises its stolen or not-stolen twin marks the stock
  instead of booking it out and in; a lowered lot counts as moved for the mass-change guard only when
  the batch's rises of the same material cover all of it.

## 2026-09-26

- **v1 published.** The schemas, the OpenAPI document, the error registry and the conformance
  fixtures.
- **`installationId`** in the installation response and the service document — the value a
  tombstone's `removedBy.installationId` carries.
