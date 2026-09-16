# Record of processing activities (Art. 30 GDPR)

> **Doc type:** Living document — kept in sync with `main`. Last reviewed: 2026-09-15.

Art. 30 GDPR requires a controller to maintain a record of its processing activities. The small-
organisation exemption in **Art. 30(5) does not apply**: it is available only where processing is
*occasional*, and this processing is continuous — a squadron-management tool runs every day and
holds member data for as long as the membership lasts.

**Controller:** the natural person named in the [Impressum](../../frontend/src/main/resources/templates/impressum.html)
and in section 2 of the privacy policy. Not repeated here — the address is published where the law
requires it and duplicating it into further files spreads personal data for no gain.

**Data protection officer:** none appointed; see
[`dpia-threshold-assessment.md`](dpia-threshold-assessment.md) for why none is required.

---

## Categories of data subjects

|              Group               |                                                  How they enter the system                                                  |
|----------------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| **Members**                      | Register via Keycloak (credentials or Discord), approved by an admin                                                        |
| **Applicants**                   | Registered but not yet decided (`PENDING`) or refused (`REJECTED`)                                                          |
| **Non-registered third parties** | Named in free-text fields by a member: external mission participants, job-order handover recipients, org-chart placeholders |
| **Visitors**                     | Reach the public landing, Impressum, terms and privacy pages without an account                                             |

---

## Processing activities

### A1 — Account and authentication

- **Purpose:** operate a member account; authenticate; decide on admission.
- **Data:** e-mail address, username / player handle (Star Citizen handle), optional self-written
  description, rank, join date; for Discord logins the Discord account id and the guild nickname;
  approval status with the deciding admin and timestamp, and the free-text reason on a refusal.
  **No first or last name is collected.**
- **Legal basis:** Art. 6(1)(b) (providing the requested service) and Art. 6(1)(f) (operating the
  organisation; secure sign-in and membership verification for the Discord path).
