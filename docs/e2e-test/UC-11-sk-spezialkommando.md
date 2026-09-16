# UC-11 — Spezialkommando (SK) als OrgUnit

|                  |                                                                                                                              |
|------------------|------------------------------------------------------------------------------------------------------------------------------|
| **ID**           | UC-11                                                                                                                        |
| **Tag**          | `e2e`                                                                                                                        |
| **Testklasse**   | [`SpecialCommandE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/SpecialCommandE2eTest.java) |
| **Scope-Regeln** | [Rollen & Scope](rollen-und-scope.md)                                                                                        |

## Akteure

- **Admin** — legt das SK an und verwaltet seinen Lebenszyklus.
- **SK Lead** (`is_lead = true` auf der SK-Mitgliedschaft) — verwaltet die Mitglieder genau dieses SK.
- **SK-Mitglied** — agiert im Kontext des SK.

## Vorbedingungen

- Ein Admin-User.
- (Für die Member-Verwaltung) ein User mit `is_lead = true` auf dem SK.

## Auslöser

Ein Spezialkommando wird als eigene OrgUnit aufgesetzt und betrieben.

## Hauptablauf

1. **Admin** legt unter `/admin/special-commands` ein SK an (anlegen / umbenennen / löschen — ADMIN-only).
2. **Admin oder SK-Lead** fügt Mitglieder hinzu / entfernt sie / toggelt deren `is_logistician` / `is_mission_manager` (Gate `SpecialCommandSecurityService.canManageMembers`). Der `is_lead`-Toggle selbst bleibt ADMIN-only (kein Self-Escalation).
3. Ein SK-Mitglied nutzt das SK als aktiven Kontext (Pin) — analog zu einer Staffel-Mitgliedschaft.

## Erwartetes Ergebnis

- Das SK existiert als vollwertige OrgUnit (`org_unit.kind = 'SPECIAL_COMMAND'`) mit Mitgliedschaften.
- Mitgliederverwaltung funktioniert für **Admin** und den **Lead dieses SK**; ein normales SK-Mitglied kann **nicht** verwalten.
- Das SK taucht im Scope eines Mitglieds auf (Vereinigung der Mitgliedschaften / pinbar).

## Sonderfälle & Lehren

- **SK als besitzende/anfragende OrgUnit von Aggregaten 400t aktuell.** Die Legacy-Spalte `owning_squadron_id` ist noch `NOT NULL`; `requesting_org_unit_id` akzeptiert ein SK laut Design, aber die Persistenz lehnt es bis zur **destruktiven Cleanup-Release** mit 400 ab. Ein E2E-Fall „SK als requesting OrgUnit eines Job Orders" muss daher aktuell **400 erwarten** (oder bis nach der Cleanup-Release vertagt werden).
- **Keine Promotion für SK:** Das Beförderungssubsystem ist per DB-CHECK (`kind = 'SQUADRON' OR is_promotion_enabled = FALSE`), V101-Trigger und JPA-Guards für SK gesperrt.
- **Lead-Scope ist eng:** `is_lead` gilt nur in *diesem einen* SK (kein cross-SK-Carry-over) und nur für die Mitgliederverwaltung — keine sonstigen erhöhten Rechte.
