> **Archived 2026-09-22.** Doc type: Historical plan (German) — frozen, kept as a record and no longer updated. Shipped as migrations V80–V93 in May 2026. It was deleted from the repository on 2026-05-20 (#152) and restored here on 2026-09-22, because Java sources and Flyway migrations still cite it by name.
>
> **Current truth:** [`org-unit-tenancy.md`](../specs/org-unit-tenancy.md), [`ROLES_AND_PERMISSIONS.md`](../../ROLES_AND_PERMISSIONS.md). Index of the archive: [`README.md`](README.md).

# Multi-Squadron-Umbau: Plan

> Status: **ENTWURF – wartet auf explizite Freigabe.** Es wird **nichts** umgesetzt, bevor der Anwender diesen Plan freigegeben hat.

> **Revision 2 (2026-05-18):** Job Orders sind nun explizit als cross-Staffel-Workspace modelliert: fuer alle Staffeln sichtbar und gemaess bestehender Rollen-/Rechte-Matrix bearbeitbar. Per `job_order_id` verknuepfte InventoryItems sind im Job-Order-Kontext fuer alle Staffeln sichtbar/bearbeitbar (inkl. Handover-Schreibwirkung auf Items fremder Staffeln), erscheinen aber **nicht** im Lager-View einer fremden Staffel. Direkter Lager-View bleibt strikt staffel-gefiltert. Betrifft Abschnitte 1, 3.2, 4.4, 4.6, 5.3, 7, 9, 11, 12.

> **Revision 3 (2026-05-18):** Job Order traegt **zwei** Staffel-Referenzen statt einer: `creating_squadron_id` (erstellende Staffel im System) und `requesting_squadron_id` (auftraggebende Staffel – ersetzt die bestehende `squadron VARCHAR`-Spalte). Beide koennen sich unterscheiden; beide sind rein informativ, kein Access-Control. Betrifft Abschnitte 1, 3.2, 3.3, 4.4, 4.5, 5.3, 6, 8, 9, 11, 12.

## 1. Zielbild

Das Basetool soll mehrere Staffeln des Profit-Bereichs verwalten können statt nur IRIDIUM. Trennlinien:

| Rolle | Sicht | Schreibrechte |
| :--- | :--- | :--- |
| **Admin** | alle Staffeln, beliebig umschaltbar | alle Staffeln |
| **Officer** | ausschliesslich eigene Staffel | eigene Staffel |
| **Logistician / Mission Manager** | ausschliesslich eigene Staffel | eigene Staffel |
| **Squadron Member** | ausschliesslich eigene Staffel | eigene Staffel (begrenzt) |
| **Guest** | nur oeffentliche, nicht-interne Einsaetze | – |

Datenklassen und ihre Sichtbarkeit:

| Bereich | Sichtbarkeit ueber Staffelgrenzen |
| :--- | :--- |
| **Einsatz / Mission** | – falls `is_internal = false`: in allen Staffeln sichtbar (Lese-/Anmelde-Sicht)<br>– falls `is_internal = true`: nur eigene Staffel + Admin<br>– Bearbeiten/Abschliessen/Auszahlung: nur Eigentuemer-Staffel + Admin |
| **Job Order / Warenauftrag** | – **Lesen:** alle authentifizierten User aller Staffeln (gem. bestehender Rechte-Matrix)<br>– **Bearbeiten / Uebergabe / Abschluss:** Logistician+ aller Staffeln<br>– **Loeschen:** Admin<br>– **Zwei** Staffel-Referenzen, beide rein **informativ**, **nicht** zugriffssteuernd:<br>&nbsp;&nbsp;• `creating_squadron_id` = erstellende Staffel (wer hat den Auftrag im System angelegt)<br>&nbsp;&nbsp;• `requesting_squadron_id` = auftraggebende Staffel (fuer wen der Auftrag laeuft); ersetzt die bisherige `squadron VARCHAR`-Spalte. Beide koennen sich unterscheiden. |
| **Mit Job Order verknuepfte Lagereintraege** | – sichtbar fuer alle Staffeln **innerhalb** des Job-Order-Kontextes (auch wenn der Eintrag einer fremden Staffel gehoert)<br>– bearbeitbar (Mengenkorrektur, Uebergabe-Verbrauch, `delivered`-Flag) ueber Job-Order-Funktionen gem. Rechte-Matrix<br>– **tauchen NICHT im Lager-View einer fremden Staffel auf** |
| **Hangar / Schiffe** | strikt eigene Staffel + Admin |
| **Lager / Inventory (Direkter View)** | strikt eigene Staffel + Admin |
| **Refinery** | strikt eigene Staffel + Admin |
| **Personal Inventory** | unveraendert benutzer-scoped |
| **Admin-Bereich (Settings, Locations, Materials, UEX, Announcements, Member-/User-Verwaltung)** | **nur Admin** – Officer verliert den Zugriff vollstaendig |
| **Globale Kataloge (UEX-Cache, Materials, JobTypes, FrequencyTypes, ShipTypes, Manufacturers, RefiningMethods, Roles, PromotionSystem)** | global, keine Staffel-Scope |

## 2. Auswirkung auf bestehende Rolle "Officer"

Heute hat ein Officer effektiv Admin-Power (Stammdaten, Member-Management, Announcements, UEX, Einstellungen). Nach dem Umbau verliert der Officer **alle Admin-Bereich-Funktionen** und wird zur Squadron-internen Fuehrungsrolle:

* **Verliert:** Admin-Bereich (Settings, Locations, Materials, UEX, Announcements, Member-Management, Mission-Stammdaten, Promotion-System-Pflege), Rollen-/Flag-Vergabe an User, system-weite Konfiguration.
* **Behaelt fuer die eigene Staffel:** Missions-Management (Eigentuemer-Sicht), Hangar-Schreibrechte, Logistician-Funktionen (per Hierarchie), Refinery-Management, Einsicht in alle Squadron-Daten.

Konsequenz: Der bisherige Hop "OFFICER kann Logistician-/Mission-Manager-Flag setzen" entfaellt – Flags werden nur noch durch Admin gesetzt.

## 3. Datenmodell

### 3.1 Bestehendes (NICHT neu erfinden)

* **`squadron`-Tabelle existiert bereits** (V17 fuegt `active` hinzu). Felder: `id`, `name`, `shorthand`, `active`. Wird heute nur sporadisch referenziert (`mission_participant.squadron_id`, `job_order_handover.recipient_squadron` als String).
* **`mission.is_internal` existiert bereits** (V13). Semantik heute: rein deskriptiv, nicht autorisierungswirksam. Wir geben ihr die in 1) beschriebene Semantik.
* **`mission.flags_version`** wird bereits separat versioniert (Optimistic Locking, vermeidet Race-409). Bleibt unveraendert.
* **`AuthHelperService`** ist der einzige Zugriffspunkt auf `SecurityContextHolder` (ArchUnit-Regel). Erweiterung erfolgt dort.

