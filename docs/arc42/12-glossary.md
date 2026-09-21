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
| **Leitung** | Leadership, both as a role and as the delegated administration page. |
| **Beförderung** | Promotion — the graded-rank process, and one of the audited areas. |

> **`owning_org_unit_id` vs `responsible_org_unit_id` / `requesting_org_unit_id`** — not synonyms.
> Staffel-scoped aggregates (Mission, Operation, Ship, InventoryItem, RefineryOrder) carry
> `owning_org_unit_id`, nullable for deliberately *ownerless* rows. Job Orders are scoped by a
> **pair**: the responsible unit does the work and governs visibility, the requesting unit is the
> customer. Reading one as the other gets the visibility rules wrong in both directions.

## 12.2 Features

| Term | Means |
| --- | --- |
| **Einsatz / Mission** | A planned squadron mission. Non-internal missions are visible to the whole organisation, not only to the owning unit. |
| **Operation** | A grouping of missions with per-participant finances and a mark/clear payout gate. |
| **Auftrag / Aufträge** | Job order(s) — the prioritised queue of material and item orders. |
| **Materialbedarf** | The cross-order demand overview: what each responsible unit still has to gather per material, with booked stock and signed-up claims side by side. |
| **Lager** | The warehouse — org-scoped, append-only stock: book in/out, **umbuchen** (transfer), earmark to orders and missions. |
| **Umbuchen** | Transferring stock, individually or for a whole marked selection at once. |
| **Materialbörse** | The org-wide exchange board. **Angebote** are offers of owned stock; **Gesuche** are requests, optionally with a minimum quality and a desired quantity. |
| **Raffinerie** | Refinery — job orders, material handovers and the planet-aware materials matrix. |
| **Kartellbank** | The organisation bank: a double-entry, append-only ledger with accounts, a holder registry, per-account grants, approval ladders and PDF statements. |
| **Mein Inventar** | A member's own item list and unlocked crafting blueprints. |
| **Hangar** | Ship tracking, per member and per org unit. |

## 12.3 Architecture and operations

| Term | Means |
| --- | --- |
| **Audited area** | One of the nine areas whose every state-mutating activity is written to the append-only trail (`REQ-AUDIT-001`). |
| **Live update** | A single client's own mutation updating the DOM **in place**, with no full-page reload (`REQ-FE-*`). |
| **Live sync** | A *peer's* change propagating to other viewers over `/ws/sync`, fanned across replicas by Redis pub/sub. Not the same thing as live update, and both are required. |
| **Quadlet** | systemd's declarative container units (`.container`, `.network`, `.volume`), generated here from the compose files. A container is a systemd service. |
| **Rootless** | Containers run under an unprivileged user's systemd instance, with no root daemon and no socket. |
| **subuid translation** | A container uid *N* appears on the host as `subuid_base + N − 1` (base 100000 here). See §7.1 — this is the single most common source of "the container cannot read its own file". |
| **Config bundle** | The promotable, signed OCI artifact carrying host configuration (ADR-0049). Distinct from the **provider-JAR bundle**, which carries the Keycloak SPI and is deliberately a *separate* artifact (ADR-0055). |
| **Promotion** | Moving a `:stable` tag onto a chosen digest. A deliberate human act; the deploy timer only consumes it. |
| **Edge** | The nginx container terminating TLS for the four public names. **acme** is a separate container that issues and renews the certificates it serves. |
| **Ingest** | The internet-facing gateway module for the desktop extractor. Owns no database; relays to the backend internally. |
| **Restore drill** | The nightly job that restores the **latest snapshot** into a throwaway PostgreSQL and scores seven artifacts. It reports on the *snapshot*, never on the host. |
| **Conformance suite** | `check-conformance.py` — invariants asserted against a **running host**, because the recurring defect class is configuration that is correct on disk and not in force in the process. |

## 12.4 Names that mean less than they look like

| Looks like | Actually |
| --- | --- |
| `basetool_docker_cleanup_*` | The cleanup job's **former** metric name. The alert still accepts it while the rename crosses two delivery channels — §11.1. Nothing writes it once a host has run the Ansible role. |
| `versions.properties` | Vestigial. Zero entries, nothing reads it. Versions live in `gradle/libs.versions.toml`. |
| `keycloak.<domain>` in `edge-certs` | A fifth certificate directory that nothing serves and nothing renews, left from before ADR-0166. |
| `IRI_COSIGN_VERIFY=false` | A break-glass for a Sigstore outage, **not** the way to run an unsigned image. The sanctioned override for a different signing identity is `IRI_COSIGN_IDENTITY_REGEXP`. |
