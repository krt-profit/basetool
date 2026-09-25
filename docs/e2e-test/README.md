# E2E-Test Use Cases

Dieses Verzeichnis dokumentiert die End-to-end-Testszenarien des Profit Basetool als Use Cases. Jeder Use Case beschreibt Akteur, Vorbedingungen, Ablauf und erwartetes Ergebnis und verlinkt die implementierenden Playwright-Testklassen; eng verwandte Klassen eines Bereichs teilen sich einen Use Case. **Jede Testklasse der Suite gehört zu genau einem Use Case** — mit Ausnahme der vier Ad-hoc-Prüfharnische am Ende dieser Seite.

Die Suite liegt im Source-Set `e2e` des Moduls `frontend` (`frontend/src/e2e/java/…/frontend/e2e/`). Für Aufbau, Cross-Browser-Matrix und Befehle siehe auch den Abschnitt *End-to-end (E2E) tests* im [Projekt-README](../../README.md).

## Übersicht

### Grund-Flows

|                         ID                          |                        Use Case                         |   Tag   |                                          Testklasse                                          |
|-----------------------------------------------------|---------------------------------------------------------|---------|----------------------------------------------------------------------------------------------|
| [UC-01](UC-01-login.md)                             | Login via Keycloak                                      | `e2e`   | `LoginSmokeE2eTest`                                                                          |
| [UC-02](UC-02-mission-anlegen.md)                   | Einsatz anlegen                                         | `e2e`   | `MissionCreateE2eTest`                                                                       |
| [UC-03](UC-03-job-order-anlegen.md)                 | Job Order anlegen                                       | `e2e`   | `JobOrderCreateE2eTest`                                                                      |
| [UC-04](UC-04-refinery-order-anlegen.md)            | Refinery Order anlegen                                  | `e2e`   | `RefineryOrderCreateE2eTest`                                                                 |
| [UC-05](UC-05-hangar-schiff-hinzufuegen.md)         | Schiff zum Hangar hinzufügen                            | `e2e`   | `HangarAddShipE2eTest`                                                                       |
| [UC-06](UC-06-job-order-handover.md)                | Job-Order-Handover protokollieren                       | `e2e`   | `JobOrderHandoverE2eTest`                                                                    |
| [UC-07](UC-07-kernseiten-smoke.md)                  | Kernseiten-Smoke (nicht-destruktiv)                     | `smoke` | `CorePagesSmokeE2eTest`                                                                      |
| [UC-13](UC-13-inventar-operationen.md)              | Inventar: Ein-/Aus-/Umbuchen, Verkauf, Zuordnung        | `e2e`   | `InventoryOperationsE2eTest`                                                                 |
| [UC-15](UC-15-job-order-bearbeiten.md)              | Job Order bearbeiten                                    | `e2e`   | `JobOrderEditE2eTest`                                                                        |
| [UC-16](UC-16-job-order-status.md)                  | Job-Order-Status ändern                                 | `e2e`   | `JobOrderStatusE2eTest`                                                                      |
| [UC-17](UC-17-item-order-handover.md)               | Item-Auftrag & Item-Handover                            | `e2e`   | `JobOrderItemHandoverE2eTest`                                                                |
| [UC-19](UC-19-refinery-order-einlagern.md)          | Refinery Order einlagern (in das Lager)                 | `e2e`   | `RefineryOrderStoreE2eTest`                                                                  |
| [UC-20](UC-20-refinery-order-lifecycle.md)          | Refinery Order: Bearbeiten/Abbrechen/Filter/Live-Sync   | `e2e`   | `RefineryOrderLifecycleE2eTest` · `RefineryOrderLiveSyncE2eTest`                             |
| [UC-22](UC-22-mission-finanzeintrag.md)             | Einsatz: Finanzeintrag anlegen & Detail erneut öffnen   | `e2e`   | `MissionFinanceEntryE2eTest`                                                                 |
| [UC-23](UC-23-job-order-bearbeiter-notizen.md)      | Job Order: Bearbeiter ein-/austragen & Notizen          | `e2e`   | `JobOrderAssigneeNotesE2eTest`                                                               |
| [UC-24](UC-24-refinery-import-extract.md)           | Refinery Order aus Screenshot-Extract importieren       | `e2e`   | `RefineryImportE2eTest`                                                                      |
| [UC-25](UC-25-befoerderung-themenbereich-crud.md)   | Beförderung: Themenbereich anlegen/umbenennen/löschen   | `e2e`   | `PromotionTopicCrudE2eTest`                                                                  |
| [UC-26](UC-26-mein-inventar-crud.md)                | Mein Inventar: Eintrag anlegen & löschen                | `e2e`   | `PersonalInventoryCrudE2eTest`                                                               |
| [UC-27](UC-27-benachrichtigung-gelesen.md)          | Benachrichtigung erhalten & als gelesen markieren       | `e2e`   | `NotificationCenterE2eTest`                                                                  |
| [UC-28](UC-28-mitglied-bearbeiten.md)               | Mitglied bearbeiten (In-Place-Save, zweite Staffel)     | `e2e`   | `MemberEditInPlaceE2eTest`                                                                   |
| [UC-29](UC-29-admin-missions-stammdaten-staffel-crud.md) | Admin Missions-Stammdaten: Staffel anlegen & löschen | `e2e`   | `AdminMissionDataCrudE2eTest`                                                                |
| [UC-30](UC-30-item-lager-operationen.md)            | Item-Lager: Ein-/Um-/Ausbuchen, Zuordnungs-Gate, Sync   | `e2e`   | `ItemInventoryOperationsE2eTest`                                                             |