### 3.2 Neue Spalten / Tabellen

| Tabelle | Aenderung | Begruendung |
| :--- | :--- | :--- |
| `app_user` | neue Spalte `squadron_id UUID` (FK → `squadron`, nullable) | Mitgliedschaft. NULL fuer Admin/Guest erlaubt. |
| `mission` | neue Spalte `owning_squadron_id UUID` NOT NULL (FK → `squadron`) | Eigentuemer-Staffel der Mission. |
| `job_order` | **Zwei** neue Spalten:<br>– `creating_squadron_id UUID` NOT NULL (FK → `squadron`) = erstellende Staffel<br>– `requesting_squadron_id UUID` NOT NULL (FK → `squadron`) = auftraggebende Staffel; **ersetzt** die bestehende `squadron VARCHAR`-Spalte (zweiphasige Migration) | Beide Felder rein **informativ**, **nicht** zugriffssteuernd – Job Orders sind cross-Staffel sichtbar/bearbeitbar (siehe Abschnitt 1). Werden in Listen-/Detail-Views als Spalte/Badge angezeigt. Die beiden koennen sich unterscheiden, wenn eine Staffel im Auftrag einer anderen taetig wird. |
| `ship` | neue Spalte `owning_squadron_id UUID` NOT NULL (FK → `squadron`) | Hangar ist staffel-scoped. |
| `inventory_item` | neue Spalte `owning_squadron_id UUID` NOT NULL (FK → `squadron`) | Lager ist staffel-scoped. |
| `refinery_order` | neue Spalte `owning_squadron_id UUID` NOT NULL (FK → `squadron`) | Refinery ist staffel-scoped. |
| `operation` | neue Spalte `owning_squadron_id UUID` NOT NULL (FK → `squadron`) | Operations enthalten Missionen einer Staffel. |

**Kind-Entitaeten** (`MissionParticipant`, `MissionUnit`, `MissionCrew`, `MissionFinanceEntry`, `MissionFrequency`, `MissionOwnership`, `JobOrderMaterial`, `JobOrderHandover`, `JobOrderHandoverItem`, `RefineryGood`): erben Staffel-Scope ueber den Aggregate-Root. **Keine Denormalisierung** dort – Filterung erfolgt ausschliesslich am Root.

Begruendung gegen Denormalisierung: Spart Backfill-Schritte, vermeidet zusaetzliche Konsistenz-Pflicht und passt zur bestehenden Aggregate-Architektur. Falls spaeter Performance-Druck entsteht, lassen sich gezielte Denormalisierungen nachschieben.

**`mission_participant.squadron_id`** (existiert bereits, nullable) bleibt erhalten und bekommt eine klar definierte Semantik: "Staffel, fuer die das Mitglied an der Mission teilnimmt" – das kann von `mission.owning_squadron_id` abweichen, wenn ein Mitglied einer anderen Staffel an einer nicht-internen Mission teilnimmt. Wichtig fuer Finanz-/Reporting-Korrektheit.

### 3.3 Flyway-Migrationen (zweiphasiger Ablauf)

Naechste freie Versionen ab `V80`. Phase 1 fuegt Spalten als nullable hinzu und backfillt; eine spaetere Release-Phase 2 tightening (NOT NULL) und Cleanup des Legacy-`job_order.squadron`-String erfolgt erst nach erfolgreichem prod-Deploy von Phase 1 (Hard-Regel aus `db/migration/README.md`).

| Migration | Inhalt |
| :--- | :--- |
| `V80__seed_iridium_squadron.sql` | IRIDIUM-Squadron via `INSERT ... ON CONFLICT DO NOTHING` mit fester UUID (Code-Konstante). Idempotent. |
| `V81__add_squadron_id_to_user.sql` | `app_user.squadron_id UUID` (FK → `squadron`, nullable) + Backfill aller User auf IRIDIUM. **Nicht** NOT NULL – Admins/Guest duerfen ohne Squadron sein. |
| `V82__add_owning_squadron_to_aggregates.sql` | `owning_squadron_id UUID` (FK → `squadron`, nullable) fuer `mission`, `operation`, `ship`, `inventory_item`, `refinery_order` + Backfill auf IRIDIUM. **Noch ohne NOT NULL.** |
| `V83__add_job_order_squadron_columns.sql` | `job_order.creating_squadron_id UUID` (FK → `squadron`) + `job_order.requesting_squadron_id UUID` (FK → `squadron`). Backfill:<br>– `creating_squadron_id = IRIDIUM` fuer alle bestehenden Rows (Single-Tenant-Vergangenheit)<br>– `requesting_squadron_id` aus der bestehenden `squadron VARCHAR`-Spalte via LEFT JOIN auf `squadron.name`/`squadron.shorthand`; nicht-aufloesbare Strings fallen auf IRIDIUM zurueck.<br>FK-Constraints anlegen. Bestehende `squadron VARCHAR`-Spalte bleibt zunaechst stehen. **Noch ohne NOT NULL.** |
| `V84__add_job_order_handover_audit_columns.sql` | `job_order_handover.executing_user_id UUID` (FK → `app_user`, `ON DELETE SET NULL`) + `executing_squadron_id UUID` (FK → `squadron`). Plan §4.4 / §7: Audit-Stempel fuer den ausfuehrenden User + dessen Staffel auf Cross-Staffel-Handovers. Ship't in **Phase 6**, NICHT Phase 7 — die Nummer steht **vor** der Phase-7-Kette (in finaler Form `V88`/`V89`/`V90`), damit Flyway's strikte aufsteigende Reihenfolge respektiert wird (`out-of-order=false` ist Projekt-Default). |
| `V85__add_owning_squadron_to_promotion_topic.sql` | `promotion_topic.owning_squadron_id UUID` (FK → `squadron`, nullable) + Backfill auf IRIDIUM. Macht das Promotion-System staffel-spezifisch — Categories / LevelContent / RankRequirement / MemberEvaluation erben den Scope ueber den Topic. Ship't in **Phase 6** (mit Officer-Zugriff auf Promotion-Pflege), Position **vor** Phase 7 aus dem gleichen Flyway-Ordering-Grund wie V84. |
| `V86__add_is_promotion_enabled_to_squadron.sql` | `squadron.is_promotion_enabled BOOLEAN NOT NULL DEFAULT TRUE`. Pro Staffel kann das gesamte Beförderungssystem (Topics/Categories/LevelContent/RankRequirements/Evaluations) per Admin-Toggle aus- und wieder eingeschaltet werden, ohne Datenverlust — der Flag-Off-Pfad short-circuited Reads zu leer und wirft 403 auf Writes; admins bypassen den Gate. Default TRUE haelt Bestands-Staffeln nach der Migration sichtbar. Ship't in **Phase 6**, Position **vor** Phase 7 — gleicher Renumbering-Grund wie V84/V85. |
| `V88__stop_writing_job_order_squadron_string.sql` | Entity entfernt das `squadron`-VARCHAR-Feld; Migration loescht `NOT NULL`-Constraint und etwaige Indizes auf der Spalte. Phase-1-Schritt fuer Legacy-Cleanup. **Phase 7 Teil 1** — solo via PR #132 (Commit `a00e5c8`) in Prod gegangen, daher springt die nachgelagerte Tightening-Migration auf V89 statt V87 (Flyway `out-of-order=false` rejected `version <= latest_applied`). |
| `V89__tighten_squadron_id_not_null.sql` | `SET NOT NULL` fuer `owning_squadron_id` (5 Aggregat-Tabellen + `promotion_topic`) und fuer `creating_squadron_id` + `requesting_squadron_id` (`job_order`). **Erst nach** mind. einem prod-Deploy von `V80`–`V86` + `V88` (eigene Release-Iteration). **Phase 7 Teil 2** — urspruenglich als V87 reserviert, durch den Solo-Deploy von V88 auf V89 hochgezogen. |
| `V90__drop_job_order_squadron_string.sql` | `ALTER TABLE job_order DROP COLUMN squadron`. **Phase 7 Teil 3** — landet zusammen mit der Entfernung der `String squadron`-DTO-Felder auf `JobOrderDto` / `JobOrderReferenceDto` / `CreateJobOrderDto` (Backend + Frontend-Mirror) und der Frontend-Form-Migration von `squadron` (String) auf `requestingSquadronId` (UUID). Deploy **erst** in der **uebernaechsten** Release-Iteration nach `V88`/`V89` — die zweiphasige Drop-Regel aus `db/migration/README.md` verlangt mindestens einen Soak-Release zwischen Stop-Write (V88) und Drop (V90); V89 stellt diese Bruecke. Urspruenglich als V89 reserviert, durch das post-Deploy-Reshuffle auf V90 verschoben. |

