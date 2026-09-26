# 12. Glossary

The domain is German and the code is English, so nearly every concept has two names. This table is
the mapping. It also names the pairs that are genuinely easy to confuse — those are the entries
worth reading even if you know the domain.

## 12.1 Organisation and tenancy

| Term | Means |
| --- | --- |
| **OrgUnit** | The tenant unit in the code. One of four kinds: `SQUADRON`, `SPECIAL_COMMAND`, `BEREICH`, `ORGANISATIONSLEITUNG`. |
| **Staffel** | A squadron — `SQUADRON`. The ordinary operational unit a member belongs to. |
| **Spezialkommando (SK)** | A special command — `SPECIAL_COMMAND`. Sits alongside the Staffeln, not under one. |
| **Bereich** | A division stacked *above* Staffeln and SKs. |
| **Organisationsleitung (OL)** | The organisation's leadership, at the top of the hierarchy. |
| **Bereichsleitung** | The leadership of a Bereich — Bereichsleiter, -koordinator and -operator, carried as membership flags. |
| **Leitung** | Leadership, both as a role and as the delegated administration page. |
| **Beförderung** | Promotion — the evaluation matrix (**Bewertungen** per category against rank requirements). Only a Staffel carries it, never an SK, Bereich or OL; one of the audited areas. |
| **Organigramm** | The organisation's org chart of functional positions (Funktionsränge), separate from the tenancy tree. |

> **`owning_org_unit_id` vs `responsible_org_unit_id` / `requesting_org_unit_id`** — not synonyms.
> Staffel-scoped aggregates (Mission, Operation, Ship, InventoryItem, RefineryOrder) carry
> `owning_org_unit_id`, nullable for deliberately *ownerless* rows. Job Orders are scoped by a
> **pair**: the responsible unit does the work and governs visibility, the requesting unit is the
> customer. Reading one as the other gets the visibility rules wrong in both directions.

## 12.2 Features

| Term | Means |
| --- | --- |
| **Startseite / Dashboard** | The landing page after login: announcement, the next seven days, quick actions, unread notifications. |
| **Einsatz / Mission** | A planned squadron mission members sign up to, check in and out of, and are paid for. Non-internal missions are visible to the whole organisation, not only to the owning unit. |
| **Operation** | A grouping of missions with per-participant finances and a mark/clear payout gate. |
| **Auftrag / Aufträge** | Job order(s) — the prioritised queue of material and item orders. |
| **Eintragung** | A material claim: a Staffel signs up to deliver part of one material on a public SK job order. |
| **Materialbedarf** | The cross-order demand overview: what each responsible unit still has to gather per material, with booked stock and signed-up claims side by side. |
| **Lager** | The warehouse — org-scoped, append-only stock: book in/out, **umbuchen** (transfer), earmark to orders and missions. |
| **Umbuchen** | Transferring stock, individually or for a whole marked selection at once. |
| **Materialbörse** | The org-wide exchange board. **Angebote** are offers of owned stock; **Gesuche** are requests, optionally with a minimum quality and a desired quantity. |
| **Raffinerie** | Refinery — job orders, material handovers and the planet-aware materials matrix. |
| **Kartellbank** | The organisation bank: a double-entry, append-only ledger with accounts, a holder registry, per-account grants, approval ladders and PDF statements. |
| **Mein Inventar** | A member's own item list and unlocked crafting blueprints. |
| **Blueprints** | Crafting recipes a member has unlocked (**Meine Blueprints**), and who across the organisation can craft an item; **Standard-Blueprints** are granted to every account automatically. |
| **Hangar** | Ship tracking, per member and per org unit. |
| **Materialien** | The material catalogue everything points at, with its units and aliases; fed partly from UEX and the SC Wiki. |
| **Buchungsantrag / Anträge** | An org unit's deposit or withdrawal request in the Kartellbank, confirmed or rejected by the bank officers. The **Org-Einheits-Bank** is a unit's own view of its account and requests. |
| **Benachrichtigungen** | A per-user inbox fed by admin-maintained **Benachrichtigungsregeln** and pushed live. |

## 12.3 Architecture and operations