### Rollen, Mandanten & staffel-/SK-übergreifend

|                      ID                            |                                        Thema                                         |  Tag  |                   Testklasse                   |
|----------------------------------------------------|---------------------------------------------------------------------------------------|-------|------------------------------------------------|
| [Rollen & Scope](rollen-und-scope.md)              | Referenz: Rollen × Flow-Matrix, Mandanten-Scope, Admin-Pin, SK-Grundlagen             | `e2e` | `RolePermissionsE2eTest` (Control-Gating je Rolle) |
| [UC-08](UC-08-job-order-staffel-uebergreifend.md)  | Job Order: Staffel A bestellt, Staffel B liefert                                      | `e2e` | `CrossStaffelJobOrderE2eTest`                  |
| [UC-09](UC-09-handover-staffel-uebergreifend.md)   | Handover staffel-übergreifend                                                         | `e2e` | `CrossStaffelHandoverE2eTest`                  |
| [UC-10](UC-10-mission-staffel-uebergreifend.md)    | Organisationsweiter Einsatz, gesehen von einer anderen Staffel                        | `e2e` | `OrgWideMissionCrossStaffelE2eTest`            |
| [UC-11](UC-11-sk-spezialkommando.md)               | Spezialkommando anlegen/deaktivieren, Grenze der Profit-Eligibility                   | `e2e` | `SpecialCommandE2eTest`                        |
| [UC-12](UC-12-mitgliederbereich.md)                | Mitgliederbereich: ohne Anmeldung nur Startseite und Rechtsseiten; Konto ohne Rolle   | `e2e` | `AnonymousSurfaceE2eTest` · `NoRoleGateE2eTest` |
| [UC-14](UC-14-inventar-mandanten-scope.md)         | Inventar-Mandanten-Scope über alle Mitgliedschaftsprofile + Admin-Pin                 | `e2e` | `InventoryTenancyE2eTest`                      |
| [UC-18](UC-18-job-order-mandanten-sichtbarkeit.md) | Job Order: SK-Warteschlange vs. staffel-privat, Requester-Escape                      | `e2e` | `JobOrderTenancyE2eTest`                       |
| [UC-21](UC-21-refinery-order-mandanten-scope.md)   | Refinery-Mandanten-Scope + Admin-Pin + BAC-004                                        | `e2e` | `RefineryOrderTenancyE2eTest`                  |
| [UC-40](UC-40-organigramm-hierarchie-ernennungen.md) | Organigramm, Bereichs-Hierarchie & Ernennungen                                      | `e2e` | `OrgChartPositionCrudE2eTest` · `OrgChartKeyboardA11yE2eTest` · `OrgHierarchyVisibilityMatrixE2eTest` · `RoleAppointmentMatrixE2eTest` |

