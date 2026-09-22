# 6. Runtime view

Seven scenarios. They were chosen because each one exercises a rule that is invisible in the static
view, and because each one has burned somebody at least once.

## 6.1 Sign-in

1. The browser hits a protected page; the frontend, as an OAuth2 *client*, redirects to Keycloak —
   served under `/auth` on the same origin (ADR-0166).
2. Keycloak authenticates — either directly, or through the **Discord identity provider** in
   `keycloak-spi`.
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
same Redis channel in both directions (ADR-0143). Design: ADR-0094; the knowledge base's Live Sync
note has the bounds and what has broken.

## 6.5 The desktop extractor sends a refinery order

1. The extractor, holding a sender-constrained (DPoP) token for the member, `POST`s JSON to
   `ingest.profit-base.online/v1/refinery-extract` (or `/v1/blueprint-preview`).
2. The **ingest** gateway validates the token, checks the client is an **approved** one — an
   unapproved caller is refused `403 CLIENT_NOT_ALLOWED` — and enforces rate and payload limits.
3. It relays to the backend over the internal network under **its own** service-account token,
   naming the member in an on-behalf-of header; the member's bound token stops at the gateway. If the
   backend refuses that token (`401`/`403`), the fault is the gateway's, not the member's: the
   extractor gets a `502`, and the cached token is dropped so the next send mints a fresh one.
4. The backend matches the payload and returns a **draft**. Ingest stages it in Redis for a single
   browser pickup and answers with a handoff link the extractor opens.
5. The member reviews the pre-filled form and saves it through the **ordinary** create path — so the
   ingest route cannot bypass a validation, a permission check or an audit event.

The backend is never exposed to the extractor directly. That is the entire reason this module is
its own deployable. Specification: [`desktop-ingest.md`](../specs/desktop-ingest.md).

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
   gate that rolls back on failure (`REQ-OPS-003`).
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
