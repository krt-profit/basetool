# Technical and organisational measures (Art. 32 GDPR)

> **Doc type:** Living document — kept in sync with `main`. Last reviewed: 2026-09-15.

Art. 32 requires measures appropriate to the risk. This document does not restate them in the
abstract; each measure names **where it is enforced**, so a reader can check the claim rather than
trust it, and so a measure that is removed from the code becomes a visible contradiction here.

The processing is of moderate risk: no special categories under Art. 9, no criminal-offence data
under Art. 10, no payment data, no first or last names, and a population in the low hundreds. The
measures below are accordingly proportionate rather than exhaustive.

---

## Confidentiality

### Access control — who can reach the system

- **Single sign-on only.** Authentication runs through Keycloak (OIDC); the backend is a resource
  server, the frontend a confidential OAuth2 client. There is no local password store in the
  application (REQ-SEC-*, [`security-and-access.md`](../specs/security-and-access.md)).
- **Membership gate.** A Discord registration is admitted only if the account is a member of the
  organisation's guild with the required role; the check lives in the Keycloak SPI, before an account
  exists.
- **Admission is explicit.** A new non-admin registration is `PENDING` and holds **no** authorities
  until an admin approves it — fail-safe by construction, and independent of detecting any token
  claim (REQ-SEC-017).
- **A role-less token is refused** (REQ-SEC-053).
- **Two-factor authentication** is available and managed in Keycloak.

### Access control — what an authenticated caller may reach