> **Hinweis zur Abdeckung:** Einsätze/Operationen und Refinery Orders sind **strict-staffel**. Die staffel-übergreifende Zusammenarbeit läuft über organisationsweite Einsätze (UC-10) und den Job-Order-Workspace inkl. Handover (UC-08/UC-09). Details in [Rollen & Scope](rollen-und-scope.md).

### Weitere Bereiche

|                         ID                           |                              Use Case                               |      Tag      |                                                                                         Testklasse                                                                                         |
|------------------------------------------------------|----------------------------------------------------------------------|---------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [UC-31](UC-31-kartellbank-buchen-dashboard.md)       | Kartellbank: Buchen, Dashboard & Admin-Reset                         | `e2e`         | `BankBookingE2eTest` · `BankDashboardE2eTest` · `BankAdminResetE2eTest`                                                                                                                    |
| [UC-32](UC-32-kartellbank-antraege-berechtigungen.md) | Kartellbank: Einheiten-Anträge, Berechtigungen & Sichtbarkeit       | `e2e`         | `BankOrgUnitRequestsE2eTest` · `BankPermissionsE2eTest` · `BankRequestsLiveSyncE2eTest` · `OrgUnitBankVisibilityMatrixE2eTest`                                                            |
| [UC-33](UC-33-einsatz-bearbeiten-live-sync.md)       | Einsatz: In-Place-Bearbeitung, Crew-Board & Live-Sync                | `e2e`         | `MissionCoreEditInPlaceE2eTest` · `MissionUnitResponsibleClearE2eTest` · `MissionParticipantCountE2eTest` · `MissionListFilterInPlaceE2eTest` · `MissionLiveSyncE2eTest` · `MissionOrganisationLiveSyncE2eTest` · `MissionCrewBoardTouchDragE2eTest` · `MissionOwnerChangeE2eTest` |
| [UC-34](UC-34-operationen-in-place-live-sync.md)     | Operationen: In-Place-Schreibvorgänge & Live-Sync                    | `e2e`         | `OperationWritesInPlaceE2eTest` · `OperationLiveSyncE2eTest` · `OperationMissionCrossPublishLiveSyncE2eTest`                                                                               |
| [UC-35](UC-35-job-order-materialbedarf-produktion.md) | Job Order: Materialbedarf, Herstellung, Lager-Verknüpfung lösen     | `e2e`         | `JobOrderMaterialDemandE2eTest` · `JobOrderProductionE2eTest` · `JobOrderInventoryUnlinkInPlaceE2eTest`                                                                                    |
| [UC-36](UC-36-auftragswarteschlange-auftragsformular.md) | Auftragswarteschlange & Auftragsformular                          | `e2e`         | `JobOrderQueueLiveSyncE2eTest` · `JobOrderReactivatePriorityInPlaceE2eTest` · `OrdersSquadronFilterE2eTest` · `OrdersCreateItemLineRendersE2eTest` · `OrdersCreateScuHintRevealE2eTest`   |
| [UC-37](UC-37-materialboerse-materialsammlung.md)    | Materialbörse & Materialsammlung eines Auftrags                      | `e2e`         | `MaterialboardItemStockOfferE2eTest` · `MaterialboardOfferedAmountFieldE2eTest` · `MaterialboardPickerServerSearchE2eTest` · `MaterialboardQuantityFieldExclusivityE2eTest` · `MaterialboardReleaseModalOpensE2eTest` · `MaterialboardRequestModalE2eTest` · `MaterialCollectionDeliveredInPlaceE2eTest` · `MaterialCollectionTransferInPlaceE2eTest` |
| [UC-38](UC-38-materialien-seiten.md)                 | Materialien: Preisübersicht, Preiskalkulation & Kategorien           | `e2e`         | `MaterialsOverviewMatrixRendersE2eTest` · `MaterialsOverviewFilterPersistenceE2eTest` · `MaterialsProfitCalculationRendersE2eTest` · `MaterialsCategoryEmptyStateInPlaceE2eTest`          |
| [UC-39](UC-39-lager-hangar-ansichten-filter.md)      | Lager & Hangar: Ansichten, Filter, Paginierung & Live-Sync           | `e2e`         | `InventoryStackViewE2eTest` · `InventoryFilterPanelCollapseE2eTest` · `LagerLocationFilterE2eTest` · `InventorySharedLagerLiveSyncE2eTest` · `FilterPersistenceE2eTest` · `HangarPaginationE2eTest` |
| [UC-41](UC-41-profil-blaupausen.md)                  | Profil & Standard-Blaupausen                                         | `e2e`         | `ProfileDescriptionInPlaceE2eTest` · `ProfilePayoutPreferenceInPlaceE2eTest` · `ProfileBlueprintSharingInPlaceE2eTest` · `DefaultBlueprintsE2eTest`                                        |
| [UC-42](UC-42-admin-einstellungen-audit-log.md)      | Admin: Systemeinstellungen, Audit-Log & Adminseiten-Smoke            | `e2e`         | `AdminSettingsInPlaceE2eTest` · `AuditLogE2eTest` · `AdminPagesSmokeE2eTest` · `AdminMaterialCreateInPlaceE2eTest`                                                                                                               |
| [UC-43](UC-43-ingest-uebergabe.md)                   | Ingest-Übergabe aus dem Desktop-Extractor                            | `e2e`         | `IngestHandoffE2eTest`                                                                                                                                                                      |
| [UC-44](UC-44-barrierefreiheit-touch-layout.md)      | Barrierefreiheit & Layout je Geräteklasse                            | `e2e` `smoke` | `AccessibilitySmokeE2eTest` (`e2e` + `smoke`) · `TouchClassLayoutE2eTest` (`e2e`)                                                                                                           |
| [UC-45](UC-45-ui-regressionen-formular-navigation.md) | UI-Regressionen: Zurück-Cache, Combobox, Datum/Zeit-Layout          | `e2e`         | `BfcacheRefreshE2eTest` · `ComboboxBlurRestoresValueE2eTest` · `MissionDatetimeSplitLayoutE2eTest`                                                                                          |

