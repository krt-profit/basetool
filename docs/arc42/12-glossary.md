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
| **Logistiker (einer Einheit)** | A member whose membership in that unit carries `is_logistician` **or** any leadership rank. Not the same as the flag alone: an officer of a unit is its Logistician without the flag being set. The web also grants it to a leadership rank's cascaded reach; the exchange does not (ADR-0257). |
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
| **Qualitätsstufe** | A catalogue tier a material requirement is stated in — a code, a floor (0–1000) and two labels, maintained by an administrator. „Keine" (floor 0) accepts any stock, „Gut (650+)" only 650 and up. An order's linked stock counts toward exactly one of its tiers (REQ-ORDERS-036/037, ADR-0241). |
| **Lager** | The warehouse — org-scoped, append-only stock: book in/out, **umbuchen** (transfer), earmark to orders and missions. |
| **Umbuchen** | Transferring stock, individually or for a whole marked selection at once. |
| **Pledge** | An RSI purchase in a member's hangar, one or more ships plus extras, with an id, a date, a price and whether it can be melted or gifted. An imported ship carries its pledge data, shown only to its owner and to admins (REQ-HANGAR-015, ADR-0295). |
| **Gestohlen** | The marker on Lager stock that is stolen cargo (REQ-INV-053). Part of the stack identity, so stolen and legitimate stock never share a stack or merge; carried by every move, shown as a danger chip wherever stock appears, and gated by a server switch until the app release that shows it. |
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
| **Audited area** | One of the fourteen areas (the bank and thirteen `AuditDomain` values) whose every state-mutating activity is written to the append-only trail (`REQ-AUDIT-001`). |
| **Live update** | A single client's own mutation updating the DOM **in place**, with no full-page reload (`REQ-FE-*`). |
| **Live sync** | A *peer's* change propagating to other viewers over `/ws/sync`, fanned across replicas by Redis pub/sub. Not the same thing as live update, and both are required. The backend's parallel SSE bridge for the app is discontinued (ADR-0274) — see §12.5. |
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
| **Ingest** | The internet-facing gateway module serving the **Exchange (API)** of approved clients, the SC Extractor included, and the single **public API entry point** (ADR-0268); its former `/v1` extractor routes were removed on 2026-09-28. Owns no database; relays to the backend internally. |
| **Public API entry point** | The ingest gateway, alone (ADR-0268). Every API interface reachable from the internet is on it: `/exchange/v1/**` and the connected-apps surface. The backend API is internal-only and has no public hostname; the web UI, Keycloak and Grafana are public surfaces but not APIs. |
| **Internal callers of the backend** | Exactly three (ADR-0268): the `frontend` module over `net-backend-frontend`, the `ingest` gateway over `net-backend-ingest`, and the **Keycloak SPI**, which calls `POST /internal/discord/account-existence` on a shared secret (REQ-SEC-022). A token is accepted only from the first two, matched on `azp` (ADR-0270); anything else reaching `/api/v1` is a defect, not a configuration option. |
| **Handoff** | The single-use Redis entry through which ingest passes a matched draft to the member's browser for review. |
| **Exchange (API)** | `/exchange/v1/**` on the ingest gateway: the capability-scoped contract through which approved external clients sync the member's own data and, for a Logistician, their units' org stock (`REQ-XCH-*`, ADR-0216, ADR-0257). Not the backend API, and not the **Materialbörse**, whose code is named `MaterialExchange*`. |
| **Capability** | An OAuth scope such as `exchange.stock.write` — what an external client is approved for and a member consents to. A route passes only when its capability is both in the token and granted to the client in the registry. |
| **Client registry** | The backend's table of approved exchange clients with their status and capabilities, plus the global exchange switch; `ADMIN` only, audited as „Verbundene Anwendungen“ (REQ-XCH-003). |
| **Registry mirror** | The JSON copy of the client registry under `exchange:registry` in Redis, which the gateway reads. Restrictions reach it before their commit, everything else after, and a 60-second reconcile repairs it. |
| **Verbundene Anwendungen** | „Connected applications": the member's page (`/connected-apps`) for their clients, installations, activity, disconnect, undo and mass-change confirmation; the admin page of the same name holds the registry and the **bulk undo** of one client for every member. Also the audit area (`CONNECTED_APPS`) of registry changes, disconnects, departures, undos, bulk undo runs and confirmed mass changes. |
| **Installation** | One external client on one PC, identified by its DPoP key thumbprint and shown by an opaque id and a client-supplied label; revoked one by one. |
| **Change feed** | `exchange_change`: the trigger-written log of which member's blueprint, lot or ship, or which unit's org-stock lot, changed, in which transaction and by which writer (`web`, `app`, `client`, `system`). A client reads it after a cursor; a snapshot ends with one (ADR-0224, ADR-0257). |
| **Tombstone** | A feed entry for a key that is gone, saying who removed it and when; live for 90 days. A client may not re-add what another channel removed without asking the member (`REMOVED_ELSEWHERE`). |
| **Journal** | `exchange_journal`: every entry an exchange write changed, before and after, kept 90 days. The guard counts removals from it and the undo restores from it. Not the audit trail, which records the same write for people. |
| **Stack / Lot** | A Lager **stack** is the Lager's stock identity — owner, material, location, quality, „gestohlen", personal flag and org-unit pool. An exchange **lot** is material + location + quality + „gestohlen" over every row the member holds, personal and shared, summed across org-unit pools (ADR-0230), with its **split** into personal stock and the member's own part of each pool (REQ-XCH-016). One lot can span several stacks. |
| **Pool / Org-Bestand** | A **pool** is one unit's shared (non-personal) Lager stock. The **org stock** (`orgstock`) is what the exchange serves of the pools of the units a member belongs to directly — the web's „Globales Lager" without oversight reach. Its lot is keyed by unit first, so one material in two units is two lots (REQ-XCH-042, ADR-0257). „Staffellager" is colloquial for a Staffel's pool. |
| **Holder ref** | The pseudonym of a holder in the org stock's single entries: an HMAC of the user id under a salt per exchange client, so one member looks the same at one client and different at every other (REQ-XCH-043, ADR-0257). |
| **Draft** | A client upload staged for review in the browser — a blueprint import or a refinery order — that writes nothing until the member saves it through the ordinary path. Distinct from a **mass change held for confirmation**, which is a staged change set. |
| **Restore drill** | The weekly job that restores the **latest snapshot** into a throwaway PostgreSQL and scores seven artifacts. It reports on the *snapshot*, never on the host. |
| **Conformance suite** | `check-conformance.py` — invariants asserted against a **running host**, because the recurring defect class is configuration that is correct on disk and not in force in the process. |