| Term | Means |
| --- | --- |
| **Audited area** | One of the twelve areas (the bank and eleven `AuditDomain` values) whose every state-mutating activity is written to the append-only trail (`REQ-AUDIT-001`). |
| **Live update** | A single client's own mutation updating the DOM **in place**, with no full-page reload (`REQ-FE-*`). |
| **Live sync** | A *peer's* change propagating to other viewers over `/ws/sync` (the app: a backend SSE stream), fanned across replicas by Redis pub/sub. Not the same thing as live update, and both are required. |
| **Discord-Registrierung / approval queue** | A self-service sign-up through Discord waits here as `PENDING` and sees nothing until an admin approves, rejects or links it to an existing account. |
| **Löschantrag** | A member's request to have their account deleted, decided in the admin area (GDPR erasure). |
| **RSI-Handle** | A member's optional Star Citizen account handle, entered on their own profile, unique across members and seen only by them and `ADMIN` (REQ-SEC-072). It counts as one of the member's names for the Personensuche and the erasure, and it is what a connected application's account check compares against, without ever getting it back. |
| **Quadlet** | systemd's declarative container units (`.container`, `.network`, `.volume`), generated here from the compose files. A container is a systemd service. |
| **Rootless** | Containers run under an unprivileged user's systemd instance, with no root daemon and no socket. |
| **subuid translation** | A container uid *N* appears on the host as `subuid_base + N − 1` (base 100000 here). See §7.1 — this is the single most common source of "the container cannot read its own file". |
| **Config bundle** | The promotable, signed OCI artifact carrying host configuration (ADR-0049). Distinct from the **provider-JAR bundle**, which carries the Keycloak SPI and is deliberately a *separate* artifact (ADR-0055). |
| **Promotion** | Moving a `:stable` tag onto a chosen digest. A deliberate human act; the deploy timer only consumes it. |
| **Front end (haproxy)** | The host service that binds `:80`/`:443` and hands connections to the edge with a PROXY v2 header naming the client (ADR-0187). Not to be confused with the `frontend` module. |
| **Edge** | The nginx container terminating TLS for the four public names, behind the haproxy front end. **acme** is a separate container that issues and renews the certificates it serves. |
| **Ingest** | The internet-facing gateway module for the desktop extractor. Owns no database; relays to the backend internally. |
| **Handoff** | The single-use Redis entry through which ingest passes a matched draft to the member's browser for review. |
| **Exchange API** *(planned)* | `/exchange/v1/**` on the ingest gateway: the capability-scoped contract through which approved external clients sync the member's own data (`REQ-XCH-*`, ADR-0216). Not the backend API. |
| **Capability** *(planned)* | An OAuth scope such as `exchange.stock.write` — what an external client is approved for and a member consents to. |
| **Installation** *(planned)* | One external client on one PC, identified by its DPoP key thumbprint; revoked one by one. |
| **Lot** *(planned)* | The exchange's stock unit: material + location + quality + „gestohlen" over a member's personal Lager rows, across org-unit pools. Not a Lager row and not a Lager stack. |
| **Tombstone** *(planned)* | The 90-day record of a removal in the exchange's change feed, saying who removed the entry. |
| **Restore drill** | The weekly job that restores the **latest snapshot** into a throwaway PostgreSQL and scores seven artifacts. It reports on the *snapshot*, never on the host. |
| **Conformance suite** | `check-conformance.py` — invariants asserted against a **running host**, because the recurring defect class is configuration that is correct on disk and not in force in the process. |

## 12.4 Names that mean less than they look like

| Looks like | Actually |
| --- | --- |
| `basetool_docker_cleanup_*` | The cleanup job's **former** metric name. The alert accepted it while the rename crossed two delivery channels, until 2026-09-22 — §11.1. Nothing writes it. |
| `versions.properties` | Removed 2026-09-23. It held zero versions; `./gradlew refreshVersions -PrefreshVersions` recreates an empty one, which is gitignored. Versions live in `gradle/libs.versions.toml`. |
| `keycloak.<domain>` in `edge-certs` | A fifth certificate directory that nothing serves and nothing renews, left from before ADR-0166. |
| `IRI_COSIGN_VERIFY=false` | A break-glass for a Sigstore outage, **not** the way to run an unsigned image. The sanctioned override for a different signing identity is `IRI_COSIGN_IDENTITY_REGEXP`. |
