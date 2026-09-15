# Processors and recipients (Art. 28 GDPR)

> **Doc type:** Living document — kept in sync with `main`. Last reviewed: 2026-09-15.

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
- **Last confirmed:** 2026-09-15.

### Outbound e-mail relay

- **Role:** processor, **once the transactional e-mail channel is switched on**.
- **Status: not active.** The channel exists in code (REQ-NOTIF-013 and its two consumers) but no
  SMTP host is configured, so `MailService` drops every message and no personal data leaves this
  way. The `MailDroppedConfigDrift` alert is correspondingly silent today and starts protecting the
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

---

## Not recipients

Listed so the question does not get re-opened every review:

- **UEX and the Star Citizen Wiki** — outbound catalogue synchronisation. The tool *reads* game data
  from them; no personal data is sent.
- **The container registry and the CI provider** — receive source and build artifacts, not personal
  data from the production database.
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