### Ad-hoc-Prüfharnische (kein Use Case)

Vier Klassen tragen `@Tag("e2e")`, sind aber keine Tests im Sinne dieser Suite: Sie gehen gegen einen **schon laufenden** lokalen Stack (`E2E_BASE_URL`), schießen Screenshots für einen visuellen Abgleich mit einem Mockup und tun nichts, solange ihre Umgebungsvariable nicht gesetzt ist. Ohne sie überspringt eine JUnit-Annahme (`assumeTrue`) in `@BeforeAll` die ganze Klasse, **bevor** ein Browser startet; in der CI erscheinen sie daher als *skipped*, nicht als grün. Gesetzt starten sie den Browser über `E2eSupport.launchBrowser`, also die Engine aus `-Pe2e.browser`.

Die Reihenfolge ist tragend (korrigiert 2026-09-23): Bis dahin starteten die vier Klassen fest Chromium und prüften ihre Variable erst im Test. Seit `playwrightInstall` nur noch die Engine der Matrixzelle installiert ([ADR-0200](../adr/0200-e2e-images-are-built-once-per-run-and-shared-as-an-artifact.md), #1993), endeten sie in allen zehn Firefox- und WebKit-Zellen mit `initializationError` (`DriverException`). `E2eBrowserLaunchSeamTest` (Frontend-Unit-Tests) verbietet seitdem jeden direkten Engine-Start außerhalb von `E2eSupport`.

| Klasse                           | Aktiviert durch            | Prüft                                   |
|----------------------------------|----------------------------|-----------------------------------------|
| `BlueprintsMockupCheckE2eTest`   | `BP_CHECK=true`            | Blaupausen-Master-Detail-Seite          |
| `ItemsMockupCheckE2eTest`        | `PI_CHECK=true`            | Item-Seite von Mein Inventar            |
| `MissionDesignFixesCheckE2eTest` | `MISSION_FIX_CHECK=true`   | Design-Korrekturen der Einsatzseite     |
| `MissionTabsMockupCheckE2eTest`  | `MISSION_ID=<uuid>`        | Tabs einer bestimmten Einsatz-Detailseite |

## Gemeinsamer Rahmen

**Akteur.** Sofern nicht anders genannt, ist der Akteur der synthetische Test-User `test-admin` (Keycloak) mit der ADMIN-Rolle und — über den Seeder — einer Mitgliedschaft in der IRIDIUM-Staffel; staffel-gescopte Aktionen (Einsatz, Schiff, Refinery Order) verlangen sie. Weitere Wegwerf-Nutzer (`test-member`, `test-officer`, `test-bank-*`, `test-bereich`, `test-norole` …) stehen im Realm-Fixture `frontend/src/e2e/resources/realm-export.e2e.json`. Seit [ADR-0159](../adr/0159-the-basetool-has-no-anonymous-or-guest-surface.md) gibt es keine anonyme Oberfläche mehr; ohne Anmeldung rendern nur Startseite und Rechtsseiten ([UC-12](UC-12-mitgliederbereich.md)).

**Ziel-Modi.** Die Suite ist ziel-agnostisch:

- *Ephemerer Stack* (Default): `E2eStackExtension` baut die App-Images und fährt `db-backend-dev`, `db-keycloak-dev`, `keycloak-dev`, `redis-dev`, `backend-dev` und `frontend-dev` per `docker compose` aus `docker-compose.yml`, `docker-compose.test.yml`, `docker-compose.build.yml` und `docker-compose.e2e.yml` hoch (Wegwerf-Credentials aus `.env.test`), seedet die Vorbedingungen und reißt den Stack danach mit `down --volumes --remove-orphans` ab. Der Ingest-Dienst läuft hier nicht mit ([UC-43](UC-43-ingest-uebergabe.md)).
- *Eigener Stack pro Checkout* (seit 2026-09-23): Ein lokal gebauter Stack taggt seine Images mit `e2e-<Verzeichnis>-<Pfad-Hash>` und läuft unter einem ebenso abgeleiteten Compose-Projektnamen, sodass zwei Checkouts (Worktrees, parallele Agenten) nie dieselben Images, Container, Netze oder Volumes teilen. Vorher trugen alle Checkouts denselben Tag `e2e-local`: Baute ein zweiter Checkout, während der erste noch startete, bootete der erste dessen Image und lief grün gegen fremden Code. Nach dem Hochfahren prüft `ServedBuildCheck`, dass jedes inhaltsgehashte Skript und Stylesheet der Startseite byte-gleich mit diesem Checkout ist, und bricht sonst vor dem ersten Test ab. Ports und Subnetze bleiben fest: Zwei Stacks gleichzeitig scheitern laut („port is already allocated“, „Pool overlaps“); ist Port 18081 schon belegt, nennt die Extension den fremden Container. Der CI-Pfad mit `-Pe2e.prebuilt=true` behält den festen Tag `e2e-local` (ein Runner, ein Checkout, [ADR-0200](../adr/0200-e2e-images-are-built-once-per-run-and-shared-as-an-artifact.md)).
- *Staging*: Mit gesetztem `E2E_BASE_URL` laufen die Tests gegen ein externes Deployment; Docker wird nicht angefasst, und Tests, die Seed-Daten brauchen, überspringen sich per `assumeTrue(STACK.managesStack())`.

**Daten-Setup.** Die Vorbedingungen werden im `@BeforeAll` hergestellt (nur im ephemeren Modus):

- `BackendSeeder` — über die Backend-REST-API mit Bearer-Token (Keycloak-Password-Grant): Mitgliedschaften, Materialien, Locations, Aufträge, verknüpftes Inventar, Bankkonten usw.
- UEX-Katalog-Snapshot (`uex-catalog-seed.sql`) — per JDBC: Städte, eine refinery-fähige Location, Hersteller, ShipType, Refining Method und ein Terminal. Diese sind normalerweise UEX-synchronisiert und über die Admin-API auf einer frischen DB nicht anlegbar. Weitere JDBC-Seeds des `BackendSeeder` (`seedOrderableItem`, `seedSellableTerminal`) legen bestellbare Items und Verkaufspreise an.

**Katalog-Caches.** Das Frontend cacht seine Kataloge (Materialien, Locations, Staffeln …) stundenlang (2 h / 6 h je Domäne) und verwirft sie nur bei Änderungen, die *über das Frontend* laufen. Was ein Test am Backend vorbei per API seedet, fehlt deshalb in jedem Picker, dessen Katalog vorher schon eine andere Klasse gerendert hat. Seit 2026-09-25 gilt: **Was ein Formular-Picker anbieten muss, seedet `E2eStackExtension` vor dem ersten Seitenaufruf** (die Refinery-Materialien in `PICKER_MATERIAL_*`, die Profit-Freigabe der IRIDIUM-Staffel, das bestellbare Item) — nicht die Testklasse, die es auswählt. Bis dahin seedeten drei Refinery-Klassen die Materialien der jeweils anderen mit und hofften, dass eine von ihnen vor der ersten Create-Seite der Suite läuft; die Dialog-Tour (`DialogA11yE2eTest`), die jede Seite rendert, riss das auf und musste vorübergehend an vorletzter Stelle laufen. Diese Reihenfolge-Abhängigkeit ist wieder entfernt.

**Reihenfolge.** Die Klassen laufen nacheinander auf demselben Stack; `junit-platform.properties` wählt `ClassOrderer.OrderAnnotation`, und `TouchClassLayoutE2eTest` läuft bewusst als letzte ([UC-44](UC-44-barrierefreiheit-touch-layout.md)) — die einzige feste Position. Tests, die globale Zustände ändern (etwa der Bank-Wipe), verlassen sich darauf.

**Lokale Läufe unter Windows.** Ein vollständiger Lauf öffnet sehr viele Verbindungen; eine einzelne Navigation kann dann mit `net::ERR_NO_BUFFER_SPACE` scheitern (Socket-Puffer des Hosts erschöpft). Das ist kein Befund der App: den betroffenen Test einzeln wiederholen.

**Browser und Geräteklassen.** Chromium (Default), Firefox und WebKit — wählbar via `-Pe2e.browser`. `-Pe2e.device=<BxH>` beschränkt den Layout-Sweep auf eine Geräteklasse.

**Ausführen.**

```bash
./gradlew :frontend:e2eTest                           # alle Klassen mit @Tag("e2e")
./gradlew :frontend:e2eTest -Pe2e.browser=firefox     # andere Engine
./gradlew :frontend:e2eTest --tests "*MissionCreate*" # eine einzelne Klasse
./gradlew :frontend:smokeTest                         # nur @Tag("smoke"): UC-07 und der axe-Scan aus UC-44
```

**CI.** [`e2e.yml`](../../.github/workflows/e2e.yml) läuft einmal täglich (angesetzt 02:37 UTC; GitHub startet geplante Läufe mehrere Stunden später), per `workflow_dispatch` und auf Pull Requests mit dem Label `e2e` — als Matrix aus drei Engines × fünf Geräteklassen (`375x812`, `810x1080`, `1024x768`, `1280x800`, `1600x900`). Seit 2026-09-22 baut ein vorgelagerter Job `build-stack` die Backend- und Frontend-Images **einmal** pro Lauf und reicht sie als Artefakt an alle fünfzehn Zellen weiter; jede Zelle lädt sie mit `docker load`, startet den Stack mit `-Pe2e.prebuilt=true` (also `docker compose up --no-build`) und installiert nur die eigene Browser-Engine (`-Pe2e.browser`). Lokal ohne `-Pe2e.prebuilt` baut `E2eStackExtension` die Images wie bisher selbst. [`e2e-smoke.yml`](../../.github/workflows/e2e-smoke.yml) läuft nur per `workflow_dispatch` gegen Staging und nur, wenn die Repository-Variable `E2E_BASE_URL` gesetzt ist; der nächtliche Zeitplan ist geparkt, bis es einen Staging-Host gibt (die Anleitung zum Wiedereinschalten steht im Workflow).

## Use-Case-Schema

Jedes Dokument folgt demselben Schema: **Akteur**, **Vorbedingungen**, **Auslöser**, **Hauptablauf**, **Erwartetes Ergebnis** und **Sonderfälle & Lehren** — letztere halten die CI-, Timing- und Tenancy-Eigenheiten fest, die der jeweilige Flow beim Aufbau aufgedeckt hat. Ein Use Case mit mehreren Testklassen gliedert seinen Hauptablauf in Abschnitte je Klasse.

Wer eine Testklasse hinzufügt, entfernt oder umbenennt, passt im selben Change den zugehörigen Use Case und diese Übersicht an.
