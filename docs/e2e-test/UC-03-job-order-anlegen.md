# UC-03 — Job Order anlegen

|                |                                                                                                                              |
|----------------|------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-03                                                                                                                        |
| **Tag**        | `e2e`                                                                                                                        |
| **Testklasse** | [`JobOrderCreateE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/JobOrderCreateE2eTest.java) |

## Akteur

Authentifizierter User mit IRIDIUM-Mitgliedschaft.

## Vorbedingungen

- Eingeloggte Session (UC-01).
- IRIDIUM-Mitgliedschaft geseedet (`ensureIridiumMembership`).
- Ein job-order-fähiges Material angelegt (`ensureJobOrderMaterial`, `isJobOrder=true`) — das Material-Dropdown des Formulars listet nur solche.

## Auslöser

Der User öffnet das Job-Order-Anlegen-Formular `/orders/create`.

## Hauptablauf

1. Navigiere zu `/orders/create`.
2. Wähle die **bearbeitende** Einheit (`#responsibleOrgUnitId` → IRIDIUM; nur profit-eligible) und die **anfragende** Einheit (`#requestingOrgUnitId` → IRIDIUM).
3. Fülle den Kontakt-Handle (`#handle`).
4. Wähle in der Materialzeile das Material (`order-material-select`) und die Menge (`order-material-amount`).
5. Submit über `order-submit`.

## Erwartetes Ergebnis

Der Auftrag erscheint in der Liste unter `/orders` als `order-row`.

## Sonderfälle & Lehren

- **Sichtbarkeit über die bearbeitende Einheit (REQ-ORG-003):** Job Orders sind bedingt staffel-scoped — Responsible = SK → öffentlich, Responsible = Staffel → privat für diese Staffel + Admins (siehe [UC-18](UC-18-job-order-mandanten-sichtbarkeit.md)). Das Anlegen selbst verlangt nur eine Anmeldung (`isAuthenticated()`; bis ADR-0149 war es `permitAll` für das öffentliche Anfrageformular); beide Owner-Dropdowns werden aus dem aktiven Org-Unit-Katalog befüllt, das Responsible-Dropdown nur mit profit-eligible Einheiten.
- Das Material-Dropdown ist auf `isJobOrder=true` gefiltert; deshalb wird ein eigens geseedetes Job-Order-Material gewählt.
