# 6. Runtime view

Seven scenarios. They were chosen because each one exercises a rule that is invisible in the static
view, and because each one has burned somebody at least once.

## 6.1 Sign-in

1. The browser hits a protected page; the frontend, as an OAuth2 *client*, redirects to Keycloak.
2. Keycloak authenticates — either directly, or through the **Discord identity provider** in
   `keycloak-spi`.
3. On the Discord path the guild/role gate runs: guild membership and an in-guild role are checked
   **fail-closed**. If Discord cannot be reached, the login is refused rather than allowed.
4. A new sign-up that passes the gate lands in the **approval queue** instead of the application:
   an admin approves it, rejects it, or links it onto an existing account.
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

1. A mutation succeeds on the backend and raises a domain event.
2. The event is published to **Redis pub/sub**, which fans it out across frontend replicas.
3. Each replica pushes over the one multiplexed **`/ws/sync`** WebSocket to the clients subscribed
   to that topic.
4. The client replaces the affected fragment **in place**. No full-page reload on success — that is
   a binding requirement, not a nicety (`REQ-FE-001…010`).

## 6.5 The desktop extractor pushes a screenshot

1. The extractor authenticates and `POST`s JSON to `ingest.profit-base.online`.
2. The **ingest** gateway checks the token, checks the client is an **approved** one — an
   unapproved caller is refused `403 CLIENT_NOT_ALLOWED` — and enforces rate and payload limits.
3. It relays to the backend over the internal container network.
4. The backend validates and stores; the user finds a pre-filled refinery order waiting.

The backend is never exposed to the extractor directly. That is the entire reason this module is
its own deployable.

## 6.6 A deploy

The only way production changes, and nobody drives it:

1. `iri-deploy.timer` fires on the host (every few minutes).
2. `deploy.sh` resolves the `:stable` tags of the app images, the **config bundle** and the
   **Keycloak provider-JAR bundle** to immutable digests.
3. **Every digest is Cosign-verified on the host** against the release workflow's keyless identity
   *before* anything is pulled, extracted or applied. A `:stable` tag moved out-of-band to an
   untrusted digest is rejected here — which is what makes a blind `:stable` pull safe.
4. Verified content is unpacked, `env.d` files are rendered from `.env`, and the Quadlet units are
   reconciled through systemd.
5. Nothing is promoted automatically: `:stable` moves only by a deliberate act in the promote
   workflow. The deploy is the *consumer* of that decision, never its author.

## 6.7 The night: backup, then the drill that proves it

1. `iri-backup.timer` runs `backup.sh`: `pg_dump` of both databases, Grafana's SQLite, the
   monitoring secrets, the three `edge-*` volumes, `.env`, the keystore, the realm export, the
   Keycloak providers and the **redis ACL** — pushed with **restic** through an **rclone** WebDAV
   remote to Nextcloud, under a GFS retention policy.
2. `iri-restore-drill.timer` then runs `restore-drill.sh`, which restores the **latest snapshot**
   into a throwaway PostgreSQL and checks seven artifacts, writing the result as Prometheus
   textfile metrics.

The drill inspects **what a restored snapshot contains**, not what the host happens to have. That
distinction is the whole value: a host can be perfectly healthy while its backups have been
unrestorable for weeks, and only the drill can tell those apart.
