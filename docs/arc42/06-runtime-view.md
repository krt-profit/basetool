# 6. Runtime view

Eight scenarios. They were chosen because each one exercises a rule that is invisible in the static
view, and because each one before the exchange's (§6.8) has burned somebody at least once.

## 6.1 Sign-in

1. The browser hits a protected page; the frontend, as an OAuth2 *client*, redirects to Keycloak —
   served under `/auth` on the same origin (ADR-0166).
2. Keycloak authenticates — either directly, or through the **Discord identity provider** in
   `keycloak-spi`.
   The Discord path is the one hop that leaves the origin (`discord.com`); a callback that returns
   in another browser context cannot be resumed and ends on a recoverable error page (REQ-SEC-071).
3. On the Discord path the guild/role gate runs: guild membership and an in-guild role are checked
   **fail-closed**. If Discord cannot be reached, the login is refused rather than allowed.
4. A new sign-up that passes the gate lands in the **approval queue** instead of the application:
   while it is pending the account sees nothing but its own registration status, and an admin
   approves it, rejects it, or links it onto an existing account.
5. The frontend receives the tokens, creates a Spring Session in **Redis**, and calls the backend
   as a *resource server* with a bearer token. The backend never sees a credential.

The session lives in Redis rather than in memory so it survives a frontend restart — which a deploy
performs routinely.

## 6.2 A scoped read

1. A controller takes the request; `@PreAuthorize` decides whether the caller may perform the
   operation at all.
2. The **service layer** applies scope through `OwnerScopeService`: which org units this caller may
   see, and by which of the aggregate's scope kinds.
3. The repository is asked only for what the scope allows.

The order matters. Scoping in the service layer rather than the controller means every path into
the aggregate is covered, including ones added later; scoping there rather than in the repository
means the rule is visible where the business decision is.

## 6.3 Two people edit the same thing

1. Both clients hold the aggregate's `version` and echo it back in the write DTO.
2. The first write succeeds and bumps the counter.
3. The second write fails with `ObjectOptimisticLockingFailureException`, which the API surfaces as
   **HTTP 409** with a problem document — never a silent overwrite.
4. The UI tells the user their copy is stale and offers to reload that part.

**The granularity is the design.** Where an aggregate is large, sections carry independent counters,
so two people editing unrelated parts of one mission do not collide. After any successful AJAX
update the frontend must propagate the **new** version to every DOM element that carries it —
missing one turns the next edit into a spurious 409.

## 6.4 A peer's change appears without a reload

1. A mutation succeeds; the client replaces the affected fragment **in place** — no full-page
   reload on success, which is a binding requirement, not a nicety (`REQ-FE-001…010`).
2. The browser that made the change announces it: a `changed` frame on its one multiplexed
   **`/ws/sync`** WebSocket, naming the topic room (`mission:{id}`, …). Publishing needs no
   subscription; only *receiving* needs an authorised subscribe. Where no browser is involved, the
   server publishes the same frame itself.
3. The frontend relays the frame to the room's local members and onto **Redis pub/sub**
   (`basetool:livesync:changed`), so every other frontend replica reaches its own members.
4. Each receiving client re-reads the named sections and swaps them in place.

The Android app takes a second door to the same relay: a backend **SSE** stream, bridged onto the
same Redis channel in both directions (ADR-0143). Design: ADR-0094.

## 6.5 The desktop extractor sends a refinery order

1. The extractor, holding a sender-constrained (DPoP) token for the member, `POST`s the order as a
   draft to `ingest.profit-base.online/exchange/v1/me/drafts/refinery-orders` (or
   `…/drafts/blueprints`).
2. The **ingest** gateway checks the token and its proof, the client registry and the draft
   capability — an unapproved caller is refused `403 CLIENT_NOT_ALLOWED` — and enforces rate and
   payload limits (§6.8).