- **Where:** `app_user`, `user_approval_event`, and the Keycloak realm.
- **Retention:** for the duration of the account; a refused registration is purged automatically
  (REQ-SEC-057). See [Retention](#retention).

### A2 — Squadron operations

- **Purpose:** plan and settle missions, operations, warehouse stock, job orders, refinery orders,
  the hangar, and the personal inventory.
- **Data:** everything a member creates or that is attributed to them, including **free-text fields**
  (notes, comments, booking reasons, mission descriptions) which may contain personal data about
  others if a member enters it there.
- **Legal basis:** Art. 6(1)(b) and Art. 6(1)(f).
- **Where:** the operational tables — `inventory_item`, `ship`, `mission*`, `job_order*`,
  `refinery_order`, `personal_inventory_item`, `personal_blueprint`, `material_*`.
- **Retention:** for the duration of the account; on deletion the account-owned rows are purged and
  the shared aggregates are reassigned (REQ-DATA-008).

> [!note] Added 2026-09-15 — two free-text handles nothing had named
> `job_order_handover.recipient_handle` and `job_order_item_handover.recipient_handle` record who
> collected material on a job order. They are **typed in by hand**, so they can name somebody with
> no account at all, and they **survive the deletion of the account** they describe — which this
> record did not say and the privacy policy did not disclose. Both are now named in
> `privacy.p_2_2`, reachable by the admin name search (REQ-SEC-060) and covered by a granted Art. 17
> erasure (REQ-SEC-062).

### A3 — Banking and settlement

- **Purpose:** keep the organisation's in-game ledger auditable.
- **Data:** postings and transactions including the counterparty and the **player handle used at the
  time of booking**, retained as a tamper-evident record.
- **Legal basis:** Art. 6(1)(f) — traceability and integrity of the organisation's financial records.
- **Where:** `bank_*`.
- **Retention:** the link to the account is cleared on deletion; the historic handle stays in the
  booking history. See [Retention](#retention).

### A4 — Activity audit trail

- **Purpose:** make every state-changing action in the audited areas attributable (REQ-AUDIT-001).
- **Data:** timestamp, acting user id **plus a denormalised handle snapshot that deliberately
  survives the user's deletion**, event type, affected subject, and a bounded details payload that
  carries ids and counts only — never user free text.
- **Legal basis:** Art. 6(1)(f).
- **Where:** `audit_event`, `bank_audit_event`.
- **Retention:** bounded by an automatic sweep. See [Retention](#retention).

### A5 — Notifications

- **Purpose:** tell a member about events relevant to them.
- **Data:** recipient, type, read status, and the parameters needed to render the message, which can
  include the handle of the member who triggered it.
- **Legal basis:** Art. 6(1)(b) / Art. 6(1)(f).
- **Where:** `notification`, `notification_rule_selector`.
- **Retention:** two windows, both finite (REQ-NOTIF-009).

### A6 — Consent to the terms of use

- **Purpose:** evidence of who accepted which wording.
- **Data:** user id, terms version (a content hash), acceptance timestamp.
- **Legal basis:** Art. 6(1)(b) and Art. 6(1)(f) — evidence that the contract terms were accepted.
- **Where:** `terms_acceptance`.
- **Retention:** for the duration of the account.

### A7 — Operation of the platform: logs, metrics, traces

- **Purpose:** stability, security, and abuse defence.
- **Data:** per request one access-log line with method, path, status and duration plus a pseudonymous
  account id and context ids; IP addresses in the edge-proxy, host-authentication and Keycloak log
  streams; **no names, e-mail addresses or tokens — these are masked at the logging layer**
  (`PiiMaskingPatternLayout` / `PiiMaskingLogstashEncoder`). Metrics are aggregate counters with no
  personal data. Traces carry the path with ids reduced to placeholders and no account id
  (`ObservationPrivacyFilter`).
- **Legal basis:** Art. 6(1)(f).
- **Keycloak's own event store** (login and admin events) is a separate record from the log stream
  above, and it is **enabled in production** — verified against the realm on 2026-09-16:
  `events_enabled` and `admin_events_enabled` both true, both expiring after **30 days**
  (`events_expiration` / the `adminEventsExpiration` realm attribute = 2592000 s), and
  `admin_events_details_enabled` **false**, so an admin event records *that* a change was made and
  not the changed representation. It is disclosed in the privacy policy's security-monitoring
  section and its expiry is in the table below.

  > An earlier revision of this entry said the store was disabled and that this had to be verified
  > on the host. The hardening step had in fact been carried out; this line is the correction.

- **Retention:** see [Retention](#retention).

### A9 — Handling data-subject requests

- **Purpose:** serve the rights under Art. 15–21 and be able to show that they were served
  (REQ-SEC-058, REQ-SEC-060, REQ-SEC-061).
- **Data:** the erasure request itself (who raised it, when, whether they also asked for the handle
  snapshots to be anonymised, the deciding admin, the decision and its recorded reason); an audit
  event per export, per person search and per state change of a request.
- **Legal basis:** Art. 6(1)(c) — a legal obligation of the controller. Serving these rights is not
  optional, and neither is being able to demonstrate it (Art. 5(2)).
- **Where:** `deletion_request`; `audit_event` rows of the `PERSONAL_DATA_EXPORTED`,
  `PERSON_SEARCH_PERFORMED`, `ACCOUNT_DELETION_*` and `HANDLE_SNAPSHOTS_ANONYMISED` types.
- **Retention:** the request row goes with the account when the request is carried out
  (`ON DELETE CASCADE`) — keeping a record that somebody asked to be forgotten would defeat the
  erasure. The audit events fall under the 24-month ceiling like every other row.
- **Data minimisation worth recording here:** a person search records the **length** of the search
  term, never the term. A trail of every name an admin ever searched for would be a second store of
  exactly the data these rights exist to remove.
- **Kept outside this system:** the request file itself — when it arrived, from whom, what was
  asked, what was answered — per the procedure in
  [`data-subject-requests.md`](data-subject-requests.md). It is personal data and does not belong in
  a git repository.

### A8 — Backups

- **Purpose:** restore the service after data loss.
- **Data:** the full restore surface, which includes both databases — therefore **all** of the
  personal data in A1–A6, including the Keycloak user database.
- **Legal basis:** Art. 6(1)(f) — availability and integrity, itself an Art. 32 obligation.
- **Protection:** client-side encrypted before it leaves the host (REQ-OPS-008); the storage target
  only ever receives encrypted blobs. The target is operated by the controller on their own hardware,
  so no third party receives the backup at all — see [`processors.md`](processors.md).
- **Retention:** GFS — 7 daily, 4 weekly, 6 monthly, so **an erased record can persist in backups for
  up to roughly six months**. Backups are not searched or edited to serve an erasure request; the
  data leaves as the snapshots expire. If a backup is ever restored, the erasures that had been
  applied since that snapshot are re-applied before the system is returned to service. Log data is
  deliberately excluded from backups so its 31-day window cannot be silently extended (ADR-0072).

---

## Retention

The single table every other statement about retention must agree with.

|                  What                   |                                     Window                                     |                                Enforced by                                 |                      Configuration                      |
|-----------------------------------------|--------------------------------------------------------------------------------|----------------------------------------------------------------------------|---------------------------------------------------------|
| Account and its owned data              | Life of the account                                                            | Deletion purges, reassigns or unlinks per REQ-DATA-008                     | —                                                       |
| Refused registration                    | **90 days** after the refusal                                                  | `RejectedRegistrationRetentionTask` (REQ-SEC-057)                          | `app.registrations.rejected-retention.max-age`          |
| Read notifications                      | **90 days** after being read                                                   | `NotificationRetentionTask` (REQ-NOTIF-009)                                | `app.notifications.retention.max-age`                   |
| Unread notifications                    | **180 days** after being raised                                                | `NotificationRetentionTask` (REQ-NOTIF-009)                                | `app.notifications.retention.unread-max-age`            |
| Activity + bank audit trail             | **24 months** after the recorded activity                                      | `AuditRetentionTask` (REQ-AUDIT-006), plus the admin purge (REQ-AUDIT-004) | `app.audit.retention.max-age`                           |
| Application + platform logs             | **31 days**                                                                    | Loki compactor retention                                                   | `retention_period` in `monitoring/loki/loki-config.yml` |
| Traces                                  | **14 days**                                                                    | Tempo retention                                                            | `monitoring/`                                           |
| Keycloak realm events (login + admin)   | **30 days**                                                                    | Keycloak realm event expiration                                            | `events/config` + the `adminEventsExpiration` attribute |
| Metrics (no personal data)              | 180 days                                                                       | Prometheus TSDB retention                                                  | `--storage.tsdb.retention.time`                         |
| Sessions                                | 30 days idle for an authenticated session                                      | Spring Session / Redis (REQ-SEC-025)                                       | `app.session.authenticated-timeout`                     |
| Backups                                 | Up to ~6 months (7d/4w/6m)                                                     | `restic forget` (REQ-OPS-008)                                              | `scripts/backup.sh`                                     |
| Bank booking history (handle snapshots) | Kept beyond account deletion under Art. 6(1)(f), subject to an Art. 17 request | —                                                                          | —                                                       |
| Audit trail handle snapshots            | Outlive the account, but only to the 24-month ceiling above                    | `AuditRetentionTask` (REQ-AUDIT-006)                                       | `app.audit.retention.max-age`                           |
| Erasure requests (`deletion_request`)   | Life of the account; removed with it when carried out                          | `ON DELETE CASCADE` (REQ-SEC-061)                                          | —                                                       |

**Every number in this table is also a sentence in the privacy policy.** Changing one without the
other publishes a false statement — see the note in [`README.md`](README.md).

---

## Recipients

Full detail, including the role each party plays, in [`processors.md`](processors.md).

|             Recipient             |          Role          |                         What reaches them                         |
|-----------------------------------|------------------------|-------------------------------------------------------------------|
| Hosting provider                  | Processor              | Everything, as the operator of the infrastructure                 |
| Discord                           | Independent controller | The authentication exchange for members who use the Discord login |
| Other members of the organisation | —                      | The data the visibility rules expose inside the tool              |

**No transfer to a third country takes place through the tool itself.** The Discord login involves a
US parent company; that transfer is described in the privacy policy with the mechanism the provider
relies on.

---

## Third parties who are not users

A member can type another person's name into a free-text field — an external mission participant, a
handover recipient, an org-chart placeholder. Those people never see this tool, so the Art. 14
information obligation cannot be discharged the usual way. The privacy policy names this category and
points them at the controller. Operationally, an admin can locate such entries across the free-text
surfaces so a rectification or erasure request from one of them can actually be served; the procedure
is in [`data-subject-requests.md`](data-subject-requests.md).