Backfill-SQL ist idempotent (`WHERE owning_squadron_id IS NULL`), keine seiteneffekte ueber Tabellen-Grenzen hinweg, Header-Kommentar erklaert das Warum.

### 3.4 Konflikt mit der heutigen Test-Konfiguration

`application-test.yml` setzt `flyway.enabled: false` und nutzt H2. Die Migrationen werden im Standard-Testlauf **nicht** angewandt (siehe `db/migration/README.md` Abschnitt "Tests"). Konsequenz fuer den Plan:

* Hibernate `ddl-auto` im Test-Profile generiert das Schema. Sobald die JPA-Entities `owningSquadron`-Felder haben, entsteht das Schema in H2 automatisch passend.
* **Risiko:** ein reiner Mockito-Unit-Test pruft keine Migration. Wir muessen vor Merge `./gradlew :backend:bootRun` gegen das dev-Postgres laufen lassen (mit `.env.test`, **nie** `.env` – siehe `feedback_env_test_isolation`). Da Docker auf dem Anwender-Rechner nicht laeuft (siehe `project_docker_unavailable`), muss der manuelle dev-Boot von einer Umgebung mit funktionierendem Docker gemacht werden oder das Postgres separat verfuegbar sein.
* TestContainers ist auf dem Anwender-Rechner ausgeschlossen ([[project_docker_unavailable]]). Alle neuen Tests werden weiterhin als reine Mockito-Unit-Tests geschrieben.

## 4. Backend-Architektur

### 4.1 Squadron-Kontext im Auth-Layer

Erweiterung von `AuthHelperService` um:

```java
Optional<UUID> currentSquadronId();   // squadron_id des eingeloggten Users, leer fuer Admin im "alle"-Modus oder Guest
boolean isAdmin();                    // ROLE_ADMIN via Role-Hierarchie
boolean canSeeSquadron(UUID id);      // isAdmin() || currentSquadronId().equals(Optional.of(id))
boolean canEditSquadron(UUID id);     // identisch fuer Schreib-Pfade
```

Neuer Service `SquadronScopeService`:

* Quelle der Wahrheit fuer alle "darf X auf Mission/Job/...?"-Entscheidungen.
* Wird in `@PreAuthorize` per SpEL aufgerufen, analog zum bestehenden `MissionSecurityService`.
* Methodensignatur z. B. `canReadMission(UUID missionId, Authentication auth)`, `canEditMission(...)`, `canReadJobOrder(...)`, etc.

Konsequenz: Authorization bleibt **am Controller-/Service-Eintritt**, nicht in der Business-Logik (CLAUDE.md-Regel).

### 4.2 Admin-Squadron-Switcher (Server-State)

Admins koennen ihren aktiven Kontext zwischen Staffeln umschalten. Der gewaehlte Kontext liegt in der Spring Session (Redis-backed) als `Optional<UUID>` (`null` = "alle Staffeln"). Wird in `AuthHelperService.currentSquadronId()` fuer Admins beruecksichtigt:

* Admin **mit** ausgewaehlter Squadron: filtert wie ein Mitglied dieser Squadron (uebersichtlicher Modus).
* Admin **ohne** Auswahl ("alle"): `currentSquadronId()` liefert `Optional.empty()`, `canSee*` gibt durch `isAdmin()` true zurueck → keine Filterung.

Vorteil: Admin-UX bleibt fuer das Tagesgeschaeft (1 Staffel) ergonomisch, ohne den globalen Ueberblick zu verlieren.

### 4.3 Repository-Schicht

Alle squadron-scoped Repositories bekommen einen zweiten Pfad:

* **Bestehende Methoden** (z. B. `MissionRepository.searchMissions(...)`) – Signatur wird um `UUID owningSquadronId` ergaenzt **plus** Boolean-Schalter `includeNonInternalFromOthers`.
* Spring-Data-`Specification`-Pattern bevorzugen, wo schon vorhanden, sonst JPQL mit zusaetzlichem `AND m.owningSquadron.id = :sid`-Clause.
* **Whitelist** fuer Admin-Pfad (Bulk-Listen, Cross-Squadron-Reports) – separate Methoden mit klarer Namensgebung `*ForAdmin(...)`, ueber `@PreAuthorize("hasRole('ADMIN')")` gesichert.

### 4.4 Service-Schicht

Pro Aggregat-Service eine zentrale Filter-Methode, die `AuthHelperService.currentSquadronId()` zieht und an die Repository-Methode reicht. Konkret betroffen:

| Service | Aenderung |
| :--- | :--- |
| `MissionService` | List/Detail-Pfade filtern via `owning_squadron_id` **oder** `is_internal = false`. Bearbeiten/Abschluss filtert strikt `owning_squadron_id`. |
| `JobOrderService` | **Kein** Squadron-Filter im List-/Detail-Pfad – alle authentifizierten User sehen alle Job Orders aller Staffeln. Beim Erstellen:<br>– `creating_squadron_id` wird automatisch aus `currentSquadronId()` befuellt; Admin ohne aktive Squadron muss das Feld explizit setzen (sonst 400). Nach dem Erstellen **immutable**.<br>– `requesting_squadron_id` ist **Pflichtfeld** im Request (UI-Default = `currentSquadronId()`, aber explizit ueberschreibbar auf eine andere Staffel). Nachtraeglich aenderbar durch Logistician+ – kein Squadron-Check.<br>Edit-/Delete-Berechtigungen folgen weiterhin der bestehenden Rechte-Matrix (Logistician+ / Admin), **ohne** Squadron-Check. Material-Management (`JobOrderMaterial`) folgt der gleichen Cross-Staffel-Logik. |
| `JobOrderHandoverService` | "Within-Transaction"-Methoden bleiben (CLAUDE.md-Regel) – nehmen die schon geladene Entity entgegen, kein zusaetzliches `save()`, keine 409-Falle. **Darf `InventoryItem`-Datensaetze fremder Staffeln schreiben** (Mengenabzug, `delivered`-Flag, Note-Update), sofern das Item per `job_order_id` an die aktuelle Order gebunden ist. Pre-Write-Check: `item.jobOrderId.equals(currentOrder.id)` – Verstoesse fuehren zu `IllegalStateException`. Handover-Record protokolliert ausfuehrenden User inkl. dessen Staffel als Audit-Information. |
| `InventoryService` | Direkte CRUD-/Lese-Pfade (Lager-View) **strikt staffel-gefiltert**. Lese-Pfad **innerhalb** eines Job-Order-Kontextes (`findByJobOrderId(...)`) **ohne** Staffel-Filter – jeder mit Job-Order-Read-Recht sieht alle verknuepften Items. Initialer Link (Setzen von `job_order_id`) bleibt nur durch Eigentuemer-Staffel des Items moeglich (Logistician+ dort), Entlinken analog. Cross-Staffel-Edit auf einem **nicht** an die aktuelle Order verknuepften Item ist immer verboten. |
| `RefineryOrderService` | strikt staffel-gefiltert. Owner-Check und Squadron-Check sind orthogonal: Owner kann eigene Order auch in fremder Staffel nicht editieren (kann technisch nicht vorkommen, da `owner.squadron_id == order.owning_squadron_id`). |
| `HangarService` | strikt staffel-gefiltert. |
| `OperationService` | strikt staffel-gefiltert. |
| `UserService` | List/Search fuer normale User: nur Mitglieder der eigenen Staffel. Admin-Pfad sieht alle. **Member-Management (Roll-Flags, Rank) wird auf `hasRole('ADMIN')` verschaerft.** |
| `AnnouncementService` | Schreib-Pfade auf Admin verschaerft (Officer verliert Zugriff). Lese-Pfad bleibt fuer alle authentifizierten User. |

### 4.5 DTOs

* Schreib-DTOs fuer staffel-scoped Aggregate (`CreateMissionRequest`, `CreateInventoryItemRequest`, `CreateRefineryOrderRequest`, ...) bekommen ein optionales `squadronId`-Feld. Wenn `null` → aus `currentSquadronId()` ableiten. Wenn gesetzt → nur fuer Admin akzeptiert (sonst 403).
* Schreib-DTOs fuer Job Order (`CreateJobOrderRequest`, `UpdateJobOrderRequest`) tragen **zwei** Felder:
  * `creatingSquadronId` (optional, nur durch Admin override-bar) – Default aus `currentSquadronId()`, **immutable** nach Erstellung.
  * `requestingSquadronId` (Pflicht) – jeder Logistician+ darf das Feld setzen oder spaeter aendern, unabhaengig von seiner eigenen Staffel.
* Lese-DTOs (`MissionListDto`, `JobOrderDto`, ...) bekommen die jeweiligen Squadron-Felder inkl. Shorthand-Display. Job-Order-DTOs tragen beide Felder (`creatingSquadron` + `requestingSquadron`) als geschachtelte Squadron-Mini-Records.
* **Frontend-Mirror-Records mitziehen** ([[feedback_backend_frontend_dto_mirror]]) – fehlt der Spiegel, kracht es zur Render-Zeit in prod.

### 4.6 ArchUnit-Regeln (neu)

Drei neue Regeln in beiden `ArchitectureTest.java` (backend + frontend, soweit applicable):

1. **Jede `@Service`-Klasse, deren Name in einer Whitelist auftaucht (`MissionService`, `InventoryService`, `RefineryOrderService`, `HangarService`, `OperationService`), muss `AuthHelperService` oder `SquadronScopeService` als Dependency haben** – stellt sicher, dass die Squadron-Filterung nicht vergessen wird. `JobOrderService` und `JobOrderHandoverService` sind **explizit ausgenommen** (cross-Staffel-Workspace, siehe 4.4), behalten aber `AuthHelperService` fuer den Owner-Stempel beim Erstellen und das Audit-Logging beim Handover.
2. **Keine direkte Verwendung von `Squadron`-Entity in Controller-Boundary-Methoden** – Pflicht zum DTO (existierende Regel deckt das implizit ab, wird explizit ausgesprochen).
3. **`@PreAuthorize("isAuthenticated()")` ist auf Schreib-Endpunkten staffel-scoped Aggregates verboten** – stattdessen `@PreAuthorize("@squadronScopeService.canEdit...")`. Pruefbar via Pattern-Match auf Methoden-Annotations.

## 5. Frontend

### 5.1 Squadron-Switcher (Admin)

* Neuer Dropdown im Header/Sidebar; visuell und strukturell wiederverwendet vom bestehenden Sprach-Dropdown (`.dropdown` / `.dropbtn` / `.dropdown-content` in `static/css/styles.css`).
* Sichtbar nur fuer `sec:authorize="hasRole('ADMIN')"`.
* Listet alle aktiven Staffeln + "Alle Staffeln" als Sentinel.
* POST an `/me/active-squadron` → schreibt in Session-Attribut `activeSquadronId`. Backend `AuthHelperService.currentSquadronId()` liest dort fuer Admins.
* Persistenz ueber Spring Session (Redis) → bleibt ueber Frontend-Neustarts erhalten (analog Login-State).

### 5.2 Anzeige des aktuellen Kontexts

