# E2E-Test Use Cases

Dieses Verzeichnis dokumentiert die End-to-end-Testszenarien des Profit Basetool als Use Cases — je ein Dokument pro funktionalem Flow. Jeder Use Case beschreibt Akteur, Vorbedingungen, Ablauf und erwartetes Ergebnis und verlinkt die implementierende Playwright-Testklasse.

Für Aufbau, Cross-Browser-Matrix und CI-Workflows der E2E-Suite siehe den Testing-Abschnitt im [Projekt-README](../../README.md).

## Übersicht

|                            ID                            |                       Use Case                        |   Tag   |            Testklasse            |
|----------------------------------------------------------|-------------------------------------------------------|---------|----------------------------------|
| [UC-01](UC-01-login.md)                                  | Login via Keycloak                                    | `e2e`   | `LoginSmokeE2eTest`              |
| [UC-02](UC-02-mission-anlegen.md)                        | Einsatz anlegen                                       | `e2e`   | `MissionCreateE2eTest`           |
| [UC-03](UC-03-job-order-anlegen.md)                      | Job Order anlegen                                     | `e2e`   | `JobOrderCreateE2eTest`          |
| [UC-04](UC-04-refinery-order-anlegen.md)                 | Refinery Order anlegen                                | `e2e`   | `RefineryOrderCreateE2eTest`     |
| [UC-05](UC-05-hangar-schiff-hinzufuegen.md)              | Schiff zum Hangar hinzufügen                          | `e2e`   | `HangarAddShipE2eTest`           |
| [UC-06](UC-06-job-order-handover.md)                     | Job-Order-Handover protokollieren                     | `e2e`   | `JobOrderHandoverE2eTest`        |
| [UC-07](UC-07-kernseiten-smoke.md)                       | Kernseiten-Smoke (nicht-destruktiv)                   | `smoke` | `CorePagesSmokeE2eTest`          |
| [UC-13](UC-13-inventar-operationen.md)                   | Inventar: Ein-/Aus-/Umbuchen, Verkauf, Zuweisung      | `e2e`   | `InventoryOperationsE2eTest`     |
| [UC-15](UC-15-job-order-bearbeiten.md)                   | Job Order bearbeiten                                  | `e2e`   | `JobOrderEditE2eTest`            |
| [UC-16](UC-16-job-order-status.md)                       | Job-Order-Status ändern                               | `e2e`   | `JobOrderStatusE2eTest`          |
| [UC-17](UC-17-item-order-handover.md)                    | Item-Auftrag & Item-Handover                          | `e2e`   | `JobOrderItemHandoverE2eTest`    |
| [UC-19](UC-19-refinery-order-einlagern.md)               | Refinery Order einlagern (in das Lager)               | `e2e`   | `RefineryOrderStoreE2eTest`      |
| [UC-20](UC-20-refinery-order-lifecycle.md)               | Refinery Order: Bearbeiten/Abbrechen/Filter/Edges     | `e2e`   | `RefineryOrderLifecycleE2eTest`  |
| [UC-22](UC-22-mission-finanzeintrag.md)                  | Einsatz: Finanzeintrag anlegen & Detail erneut öffnen | `e2e`   | `MissionFinanceEntryE2eTest`     |
| [UC-23](UC-23-job-order-bearbeiter-notizen.md)           | Job Order: Bearbeiter ein-/austragen & Notizen (AJAX) | `e2e`   | `JobOrderAssigneeNotesE2eTest`   |
| [UC-24](UC-24-refinery-import-extract.md)                | Refinery Order aus Screenshot-Extract importieren     | `e2e`   | `RefineryImportE2eTest`          |
| [UC-25](UC-25-befoerderung-themenbereich-crud.md)        | Beförderung: Themenbereich anlegen/umbenennen/löschen | `e2e`   | `PromotionTopicCrudE2eTest`      |
| [UC-26](UC-26-mein-inventar-crud.md)                     | Mein Inventar: Eintrag anlegen & löschen              | `e2e`   | `PersonalInventoryCrudE2eTest`   |
| [UC-27](UC-27-benachrichtigung-gelesen.md)               | Benachrichtigung erhalten & als gelesen markieren     | `e2e`   | `NotificationCenterE2eTest`      |
| [UC-28](UC-28-mitglied-bearbeiten.md)                    | Mitglied bearbeiten (In-Place-Save)                   | `e2e`   | `MemberEditInPlaceE2eTest`       |
| [UC-29](UC-29-admin-missions-stammdaten-staffel-crud.md) | Admin Missions-Stammdaten: Staffel anlegen & löschen  | `e2e`   | `AdminMissionDataCrudE2eTest`    |
| [UC-30](UC-30-item-lager-operationen.md)                 | Item-Lager: Ein-/Um-/Ausbuchen, Zuordnungs-Gate, Sync | `e2e`   | `ItemInventoryOperationsE2eTest` |