3. It relays to the backend's exchange layer over the internal network under **its own**
   service-account token, naming the member in an on-behalf-of header; the member's bound token
   stops at the gateway. If the backend refuses that token (`401`/`403`), the fault is the
   gateway's, not the member's: the extractor gets `502 BACKEND_RELAY_FAILED`.
4. The backend matches the payload and returns a **draft**. Ingest stages it in Redis for a single
   browser pickup and answers with the review URL the extractor opens.
5. The member reviews the pre-filled form and saves it through the **ordinary** create path — so the
   draft route cannot bypass a validation, a permission check or an audit event.

The backend is never exposed to the extractor directly. That is the entire reason this module is
its own deployable. Specification: [`desktop-ingest.md`](../specs/desktop-ingest.md) and
REQ-XCH-019. *Until 2026-09-28 the extractor posted to the gateway's own `/v1/refinery-extract` and
`/v1/blueprint-preview`, behind a client allowlist; both are removed (REQ-XCH-033).*

## 6.6 A deploy

The only way the running application and its configuration change, and nobody drives it (the host
itself changes only through the Ansible role, which never delivers):

1. `iri-deploy.timer` fires on the host every five minutes and runs `deploy.sh` as the `deploy`
   account.
2. It resolves the `:stable` tags of the app images, the **config bundle** and the
   **Keycloak provider-JAR bundle** to immutable digests.
3. **Every digest is Cosign-verified on the host** against the release workflow's keyless identity
   *before* anything is pulled, extracted or applied. A `:stable` tag moved out-of-band to an
   untrusted digest is rejected here — which is what makes a blind `:stable` pull safe.
4. Verified content is unpacked, `env.d` files are rendered from `.env` by `render-env-d.py`, and
   the Quadlet units are reconciled through the service user's systemd instance, behind a health
   gate that rolls back on failure (`REQ-OPS-003`). A failure *before* the gate — an unwritable
   directory, a failed mirror, a failed pull — is refused up front where it can be, and otherwise
   recorded like any other failed deploy: the host tree and the pin are put back, the target backs
   off, and `DeployFailed` fires (since 2026-09-25; until then it ended the run in silence).
   A moved **provider JAR** rides the same apply (`REQ-OPS-007`, ADR-0213): extracted before
   anything changes, swapped in as the last step before the gate, and keycloak recreated together
   with the app images. The apply takes every re-defined unit down in **one** `systemctl stop` —
   `Requires=` takes what depends on them along — and then starts the stack in dependency order, so
   a release is one restart window in which each unit restarts once, and one gate covers images,
   units and JAR. A failed gate rolls all of them back together; the log names what the release
   changed and what did not come up, because a single gate cannot tell a bad JAR from a bad image.
   (Until 2026-09-25 the JAR was swapped only *after* the gate, and its keycloak restart took the
   app down a second time.)
5. Nothing is promoted automatically: `:stable` moves only by a deliberate act in the promote
   workflow. The deploy is the *consumer* of that decision, never its author.

## 6.7 Every night a backup, every week the drill that proves it

1. `iri-backup.timer` (daily, 04:15) runs `backup.sh`: `pg_dump` of both databases, Grafana's
   SQLite, the monitoring secrets, the three `edge-*` volumes, `.env`, the keystore, the realm
   export, the Keycloak providers and the **redis ACL** — pushed with **restic** through an
   **rclone** WebDAV remote to Nextcloud, under a GFS retention policy.
2. `iri-restore-drill.timer` (weekly, Sunday 05:30) runs `restore-drill.sh`, which restores the
   **latest snapshot** into a throwaway PostgreSQL and checks seven artifacts, writing the result
   as Prometheus textfile metrics (`basetool_restore_drill_artifact_ok`).

The drill inspects **what a restored snapshot contains**, not what the host happens to have. That
distinction is the whole value: a host can be perfectly healthy while its backups have been
unrestorable for weeks, and only the drill can tell those apart. Procedure:
[`docs/backup.md`](../backup.md); requirements: [`backup-recovery.md`](../specs/backup-recovery.md).

