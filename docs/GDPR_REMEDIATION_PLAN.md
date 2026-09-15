# GDPR remediation — analysis, status and plan

> **Doc type:** Historical plan — a working handover, not a living spec. **Delete this file once the
> last open item is done**; what survives is the requirements in `docs/specs/`, the decisions in
> `docs/adr/` and the records in [`docs/privacy/`](privacy/README.md).
>
> Written 2026-09-15 by a Claude Code **cloud** session on branch `claude/quirky-bohr-fzd0ry`
> (PR [#1920](https://github.com/krt-profit/basetool/pull/1920)) for a **local** session to finish.

---

## 1. How to use this document

Sections 2–4 are context: what the cloud session could and could not do, and why the branch looks
the way it does. **Section 5 is the state of the branch.** **Sections 6–11 are the work that
remains**, one section per open item, each with the design already decided, the files to touch and
what "done" means.

Sections 12–13 are the two things only a local machine can do: the Knowledge Base and the
production host.

**Every decision in section 4 was made by @greluc during the cloud session.** Do not re-open them;
if one looks wrong, raise it rather than quietly choosing differently.

---

## 2. Why this exists

A GDPR gap analysis of the Basetool found that the published privacy policy is unusually thorough
and mostly accurate, but that several things it promises are not implemented, several stores of
personal data have no expiry at all, and the organisational records the GDPR requires a controller
to *hold* did not exist.

The findings, in the order they were prioritised:

1. **Refused registrations were kept forever** with no way to remove them. *(done)*
2. **Unread notifications were never deleted**, while the policy stated a flat 90-day retention.
   *(done)*
3. **Backups were absent from the privacy policy**, though they hold every category of personal data
   for up to six months. *(done)*
4. **Audit logs have no automatic retention** — REQ-AUDIT-004 is a manual admin purge only, and
   every row carries a player handle that outlives the account by design. *(half done, section 6)*
5. **No Art. 15/20 data export exists.** *(open, section 7)*
6. **No self-service deletion, and no guard on orphaned accounts.** *(open, section 8)*
7. **No link to the Keycloak account console**, so Art. 16 rectification has no in-app route.
   *(open, section 9)*
8. **No way to find a person's name across free-text fields**, so an erasure request from a
   non-registered third party cannot be served. *(open, section 10)*
9. **The Art. 30/32/28/12/33/35 records did not exist.** *(done)*

---

## 3. What the cloud session could not do — read this before judging the branch

The container had **only JDK 21**; the build requires the **JDK 25** toolchain. The network policy
denied `api.adoptium.net` and GitHub release downloads, so no JDK 25 could be obtained.

Consequences, all of which the local session simply does not have:

- **`./gradlew :backend:test`, `check` and any compile task could not run.** Nothing on this branch
  has been compiled locally. CI was the first execution of every line of it.
- **SpotBugs could not run** (it needs compiled classes).
- **`./gradlew spotlessApply` and `spotlessCheck` DO run on JDK 21** — only compile/test tasks need
  the toolchain. This was discovered late, after a CI failure; before that the session had verified
  Java formatting with a standalone `google-java-format` jar and missed that Spotless also reflows
  **Markdown** through flexmark, which pads table columns. That was the one CI failure on this
  branch (commit `ae16cb9`), fixed in `491fdaa`.
- **CI does not start by itself on this PR.** `ci.yml` triggers on `pull_request`, and the PR was
  opened through a GitHub App token, which GitHub deliberately does not let trigger workflows. Every
  run on this branch was started manually via `workflow_dispatch`. **Once you push from a local
  clone under your own identity, or close and reopen the PR, normal PR checks resume.** Until then,
  a green-looking PR may simply have had no CI at all — only CodeQL runs on its own.

> [!warning] The last push was made **deliberately without the lint gate**, at @greluc's instruction,
> because the work is incomplete. Do not assume the tip of this branch is gate-green. Run the full
> gate before doing anything else (section 14).

---

## 4. Decisions already made by @greluc — do not re-open

| #  |                Question                 |                                                                              Decision                                                                              |
|----|-----------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1  | Retention for refused registrations     | **90 days**, configurable. It doubles as the window in which an erroneous rejection can still be reopened (REQ-SEC-034).                                           |
| 2  | Retention for unread notifications      | **180 days** from `createdAt` (read rows stay at 90 days from `readAt`).                                                                                           |
| 3  | Retention for audit logs                | **24 months** (`P730D`).                                                                                                                                           |
| 4  | Minimum age                             | **18**, because KRT membership already requires 18 and only members get an account. Accepted that the terms hash changes and everyone must re-consent.             |
| 5  | Self-service deletion                   | **A request that lands in an admin queue**, not an immediate self-delete.                                                                                          |
| 6  | Art. 17 beyond the standard deletion    | **A checkbox in the request form** for also erasing audit and bank history. The admin decides it deliberately; it is never executed automatically.                 |
| 7  | Data export                             | **Every member exports their own, JSON + PDF.** **All personal data not relating to that member must be anonymised in the export.**                                |
| 8  | Personensuche (cross-field name search) | **ADMIN only.**                                                                                                                                                    |
| 9  | Backup target                           | **Runs on @greluc's own hardware** → not a processor, not a recipient to name.                                                                                     |
| 10 | Hosting processing agreement            | **Dated 2026-07-20 with Hetzner**, as host of the VM.                                                                                                              |
| 11 | Transactional e-mail channel            | **Stays off.** No privacy-policy section for it; the pre-activation checklist in `docs/privacy/processors.md` is what must be worked through if that ever changes. |

---

## 5. State of the branch

Three commits, pushed:

|  Commit   |                           Contents                            |                   CI                   |
|-----------|---------------------------------------------------------------|----------------------------------------|
| `ae16cb9` | Retention for refused registrations + unread notifications    | red (spotless markdown)                |
| `491fdaa` | The six `docs/privacy/` records + the spotless fix            | **green**                              |
| `d0d7d2f` | Backup disclosure, minimum-age clause, regenerated terms hash | dispatched, result unknown at handover |

Plus **uncommitted-at-the-time, now pushed without gates**: the half-finished audit retention sweep
(section 6).

### Done and CI-verified (green on `491fdaa`)

- `RejectedRegistrationRetentionService` + `RejectedRegistrationRetentionTask` — daily purge of
  `app_user`, `user_approval_event` and the Keycloak user 90 days after a refusal. Reuses
  `UserDeletionService`; database half commits before the external Keycloak delete (ADR-0111
  ordering); re-checks each candidate inside its own transaction so a concurrent reopen survives.
- `NotificationRetentionTask` extended to two windows; `deleteUnreadOlderThan` added.
- `REQ-SEC-057` (new), `REQ-NOTIF-009` (amended), `ADR-0178`, the ADR index row, CHANGELOG,
  `ScheduledJobStale` coverage, privacy-policy corrections in all three bundles.
- The six records under `docs/privacy/`.

### Done but CI result unknown at handover (`d0d7d2f`)

- Backup retention disclosed in `privacy.p_2_2` (DE/EN/base), age note in `privacy.p_3_4_1`.
- `terms.p_3_4` minimum-age clause in the backend bundles, `terms.last_updated` bumped, and
  **`terms-version.properties` regenerated by hand**: `07d8b5ff678b80a2` → `df1f9b31581b0d0d`.
  The digest was computed with a Python reproduction of `termsVersionDigest` that was first verified
  to reproduce the *old* committed hash exactly. `TermsVersionParityTest` re-derives it in CI, so a
  mistake here fails the build rather than shipping a gate that never re-prompts. **Confirm that
  test passed.**

---

## 6. Open — audit-log retention (half implemented)

**This is the one place where the branch contains unfinished code.** The sweep was written; nothing
around it was.

### Already on the branch

- `backend/.../service/AuditRetentionService.java` — loops `AuditDomain.values()`, asks
  `existsByDomainAndOccurredAtBefore` first, then delegates to the existing
  `AuditService.purgeBefore(domain, cutoff)`; then the same for `BankAuditService.purgeBefore`.
  Per-domain try/catch so one area cannot abort the run.
- `backend/.../task/AuditRetentionTask.java` — daily, gated on `app.audit.retention.enabled`,
  `max-age` default `P730D`, wrapped in `TaskMetrics.recordCounting`.
- `ScheduledJob.AUDIT_RETENTION("audit_retention")`.
- `existsByDomainAndOccurredAtBefore` / `existsByOccurredAtBefore` on the two audit repositories.
- `app.audit.retention.*` in `application.yml`, disabled in `application-test.yml`.
- `audit_retention` added to the `ScheduledJobStale` alert.

**Why the exists-check is there, so nobody removes it:** `purgeBefore` writes its `*_AUDIT_PURGED`
marker unconditionally. That is right for an admin's deliberate purge and wrong for a daily job,
which would otherwise mint ten marker rows a day forever — growing the very table the sweep exists
to bound. Asking first leaves the manual purge's semantics untouched.

### Still to do

1. **Tests** — `AuditRetentionServiceTest` (purges each domain with rows; skips domains without;
   one failing domain does not abort the run; the bank trail is included) and
   `AuditRetentionTaskTest` (cutoff is `now - max-age`; failures are swallowed). Mirror
   `RejectedRegistrationRetentionServiceTest` for structure.
2. **`REQ-AUDIT-006`** in `docs/specs/audit.md`. **The number is free and already claimed by the
   code**, but the spec section does not exist yet — the spec currently defines 001–005 only. Write
   it, and cross-reference it from REQ-AUDIT-004 so the manual purge and the sweep read as one story.
3. **ADR-0179** (next free number). The decision is a deliberate departure from ADR-0038, which made
   the purge an admin act because an audit trail is evidence. Record why a *ceiling* is different
   from a *deletion*: the admin purge stays for a shorter deliberate cutoff, the sweep only stops
   "indefinitely" from being the default. Add the ADR index row in `docs/adr/README.md`.
4. **Privacy policy** — `privacy.p_2_2` currently says the audit and bank logs "bleiben … bestehen"
   with no duration, which now understates what the code does. State the 24 months, in all three
   bundles, and keep DE umlauts as `\uXXXX`.
5. **`docs/privacy/processing-activities.md`** — the retention table row for the audit trail still
   reads "See REQ-AUDIT-004 / the audit retention sweep". Replace with **24 months** and the
   configuration key.
6. **CHANGELOG** under `[Unreleased]`.
7. Consider whether the admin purge screen should mention that a sweep also runs — an admin who sees
   rows disappear without having purged should not have to guess why.

---

## 7. Open — the Art. 15/20 data export

The largest remaining item, and the one every access request currently costs manual work across
~25 tables.

### Design

- **Endpoint:** `GET /api/v1/me/export` (JSON) and a PDF rendering. Self-service for every
  authenticated member; an admin variant for a deleted account or a third party.
- **Two rights, one export.** Art. 15 covers everything; Art. 20 covers only what the person
  provided themselves under Art. 6(1)(a)/(b). **Mark each section with its legal basis** so the
  portable subset is identifiable without re-deriving it — `docs/privacy/data-subject-requests.md`
  already tells the reader that the export does this.
- **Third-party data must be anonymised** (decision 7). This is the hard part and the reason the
  export cannot be a naive dump:
  - Free-text fields (notes, comments, booking reasons, mission descriptions) may name other
    people. They belong in the export as *the requester's own data* where the requester wrote them,
    but any other member's handle inside them must be replaced.
  - Rows that pair the requester with a counterparty (bank postings, handovers, material-exchange
    interests, mission rosters) must show the requester's side and a placeholder for the other.
  - The audit trail contains rows where the requester is the *target* and someone else the *actor* —
    the actor handle is another person's data.
  - Suggested approach: a single `anonymise(text, selfHandle)` seam plus per-section projections
    that never select another user's handle column at all. **Prefer not selecting over scrubbing
    after the fact** — a redaction that has to find names in prose will miss some.
- **Scope of tables:** derive from `REQ-DATA-008`'s list — it already enumerates every FK into
  `app_user`. Add `terms_acceptance`, `user_approval_event`, `org_unit_membership`,
  `org_chart_position`, and the audit rows where the requester is actor or target.
- **Out of scope, and already documented as such** in `docs/privacy/data-subject-requests.md`:
  platform logs/metrics/traces, backups, and Keycloak's own record. Keep that document and the
  implementation in agreement.

### Deliverables

New `REQ-SEC-058`; an ADR if the anonymisation strategy is non-obvious (it probably is); the
endpoint with `@PreAuthorize`, SpringDoc annotations and `openapi.json` regenerated; a profile-page
button; DE/EN strings; tests including one that **proves another member's handle does not appear**
in an export; CHANGELOG; and a line in `processing-activities.md`.

---

## 8. Open — self-service deletion and the orphaned-account guard

Two related gaps.

### Deletion request (decision 5 + 6)

- **Profile → "Konto löschen"** raises a request; an admin executes it. Model it on the existing
  registration-approval queue rather than inventing a second pattern.
- The form carries **a checkbox** asking whether audit and bank history should also be erased
  (decision 6). The checkbox records a *wish*; the admin decides it and the reasoning is recorded.
  `docs/privacy/data-subject-requests.md` describes how to weigh it.
- Requires a new status or a small `deletion_request` table + Flyway migration, an admin surface,
  audit events for raise/withdraw/decide, notifications, and i18n.
- **Do not make it delete on the spot.** The deletion removes the Keycloak account and reassigns
  shared records; a mis-click must not be irreversible.

### Orphaned accounts

An admin deletes in the Keycloak console first; the member list's delete button only appears once
the roster sync has flipped `in_keycloak = false`. **If step two is forgotten, the row keeps the
e-mail address, handle, Discord id and description indefinitely, and nothing notices.**

Add a gauge (`basetool_users_pending_deletion` or similar) counting `in_keycloak = false` rows and
the age of the oldest, plus an alert past a threshold. `BusinessMetricsCollector` is the place;
`RegistrationApprovalOverdue` is the pattern to copy.

New `REQ-SEC-059` (and an OBS requirement if the metric warrants one).

---

## 9. Open — link the Keycloak account console

`profile.security.hint` tells the member that password, e-mail and two-factor are managed centrally
in Keycloak, and links nowhere. Art. 16 rectification therefore has no in-app route.

Add the account-console link to the profile page. The realm base URL is already configured for the
OAuth2 client — derive it rather than adding a new property if possible, and open it in a new tab.
Small, self-contained, and the cheapest item on this list.

---

## 10. Open — admin Personensuche across free-text fields

A name can sit where no foreign key points: an external mission participant (`guest_name`), a
job-order handover recipient, an org-chart placeholder, a note, a booking reason. An erasure or
rectification request from such a person cannot be served today, because nothing finds the entries.

- **ADMIN only** (decision 8).
- Search a name across every free-text and placeholder surface, listing each hit with its area, a
  link to the record, and enough context to decide.
- `docs/privacy/data-subject-requests.md` already points at this feature as **Admin →
  Personensuche** and tells the reader to use it for *every* Art. 16/17 request. **Either build it
  under that name or correct that document** — it currently describes a screen that does not exist.

New `REQ-SEC-060`. Mind the cost: this is a multi-table `ILIKE` search; bound it and keep it off any
hot path.

---

## 11. Numbering, so two sessions do not collide

- Next free **REQ-SEC**: `058` (057 is the refused-registration retention).
- Next free **REQ-AUDIT**: `006` — **claimed by the code on this branch, spec section not yet
  written** (section 6.2).
- Next free **ADR**: `0179` (0178 is on this branch).
- `REQ-NOTIF-009` was amended, not renumbered.

---

## 12. Knowledge Base — the hard rule, not yet honoured

The cloud session could not reach the vault: it is local to @greluc's machine and is not a
submodule, and `krt-profit/basetool-knowledge` is not reachable from the cloud environment. **The
Knowledge Base has therefore not been read and not been updated**, which by the project's own rule
makes this work incomplete until a local session fixes it.

What needs to move into the vault, at minimum:

- **Retention** — a note that gathers every window in one place (the table in
  `docs/privacy/processing-activities.md` is the source): refused registrations 90 d, read
  notifications 90 d, unread 180 d, audit 24 months, logs 31 d, traces 14 d, metrics 180 d, sessions
  30 d, backups ~6 months.
- **Scheduled jobs** — two new ones (`rejected_registration_retention`, `audit_retention`), both on
  `ScheduledJobStale`.
- **Registration lifecycle** — `REJECTED` is now terminal *with an expiry*, and the reopen window
  (REQ-SEC-034) is bounded by the same 90 days.
- **Deletion** — REQ-DATA-008's split now has a second caller (the retention sweep), and the
  `USER_DELETED` audit row for it carries actor `system`.
- **Data protection** — a new note pointing at `docs/privacy/`, so the vault does not duplicate the
  records but knows they exist and where.
- **Terms consent** — the hash changed; the vault should say that a wording change re-prompts
  everyone and pauses ingest until they accept.

Also reconcile the vault against anything this work proved wrong, per the "never leave a known
contradiction standing" rule.

---

## 13. Production host — one verification only

**Keycloak's own event store.** `docs/KEYCLOAK_HARDENING_RUNBOOK.md` records the state as
`eventsEnabled: false`, `adminEventsEnabled: false`, no expirations, with a planned step enabling
both at 30 days (`eventsExpiration=2592000`). Whether that step was ever executed could not be
checked from the cloud.

- If **still off**: `docs/privacy/processing-activities.md` activity A7 is already correct; just
  remove the "to be verified" caveat.
- If **enabled**: add Keycloak events to the retention table with their expiry, and add a sentence
  to the privacy policy's security-monitoring section — it currently describes the Keycloak *log
  stream* (31 days in Loki), not the realm's own event store.

Reading the realm's events config is a non-mutating inspection and needs no approval. Changing it
does.

---

## 14. Before merging

1. **Run the full gate**, which the last push deliberately skipped:
   `./gradlew spotlessApply` then `./gradlew check`, plus the frontend asset linters and
   `:frontend:typecheckJs` (see the `lint-gate` skill). Nothing on this branch has been compiled or
   tested locally.
2. **Confirm `TermsVersionParityTest` passes** — it is what proves the hand-computed terms hash is
   right.
3. **Confirm the three privacy bundles stayed consistent** (base, `_de`, `_en`) and that DE umlauts
   are `\uXXXX` everywhere.
4. **Re-read the privacy policy end to end** against the retention table in
   `processing-activities.md`. Those two must agree exactly; the numbers appear in prose in the
   policy and in configuration in the code.
5. **Deployment note, already in the PR body and worth repeating:** the first run after deploy
   purges every registration refused more than 90 days ago, every unread notification older than 180
   days, and — once section 6 ships — every audit row older than 24 months. All irreversible. If
   anything should be kept, set the corresponding `*_ENABLED=false` before deploying.
6. Decide whether this branch merges as one PR or is split. It is already large, and sections 7, 8
   and 10 are each big enough to stand alone.

---

## 15. What this work does not claim

- **It is not legal advice.** The records in `docs/privacy/` are engineering documents, written to
  be useful to a lawyer or a supervisory authority rather than to replace one. A professional review
  of the Art. 30 record, the breach runbook and the DPIA assessment would be worth having.
- **The 24-month and 90-day windows are judgements, not derivations.** No statute sets them.
- **The anonymisation requirement in the export (decision 7) is the hardest thing left** and the
  easiest to get subtly wrong. Treat "no other member's handle appears in an export" as a test, not
  as a review comment.

