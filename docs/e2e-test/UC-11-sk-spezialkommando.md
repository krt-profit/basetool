# UC-11 — Spezialkommando (SK) als OrgUnit

|                  |                                                                                                                              |
|------------------|------------------------------------------------------------------------------------------------------------------------------|
| **ID**           | UC-11                                                                                                                        |
| **Tag**          | `e2e`                                                                                                                        |
| **Testklasse**   | [`SpecialCommandE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/SpecialCommandE2eTest.java) |
| **Scope-Regeln** | [Rollen & Scope](rollen-und-scope.md)                                                                                        |

## Akteur

Der synthetische Test-User `test-admin` (ADMIN) — in der UI und über die REST-API.

## Vorbedingungen

- Eingeloggte Admin-Session.
- Im ephemeren Modus per REST geseedet: IRIDIUM-Mitgliedschaft, ein SK „E2E SK Alpha" (`createSpecialCommand`) und ein Job-Order-Material.
- Für den Deaktivierungs-Fall ein eigens geseedetes Wegwerf-SK (nur ephemer, `assumeTrue(STACK.managesStack())`).

## Auslöser

Ein Spezialkommando wird als eigene OrgUnit aufgesetzt, deaktiviert und als bearbeitende Einheit eines Auftrags versucht.

## Hauptablauf

### SK anlegen (UI)

1. `/admin/special-commands` öffnen, „SK anlegen" (`#add-sc-btn`), Name (`#sc-name`) und Kürzel (`#sc-shorthand`) füllen und in place absenden (`#sc-form`).
2. Die Liste neu laden.

### SK deaktivieren (UI)

3. In der Zeile des Wegwerf-SK den Papierkorb (`.delete-btn`) klicken → das KRT-Bestätigungsmodal `#sc-delete-modal` öffnet (kein natives `confirm()`); bestätigen (`#sc-delete-form`).
4. Die Liste neu laden, anschließend mit `?includeInactive=true`.

### SK als bearbeitende Einheit (API)

5. `POST /api/v1/orders` mit dem geseedeten SK als `responsibleOrgUnitId`.

## Erwartetes Ergebnis

- Anlegen und Deaktivieren speichern **in place** (#582): der Reload-Marker `window.__krtNoReload` überlebt den Submit; das neu angelegte SK steht nach dem Neuladen in der Liste.
- Das deaktivierte SK fehlt in der Standardliste (nur aktive) und erscheint unter `includeInactive=true` wieder, markiert mit `.badge-inactive` — es wird weich gelöscht, nicht entfernt.
- Der Auftrag mit dem SK als bearbeitender Einheit wird mit **HTTP 400** abgelehnt: nur profit-eligible Einheiten bearbeiten Aufträge (V128), und ein frisch angelegtes SK ist das standardmäßig nicht.

## Sonderfälle & Lehren

- **Die Grenze ist Profit-Eligibility, nicht die Art der OrgUnit.** Seit V102/V103 gibt es die Legacy-Spalte `owning_squadron_id` nicht mehr; ein SK kann besitzende Einheit von Inventar, Schiffen, Refinery Orders, Einsätzen und Operationen sein ([Rollen & Scope](rollen-und-scope.md)) und anfragende Einheit eines Auftrags. Nur als *bearbeitende* Einheit braucht es die Profit-Eligibility.
- **Der Papierkorb war tot.** Vor dem Fix hatte der Button weder ein umschließendes Formular noch ein Skript — ein SK ließ sich über die Liste nie löschen. Der Deaktivierungs-Fall bewacht genau das.
- **Nicht getestet:** die Mitgliederverwaltung durch den SK-Lead (`@specialCommandSecurityService.canManageMembers`, Lead-Toggle ADMIN-only) und der SK-Pin als aktiver Kontext. Die Regeln stehen in [Rollen & Scope](rollen-und-scope.md); das SK als besitzende Einheit von Inventar prüft [UC-14](UC-14-inventar-mandanten-scope.md).
- **Keine Promotion für SK:** Das Beförderungssubsystem ist per DB-CHECK (`kind = 'SQUADRON' OR is_promotion_enabled = FALSE`), V101-Trigger und JPA-Guards für SK gesperrt.
- **Lead-Scope ist eng:** `is_lead` gilt nur in *diesem einen* SK (kein cross-SK-Carry-over) und nur für die Mitgliederverwaltung — keine sonstigen erhöhten Rechte.
