# DPIA threshold assessment (Art. 35) and DPO assessment (Art. 37)

> **Doc type:** Living document — kept in sync with `main`. Last reviewed: 2026-11-02.

Two obligations that turn out **not** to apply. Both are written down anyway, because "we considered
it and concluded no" is a defensible position and "nobody ever asked" is not. A supervisory authority
that asks why there is no DPIA wants to see this page, not a shrug.

---

## Art. 35 — Data protection impact assessment: not required

A DPIA is required where processing is **likely to result in a high risk** to the rights and freedoms
of natural persons, in particular using new technologies. Art. 35(3) names three cases where it is
always required, and the supervisory authorities publish a list of further ones.

### Against the Art. 35(3) cases

|                                                                                            Case                                                                                             | Applies? |                                                                                                                                                         Why                                                                                                                                                         |
|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **(a)** Systematic and extensive evaluation of personal aspects based on automated processing, including profiling, producing legal effects or similarly significantly affecting the person | **No**   | The tool neither profiles nor rates members. A member's rank in a Star Citizen squadron is set **manually** by a person, not by automated processing, in a voluntary gaming organisation, and it has no legal or similarly significant effect. |
| **(b)** Processing on a large scale of special categories (Art. 9) or criminal-offence data (Art. 10)                                                                                       | **No**   | Neither is processed. No health, biometric, political, religious, trade-union, sexual-orientation or criminal-offence data. The tool does not even collect a first or last name.                                                                                                                                    |
| **(c)** Systematic monitoring of a publicly accessible area on a large scale                                                                                                                | **No**   | Nothing is monitored; the tool is members-only and holds no public area.                                                                                                                                                                                                                                            |

### Against the usual further criteria

- **Scale:** a squadron in the low hundreds of members, not a large scale by any reading.
- **Sensitive or highly personal data:** no. Pseudonymous game identities, an e-mail address, a
  Discord id.
- **Data matching or combining:** the Discord identity is linked to the account, but for
  authentication and membership verification only, not to build a profile.
- **Vulnerable data subjects:** members must be 18, and only members of the organisation receive an
  account. No employment relationship, so none of the dependency that makes workplace monitoring
  sensitive.
- **Innovative use of new technology:** no. A conventional web application.
- **Preventing data subjects from exercising a right or using a service:** no. Participation is
  voluntary, and the tool is not a precondition for anything outside the game.

### Conclusion

**No DPIA is required.** The combination that would change this:

- collecting real names, addresses or dates of birth,
- automating a decision that affects someone outside the game,
- admitting members under 18,
- adding location tracking, voice or video recording,
- or making the tool available beyond the organisation.

**Re-run this assessment when any of those becomes true**, and record the new result here rather than
overwriting the old one.

### Re-checked 2026-09-28 — connected applications (A11)

Connecting approved clients to the exchange API, going live on 2026-09-28, meets none of the
triggers above: no new data categories (the member's own game data, already processed under A2), no
profiling or automated decision, no monitoring, the scale unchanged, and the transfer goes to
software on the member's own device at the member's request. **Still no DPIA required.**

### Re-checked 2026-11-09 — the Kartellbank on connected applications (A11)

The bank reads (REQ-BXCH-002) put other members' bank data on third-party devices: the balances and
booking history of the accounts the member may view, with the handle of a counterparty. They meet
none of the triggers above either: no new data category (the bank data of A3, shown to the same
members as in the Basetool), no profiling or automated decision, no monitoring and no money moved.
The data stays with the member who may already see it, on a client approved only if it keeps
other people's bank data in memory for at most 7 days (REQ-BXCH-007), and a member can withhold
their name. **Still no DPIA required.**

The staff queue (REQ-BXCH-005) adds, for bank staff and approvers only, the pending requests they
already work on in the Basetool: the requester's handle, the justification and note, and the
account balance for the Bankleitung and on granted accounts. Still the A3 bank data, to the same
people as in the Basetool, read-only, under the same 7-day memory-only condition and the same name
withholding; no new trigger. **Still no DPIA required.**

---

## Art. 37 GDPR / § 38 BDSG — Data protection officer: not required

A DPO must be designated where:

|                                                                   Ground                                                                    | Applies? |                                                                                             Why                                                                                             |
|---------------------------------------------------------------------------------------------------------------------------------------------|----------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Art. 37(1)(a)** — a public authority or body                                                                                              | **No**   | A private, voluntary project.                                                                                                                                                               |
| **Art. 37(1)(b)** — core activities consist of processing that requires regular and systematic monitoring of data subjects on a large scale | **No**   | The core activity is squadron administration for its own members. There is no monitoring of data subjects as a purpose, and no large scale.                                                 |
| **Art. 37(1)(c)** — core activities consist of large-scale processing of Art. 9 or Art. 10 data                                             | **No**   | Neither category is processed at all.                                                                                                                                                       |
| **§ 38(1) BDSG** — as a rule, 20 or more persons are constantly engaged in the automated processing of personal data                        | **No**   | The people who administer the tool are a handful of admins, far below twenty. Members *using* the tool are data subjects, not persons engaged in the processing on the controller's behalf. |
| **§ 38(1) BDSG, second sentence** — processing subject to a DPIA, or commercial processing for transfer or market/opinion research          | **No**   | No DPIA required (above); no data is transferred commercially and no market or opinion research is carried out.                                                                             |

### Conclusion

**No DPO is required.** The controller is the contact point for data-protection matters and is named
in the Impressum and in section 2 of the privacy policy. That contact point is what the breach
notification under Art. 33(3)(b) names.

**Re-run this assessment** if the number of people regularly administering the tool approaches
twenty, if a DPIA becomes required, or if the processing is ever carried out commercially.

---

## Why these are written down at all

Art. 5(2) puts the burden of demonstrating compliance on the controller. For an obligation that does
*not* apply, the demonstration is exactly this: the criteria, the facts checked against them, the
conclusion, and the conditions that would reverse it. Without it, the honest answer to "why is there
no DPIA?" is indistinguishable from never having considered the question.
