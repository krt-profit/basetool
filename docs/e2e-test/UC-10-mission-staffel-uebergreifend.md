# UC-10 — Öffentlicher Einsatz staffel-übergreifend (Teilnehmer aus anderer Staffel)

|                |                                                                                                                                                      |
|----------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-10                                                                                                                                                |
| **Tag**        | `e2e`                                                                                                                                                |
| **Testklasse** | [`OrgWideMissionCrossStaffelE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/OrgWideMissionCrossStaffelE2eTest.java) |
| **Basis-Flow** | [UC-02](UC-02-mission-anlegen.md) · Scope-Regeln: [Rollen & Scope](rollen-und-scope.md)                                                              |

> Code-/Englisch-Begriff *Mission* = dt. **Einsatz** (Entity `Mission`, Endpunkt `/missions`).

## Akteure

- **`test-officer`** (Heimat-Staffel IRIDIUM = Staffel A) — Besitzer beider Einsätze; wird nur per REST verwendet.
- **`test-member`** (Heimat einer frisch angelegten Staffel B „E2E Mission B") — treibt die UI.

## Vorbedingungen

Nur im ephemeren Modus per REST geseedet: Staffel B und beide Mitgliedschaften; `test-officer` legt einen **organisationsweiten** (`is_internal = false`) und einen **internen** Einsatz an (`createMission`), beide auf Staffel A gestempelt.

## Auslöser

Ein Mitglied der Staffel B öffnet die Einsatzliste.

## Hauptablauf

1. `test-member` meldet sich an und öffnet `/missions`.
2. Er öffnet die Detailseite `/missions/{id}` des organisationsweiten Einsatzes.

## Erwartetes Ergebnis

- Der organisationsweite Einsatz steht in B's Liste; der interne Einsatz von A steht dort **nicht** (`hasCount(0)`).
- Die Detailseite des organisationsweiten Einsatzes rendert für B mit seinem Namen.
- Der Beitritt als Teilnehmer ist **nicht** automatisiert (er hängt an geseedeten Job-Typen); getestet sind Sichtbarkeit und Detailzugriff, auf denen er aufbaut.

## Sonderfälle & Lehren

- **Der organisationsweite Escape ist die einzige Cross-Staffel-Sichtbarkeit für Einsätze:** Das Repository-`searchMissions` setzt die Klausel `owning_org_unit.id IN (:memberOrgUnitIds) OR is_internal = false` — interne Einsätze bleiben strikt bei der Eigentümer-Staffel.
- **Regressionswächter für ADR-0159:** Die Klasse hieß bis dahin `PublicMissionCrossStaffelE2eTest`. „Öffentlich" meinte zweierlei, und nur eines davon gilt noch: `is_internal = false` öffnet einen Einsatz weiterhin der ganzen Organisation, aber nicht mehr dem Internet. Ein Gate, das beim Umbau eine Stufe zu eng geschrieben worden wäre, hätte den Escape geschlossen — jedes Mitglied außerhalb der besitzenden Staffel hätte den Einsatz still nicht mehr gesehen.
- Die Staffel-Zugehörigkeit eines Teilnehmers steht heute in der Zuordnungstabelle `mission_participant_org_unit` (`MissionParticipant.orgUnits`) — Grundlage für die staffel-übergreifende Beteiligungsauswertung; dieser Test prüft sie nicht.
- Anlegen ist `isAuthenticated() and @authHelperService.isMemberOrAbove()`; **Verwalten** gatet `@missionSecurityService.canManageMission(#id, authentication)` auf Eigentümer-Staffel + Admins.