* Permanent sichtbarer Kontext-Badge in der Sidebar / oben rechts: "IRIDIUM" / "Staffel X" / "Alle Staffeln (Admin)".
* Fuer Nicht-Admin: nicht klickbar, einfach Anzeige.
* Stil: dezent, nicht ablenkend; nutzt das vorhandene KRT-Styling (Ethnocentric Caps, Lato Body, primaerer Orange-Akzent auf der aktiven Auswahl).

### 5.3 Templates – betroffene Dateien

| Datei | Aenderung |
| :--- | :--- |
| `fragments/sidebar.html` | Admin-Submenue auf `hasRole('ADMIN')` verschaerfen (statt `hasAnyRole('ADMIN', 'OFFICER')`). Sprach-Dropdown bleibt; neuer Squadron-Dropdown wird hinzugefuegt. Kontext-Badge einbauen. |
| `admin/*.html` | Top-Level-Checks verschaerfen. |
| `missions.html`, `mission-detail.html` | Anzeige der Eigentuemer-Staffel pro Eintrag. Filter "Nur eigene Staffel" / "Inkl. nicht-interne Fremdstaffel" als Toggle. |
| `inventory-index.html`, `inventory-admin.html`, `hangar.html`, `hangar-squadron.html`, `refinery-orders-index.html` | Eigentuemer-Staffel-Spalte (sichtbar nur fuer Admin im "Alle"-Modus, da staffel-gefiltert). |
| Job-Order-Listen / -Detail (`job-orders*.html`) | **Zwei** Staffel-Badges/-Spalten **immer** sichtbar (Cross-Staffel-Workspace): "Auftraggeber" (`requestingSquadron`) und "Erstellt durch" (`creatingSquadron`). Bei Identitaet darf die UI sie zu einem Badge zusammenfassen. Default-Filter "Nur eigene Staffel" matcht gegen **beide** Felder (`creating ODER requesting = userSquadron`), frei umschaltbar auf "Alle Staffeln". Edit-Form bietet ein Dropdown zur Aenderung des Auftraggebers (Logistician+); der Ersteller ist immutable, wird als Read-only-Feld angezeigt. Im Detail-View: pro verknuepftem Lagereintrag Anzeige der Eigentuemer-Staffel (Transparenz, wer beitraegt) plus visueller Marker, falls der Eintrag einer fremden Staffel gehoert. |
| `mission-data.html` | Squadron-Stammdatenpflege (CRUD) wandert hierher; war heute bereits geplanter Anker. |

### 5.4 i18n

* Alle neuen Strings via `messages*.properties`. Umlaute als `\uXXXX`-Escapes (Hard-Regel).
* "IRIDIUM" als hardcoded Tooltext entfernen – `app.title` wird generisch ("Basetool") oder dynamisch (`Basetool – ${aktiveStaffel}`).
* Bestehende 28 squadron-bezogenen Keys werden uebernommen, fehlende Keys (z. B. `squadron.switcher.all`, `squadron.switcher.label`, `squadron.badge.admin`, `squadron.context.none`) ergaenzt.

### 5.5 Frontend-DTO-Mirror

Pro neuem Backend-DTO-Feld (`squadronId`, `squadronShorthand`, `owningSquadronId`) ein passendes Frontend-Record-Feld im Mirror anlegen ([[feedback_backend_frontend_dto_mirror]]). Build-Pipeline faengt das **nicht**; Thymeleaf-Template-Renderfehler erst in prod sichtbar.

### 5.6 Responsiveness

Alle neuen UI-Bestandteile (Switcher, Badge, Spalten) muessen ueber alle vier Geraeteklassen (Smartphone, Tablet, Desktop, Ultrawide) funktionieren. Squadron-Dropdown kollabiert auf Mobile in das bestehende Sidebar-Pattern. Zusatz-Spalten "Eigentuemer-Staffel" werden auf engen Viewports in die Detail-Ansicht verlagert.

## 6. Migration der Bestandsdaten

* **Alle existierenden Datensaetze gehoeren IRIDIUM.** Die Migrationen `V80`–`V83` setzen das atomar in SQL.
* IRIDIUM bekommt eine **feste UUID** (Konstante im Code, z. B. `Squadron.IRIDIUM_ID`), damit Backfill, `DataInitializer`-Seeds und Tests deterministisch sind.
* `DataInitializer` wird erweitert: legt IRIDIUM an, falls nicht vorhanden (idempotent).
* Bestehende User bekommen `squadron_id = IRIDIUM`, ausser Admins (die bleiben `NULL` = "alle Staffeln"). Differenzierung erfolgt anhand der `role`-Zuordnung. Risiko bei Personen mit mehreren Rollen: wenn jemand "Admin + Officer in IRIDIUM" ist, wird der Datensatz auf IRIDIUM gesetzt (vertraegt sich mit Admin, weil Admin sowieso ueber Hierarchie alle sieht).
* **Job Order – speziell:**
  * `creating_squadron_id` ist trivial = IRIDIUM (Single-Tenant-Vergangenheit – es gab keine andere Erstellerin).
  * `requesting_squadron_id` wird aus der bestehenden `squadron VARCHAR`-Spalte abgeleitet: SQL-Backfill mit LEFT JOIN auf `squadron.name` **und** `squadron.shorthand` (case-insensitive); nicht-aufloesbare Strings fallen auf IRIDIUM zurueck und werden im Migrations-Log gezaehlt.
  * Pre-Migration-Diagnose: `SELECT DISTINCT squadron FROM job_order` im PR-Kommentar protokollieren, damit klar ist, welche Strings auf IRIDIUM gemappt werden bzw. ob neue Squadron-Stammdaten **vor** der Migration eingelegt werden muessen.
  * Post-Migration-Sanity: `SELECT COUNT(*) FROM job_order WHERE requesting_squadron_id = IRIDIUM_UUID AND squadron <> 'IRIDIUM'` muss `0` ergeben (oder die abweichenden Faelle sind dokumentierte Fallbacks).
  * Bestehende `squadron VARCHAR`-Spalte bleibt fuer mind. einen Release-Zyklus stehen (`V88` lockert NOT NULL, `V90` droppt; ehemals `V88`/`V89`, durch Solo-Deploy-Reshuffle verschoben).

## 7. Sicherheit / Datenschutz

* Cross-Squadron-Information-Disclosure ist die Hauptgefahr. Mitigierung:
  * Service-Schicht filtert immer; Controller validiert nur Input.
  * ArchUnit-Regel erzwingt Dependency-Vorhandensein (siehe 4.6).
  * Mockito-Unit-Tests pro Service mit zwei Szenarien: "User aus Staffel A liest" / "User aus Staffel B liest" → erwarten disjunkte Ergebnislisten.
  * Bei `mission` mit `is_internal = true` zusaetzlich ein Test "User aus Staffel B sieht NICHT" – sonst regressionsanfaellig.
