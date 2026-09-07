# UC-10 — Öffentlicher Einsatz staffel-übergreifend (Teilnehmer aus anderer Staffel)

|                |                                                                                                                                                      |
|----------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-10                                                                                                                                                |
| **Tag**        | `e2e`                                                                                                                                                |
| **Testklasse** | [`OrgWideMissionCrossStaffelE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/OrgWideMissionCrossStaffelE2eTest.java) |
| **Basis-Flow** | [UC-02](UC-02-mission-anlegen.md) · Scope-Regeln: [Rollen & Scope](rollen-und-scope.md)                                                              |

> Code-/Englisch-Begriff *Mission* = dt. **Einsatz** (Entity `Mission`, Endpunkt `/missions`).

## Akteure

- **Ersteller (Staffel A)** — legt einen **organisationsweiten** Einsatz an (`is_internal = false`).
- **Teilnehmer (Staffel B)** — sieht den Einsatz und tritt ihm bei.

## Vorbedingungen

- Zwei Staffeln A und B; je ein Test-User mit Mitgliedschaft.

## Auslöser

Staffel A öffnet einen Einsatz für staffel-übergreifende Teilnahme.

## Hauptablauf

1. **Staffel A** legt unter `/missions/new` einen Einsatz an und lässt ihn **organisationsweit** (`is_internal = false`).
2. **Staffel B** öffnet `/missions` und sieht A's organisationsweiten Einsatz (Cross-Staffel-Escape).
3. **Staffel B** tritt dem Einsatz als Teilnehmer bei.

## Erwartetes Ergebnis

- Der organisationsweite Einsatz ist für B **sichtbar**; ein **interner** Einsatz (`is_internal = true`) wäre es nicht. „Öffentlich“ hieß das bis 2026-09-06 und meinte zwei Dinge; nur eines davon gilt noch (ADR-0159).
- B's Teilnahme wird mit seiner **Heimat-Staffel** vermerkt (`MissionParticipant.squadron = B`).
- Finanz-/Beteiligungs-Auswertungen splitten nach `participant.squadron` (A vs. B sichtbar).
- Editieren des Einsatzes bleibt der besitzenden Staffel A (+ Admins) vorbehalten — B kann teilnehmen, aber den Einsatz nicht bearbeiten.

## Sonderfälle & Lehren

- **Public-Escape ist die einzige Cross-Staffel-Sichtbarkeit für Einsätze:** Das Repository-`searchMissions` setzt die Klausel `owning_org_unit.id IN (:memberOrgUnitIds) OR is_internal = false` — interne Einsätze bleiben strikt bei der Eigentümer-Staffel.
- **`MissionParticipant.squadron`** ist eine der wenigen grandfatherten `squadron_id`-Referenzen; sie hält fest, *aus welcher* Staffel ein Teilnehmer kommt — Grundlage für staffel-übergreifende Beteiligungsabrechnung.
- Anlegen ist `isAuthenticated()` (jeder Auth-User, auch ein einfaches Mitglied); **Editieren/Verwalten** gaten `canEditMission` / `MissionSecurityService.canManageMission` auf Eigentümer-Staffel + Admins.

