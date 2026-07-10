# UC-18 — Job-Order Mandanten-Sichtbarkeit (wer sieht was)

|                |                                                                                                                                |
|----------------|--------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-18                                                                                                                          |
| **Tag**        | `e2e`                                                                                                                          |
| **Testklasse** | [`JobOrderTenancyE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/JobOrderTenancyE2eTest.java) |
| **Basis-Flow** | [Rollen & Scope](rollen-und-scope.md) · Spec [`org-unit-tenancy.md` REQ-ORG-003](../specs/org-unit-tenancy.md)                 |

## Akteure

- **Officer der Staffel B** (profit-eligible) — `test-officer`, in eine frische profit-eligible Staffel B gehomed.
- **Mitglied der Staffel C** (non-profit) — `test-member`, in eine frische non-profit Staffel C gehomed.

## Vorbedingungen

- Ein profit-eligible SK; ein **SK-verantworteter** Auftrag (öffentlich).
- Ein **IRIDIUM-verantworteter** Auftrag (staffel-privat).
- Ein von **Staffel C angeforderter** Auftrag (Requester = Staffel C, verantwortlich = SK) — für den Requester-Escape.
- Staffel B profit-eligible (Officer ist Mitglied), Staffel C non-profit (Member ist Mitglied).

## Auslöser

Officer bzw. Member rufen die Auftragsliste `/orders` bzw. eine Detailseite `/orders/{id}` auf.

## Hauptablauf & Erwartetes Ergebnis

1. **SK-öffentliche Warteschlange:** Officer (Staffel B) öffnet `/orders?scope=all&status=OPEN`.
   - Der **SK-verantwortete** Auftrag ist als `order-row` mit seiner `data-id` **sichtbar**.
   - Der **IRIDIUM-verantwortete** Auftrag ist es **nicht** (staffel-privat; B ist kein Mitglied von IRIDIUM).
2. **Requester-Escape (REQ-ORDERS-023):** Das Member (Staffel C, non-profit) öffnet `/orders` → „Meine Aufträge": der **von Staffel C angeforderte** Auftrag ist als `order-row` mit seiner `data-id` **sichtbar**, der **SK-öffentliche** Auftrag (von einer anderen Einheit angefordert) **nicht**, und es wird **nicht** aufs Anlege-Formular umgeleitet (`order-mode-material` fehlt). Ein direkter Aufruf von `/orders/{skOrderId}` (fremd, nicht selbst angefordert) wird abgewiesen und zurück auf die eigene „Meine Aufträge"-Liste geleitet — die Detailseite öffnet nicht.

## Sonderfälle & Lehren

- **Sichtbarkeit über `responsibleOrgUnit.kind` (REQ-ORG-003):** Responsible = SK → **öffentlich** für alle profit-eligible Mitglieder (geteilte SK-Warteschlange); Responsible = Staffel → **privat** für diese Staffel + Admins. Die SK-Public-Escape ist `TYPE(responsibleOrgUnit) = SpecialCommand` in `findScopedJobOrders`. `requestingOrgUnit` (Auftraggeber) gewährt **keine** Sichtbarkeit.
- **Officer-Rolle ≠ Cross-Staffel-Sicht:** Die Officer-Realm-Rolle gibt Bearbeitungs-Capability (Rollen-Gate), aber **keine** staffel-übergreifende Sichtbarkeit — der Scope bleibt mitgliedschaftsbasiert. SK-Auftrags-*Edits* laufen über das Rollen-Gate (LOGISTICIAN+), nicht über den Staffel-Scope.
- **Profit-Gate vor Per-Order, aber Requester-Escape daneben:** `canViewJobOrders` (Admin, oder mindestens eine profit-eligible Mitgliedschaft) schließt die allgemeine **Warteschlange** kurz; ein non-profit Member sieht dort selbst SK-öffentliche Aufträge nicht. Über den **Requester-Escape** (REQ-ORDERS-023, ADR-0091) sieht es jedoch die von der eigenen Einheit **gestellten** Aufträge in reduzierter Form („Meine Aufträge") und darf sie — solange noch nichts geliefert wurde — eingeschränkt bearbeiten; fremde Aufträge bleiben `403`.
- **Shared-DB-robust:** Die Assertions prüfen je Auftrag die eigene `data-id`, sodass von Geschwistertests akkumulierte Aufträge (z. B. Intake-SK-Aufträge aus UC-12) die Erwartung nicht verfälschen.
- **Ergänzung zur Schreib-Matrix:** Wer welche Aktions-Controls (Bearbeiten/Löschen/Handover) auf einem **sichtbaren** Auftrag sieht, deckt [`RolePermissionsE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/RolePermissionsE2eTest.java) ab (Edit = LOGISTICIAN, Löschen = ADMIN, Handover = LOGISTICIAN/OFFICER/ADMIN).