UC-25 bis UC-29 schließen die zuvor offenen E2E-Lücken der auditierten Bereiche **Beförderung** (UC-25) und **Mein Inventar** (UC-26), der **Benachrichtigungen** (UC-27), der ADMIN-**Mitgliederverwaltung** (UC-28) und des Admin-Datenpflege-Clusters (UC-29). Ergänzend deckt der ADMIN-authentifizierte [`AdminPagesSmokeE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/AdminPagesSmokeE2eTest.java) (`@Tag("e2e")`) die reinen Lese-Adminseiten als Page-Load-Smoke ab, und `CorePagesSmokeE2eTest` (`@Tag("smoke")`) lädt jetzt zusätzlich die zuvor ungesmokten Lese-Seiten (`/operations`, `/materials`, `/materials/overview`, `/materials/profit-calculation`, `/ship-data`, `/blueprint-overview`, `/org-chart`, `/notifications`, `/personal-inventory`, `/personal-inventory/blueprints`).

UC-01 bis UC-07 sowie UC-13 sind als Playwright-Tests implementiert (Happy Path als Admin/IRIDIUM-Mitglied); UC-15 bis UC-17 erweitern die Job-Order-Flows (Bearbeiten, Status-Wechsel, Item-Auftrag/Item-Handover), UC-19 und UC-20 die Refinery-Flows (Einlagern, Lifecycle/Edge Cases), UC-22 den Einsatz-Finanzeintrag (Regressionsschutz gegen den Detail-500, wenn ein Einsatz einen Finanzeintrag besitzt) — ebenfalls `@Tag("e2e")`. Zusammen mit UC-04 (Anlegen) bilden UC-19/UC-20 den vollen Refinery-Funktionsumfang ab.

### Rollen & staffel-/SK-übergreifend

Die folgenden Dokumente erweitern die Grund-Flows um **Rollen** (Offizier, einfaches Mitglied) und **Mehr-Staffel-/SK-Szenarien** — inkl. der Fälle, in denen eine Staffel etwas anlegt und eine andere damit weiterarbeitet. Sie sind **spezifiziert und als Playwright-Tests implementiert** (`@Tag("e2e")`).

|                      Dokument                      |                                                                             Thema                                                                              |                   Testklasse                   |
|----------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------|
| [Rollen & Scope](rollen-und-scope.md)              | Rollen × Flow-Matrix, Mandanten-Scope-Modell, Admin-Pin, SK-Grundlagen (Referenz); Control-Gating Handover/Edit/Delete je Rolle                                | `RolePermissionsE2eTest`                       |
| [UC-08](UC-08-job-order-staffel-uebergreifend.md)  | Job Order: Staffel A bestellt, Staffel B liefert (B's Inventar verknüpft)                                                                                      | `CrossStaffelJobOrderE2eTest`                  |
| [UC-09](UC-09-handover-staffel-uebergreifend.md)   | Handover staffel-übergreifend (Material von B, Empfänger ggf. dritte Staffel)                                                                                  | `CrossStaffelHandoverE2eTest`                  |
| [UC-10](UC-10-mission-staffel-uebergreifend.md)    | Organisationsweiter Einsatz mit Teilnehmern aus anderer Staffel                                                                                                | `OrgWideMissionCrossStaffelE2eTest`            |
| [UC-11](UC-11-sk-spezialkommando.md)               | Spezialkommando (SK) als OrgUnit (Lifecycle, Mitglieder, aktuelle Grenzen)                                                                                     | `SpecialCommandE2eTest`                        |
| [UC-12](UC-12-mitgliederbereich.md)                | Mitgliederbereich: ohne Anmeldung nur Startseite und Rechtsseiten, Deep-Link führt nach der Anmeldung zurück, ein Konto ohne Rolle landet auf der Hinweisseite | `AnonymousSurfaceE2eTest`, `NoRoleGateE2eTest` |
| [UC-14](UC-14-inventar-mandanten-scope.md)         | Inventar-Mandanten-Scope: Sicht/Anlage/Edit über Staffel-, SK-, beide und keine Zugehörigkeit + Admin-Pin                                                      | `InventoryTenancyE2eTest`                      |
| [UC-18](UC-18-job-order-mandanten-sichtbarkeit.md) | Job Order: wer sieht was — SK-öffentliche Warteschlange vs. staffel-privat, Profit-Gate (REQ-ORG-003)                                                          | `JobOrderTenancyE2eTest`                       |
| [UC-21](UC-21-refinery-order-mandanten-scope.md)   | Refinery-Mandanten-Scope: Sicht/Anlage/Edit/Einlagern über Staffel-, SK-, beide und keine Zugehörigkeit + Admin-Pin + BAC-004                                  | `RefineryOrderTenancyE2eTest`                  |

> **Hinweis zur Abdeckung:** Einsätze/Operationen und Refinery Orders sind **strict-staffel** (nicht staffel-übergreifend). Die Refinery-Mandanten-Regeln (Sicht/Anlage/Edit/Einlagern, Admin-Pin, BAC-004) sind in [UC-21](UC-21-refinery-order-mandanten-scope.md) abgedeckt. Die staffel-übergreifende Zusammenarbeit läuft über organisationsweite Einsätze (UC-10) und den Job-Order-Workspace inkl. Handover (UC-08/UC-09). Details in [Rollen & Scope](rollen-und-scope.md).

## Gemeinsamer Rahmen

**Akteur.** Sofern nicht anders genannt, ist der Akteur der synthetische Test-User `test-admin` (Keycloak) — nach dem Login eine authentifizierte Session mit Mitgliedschaft in der IRIDIUM-Staffel. Der Test-User hat die ADMIN-Rolle; staffel-scoped Aktionen (Einsatz, Ship, Refinery Order) verlangen eine OrgUnit-Mitgliedschaft, die der Seeder herstellt.

**Ziel-Modi.** Die Suite ist ziel-agnostisch:

- *Ephemerer Stack* (Default): `E2eStackExtension` fährt den vollen Stack (Postgres ×2 + Keycloak + Redis + Backend + Frontend) per `docker compose` hoch, seedet die Vorbedingungen und reißt ihn danach ab (`down --volumes`).
- *Staging*: Mit gesetztem `E2E_BASE_URL` laufen die Tests gegen ein externes Deployment; Docker wird nicht angefasst.

**Daten-Setup.** Die Vorbedingungen werden im `@BeforeAll` hergestellt (nur im ephemeren Modus):

- `BackendSeeder` — über die Backend-REST-API mit Bearer-Token (Keycloak-Password-Grant): IRIDIUM-Mitgliedschaft, Materialien, Locations, Job Orders, verknüpftes Inventar.
- UEX-Katalog-Snapshot (`uex-catalog-seed.sql`) — per JDBC: refinery-fähige Location, ShipType und Refining Method. Diese sind normalerweise UEX-synced und über die Admin-API auf einer frischen DB nicht anlegbar.

**Browser.** Chromium (Default), Firefox und WebKit — wählbar via `-Pe2e.browser`, in der CI als Matrix.

**Ausführen.**

```bash
./gradlew :frontend:e2eTest                           # alle e2e-Flows (UC-01..06)
./gradlew :frontend:e2eTest -Pe2e.browser=firefox     # andere Engine
./gradlew :frontend:e2eTest --tests "*MissionCreate*" # ein einzelner Flow
./gradlew :frontend:smokeTest                         # nur der Smoke-Subset (UC-07)
```

## Use-Case-Schema

Jedes Dokument folgt demselben Schema: **Akteur**, **Vorbedingungen**, **Auslöser**, **Hauptablauf**, **Erwartetes Ergebnis** und **Sonderfälle & Lehren** — letztere halten die CI-, Timing- und Tenancy-Eigenheiten fest, die der jeweilige Flow beim Aufbau aufgedeckt hat.
