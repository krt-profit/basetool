# Handling a data-subject request (Art. 12, 15–21 GDPR)

> **Doc type:** Living document — kept in sync with `main`. Last reviewed: 2026-09-15.

A request can arrive by any channel and in any wording. Somebody writing *"please delete my
account"* has exercised Art. 17 whether or not they cite it, and the clock starts when the message
arrives — not when it is recognised as a request.

---

## The deadline

**One month from receipt** (Art. 12(3)). Extendable by two further months for complex or numerous
requests, but only if the data subject is told **within the first month** that it is being extended
and why. A silent overrun is itself a breach of Art. 12.

Record for each request: when it arrived, who it came from, what it asked for, what was done, when
the reply went out. Keep that record **outside this repository** — it is personal data.

---

## Step 1 — Identify the requester (Art. 12(6))

Only where there is **reasonable doubt** about identity may further information be requested. A
request from the e-mail address on file, or made from inside a logged-in session, is normally proof
enough; demanding an ID document by default is itself a data-protection problem.

- **Member, logged in or writing from the address on file** → proceed.
- **Member, writing from an unknown address** → reply to the address on file and ask them to confirm
  from there.
- **A non-registered third party** whose name a member entered into a free-text field → they cannot
  authenticate at all, because they have no account. Match on what they can state about the entry and
  weigh the risk of disclosing another person's data if the match is wrong; when in doubt, ask for
  the detail that would confirm it rather than refusing.

**Never** hand out data on a weaker identity check than it took to create it.

---

## Step 2 — Serve the right

### Art. 15 — Access

The member exports their own data in the application: **Profile → Meine Daten exportieren**. The
export covers every category the tool holds about them. For the categories it deliberately leaves
out, and why, see [the export's limits](#what-the-export-does-not-contain).

If the requester cannot use the application (deleted account, or a non-registered third party), an
admin produces the same content and sends it through a channel the requester controls.

**Art. 15(4) — the rights of others.** Free-text fields can name third parties. Before releasing free
text verbatim, read it: if a note names another member, redact that name rather than withholding the
whole entry. The right is to a copy of *their* data, not to everything that mentions them.

### Art. 16 — Rectification

- **E-mail address, password, two-factor** → the member changes these themselves in the account
  console, linked from the profile page.
- **Display name, rank, join date, org-unit membership** → an admin edits them in the member
  management screen.
- **A free-text entry someone else wrote** → an admin edits the entry. Use the
  [name search](#finding-every-mention-of-a-person) to find them all; a rectification that fixes one
  of four occurrences is not a rectification.

### Art. 17 — Erasure

A member requests deletion in the application: **Profile → Konto löschen**. That raises a request an
admin acts on; it does not delete the account on the spot, because the deletion also removes the
Keycloak account and reassigns shared records, and because a mis-click must not be irreversible.

What deletion does, in detail, is REQ-DATA-008, and the privacy policy states it in plain language.
In short:

- **Purged**: warehouse stock, hangar, personal inventory and blueprints with their free-text notes,
  notifications, notification rules, promotion evaluations, material-exchange offers and interests,
  account permissions, their own approval records.
- **Reassigned to an admin**: missions and refinery orders — operational history that must stay
  readable.
- **Unlinked**: mission participation survives as *"Gelöschter Nutzer"* with no name; the
  material-claim stamp is nulled.
- **Kept**: the audit trail and bank booking history, including the **handle used at the time**,
  under Art. 6(1)(f).

**If the requester asks for the kept records to go as well**, that is a legitimate Art. 17 request
and the privacy policy already says so. Weigh it: the interest in an auditable ledger is real but not
automatically overriding, it weakens as the records age, and a request that names a specific booking
is easier to grant than one asking to rewrite the ledger. Decide it explicitly, record the reasoning,
and tell the requester the outcome either way — Art. 12(4) requires telling them **why** if the
answer is no, together with their right to complain and to a judicial remedy.

**Do not confuse an erasure request with leaving the organisation.** A departing member whose account
is deleted for organisational reasons gets the standard deletion; someone exercising Art. 17 is
entitled to the reasoning above.

### Art. 18 — Restriction

There is no "processing is suspended" state in the data model, so restriction is handled
organisationally: **disable the Keycloak account** (the member can no longer sign in, so no further
processing happens through the application) and record the restriction and its scope in the request
file. Do not delete anything — restriction exists precisely to preserve data while a dispute is
resolved. Lift it, and tell the data subject before lifting it, as Art. 18(3) requires.

### Art. 20 — Portability

Narrower than Art. 15: it covers only data the person **provided themselves**, processed by automated
means, on the basis of consent or contract (Art. 6(1)(a)/(b)). It does **not** cover data derived by
the organisation or retained under legitimate interest — so the audit trail, the bank ledger, the
handle snapshots and the approval records are outside it.

The export marks each section with its basis so the portable subset is identifiable without
re-deriving it. The format is machine-readable JSON, which satisfies "structured, commonly used and
machine-readable".

### Art. 21 — Objection

Applies to the processing based on Art. 6(1)(f): the visibility of a member's data to others inside
the organisation, the audit trail, the retained booking handles, the security logging.

There is no per-feature opt-out, and adding one would in most cases empty the feature — a mission
roster that hides a participant from the other participants does not work. So an objection is
decided case by case: assess whether compelling legitimate grounds override the interests of the data
subject, and if they do not, stop that processing. In practice the honest answer for a member who
objects to being visible to their own squadron is usually that the tool cannot serve them without it,
which makes deletion the remedy — but that conclusion has to be reached and explained, not assumed.

### Art. 77 — Complaint

Every reply that refuses or partly refuses a request must state the right to complain to a
supervisory authority. The competent authority is the one named in the privacy policy.

---

## Finding every mention of a person

A name can sit in a free-text field with no foreign key — an external mission participant, a job-order
handover recipient, an org-chart placeholder, a note or a booking reason. **Admin → Personensuche**
searches those surfaces for a name and lists every entry, so a rectification or erasure covers all of
them instead of the ones somebody happened to remember.

Use it for **every** Art. 16 or Art. 17 request, including from members — a member's handle can appear
in free text written by someone else, where no account link exists to follow.

---

## What the export does not contain

Stated here so nobody has to guess whether a gap is deliberate:

- **Platform logs, metrics and traces.** Retained 31 / 180 / 14 days, keyed by a pseudonymous account
  id, and disclosed in the privacy policy. Art. 15 covers them in principle; they are not in the
  self-service export because searching short-lived operational logs by account id is an admin
  operation. On request, an admin produces them.
- **Backups.** Not searched. They are copies of data already exported, and they expire within roughly
  six months.
- **Keycloak's own record** (login timestamps, sessions, credentials metadata). The application data
  is exported; the identity provider's internal record is produced by an admin on request.

---

## After the request

- Reply **within the month**, even if the answer is "nothing found".
- Say what was done, per right exercised.
- On any refusal or partial refusal: the reason, the right to complain, the right to a judicial
  remedy.
- If the request revealed that the tool cannot serve a right properly — that is a defect. File it and
  fix it, the same way this document came about.