* **Cross-Staffel-Job-Order-Workspace ist gewollt** – Job Orders, deren `JobOrderMaterial`-Eintraege und die per `job_order_id` verknuepften InventoryItems sind absichtlich nicht staffel-gefiltert. Daraus folgt:
  * Repository-Methoden sind klar zweigeteilt: `findInventoryForSquadronLager(owningSquadronId, ...)` (Lager-View, immer gefiltert) vs. `findInventoryByJobOrderId(jobOrderId)` (Order-Kontext, ungefiltert). Beide Pfade duerfen niemals vermischt werden.
  * Mockito-Tests pruefen explizit: gemischter Datensatz mit Items aus Staffel A und Staffel B, beide Methoden geben disjunkte/ueberlappende Mengen wie spezifiziert zurueck.
  * Handover-Pre-Write-Check `item.jobOrderId.equals(currentOrder.id)` verhindert, dass die Cross-Staffel-Schreibwirkung versehentlich auf nicht-verknuepfte fremde Items angewandt wird.
* **Guest-Pfade** (`cleanupForGuest`-Pattern, CLAUDE.md): Guest sieht nicht-interne Missionen; sensitive Felder weiterhin entfernt. Squadron-Zugehoerigkeit (Shorthand) ist NICHT sensitiv → darf bleiben.
* **Logging**: pro Anfrage zusaetzlich MDC-Feld `squadronId` (= Kontext-Squadron oder `none`). Hilfreich fuer Audit/Forensik. Nicht in den User-PII-Bereich (Name, Email, Token bleiben unlogbar).

## 8. Concurrency / Optimistic Locking

* Bestehende `@Version`-Felder bleiben. `mission.flags_version` (V77) bleibt.
* Backfill-Migrationen schreiben **direkt per SQL**, **nicht** via Entity-`save()` – sonst springt `@Version` und es kommt zu falscher Drift. (Steht so schon im Migrations-README.)
* `owning_squadron_id` ist nach Backfill **immutable** auf Aggregat-Roots (Mission, Inventory, Ship, Refinery, Operation). Es gibt keinen "Mission von Staffel A nach Staffel B verschieben"-Use-Case in Phase 1. Falls spaeter gebraucht, ueber dedizierten `transferToSquadron`-Endpoint mit Admin-Check + Audit-Eintrag.
* **Job Order – Sonderfall:** `creating_squadron_id` ist immutable nach Erstellung; `requesting_squadron_id` ist **editierbar** durch jeden Logistician+ (Cross-Staffel-Workspace). Aenderungen am `requestingSquadron` durchlaufen den normalen `@Version`-Bump und sind durch das bestehende Optimistic-Locking abgesichert.

## 9. API-Vertraegliche Aenderungen

* **Keine Versionsanhebung** noetig. Bestehende `/api/v1/...`-Endpunkte werden additiv erweitert (`squadronId`-Felder im Request/Response). Existierende Clients ohne das Feld senden `null`, was als "eigene Staffel" interpretiert wird.
* Job-Order-Endpunkte (`/api/v1/job-orders/**`) tragen **zwei** Felder (`creatingSquadronId`, `requestingSquadronId`); fuer Backward-Compatibility wird ein fehlendes `requestingSquadronId` mit dem `creatingSquadronId`-Wert (= `currentSquadronId()`) initialisiert, sodass Bestands-Clients ohne Schemaerweiterung weiter funktionieren.
* Schreib-Endpunkte **staffel-scoped Aggregate** (Mission – `is_internal`-respektierend, Inventory-Direktpfad, Refinery, Hangar, Operation) werden via `@PreAuthorize` auf `SquadronScopeService.canEdit*` umgestellt. Effekt fuer User in IRIDIUM: keiner. Effekt fuer kuenftige User anderer Staffeln: korrekte Filterung.
* Schreib-Endpunkte **Job-Order-Aggregat** (`/api/v1/job-orders/**` inkl. Handover, Material-Management) **behalten** die bestehenden Rollen-Checks ohne Squadron-Komponente (Logistician+ darf editieren, Admin darf loeschen). Squadron-Information im Request ist optional und wird – sofern vorhanden – nur fuer den Owner-Stempel beim Erstellen genutzt.
* Kein Endpunkt wird in Phase 1 entfernt; veraltete Pfade (z. B. Officer-Routen im Admin-Bereich) werden mit `@ApiDeprecation` markiert und nach `V88`/`V90`/Phase 2 entsorgt.
* OpenAPI-Spec (`openapi.json`) wird mitgepflegt.

## 10. Phasenplan / Schnittfolge

Pro Phase: ein Commit-Schwerpunkt, mit Tests, Linting (Checkstyle + SpotBugs warning-free auf neuem/geaenderten Code), Spotless-Apply, CHANGELOG-Eintrag und (wo passend) ROLES_AND_PERMISSIONS-Update.

### Phase 1 – Datenmodell (additiv, nullable)
* Flyway `V80` (IRIDIUM-Seed), `V81` (`app_user.squadron_id`), `V82` (5 Aggregat-Roots `owning_squadron_id` nullable + Backfill), `V83` (`job_order.creating_squadron_id` + `requesting_squadron_id` nullable + Backfill aus VARCHAR).
* JPA-Entity-Anpassungen + JavaDoc.
* `DataInitializer` legt IRIDIUM idempotent an.
* Tests: Mockito-Unit-Test fuer `DataInitializer`-Idempotenz; Entity-Mapping-Test.

### Phase 2 – Authorization-Layer
* `AuthHelperService`-Erweiterung (`currentSquadronId`, `isAdmin`, `canSee*`, `canEdit*`).
* Neuer `SquadronScopeService` + Unit-Tests.
* Spring Session-Eintrag fuer `activeSquadronId` (Redis).
* Bestehende `@PreAuthorize`-Annotationen an staffel-scoped Endpunkten umstellen.
* ArchUnit-Regeln aktualisieren (Service-Dependency-Check).

### Phase 3 – Repository / Service-Filter
* Aggregat-Service fuer Aggregat-Service durchgehen: Repository-Methoden um `owningSquadronId`-Filter ergaenzen, Service-Schicht zieht den Kontext aus `AuthHelperService`.
* Mission-Sichtbarkeit: List-Pfad implementiert die `owning OR (NOT is_internal)`-Logik.
* **Job-Order-Sonderfall:** `JobOrderRepository` und `JobOrderHandoverRepository` bekommen **keinen** Squadron-Filter; `JobOrderMaterialRepository` ebenfalls nicht. `InventoryItemRepository` wird **zweigeteilt** in `findForSquadronLager(...)` (gefiltert) und `findByJobOrderId(...)` (ungefiltert). Handover-Service erhaelt den `item.jobOrderId == currentOrder.id`-Guard.
* Tests: pro Aggregat-Service mind. zwei Tests "User aus Staffel A vs. B" + ein Test "is_internal-Mission ist cross-squadron unsichtbar" + Cross-Staffel-Job-Order-Tests gem. Abschnitt 11.

