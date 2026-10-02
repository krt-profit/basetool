# Processors and recipients (Art. 28 GDPR)

> **Doc type:** Living document — kept in sync with `main`. Last reviewed: 2026-10-02.

Every third party that can reach personal data, what role it plays, and what has to be in place for
it. A party is a **processor** when it processes on the controller's instructions (Art. 28 — a data
processing agreement is required), an **independent controller** when it decides its own purposes
(no processing agreement; the transfer is disclosed instead), and neither when it never receives
personal data at all.

The distinction matters practically: adding a processor is a paperwork obligation, adding an
independent controller is a disclosure obligation, and getting the two the wrong way round produces
either a missing contract or a missing paragraph in the privacy policy.

---

## Processors (Art. 28 — processing agreement required)

### Hosting provider

- **Role:** processor. Operates the infrastructure the whole application runs on.
- **Reaches:** everything, by the nature of hosting.
- **Location:** Germany (EU) — no third-country transfer.
- **Named in the privacy policy:** yes, with address, in the *External hosting* section.
- **Processing agreement:** in place — **data processing agreement dated 2026-07-20 with Hetzner as
  the host of the virtual machine**. The executed copy is filed outside this repository; only its
  existence and date are recorded here.
- **Last confirmed:** 2026-09-15. On 2026-09-22 production moved to a new virtual machine (Rocky
  Linux 10, rootless Podman) **at the same provider**, so neither the processor nor the location
  changed.

### Outbound e-mail relay

- **Role:** processor, **once the transactional e-mail channel is switched on**.
- **Status: not active.** The channel exists in code (REQ-NOTIF-013 and its two consumers) but no
  SMTP host is configured (the backend's `SPRING_MAIL_HOST` defaults to empty in
  `quadlet/env.d/backend.env.tmpl`), so `MailService` drops every message and no personal data
  leaves this way. The `MailDroppedConfigDrift` alert is correspondingly silent today and starts protecting the
  moment mail is enabled.
- **What activation would send:** the approval or refusal decision to the applicant's own e-mail
  address, and a notice to every admin's e-mail address **carrying the new registrant's handle**.
- **Before setting `SPRING_MAIL_HOST` in production, all three must happen:**
  1. a processing agreement with the relay provider,
  2. the relay named as a recipient in the privacy policy,
  3. the privacy policy's notification section corrected — it currently states that notifications are
     *not* dispatched by e-mail or to third parties, which is true only while the channel is off.
- **Last confirmed inactive:** 2026-09-15, by the controller. Activation is **not currently
  planned**, which is why the privacy policy carries no e-mail section: writing one for a channel
  that does not run would describe a processing that does not happen. The checklist above is what
  turns that from an omission into a deferred decision.

---

## Independent controllers (no processing agreement; disclosure instead)

### Discord

- **Role:** independent controller. Members may authenticate with a Discord account; Discord decides
  its own purposes for the data it holds.
- **Exchanged:** the authentication handshake — Discord returns the account id, username, possibly an
  e-mail address and an avatar; the guild membership and roles are read with the member's
  authorisation to verify eligibility. Of this the tool **persists only** the Discord account id and
  the guild nickname.
- **Third country:** the EU entity is the contracting party, with a US parent involved in the
  service. The transfer, and the mechanism the provider relies on, are disclosed in the privacy
  policy's Discord section.
- **Named in the privacy policy:** yes, with both entities and a link to Discord's own policy.

### GitHub

- **Role:** independent controller, and **not a recipient of anything from the tool**. The Android
  app is distributed through GitHub releases, and the app checks for a newer release against the
  GitHub API directly from the member's device. The tool itself sends GitHub no personal data.
- **Named in the privacy policy:** yes, in the Android-app section, with a link to GitHub's privacy
  statement.

### Providers of approved exchange clients

- **Role:** **not a processor**, and no joint controllership. The controller gives a client's
  provider no instructions and the provider processes nothing on the controller's behalf: the
  member chooses to connect the client, which runs on the member's own device and receives the
  member's own data at the member's request (REQ-XCH-002, REQ-XCH-009). The controller defines the
  interface and approves the client, but decides nothing about the client's own processing. If the
  provider itself receives data — a cloud backup, a sync service of its own — it does so as an
  **independent controller** under its own privacy statement, which the approval requires to be
  published (owner decision 2026-09-28).
- **Reaches:** within the capabilities the member consented to — the member's own blueprints with
  their note, warehouse stock (personal and shared) and ships, the anonymised org demand of the member's units, catalogue
  data. Never another member's data, the e-mail address or the display name.
- **Third country:** none through the tool; the data goes to the member's device. A provider's own
  onward processing is disclosed in its privacy statement.
- **Named in the privacy policy:** as a category, in the section „Verbundene Anwendungen", with the
  public list [`docs/legal/approved-clients.md`](../legal/approved-clients.md) linked. The individual
  clients are named on that list, so a new approval changes neither the policy nor the consent.
- The SC Extractor, although listed there, is the controller's own software and not a third party;
  its processing is the policy's section „Begleitende Desktop-Anwendung (Datenimport)".

---

## Not recipients

Listed so the question does not get re-opened every review:

- **UEX and the Star Citizen Wiki** — outbound catalogue synchronisation. The tool *reads* game data
  from them; no personal data is sent.
- **The container registry and the CI provider** (GitHub Container Registry, GitHub Actions) —
  receive source and build artifacts, not personal data from the production database.
- **Alert delivery** — Alertmanager sends alerts by e-mail through an SMTP relay, to a Discord
  webhook, and a heartbeat to an external dead-man's-switch service
  (`monitoring/alertmanager/alertmanager.yml.tmpl`). Alert labels and annotations carry no personal
  data (REQ-OBS-006/-007), so none of the three receives any.
- **The certificate authority and the signature transparency log** — the ACME client and `cosign`
  exchange certificates and signatures, never member data.
- **The monitoring stack** (Prometheus, Loki, Tempo, Grafana, Alertmanager) — self-hosted alongside
  the application, not a third party. It does hold personal data (IP addresses in the log streams),
  which is why its retention windows are in the record of processing activities.
- **The backup storage target** (Nextcloud over an rclone WebDAV remote) — **operated by the
  controller on their own hardware**, confirmed 2026-09-15. No third party receives the repository,
  so there is no processing agreement to hold and nothing to name as a recipient in the privacy
  policy. The independence the off-site property requires is satisfied by separate hardware, not by a
  separate organisation (REQ-OPS-008). The blobs are client-side encrypted regardless, which is what
  makes the arrangement robust to the target ever moving.
  **If the target is ever moved to a hosted Nextcloud, it becomes a processor** and all three
  obligations in the checklist below apply.

---

## Review checklist

Run through this whenever an integration is added, changed or removed — the trigger is a new
outbound call or a new place data can leave, not a calendar date:

- [ ] Is the new party a processor, an independent controller, or neither?
- [ ] If a processor: is there an executed processing agreement, and is it filed?
- [ ] Is it named in the privacy policy's recipients section?
- [ ] Does a third-country transfer occur, and on what mechanism?
- [ ] Is it in the record of processing activities' recipients table?
- [ ] Does anything it receives need a retention window of its own?