## 6.8 An approved client syncs through the exchange

The building blocks are in §5.5; every rule is in
[`external-exchange.md`](../specs/external-exchange.md).

1. **Connect.** The client runs the device grant against its product's public Keycloak client; the
   member consents per capability, and the tokens are bound to the client's DPoP key. That key's
   thumbprint is the **installation**; its first request notifies the member.
2. **Service document.** `GET /exchange/v1` answers the granted capabilities, limits, the minimum
   client version and the `installationId`. The first proof lacks a server nonce and is retried
   once with the one the `401` carries.
3. **The gateway's chain**, on every request: token gate → registry gate (mirror through a 5 s
   cache, revocations uncached, scope ∩ grant, version) → limits and quota → idempotency for writes
   → schema check → relay under the gateway's own identity. The backend swaps in the acting
   member's reduced authentication and re-checks the capability (`@exchangeGate`).
4. **Pull.** A snapshot pages the resource and ends with a feed cursor; later pulls read the change
   feed since that cursor — each changed key once, its current state or a tombstone naming who
   removed it. A cursor older than the 90-day horizon is `410 CURSOR_EXPIRED` and means a new
   snapshot.
5. **Push.** A change set of at most 500 ops, with an `Idempotency-Key`. The backend plans every op
   (`REMOVED_ELSEWHERE`, `VERSION_CONFLICT`, …), asks the mass-change guard, writes through the
   domain's own services and journals each entry, all in one transaction; the feed triggers record
   the write as `client|<id>|<installation>`.
6. **Live sync.** After the commit `ExchangeLiveSync` raises the frames the member's pages listen
   on — Hangar, Blueprints, Lager, and the Materialbörse when an offer changed (§6.4).

**A mass change held for confirmation.** When a batch would take the rolling 24-hour removals of
one resource past the guard's limit, the backend writes nothing and answers
`MASS_CHANGE_CONFIRMATION_REQUIRED`. The gateway stages the batch in Redis and answers `409` with a
link to `/connected-apps/confirm`. That page consumes the staging only by an explicit request
(ADR-0110) and keeps the batch in the member's server session. The backend checks switch, client,
capability and disconnects again, shows a dry run, and on confirmation applies it without the guard
as the installation's own write; „Verwerfen" drops it.

**An undo.** On „Verbundene Anwendungen" the member undoes a client's writes since a chosen time,
at most as far back as the journal is kept (90 days by default). From the journal, each entry goes back to its state before the client's first
write in that span, through the same domain services; an entry changed afterwards by anything else
is skipped as `CHANGED_AFTERWARDS`, a vanished one as `GONE`. Materialbörse offers a book-out
lowered stay lowered, and a removed ship comes back under a new id without the mission units it was
detached from (§11.7a). The client sees the undo in its feed, like any web edit.

**A bulk undo.** After a malicious or faulty release, an admin undoes one client's writes for
every member on *Administration → Verbundene Anwendungen*: the dialog checks the scope (since
when, optionally one installation or resource) and shows the members and entries it reaches;
on confirmation the backend suspends the client through the registry — mirror first, so the
gateway refuses it — records the run and answers at once. A single-thread executor then works
through the members under the admin's authentication, one transaction each, with the member's
own undo semantics; a failing member rolls back alone. Each member whose data changed gets one
notification, the page refreshes the run list while it runs, and the run ends `COMPLETED` or
`FAILED` (then `ExchangeBulkUndoFailed` alerts). Re-activating the client is a separate admin
action (REQ-XCH-034, ADR-0227).

**A draft.** `drafts/blueprints` and `drafts/refinery-orders` write nothing: the backend builds the
same preview the web's own upload builds, the gateway stages it for a one-time browser pickup and
answers the review URL, and the member saves through the ordinary path — as in §6.5.