### Phase 4 – Admin-Bereich verschaerfen
* Backend: alle Admin-Controller von `hasAnyRole('ADMIN', 'OFFICER')` auf `hasRole('ADMIN')`.
* User-Management-Endpunkte (Roll-Flags, Rank, Member-Edit) auf `hasRole('ADMIN')` ziehen.
* Announcement-Schreib-Pfade auf `hasRole('ADMIN')` ziehen.
* Frontend: `sec:authorize`-Bedingungen in `sidebar.html` und allen `admin/*.html` anpassen.
* ROLES_AND_PERMISSIONS.md neu schreiben (Matrix korrekt).
* Tests: Mockito-Tests pro Endpunkt mit Officer-Auth → 403 erwartet.

### Phase 5 – Frontend-Squadron-Kontext
* Squadron-Switcher-Dropdown (Sidebar) fuer Admin, Kontext-Badge fuer alle.
* `MeController` (oder analoger Endpunkt) fuer `POST /me/active-squadron`.
* Lege-Pfade aller squadron-bewussten Templates zeigen Eigentuemer-Staffel-Spalten (admin only).
* `app.title` wird generisch + Kontext-Suffix.
* i18n-Keys ergaenzen, Umlaut-Encoding pruefen.
* Frontend-DTO-Mirror konsistent halten.
* Manuelle Browser-Verifikation auf den vier Geraeteklassen.

### Phase 6 – Tests, Lint, Docs, manueller Stack-Test
* `./gradlew check` muss gruen sein (Checkstyle/SpotBugs/Spotless/ArchUnit).
* CHANGELOG.md (UTF-8 Umlaute) gepflegt.
* README.md: neue Env-Vars (falls vorhanden), neue Squadron-Konzept-Sektion.
* ROLES_AND_PERMISSIONS.md: kompletter Rewrite der Matrix.
* CLAUDE.md: Multi-Tenant-Sektion ergaenzen ("Squadron-Filterung MUSS in Service-Schicht passieren, niemals in Controller-Bedingung"). Spiegelt die Concurrency- und API-Sektion.
* Manueller dev-Stack-Boot (mit `.env.test`-Pendant – produktive `.env` weiterhin tabu, siehe [[feedback_env_test_isolation]]).

