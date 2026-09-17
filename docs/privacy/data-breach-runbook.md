# Personal-data breach runbook (Art. 33, 34 GDPR)

> **Doc type:** Living document — kept in sync with `main`. Last reviewed: 2026-09-15.

**The clock is 72 hours from becoming aware, and it does not pause for the weekend.** "Aware" means
having a reasonable degree of certainty that a security incident has led to personal data being
compromised — not having finished the investigation. A report that is late is itself a violation, and
Art. 33(1) explicitly allows a report to be incomplete: information may be provided in phases.

This is different from the vulnerability-disclosure process in
[`.github/SECURITY.md`](../../.github/SECURITY.md), which is about someone *reporting a weakness*.
This runbook is about personal data having *actually* been exposed, altered, lost or destroyed.

---

## What counts as a personal-data breach

Not only "someone stole the database". Art. 4(12) covers three kinds, and the second and third are
the ones that get missed:

|                          Kind                           |                                                                                               Examples here                                                                                                |
|---------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Confidentiality** — unauthorised disclosure or access | A leaked credential; an authorization gate that let a member read another squadron's data; a log or screenshot containing e-mail addresses posted somewhere public; a backup repository password disclosed |
| **Integrity** — unauthorised alteration                 | An account takeover used to change another member's records; a mass-edit by a compromised admin account                                                                                                    |
| **Availability** — loss or destruction                  | The database destroyed with no working backup; an erroneous bulk delete; ransomware                                                                                                                        |

**A credential in a commit is a breach only if personal data was actually reachable with it.** Treat
a leaked production secret as a breach until the access logs say otherwise, not the other way round.

---

## Hour 0 — Contain

1. **Stop the bleeding first.** Containment outranks evidence and outranks the report. Revoke the
   credential, disable the account, take the surface offline.
2. **Note the time you became aware, in UTC.** Write it down before anything else; it is the only
   number the 72 hours is measured from, and it becomes impossible to reconstruct honestly later.
3. **Do not delete anything.** Not logs, not the compromised container, not the suspicious account.
   Containment means cutting access, not erasing the trail.
4. **Preserve the evidence window.** Application logs live **31 days** and traces **14 days** — an
   incident investigated four weeks later has no logs left. If the incident may be older than a few
   days, export the relevant Loki streams to a file *now*, before retention takes them.

Production writes need @greluc's explicit per-action approval, and that rule holds during an
incident. Prepare the exact command, the expected output and the rollback, and wait for the yes.

---

## Hours 0–24 — Assess

Answer these, in writing, in the incident record:

- **What happened**, in one sentence.
- **Which categories of data** — account data, operational data, bank ledger, free text, logs with
  IP addresses?
- **How many data subjects**, approximately. An estimate with a stated basis beats a blank.
- **Which of the three kinds** — confidentiality, integrity, availability?
- **When did it start, when did it end, when were we aware?**
- **What is the likely consequence for the people affected?**

That last question decides everything that follows. For this tool the realistic worst case is the
exposure of e-mail addresses, Star Citizen handles, Discord account ids and organisation-internal
activity — enough to identify and contact someone, and to link a Discord identity to a game identity.
It is not financial or special-category data. State the actual risk, neither minimised nor inflated.

---

## Within 72 hours — Notify the supervisory authority (Art. 33)

**Report unless** the breach is *unlikely to result in a risk* to the rights and freedoms of natural
persons. That is a narrow exemption — it fits, for example, the loss of a backup whose contents are
encrypted with a key the finder does not have. It does not fit "we think nobody noticed".

If in doubt, **report**. A report that turns out to have been unnecessary costs an e-mail; an
omitted one that turns out to have been necessary is a separate violation on top of the breach.

The competent authority is the one named in section 2 of the privacy policy (the state data
protection commissioner for the controller's Land). Most authorities provide an online form; use it
rather than free-form e-mail.

Art. 33(3) requires at least:

1. the nature of the breach, the categories and approximate number of data subjects, and the
   categories and approximate number of records;
2. the name and contact details of the contact point — here the controller, as no DPO is appointed;
3. the likely consequences;
4. the measures taken or proposed, including mitigation.

**If you do not have all of it at 72 hours, file what you have** and say the rest will follow.

**If you decide not to report**, write down the reasoning and the facts it rests on, in the incident
record. Art. 33(5) requires the controller to document *every* breach, including the ones not
reported — that documentation is what the authority asks for.

---

## Without undue delay — Notify the data subjects (Art. 34)

Required when the breach is likely to result in a **high** risk to the people affected. Higher bar
than the authority report, so some breaches are reported to the authority and not to members.

Not required if the data was unintelligible to the attacker (e.g. properly encrypted), if subsequent
measures made the high risk unlikely to materialise, or if individual notice would take
disproportionate effort — in which case a public announcement is the substitute.

The notice goes to the people, in **clear and plain language**, and must state the nature of the
breach, the contact point, the likely consequences, and the measures taken. For this tool the
channels are the in-app announcement and the organisation's Discord. Say what happened and what they
should do — for example, whether to change a password or watch for phishing that uses their handle.

**Write it as you would want to be told.** A member reading it should understand, in ten seconds,
whether they need to do something.

---

## After — Learn

- **Fix the cause**, not the instance.
- **Record it** in the incident record and, where the cause was architectural, as an ADR — a breach
  is the most expensive lesson the project will ever get and it should not have to be learned twice.
- **Check whether the same weakness exists elsewhere.** An authorization gate that was missing on one
  endpoint is a question about every endpoint.
- **Update these documents** if the incident showed them to be wrong, thin, or unusable under
  pressure. A runbook that did not help during the one incident it existed for is a defect.

---

## Where the evidence lives

|          Source          |                                   Holds                                    |        Retention        |
|--------------------------|----------------------------------------------------------------------------|-------------------------|
| Application access logs  | Method, path, status, duration, pseudonymous account id, context ids       | 31 days                 |
| Edge-proxy access logs   | All requests to public services, including IP addresses                    | 31 days                 |
| Keycloak logs            | Authentication events, IP addresses, usernames                             | 31 days                 |
| Host authentication logs | SSH and host security events                                               | 31 days                 |
| Activity audit trail     | Every state-changing action in the audited areas, with actor and timestamp | see the retention table |
| Traces                   | Request paths and timings, no personal data                                | 14 days                 |
| Metrics                  | Aggregate counters only                                                    | 180 days                |

The audit trail is the one that survives longest and attributes actions to accounts — for an
integrity breach it is the primary source, not the logs.