- **Authorization is centralised** in `@PreAuthorize` on the controller layer, with the role
  hierarchy in [`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md). ArchUnit tests fail the
  build if an endpoint is added without a gate.
- **Per-subject isolation.** Personal aggregates are scoped to the caller's own subject in the
  service layer, not in the UI.
- **Org-unit tenancy.** Squadron-scoped data is filtered through `OwnerScopeService`, with
  ArchUnit guards against an endpoint that forgets the scope
  ([`org-unit-tenancy.md`](../specs/org-unit-tenancy.md)).
- **Field-level redaction.** Members below the relevant role have sensitive fields stripped in the
  controller layer rather than merely hidden in the template (REQ-SEC-007).
- **No direct security-context access** outside the auth-helper seam — ArchUnit-enforced, so the
  "who is the caller" answer has exactly one source.

### Encryption in transit

- **HTTPS everywhere**, terminated at the edge proxy; the certificate is renewed automatically and a
  renewed certificate is not delivered until the edge can actually open it (REQ-OPS-026).
- **The internal hops are TLS as well** — the backend serves HTTPS, and the SMTP transport (when
  enabled) requires STARTTLS and fails rather than falling back to plaintext.
- **Session cookie** is `Secure`, `HttpOnly`, `SameSite=Strict`.

### Encryption at rest

- **Backups are client-side encrypted** before leaving the host; the storage target only ever
  receives encrypted blobs (REQ-OPS-008). This is the copy that leaves the controller's own
  infrastructure, so it is the one that must be encrypted regardless of the target.
- **The keystore is not world-readable**: delivered `0640` with a POSIX ACL for the two container
  uids, so the private key is not readable by other accounts on the host (REQ-OPS-016).

### Confidentiality of the operational record

- **Names, e-mail addresses and tokens are masked at the logging layer** — every sink, including the
  JSON appender, goes through `PiiMaskingPatternLayout` / `PiiMaskingLogstashEncoder`. This is a
  mechanism, not a convention: a developer who logs a user object does not defeat it.
- **Metrics and traces carry no personal data.** `ObservationPrivacyFilter` cuts query strings and
  reduces UUID and numeric path segments to placeholders before they can become metric tags or span
  attributes (REQ-OBS-006/-009).
- **The audit details payload is bounded** to ids, counts and non-personal labels — never user free
  text (REQ-AUDIT-001).
- **Monitoring surfaces are admin-only.**

---

## Integrity

- **Optimistic locking** on every write, with concurrent modification surfacing as HTTP 409 rather
  than a silent overwrite; fine-grained per-section counters where a coarse lock would force
  unrelated edits to collide.
- **An append-only audit trail** for every state-changing action in the audited areas, written in the
  same transaction as the business write, so the trail has no silent gaps (REQ-AUDIT-001).
- **Schema changes are migrations**, applied by Flyway with `ddl-auto = validate` in every profile —
  the schema can never drift from the entities without failing startup.
- **Ledger integrity is swept**, hourly, with a critical alert if the sweep stops running.
- **Input validation** at the boundary (Jakarta validation on write DTOs), and bounded free-text
  fields so an authenticated caller cannot store an unbounded blob.

---

## Availability and resilience

- **Automated, scheduled, encrypted, off-site backups**, daily, with a post-upload integrity check
  and a **weekly restore drill** — a backup nobody has restored is a hypothesis, not a backup
  (REQ-OPS-008, [`backup-recovery.md`](../specs/backup-recovery.md)).
- **Backups are consistent**, taken around a minimal bounded quiesce (REQ-OPS-009).
- **Health-gated deploys with automatic rollback** on a failed health check (REQ-OPS-003).
- **Resilience4j** (timeout, retry, circuit breaker, bulkhead) around every backend call from the
  frontend, so a partial outage degrades rather than cascades.
- **Rate limiting** per subject and per client address, evaluated in memory, rejecting with HTTP 429.
- **Monitoring and alerting** across the stack, including alerts that fire when a *guard itself*
  stops running — a silent monitor is the failure mode that hides every other one.

---

## Runtime hardening

- **Every production container** runs `no-new-privileges`, `cap_drop: [ALL]` with a minimal explicit
  add-back, a pid ceiling, and — for the application services — a fixed non-root uid binding only
  high ports (REQ-OPS-014).
- **The deploy path is confined** by a systemd sandbox with an empty capability bounding set, a
  seccomp allow-list, and a narrow set of writable paths (REQ-OPS-016).
- **Pull-only delivery**: the host pulls; nothing is pushed into it and no inbound access is required
  (REQ-OPS-001). Backups are outbound-only for the same reason.
- **Artifacts are digest-pinned and signature-verified** on the host before they are applied
  (REQ-OPS-015), and a promotion is gated on the promoted digest's vulnerability scan (REQ-OPS-024).
- **No secrets in the delivered bundle** (REQ-OPS-005).

---

## Secure development

- **Static analysis in CI** — Checkstyle, SpotBugs, secret scanning (gitleaks), CodeQL, and an OWASP
  dependency check that fails the build at CVSS ≥ 7.0.
- **An SBOM per shipped module** (REQ-OPS-025) and build provenance anchored outside the registry
  (REQ-OPS-023).
- **ArchUnit tests encode the security invariants** so they fail the build rather than a review.
- **Never real credentials in tests or local stacks** — a hard project rule with dedicated throwaway
  material, on the reasoning that anything entering a worktree, a CI log or a container volume has to
  be assumed leaked (ADR-0139).
- **Requirements move with the code**: a behaviour change without its spec change is incomplete, which
  is what keeps this document from going stale.

---

## Data protection by design and by default (Art. 25)

- **Minimisation by construction**: no first or last name is collected; the login flow takes what
  authentication needs and no more.
- **Sharing is opt-in, not opt-out**: the cross-org-unit blueprint visibility defaults to off and, when
  enabled, exposes the owner by display name only — never the subject id or e-mail.
- **Pseudonymous operational record**: logs carry an internal account id, not a name.
- **Deletion is designed, not improvised**: the deletion path enumerates every foreign key and states
  for each whether the row is purged, reassigned or unlinked, so what survives an erasure is a
  documented decision rather than an accident (REQ-DATA-008).
- **Retention windows are configuration with finite defaults**, swept automatically, rather than an
  admin remembering to clean up.

---

## Organisational measures

- **Production writes require explicit, per-action approval** by the repository owner; reading is
  permitted but never of secrets. This is the strictest rule in the project's contributor guidance
  and it outranks every convenience argument, including during an incident.
- **Admin access is limited** to the small group that needs it, through the same SSO and MFA as
  everyone else.
- **Every administrative action in the audited areas is attributable** through the audit trail.
- **A documented process exists** for data-subject requests
  ([`data-subject-requests.md`](data-subject-requests.md)) and for personal-data breaches
  ([`data-breach-runbook.md`](data-breach-runbook.md)).

---

## Known limitations

Stated deliberately, because a measures document that lists only strengths is not a useful one.

- **Database storage is not separately encrypted at rest** beyond the host's own protections. The
  copy that leaves the infrastructure — the backup — is encrypted; the volume on the host is not.
  Accepted for the risk level, and revisited if the data categories change.
- **Docker-group membership on the deploy host remains root-equivalent.** A socket-proxy or
  rootless-Docker reduction is deferred and documented in the deployment runbook rather than
  silently ignored (REQ-OPS-016).
- **An erasure request is not applied to existing backups.** The data leaves as the snapshots expire,
  within roughly six months; re-erasure after a restore is part of the restore procedure.
- **The project is run voluntarily**, so availability is explicitly not warranted — stated in the
  terms of use rather than implied.