### Phase 7 – Release 1 deployen, **dann** Folge-Release fuer Cleanup
* **Erst nach prod-Deploy** von Phase 1–6 (inkl. `V84` Handover-Audit, `V85` Promotion-Topic-Squadron und `V86` Promotion-Toggle) die Folge-Migrationen einreihen. **Slot-Reshuffle:** urspruenglich als `V87`/`V88`/`V89` reserviert, durch den Solo-Deploy von `V88` (Phase 7 Teil 1 via PR #132 ohne den damals geplanten `V87`-Companion) auf finale Form `V88`/`V89`/`V90` verschoben — Flyway `out-of-order=false` (Projekt-Default) rejected jede neu hinzukommende Migration mit `version <= flyway_schema_history.latest_applied`, also musste die nachgereichte NOT-NULL-Tightening-Datei oberhalb von V88 landen.
  * `V88` – `job_order.squadron`-VARCHAR aus Entity entfernen, DB-Spalte stop-write (NOT NULL droppen, Indizes loeschen). **Bereits in Prod** (PR #132, Commit `a00e5c8`).
  * `V89` – tightening: `SET NOT NULL` fuer `owning_squadron_id` (5 Aggregat-Tabellen + `promotion_topic`) sowie `creating_squadron_id` + `requesting_squadron_id` (`job_order`). Phase 7 Teil 2, urspruenglich V87.
  * `V90` – `DROP COLUMN job_order.squadron` (erst in der **uebernaechsten** Release-Iteration nach `V88`/`V89`, gem. Zweiphasen-Regel aus `db/migration/README.md`). Urspruenglich V89.

## 11. Tests

Pro Phase ein Test-Block – durchgaengig Mockito-Unit-Tests, **nie** TestContainers ([[project_docker_unavailable]]):

* **AuthHelperService**: `currentSquadronId()` korrekt fuer Admin (Session-Eintrag), Member (User-FK), Guest (`Optional.empty()`).
* **SquadronScopeService**: Wahrheitstabelle pro Methode (Admin alle, Mitglied eigene, Fremd-Mitglied bei `is_internal=false` Mission ja, sonst nein).
* **MissionService/Repository**: Cross-Squadron-Sichtbarkeitsmatrix (interne vs. nicht-interne Mission, Owner-Staffel vs. Fremd-Staffel).
* **JobOrderService**: Cross-Staffel-Sichtbarkeit (User aus Staffel A liest Order von Staffel B → 200) und Cross-Staffel-Editierbarkeit (Logistician aus Staffel A editiert Order von Staffel B → 200; Squadron-Member ohne Logistician-Recht → 403, gleiches Verhalten wie heute, ohne Squadron-Komponente). Zusatz-Tests fuer das Dual-Squadron-Modell:
  * Create mit `creatingSquadronId != requestingSquadronId` → beide Werte korrekt persistiert.
  * Nachtraegliche Aenderung von `creatingSquadronId` → 400/409 (immutable).
  * Nachtraegliche Aenderung von `requestingSquadronId` durch beliebigen Logistician+ aus beliebiger Staffel → 200; `@Version` bumpt korrekt.
  * List-Filter "Nur eigene Staffel" matcht Orders, deren `creating ODER requesting` der User-Staffel entsprechen.
  * Backward-Compat: Create-Request ohne `requestingSquadronId` → `requestingSquadronId` wird auf `creatingSquadronId` gesetzt.
* **JobOrderHandoverService**: Handover durch User aus Staffel A auf einer Order mit `InventoryItem` aus Staffel B → erfolgreich; Item-Menge sinkt korrekt, `delivered` wird gesetzt, Handover-Record speichert Ausfuehrer inkl. dessen Staffel als Audit. Negativ-Test: Versuch, ein nicht an die Order verknuepftes Fremd-Item zu schreiben → `IllegalStateException` / 400.
* **InventoryService – Lager-View**: User aus Staffel A liest Lager direkt → sieht **nur** Items aus Staffel A. Items mit `job_order_id != null` von Staffel B tauchen im Staffel-A-Lager **nicht** auf, auch nicht wenn die Order cross-Staffel sichtbar ist.
* **InventoryService – Job-Order-Kontext**: `findByJobOrderId(jobOrderId)` liefert User aus Staffel A alle verknuepften Items inkl. derer aus Staffel B.
* **RefineryOrderService / HangarService**: strikter Staffel-Filter (Tests pro Aggregat: Cross-Staffel-Lesen 403/404).
* **UserService**: User-Search Filter pro Aufrufer-Rolle (Member sieht eigene Staffel, Admin sieht alle).
* **Admin-Controller-403-Tests**: Officer hat 403 auf `/api/v1/admin/*`.
* **ArchUnit**: neue Regeln (Service-Dependency-Check, PreAuthorize-Style-Check).

Keine Tests gegen produktive Credentials (`backend/src/main/resources/keystore.p12`, prod `.env`, `realm-export.json`) – Hard-Regel CLAUDE.md "Testing"-Abschnitt.

## 12. Risiken und Gegenmassnahmen

| Risiko | Gegenmassnahme |
| :--- | :--- |
| **Cross-Squadron-Leak durch vergessenen Service-Filter** | ArchUnit-Regel (Service-Dependency), Mockito-Tests pro Aggregat, Reviewer-Checkliste in CHANGELOG. |
| **Linked-Inventory-Leak: User aus Staffel A sieht InventoryItem von Staffel B im Lager-View** (statt nur im Job-Order-Kontext) | Repository klar zweigeteilt: `findInventoryForSquadronLager(owningSquadronId, ...)` (Lager-View, immer gefiltert) vs. `findInventoryByJobOrderId(jobOrderId)` (Order-Kontext, ungefiltert). Mockito-Test mit gemischtem Datensatz fuer beide Methoden. ArchUnit-Regel: `InventoryItemRepository`-Methoden ohne `owningSquadronId`- **oder** `jobOrderId`-Parameter sind verboten (whitelistbar via expliziter Annotation `@AdminInventoryQuery` fuer Admin-Reports). |
| **Cross-Staffel-Handover schreibt versehentlich auf nicht-verknuepftes InventoryItem fremder Staffel** | Handover-Service prueft vor jedem Write: `item.jobOrderId.equals(currentOrder.id)`. Verstoss → `IllegalStateException`. Unit-Test deckt den Pfad ab. |
| **Job-Order-Backfill von `requesting_squadron_id` aus VARCHAR matcht falsch oder verliert Daten** | SQL-Backfill mit LEFT JOIN auf `squadron.name` UND `squadron.shorthand` (case-insensitive); nicht-aufloesbare Strings → IRIDIUM-Fallback mit Log-Eintrag. Pre-Migration-Diagnose-Query (`SELECT DISTINCT squadron FROM job_order`) verpflichtend im PR-Kommentar. Post-Migration-Sanity-Count auf inkonsistente Faelle. Falls noch andere Staffeln vor der Migration angelegt werden muessen, geschieht das via separatem Seed-Schritt **vor** `V83`. |
| **Admin verliert Ueberblick durch Switcher-State** | Default-Kontext = "Alle Staffeln" + sichtbarer Badge. Admin kann jederzeit zurueckschalten. |
| **Officer-Erwartung "ich war Quasi-Admin"** | ROLES_AND_PERMISSIONS.md klar dokumentieren, CHANGELOG explizit als Breaking-Change-Hinweis ("BREAKING: OFFICER verliert Admin-Bereich"). |
| **Backfill geht schief, alte JobOrders haben falsche Staffel** | Backfill in SQL, idempotent, mit `WHERE owning_squadron_id IS NULL`. Verifizierbar via Count-Query. |
| **Optimistic-Locking-409 durch Entity-Save in Backfill** | Migration nutzt nur reines SQL – keine Entity-Operationen. |
| **Hibernate-validate-Fehler beim ersten Boot nach Phase 1** | Migration und Entity-Aenderung im **selben** Commit (Migrations-README-Regel). Manueller dev-Boot vor Merge. |
| **i18n-Luecken (Umlaut-Encoding, IRIDIUM-Hardcodes)** | Pre-Merge-Grep auf `IRIDIUM` in templates + properties; Tests fuer `messages*.properties`-Encoding falls vorhanden, sonst manuelle Inspektion. |
| **Frontend-DTO-Mirror vergessen** | Jeder Backend-DTO-Field-Commit zieht den Frontend-Record-Commit mit ([[feedback_backend_frontend_dto_mirror]]). |
| **Docker nicht verfuegbar fuer Migrations-Probelauf** | Migrationsausfuehrung manuell gegen externes Postgres oder in CI vor Merge ([[project_docker_unavailable]]). |

## 13. Was ich **bewusst nicht** im Plan habe

* **Mandanten-RLS / PostgreSQL Row-Level-Security**: Schwergewichtig, schwieriger zu testen, lohnt sich erst, wenn die Applikationsfilterung nicht reicht. Heute ueberkonfiguriert.
* **Pro-Staffel-Theming / pro-Staffel-Logos**: nicht beauftragt, Styleguide stellt das Kartell-Branding fix.
* **Pro-Staffel-Keycloak-Realm**: ein Realm, ein Token-Issuer; Staffel-Mitgliedschaft ist Applikationsdaten, kein Identity-Provider-Konzept. Aenderung waere disruptiv und nicht angefordert.
* **Cross-Squadron-Mission-Finance-Aggregation**: heute werden Finanz-Eintraege pro Participant gepflegt; `mission_participant.squadron_id` bleibt dafuer die Quelle. Detaillierte Cross-Squadron-Auszahlungslogik (z. B. Anteil an fremde Staffeln) sprenge den Rahmen und ist hier nicht angefasst.
* **Loeschen von Staffeln / Verschmelzen**: nicht in Phase 1. Stattdessen `squadron.active` umschalten.

## 14. Abnahme

Bitte ueberpruefen und freigeben oder Korrekturen anfordern. Insbesondere:

1. Sind die **Sichtbarkeitsregeln** in Abschnitt 1 wie gewuenscht?
2. Ist die **Officer-Rechte-Reduktion** in Abschnitt 2 so gewollt (komplett raus aus Admin-Bereich + User-Management)?
3. **Ein-Staffel-pro-User** OK oder soll ein User in mehreren Staffeln gleichzeitig sein duerfen (waere ein Join-Table statt FK)?
4. **Admin-Default-Kontext** "Alle Staffeln" OK, oder lieber "letzte aktive Staffel"?
5. **Phasen-Schnitt** (Phase 1–6 jetzt, Phase 7 spaeter) akzeptabel, oder lieber alles in einem Rutsch (riskanter, aber atomar)?

Nichts wird umgesetzt, bis explizite Freigabe vorliegt.