## 12.4 Names that mean less than they look like

| Looks like | Actually |
| --- | --- |
| `basetool_docker_cleanup_*` | The cleanup job's **former** metric name. The alert accepted it while the rename crossed two delivery channels, until 2026-09-22 — §11.1. Nothing writes it. |
| `versions.properties` | Removed 2026-09-23. It held zero versions; `./gradlew refreshVersions -PrefreshVersions` recreates an empty one, which is gitignored. Versions live in `gradle/libs.versions.toml`. |
| `keycloak.<domain>` in `edge-certs` | A fifth certificate directory that nothing serves and nothing renews, left from before ADR-0166. |
| `BASETOOL_COSIGN_VERIFY=false` | A break-glass for a Sigstore outage, **not** the way to run an unsigned image. The sanctioned override for a different signing identity is `BASETOOL_COSIGN_IDENTITY_REGEXP`. |

## 12.5 Discontinued with the Android app

The native app is discontinued (issue #2638) and members use the installable web app instead, which
is the frontend itself (ADR-0164). The six terms below named things the app was the only reason for.
The decision to end each is recorded; the code and configuration follow in the sub-issues named, so
until those land the artifacts are still there — and nothing new may be built on them.

| Term | Actually |
| --- | --- |
| **API vhost** (`api.profit-base.online`) | The backend's public vhost (ADR-0135), **discontinued by ADR-0268**: the backend is internal-only and the ingest gateway is the only public API. Removed in #2672, together with the DNS record, the certificate SAN, `docker/edge/conf.d/50-api.conf.template`, the admission include and the `net-proxy-api` network — and replaced by a probe asserting the host does not resolve. |
| **Frozen contract**, **contract tiers T0 / T1** | `contract-tiers.txt`, `ContractTiers`, `x-contract-tier`, `ExternalContractTest`, the app call lists and the declared-break ledger (ADR-0136, ADR-0234), which froze `/api/v1` because one client could not redeploy. **Discontinued by ADR-0269**, removed in #2673; `/api/v1` is back under REQ-API-001's atomic-deploy carve-out. The **Exchange (API)**'s freeze is a different thing and stays (ADR-0219). |
| **App floor**, **version policy** | `GET /api/v1/app/version-policy` and the release-bound minimum app `versionCode` behind the non-dismissible update wall (ADR-0234). **Discontinued by ADR-0269**, removed in #2673: no release raises a floor, and no deploy waits on one. |
| **Retired operation** | A path that answered `410 APP_UPDATE_REQUIRED` after a declared hard-cut break (`retired-operations.txt`, `RetiredOperationFilter`, REQ-API-020). **Discontinued by ADR-0269**, removed in #2673. |
| **App live-sync bridge** | `GET /api/v1/live-sync/stream` and `POST /api/v1/live-sync/changed` in the backend, with their own topic registry, authorizer and buckets (ADR-0143). **Discontinued by ADR-0274**, removed in #2673: the installable web app is the frontend, so a PWA tab uses `/ws/sync` like any other tab. |
| **App Link** | `/.well-known/assetlinks.json`, `/app/callback` and `/app/link-help` on the frontend, which made an Android App Link verifiable and caught a member mid-login (REQ-SEC-038). **Discontinued with the app (ADR-0268)**, removed in #2674 together with the `basetool-android` Keycloak client. |

## 12.6 Planned: navigation, home dashboard, officer reach, minimum stock

> **Status: planned** (ADR-0297…0302, map #2800). These terms name what the accepted ADRs decide;
> until it ships, the entries above and the current code stay authoritative.

| Term | Means |
| --- | --- |
| **Navigationseintrag / navigation entry** | One page or one tab of a tabbed page as the navigation knows it: a link in the sidebar, a target of the quick access, a favourite, a tab-bar slot or a start page (REQ-UI-026, REQ-UI-032, ADR-0297). Single missions, operations or materials are entities, not entries. |
| **Entry key** | The stable id of a navigation entry, such as `missions.list` or `orders.demand`, from the frontend's entry registry. Every stored preference names an entry by its key, never by its URL; the backend checks only the key's format, and a key the user may no longer see is deleted on the next load (ADR-0298). |
| **Favoriten / favourites** | The user's starred entries, at most 12, in their own order, shown as a group at the top of the navigation while the entry also stays in its task group. One list serves both modes; an admin entry shows only in admin mode. An entry unavailable in the active context renders greyed out (REQ-UI-032). |
| **Ausgeblendeter Eintrag / hidden entry, „Mehr“** | A page the user hid: it moves into the collapsed „Mehr“ area at the end of the navigation and leaves the quick access with its tabs. Only pages can be hidden, personal pages never; favourite and hidden exclude each other per entry key (REQ-UI-032). |
| **Anpassen / edit mode** | The edit mode of the navigation and of the dashboard: star, hide and up/down per link, up/down per group, the „Tab-Leiste“ pickers, the catalogue and the options per widget. No reorder needs a drag (REQ-UI-031, REQ-UI-032, REQ-UI-034). |
| **Häufig / frecency ranking** | The quick access's ranking by how often and how recently an entry was visited, kept per device in the browser and decaying over about 30 days. It ranks typed results and fills the empty palette after Favoriten and before Zuletzt; the sidebar never reorders itself (REQ-UI-033). |
| **Darstellung** | The profile section, and the server-side preference section, holding the desktop mode and the start page per mode (REQ-UI-032, ADR-0298). |
| **Desktop-Modus / desktop mode** | How the navigation sits on a desktop: the **Drawer** (default), the **Rail** of one icon per group with flyouts, from 1025 px, or the **angeheftete Seitenleiste** (pinned labelled sidebar), from 1280 px. One mode for member and admin mode; below 1025 px the drawer and the tab bar apply (REQ-UI-026, ADR-0297). |
| **Tab-Leiste / tab bar** | The phone's bottom bar: three slots the user chooses, „Meldungen“ and „Menü“ fixed, one bar per mode. In **automatischer Belegung** (automatic assignment) the slots follow the favourites, then the default; the first edit stores all three slots as **feste Belegung** (explicit assignment), and „Tab-Leiste zurücksetzen“ returns to automatic (REQ-UI-026, REQ-UI-032). |
| **Stand-in** | What a tab-bar slot shows while its entry is unavailable in the active context or was deleted: the next favourite not yet shown, else the next free default entry. A slot unavailable only in the context keeps its stored choice (REQ-UI-032). |
| **Dashboard** | The personalisable home page of widgets in two columns (2 : 1), one layout per mode; the logo always leads to it. It replaces the fixed cards of today's **Startseite / Dashboard** above (REQ-UI-034, ADR-0299). |
| **Widget / Widget-Instanz** | A **widget** is a dashboard card type from the catalogue, shown only to users who may read its data; a **widget instance** is one placed copy of it with its own options (org unit, time range, row count, status filter) and its own version. A widget may be placed more than once, at most 12 instances per mode (REQ-UI-034, ADR-0299). |
| **Startseite / start page** | The page or tab a user chose to land on after login, on launching the installed app and, for the admin start page, on switching into admin mode; one per mode, chosen under „Darstellung“. A deep link wins, and a start page no longer permitted falls back to the dashboard (REQ-UI-034). |
| **Mindestbestand / Zielbestand** | Minimum and target stock level of one unit's pool for a material in an optional quality tier, or for a game item; target ≥ minimum > 0. Free stock is allocated exclusively from the highest tier down, each level up to its target, and a level is **niedrig** (low) below its minimum. Not the same as the open supply needs, which the tab shows beside each level as requested without subtracting them (REQ-INV-067, REQ-INV-068, ADR-0302). |
| **Bearbeiter / assignee** | A person entered on a job order to work on it (`JobOrderAssignee`), with an optional note; „Ich bearbeite“ lists the orders where the user is one. An **externer Bearbeiter** (external assignee) is not a direct member of the processing unit: the entry alone lets them read the whole order, edit their own note and remove themselves, as long as the entry exists — an exception to org-unit tenancy (REQ-ORDERS-047, ADR-0301). |
| **Offiziersreichweite / officer reach** | Where a member counts as an officer, derived per org unit from leadership seats only: a Staffel or SK leadership seat for its own unit, a Bereich rank for the Bereich and its direct children, the OL for every unit, each seat requiring the role `KRT Member`. An officer check evaluates it in the target's owning unit. It retires the Keycloak role `OFFICER` and grants no manual override (ADR-0300). |
