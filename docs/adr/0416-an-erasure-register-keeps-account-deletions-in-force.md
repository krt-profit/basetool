# ADR-0416 — An erasure register keeps account deletions in force

- **Status:** Accepted
- **Date:** 2026-11-27
- **Deciders:** @greluc
- **Related:** spec REQ-DATA-048 · REQ-DATA-047 · REQ-DATA-008 · REQ-SEC-059 · REQ-SEC-061 ·
  REQ-SEC-062 · ADR-0181 · ADR-0183 · [`docs/privacy/processing-activities.md`](../privacy/processing-activities.md) ·
  [`docs/backup.md`](../backup.md)

## Context

An account deletion removed the member's data in the database and then the Keycloak user. Three
things could bring the account back or keep its data:

- **A still-valid token.** An access token lives 300 seconds and the backend checks it locally. A
  request in that window found no account row and created a new one from the token's claims, as a
  pending registration with the member's e-mail address and Discord id, and told the admins.
- **A failed Keycloak delete.** The daily roster sync re-created the row from the surviving
  Keycloak user.
- **A restore.** The privacy policy promises that erasures made since a snapshot are re-applied
  after a restore. Nothing recorded which accounts had been deleted outside the restored database,
  so nothing could re-apply them.

The deleted member's frontend sessions also stayed in Redis, with their claims and tokens, for up
to 30 days.

The law, as researched for this change: an account deletion is an Art. 17 request and must hold
without undue delay (Art. 12(3)); a restore must not bring erased data back, which German
supervisory authorities expect to be handled by recording the deletions (EDPB CEF report 2025 on the
right to erasure, 2026); a retained id stays pseudonymous personal data while anything can resolve
it (CJEU C-413/23 P), and every retained remnant needs a basis and a fixed period (CJEU C-484/24).

## Decision

1. **The identity module keeps an erasure register**, `erased_account`, with the deleted
   account's id and the time and nothing else. Every account deletion enters it inside the
   deletion's transaction, except the stray row of a configured gateway's service account.
2. **The register refuses re-creation.** A token of a registered id is refused as unauthenticated,
   the roster sync skips its Keycloak user, and the account's cached authorities are dropped once
   the deletion commits.
3. **A daily job keeps every registered deletion in force.** It runs at startup and then daily,
   deletes a returned account row through the original deletion's code and then a surviving
   Keycloak user, and is observable through a gauge, an alert and the stale-job alert. This one
   path covers a failed Keycloak delete and a restore.
4. **An entry is kept 12 months**, longer than the backups, on Art. 6(1)(c) with Art. 17, and the
   privacy policy says so.
5. **A restore imports the newest register before the frontend and ingest start**: from the
   running database when it is readable, otherwise from the newest snapshot, completed from the
   logs for the hours since. The backend then re-applies the deletions at startup.
6. **The frontend ends the deleted account's sessions** when an admin deletes it, carries out its
   request or dissolves it into another account.
7. **Retained ids and names of deleted accounts get a fixed period.** The name retention of
   REQ-DATA-047 also replaces, three years after the event, the name snapshot of a deleted account
   in the booking history and the holder register and the id of a deleted admin on catalogue
   imports and review cases, patch changes and default blueprints. The records stay.

## Consequences

- A deleted member cannot come back through a token, the roster or a restore while their entry
  lasts; after 12 months no backup that predates the deletion is left.
- The register itself is a small store of pseudonymous ids with a fixed period, named in the privacy
  policy and the record of processing activities.
- The deletions after the newest snapshot depend on the running database or the logs when a restore
  follows a total loss; the runbook names both sources.
- Kept acts and bookings lose their link to a deleted person after three years, which ends the
  indefinite retention of names and ids the privacy policy stated.

## Alternatives considered

- **Revoke tokens instead of refusing them.** Keycloak cannot revoke an access token the backend
  checks locally, and token introspection on every request would put Keycloak on the hot path.
- **Keep the register only in the logs.** The logs live 31 days and are not backed up; the register
  must outlive the backups.
- **Delete sessions from the backend.** The backend has no access to the session store by design
  (REQ-SEC-089); the frontend, which owns the sessions, ends them.
- **Search and edit the backups.** The policy rules it out, and re-applying on restore reaches the
  same result without touching encrypted snapshots.
